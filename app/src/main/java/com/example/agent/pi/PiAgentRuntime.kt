package com.example.agent.pi

import android.content.Context
import com.example.agent.opencode.OpenCodeGatewayBridgeServer
import com.example.agent.opencode.ToolUseStatus
import com.example.agent.tools.ToolBroker
import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SecretRedactor
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.gateway.GatewayRepository
import com.example.data.gateway.JhGatewayHttpClient
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentRuntime
import com.example.domain.contracts.AgentSession
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.contracts.RuntimeAvailability
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.ActiveExtensionPolicy
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AgentBackendId
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.PermissionClass
import com.example.domain.model.TaskState
import com.example.domain.model.ToolBrokerDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Second pluggable [AgentRuntime] implementation adapting Pi Coding Agent (`@mariozechner/pi-coding-agent`)
 * with `-p --mode json --no-session`, routed strictly through AGT's ephemeral loopback
 * [OpenCodeGatewayBridgeServer] and guarded by [ToolBroker].
 */
class PiAgentRuntime(
    context: Context,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val piInstaller: PiInstallerRepository,
    private val runtimeExecutor: LocalRuntimeExecutor,
    private val gatewayRepository: GatewayRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialVault: GatewayCredentialVault,
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
    private val gitHubCredentialVault: GitHubCredentialVault? = null,
    private val extensionRegistryRepository: ExtensionRegistryRepository? = null,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
) : AgentRuntime {

    override val id: String = AgentBackendId.PI.id

    private val appContext = context.applicationContext

    private data class ActiveSessionState(
        val session: AgentSession,
        val sessionDir: File,
        val sessionSubpath: String,
        val bridge: OpenCodeGatewayBridgeServer,
        var activeProcessId: String? = null,
        val pendingApprovals: ConcurrentHashMap<String, CompletableDeferred<Boolean>> = ConcurrentHashMap(),
    )

    private data class ToolCallContext(
        val toolName: String,
        val permissionClass: PermissionClass,
        val summary: String,
        val argumentsJson: String,
        val rawArgumentsJson: String,
        val affectedRelativePath: String?,
    )

    private val activeSessions = ConcurrentHashMap<String, ActiveSessionState>()

    override suspend fun checkAvailability(): RuntimeAvailability {
        val baseReady = runtimeExecutor.ensureReady()
        val piState = piInstaller.refreshStatusSync()
        val isReady = baseReady.isInstalled && piState.isInstalled
        val detail = when {
            !baseReady.isInstalled -> baseReady.detail
            !piState.isInstalled -> piState.statusDetail
            else -> "Pi Coding Agent v${piState.version.orEmpty()} ready (${baseReady.detail})"
        }
        return RuntimeAvailability(
            isInstalled = isReady,
            version = piState.version,
            detail = detail,
        )
    }

    override suspend fun startSession(project: ProjectRef): Result<AgentSession> {
        val availability = checkAvailability()
        if (!availability.isInstalled) {
            return Result.failure(IllegalStateException(availability.detail))
        }
        val projectDir = File(project.rootPath)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return Result.failure(IllegalArgumentException("Project directory does not exist: ${project.rootPath}"))
        }

        val sessionId = "pi-${UUID.randomUUID().toString().take(8)}"
        val sessionSubpath = "tmp/agt-sessions/$sessionId"
        val sessionDir = File(runtimeInstallerRepository.activeDir, sessionSubpath).apply { mkdirs() }
        val bridge = OpenCodeGatewayBridgeServer(
            gatewayConfigProvider = {
                val prefs = preferencesRepository.preferences.first()
                val token = credentialVault.readToken().getOrNull()
                prefs.gatewayBaseUrl to token
            },
            okHttpClient = okHttpClient,
        )
        bridge.start()

        val session = AgentSession(sessionId = sessionId, project = project)
        activeSessions[sessionId] = ActiveSessionState(
            session = session,
            sessionDir = sessionDir,
            sessionSubpath = sessionSubpath,
            bridge = bridge,
        )
        return Result.success(session)
    }

    override fun submitTask(session: AgentSession, task: AgentTask): Flow<AgentEvent> = callbackFlow {
        val sessionState = activeSessions[session.sessionId]
        if (sessionState == null) {
            trySend(
                AgentEvent.Failed(
                    reason = "Pi session is not active.",
                    error = AgtError.ProcessStartFailed("Pi session is not active."),
                )
            )
            close()
            return@callbackFlow
        }

        val availability = checkAvailability()
        if (!availability.isInstalled) {
            trySend(
                AgentEvent.Failed(
                    reason = availability.detail,
                    error = AgtError.RuntimeNotInstalled(availability.detail),
                )
            )
            close()
            return@callbackFlow
        }

        val binFile = piInstaller.resolveBinaryFile()
        if (binFile == null) {
            val err = AgtError.RuntimeNotInstalled("Pi binary missing inside Linux runtime.")
            trySend(AgentEvent.Failed(err.userMessage, err))
            close()
            return@callbackFlow
        }

        val models = gatewayRepository.discoveredModels.first()
        val modelInfo = models.firstOrNull { it.id == task.modelId }
        val capError = PiConfigAdapter.validateTaskCapabilities(
            modelInfo = modelInfo,
            requiresToolCalling = task.requiresToolCalling,
            attachments = task.attachments,
        )
        if (capError != null) {
            trySend(AgentEvent.StateChanged(TaskState.FAILED))
            trySend(AgentEvent.Failed(capError.userMessage, capError))
            close()
            return@callbackFlow
        }

        val prefs = preferencesRepository.preferences.first()
        val realToken = credentialVault.readToken().getOrNull()
        val ghToken = gitHubCredentialVault?.readToken()?.getOrNull()
        val extTokens = extensionRegistryRepository?.allSecretTokens().orEmpty()
        val knownSecrets = listOfNotNull(realToken, ghToken) + extTokens
        val validModel = modelInfo!!

        val attachedSkillIds = task.attachments
            .filter { it.kind == ComposerAttachmentKind.SKILLS }
            .map { it.id }
            .toSet()
        val attachedMcpIds = task.attachments
            .filter { it.kind == ComposerAttachmentKind.MCP_SERVERS }
            .map { it.id }
            .toSet()
        val attachedPluginIds = task.attachments
            .filter { it.kind == ComposerAttachmentKind.PLUGINS }
            .map { it.id }
            .toSet()

        if (attachedPluginIds.isNotEmpty() && !prefs.allowProotSandboxExtensions) {
            val err = AgtError.PermissionDenied(
                "Enable Sandboxed PRoot Extension Host in Extensions -> Plugins before running attached plugins."
            )
            trySend(AgentEvent.StateChanged(TaskState.FAILED))
            trySend(AgentEvent.Failed(err.userMessage, err))
            close()
            return@callbackFlow
        }

        if (extensionRegistryRepository != null &&
            (attachedSkillIds.isNotEmpty() || attachedMcpIds.isNotEmpty() || attachedPluginIds.isNotEmpty())
        ) {
            val enabledSkillIdSet = extensionRegistryRepository.getEnabledSkills().map { it.id }.toSet()
            val enabledMcpIdSet = extensionRegistryRepository.getEnabledMcpServers().map { it.id }.toSet()
            val enabledPluginIdSet = extensionRegistryRepository.getEnabledPlugins().map { it.id }.toSet()
            val disabledSkill = task.attachments.firstOrNull {
                it.kind == ComposerAttachmentKind.SKILLS && it.id !in enabledSkillIdSet
            }
            val disabledMcp = task.attachments.firstOrNull {
                it.kind == ComposerAttachmentKind.MCP_SERVERS && it.id !in enabledMcpIdSet
            }
            val disabledPlugin = task.attachments.firstOrNull {
                it.kind == ComposerAttachmentKind.PLUGINS && it.id !in enabledPluginIdSet
            }
            val blocked = disabledSkill ?: disabledMcp ?: disabledPlugin
            if (blocked != null) {
                val err = AgtError.PermissionDenied(
                    "Extension '${blocked.displayName}' is disabled or uninstalled and cannot run."
                )
                trySend(AgentEvent.StateChanged(TaskState.FAILED))
                trySend(AgentEvent.Failed(err.userMessage, err))
                close()
                return@callbackFlow
            }
        }

        val activeSkills = extensionRegistryRepository?.getActiveSkillPrompts(
            selectedSkillIds = attachedSkillIds.takeIf { it.isNotEmpty() },
        ).orEmpty()
        val activeMcpServers = extensionRegistryRepository?.getEnabledMcpRuntimeConfigs(
            selectedMcpIds = attachedMcpIds.takeIf { it.isNotEmpty() },
        ).orEmpty()
        val activePlugins = if (prefs.allowProotSandboxExtensions) {
            extensionRegistryRepository?.getEnabledPluginRuntimeConfigs(
                selectedPluginIds = attachedPluginIds.takeIf { it.isNotEmpty() },
            ).orEmpty().filter { cfg ->
                attachedPluginIds.isNotEmpty() || cfg.agentTarget != com.example.domain.model.ExtensionAgentTarget.OPENCODE
            }
        } else {
            emptyList()
        }
        val activeExtPolicy = extensionRegistryRepository?.buildActiveExtensionPolicy(
            selectedMcpIds = attachedMcpIds.takeIf { it.isNotEmpty() },
            selectedPluginIds = attachedPluginIds.takeIf { it.isNotEmpty() }
                ?: activePlugins.map { it.id }.toSet().takeIf { prefs.allowProotSandboxExtensions },
        ) ?: ActiveExtensionPolicy()

        val modelsJson = PiConfigAdapter.buildPiModelsJson(
            bridgeBaseUrl = sessionState.bridge.bridgeBaseUrl,
            ephemeralSessionNonce = sessionState.bridge.sessionNonce,
            protocolMode = task.protocolMode,
            model = validModel,
        )
        val settingsJson = PiConfigAdapter.buildPiSettingsJson(
            model = validModel,
            preferences = prefs,
        )
        val appendSystemMd = PiConfigAdapter.buildSystemAppendMarkdown(
            activeSkills = activeSkills,
            mcpServers = activeMcpServers,
            activePlugins = activePlugins,
        )
        val writtenDir = PiConfigAdapter.writeSessionAgentDir(
            sessionDir = sessionState.sessionDir,
            modelsJson = modelsJson,
            settingsJson = settingsJson,
            systemAppendMarkdown = appendSystemMd,
        )
        extensionRegistryRepository?.syncEnabledSkillsToDirectory(
            skillsTargetDir = File(writtenDir.agentDir, "skills"),
            selectedSkillIds = attachedSkillIds.takeIf { it.isNotEmpty() },
        )
        if (prefs.allowProotSandboxExtensions) {
            extensionRegistryRepository?.syncEnabledPluginsToDirectory(
                pluginsTargetDir = File(writtenDir.agentDir, "extensions"),
                selectedPluginIds = activePlugins.map { it.id }.toSet(),
            )
        }

        val stagedAttachments = stageAttachmentsIntoSession(
            attachments = task.attachments,
            sessionDir = sessionState.sessionDir,
            sessionSubpath = sessionState.sessionSubpath,
        )

        val promptFile = File(sessionState.sessionDir, "prompt.txt")
        promptFile.writeText(buildPromptWithAttachments(task, stagedAttachments))

        val processId = "pi-proc-${UUID.randomUUID().toString().take(8)}"
        sessionState.activeProcessId = processId

        val relBinPath = binFile.canonicalFile
            .relativeTo(runtimeInstallerRepository.activeDir.canonicalFile)
            .path
            .replace('\\', '/')
        val shellCmd = buildPiInvocation(
            piAbsPath = binFile.absolutePath,
            piRelPath = relBinPath,
            sessionSubpath = sessionState.sessionSubpath,
            modelId = validModel.id,
            hasAppendSystem = writtenDir.appendSystemFile != null,
            stagedAttachments = stagedAttachments,
        )

        val runtimeEnv = buildMap {
            put("PI_CODING_AGENT_DIR", writtenDir.agentDir.absolutePath)
            put("PI_OFFLINE", "1")
            put("DO_NOT_TRACK", "1")
            put("AGT_PROMPT_FILE", promptFile.absolutePath)
            writtenDir.appendSystemFile?.let { put("AGT_PI_APPEND_FILE", it.absolutePath) }
            activeMcpServers.forEach { srv ->
                val envName = srv.envVarTokenName
                val tok = srv.bearerToken
                if (!envName.isNullOrBlank() && !tok.isNullOrBlank()) {
                    put(envName, tok)
                }
            }
        }

        val worker = launch(Dispatchers.IO) {
            trySend(AgentEvent.SessionStarted(session.sessionId, "Pi Coding Agent v${availability.version.orEmpty()}"))
            trySend(AgentEvent.StateChanged(TaskState.EXECUTING))

            var promptTokens: Int? = null
            var completionTokens: Int? = null
            var fatalEmitted = false
            val seenToolCalls = LinkedHashSet<String>()
            val seenAuditKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val auditJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
            val toolCallContexts = ConcurrentHashMap<String, ToolCallContext>()
            val toolBroker = ToolBroker(
                projectRootPath = session.project.rootPath,
                knownSecretsProvider = { knownSecrets },
                extensionPolicyProvider = { activeExtPolicy },
                onAuditRecorded = { entry ->
                    val repo = intelligenceRepository ?: return@ToolBroker
                    val key = "${entry.toolCallId}:${entry.decision}"
                    if (seenAuditKeys.add(key)) {
                        val outcome = when (entry.decision) {
                            "ALLOWED" -> FlightRecorderOutcome.ALLOWED
                            "AWAITING_APPROVAL" -> FlightRecorderOutcome.REQUIRES_APPROVAL
                            else -> FlightRecorderOutcome.REJECTED
                        }
                        auditJobs += launch(Dispatchers.IO) {
                            repo.recordFlightEvent(
                                projectId = session.project.id,
                                taskId = task.taskId,
                                eventType = FlightRecorderEventType.TOOL_CHECK,
                                outcome = outcome,
                                toolName = entry.toolName,
                                commandOrPath = entry.summary,
                                outputExcerpt = "Permission class: ${entry.permissionClass.name} (${entry.decision})",
                                modelId = validModel.id,
                                backendId = id,
                                timestampMs = entry.timestampMs,
                            )
                        }
                    }
                },
            )

            runtimeExecutor.execute(
                RuntimeCommand(
                    command = shellCmd,
                    workingDirectory = session.project.rootPath,
                    timeoutSeconds = prefs.commandTimeoutSeconds,
                    processId = processId,
                    environment = runtimeEnv,
                    maxOutputLines = 4000,
                )
            ).collect { procEvent ->
                when (procEvent) {
                    is ProcessEvent.Started -> {
                        trySend(AgentEvent.StatusMessage("Pi Coding Agent running (PID ${procEvent.pid ?: "?"})"))
                    }
                    is ProcessEvent.Stdout -> {
                        val parsed = PiEventParser.parseLine(procEvent.line, knownSecrets)
                        if (parsed == null) {
                            val cleaned = SecretRedactor.redact(procEvent.line, knownSecrets).trim()
                            if (cleaned.isNotEmpty() && !cleaned.startsWith("{")) {
                                trySend(AgentEvent.TextDelta("$cleaned\n"))
                            }
                        } else {
                            when (parsed) {
                                is PiParsedEvent.Status -> {
                                    trySend(AgentEvent.StatusMessage(parsed.message))
                                }
                                is PiParsedEvent.TextDelta -> {
                                    trySend(AgentEvent.TextDelta(parsed.text))
                                }
                                is PiParsedEvent.ToolUse -> {
                                    val prevCtx = toolCallContexts[parsed.callId]
                                    val mergedPerm = if (parsed.argumentsJson.isNotBlank() || prevCtx == null) {
                                        parsed.permissionClass
                                    } else {
                                        prevCtx.permissionClass
                                    }
                                    val mergedPath = parsed.affectedRelativePath ?: prevCtx?.affectedRelativePath
                                    val mergedSummary = if (parsed.summary == "Executing ${parsed.toolName}" && prevCtx != null) {
                                        prevCtx.summary
                                    } else {
                                        parsed.summary
                                    }
                                    val mergedArgs = parsed.argumentsJson.ifBlank { prevCtx?.argumentsJson.orEmpty() }
                                    val mergedRawArgs = parsed.rawArgumentsJson.ifBlank { prevCtx?.rawArgumentsJson.orEmpty() }
                                    toolCallContexts[parsed.callId] = ToolCallContext(
                                        toolName = parsed.toolName,
                                        permissionClass = mergedPerm,
                                        summary = mergedSummary,
                                        argumentsJson = mergedArgs,
                                        rawArgumentsJson = mergedRawArgs,
                                        affectedRelativePath = mergedPath,
                                    )

                                    val isFirstSight = seenToolCalls.add(parsed.callId)
                                    if (isFirstSight) {
                                        trySend(
                                            AgentEvent.ToolRequested(
                                                toolCallId = parsed.callId,
                                                toolName = parsed.toolName,
                                                permissionClass = mergedPerm,
                                                summary = mergedSummary,
                                                argumentsJson = mergedArgs,
                                            )
                                        )
                                    }

                                    val decision = toolBroker.evaluateToolCall(
                                        toolCallId = parsed.callId,
                                        rawToolName = parsed.toolName,
                                        rawArgumentsJson = mergedRawArgs,
                                        preferences = prefs,
                                        alreadyApproved = parsed.status == ToolUseStatus.COMPLETED,
                                    )

                                    if (decision is ToolBrokerDecision.Rejected) {
                                        trySend(
                                            AgentEvent.ToolCompleted(
                                                toolCallId = parsed.callId,
                                                toolName = parsed.toolName,
                                                success = false,
                                                outputSummary = decision.error.userMessage,
                                            )
                                        )
                                        trySend(AgentEvent.StatusMessage("Tool Broker blocked ${parsed.toolName}: ${decision.error.userMessage}"))
                                        if (!fatalEmitted) {
                                            fatalEmitted = true
                                            trySend(AgentEvent.StateChanged(TaskState.FAILED))
                                            trySend(AgentEvent.Failed(decision.error.userMessage, decision.error))
                                            runtimeExecutor.terminate(processId)
                                        }
                                    } else {
                                        when (parsed.status) {
                                            ToolUseStatus.PENDING, ToolUseStatus.RUNNING -> {
                                                if (isFirstSight && (decision is ToolBrokerDecision.RequiresApproval ||
                                                        shouldRequireApproval(mergedPerm, prefs))
                                                ) {
                                                    val approved = awaitUserApproval(
                                                        sessionState = sessionState,
                                                        approvalId = "apr-${parsed.callId}",
                                                        title = "Approve ${parsed.toolName}",
                                                        reason = mergedSummary,
                                                        permissionClass = mergedPerm,
                                                        toolCallId = parsed.callId,
                                                        emit = { trySend(it) },
                                                    )
                                                    if (!approved) {
                                                        trySend(
                                                            AgentEvent.ToolCompleted(
                                                                toolCallId = parsed.callId,
                                                                toolName = parsed.toolName,
                                                                success = false,
                                                                outputSummary = "Denied by user policy",
                                                            )
                                                        )
                                                        if (!fatalEmitted) {
                                                            fatalEmitted = true
                                                            val deniedErr = AgtError.PermissionDenied("Action denied by user: $mergedSummary")
                                                            trySend(AgentEvent.StateChanged(TaskState.FAILED))
                                                            trySend(AgentEvent.Failed(deniedErr.userMessage, deniedErr))
                                                            runtimeExecutor.terminate(processId)
                                                        }
                                                    } else {
                                                        trySend(
                                                            AgentEvent.ToolStarted(
                                                                toolCallId = parsed.callId,
                                                                toolName = parsed.toolName,
                                                                summary = mergedSummary,
                                                            )
                                                        )
                                                    }
                                                } else {
                                                    trySend(
                                                        AgentEvent.ToolStarted(
                                                            toolCallId = parsed.callId,
                                                            toolName = parsed.toolName,
                                                            summary = mergedSummary,
                                                        )
                                                    )
                                                }
                                            }
                                            ToolUseStatus.COMPLETED -> {
                                                trySend(
                                                    AgentEvent.ToolCompleted(
                                                        toolCallId = parsed.callId,
                                                        toolName = parsed.toolName,
                                                        success = true,
                                                        outputSummary = parsed.outputSummary,
                                                    )
                                                )
                                                if (mergedPath != null && mergedPerm == PermissionClass.PROJECT_WRITE) {
                                                    trySend(
                                                        AgentEvent.FileChanged(
                                                            relativePath = mergedPath,
                                                            summary = "${parsed.toolName}: $mergedPath",
                                                        )
                                                    )
                                                }
                                            }
                                            ToolUseStatus.ERROR -> {
                                                trySend(
                                                    AgentEvent.ToolCompleted(
                                                        toolCallId = parsed.callId,
                                                        toolName = parsed.toolName,
                                                        success = false,
                                                        outputSummary = parsed.outputSummary.ifBlank { "Tool execution failed" },
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                is PiParsedEvent.StepFinished -> {
                                    if (parsed.promptTokens != null) promptTokens = parsed.promptTokens
                                    if (parsed.completionTokens != null) completionTokens = parsed.completionTokens
                                }
                                is PiParsedEvent.ErrorEvent -> {
                                    if (!fatalEmitted) {
                                        fatalEmitted = true
                                        val nextState = if (parsed.error is AgtError.ProcessCancelled) {
                                            TaskState.CANCELLED
                                        } else {
                                            TaskState.FAILED
                                        }
                                        trySend(AgentEvent.StateChanged(nextState))
                                        trySend(AgentEvent.Failed(parsed.error.userMessage, parsed.error))
                                    }
                                }
                            }
                        }
                    }
                    is ProcessEvent.Stderr -> {
                        val safe = SecretRedactor.redact(procEvent.line, knownSecrets).trim()
                        if (safe.isNotEmpty()) {
                            trySend(AgentEvent.StatusMessage("[pi] $safe"))
                        }
                    }
                    is ProcessEvent.Exited -> {
                        if (!fatalEmitted) {
                            if (procEvent.exitCode == 0) {
                                trySend(AgentEvent.StateChanged(TaskState.COMPLETED))
                                trySend(AgentEvent.Completed(promptTokens, completionTokens))
                            } else {
                                val err = AgtError.ProcessStartFailed("Pi exited with code ${procEvent.exitCode}.")
                                trySend(AgentEvent.StateChanged(TaskState.FAILED))
                                trySend(AgentEvent.Failed(err.userMessage, err))
                            }
                        }
                    }
                    is ProcessEvent.Failed -> {
                        if (!fatalEmitted) {
                            fatalEmitted = true
                            val nextState = if (procEvent.error is AgtError.ProcessCancelled) {
                                TaskState.CANCELLED
                            } else {
                                TaskState.FAILED
                            }
                            trySend(AgentEvent.StateChanged(nextState))
                            trySend(AgentEvent.Failed(procEvent.error.userMessage, procEvent.error))
                        }
                    }
                }
            }
            auditJobs.toList().forEach { runCatching { it.join() } }
            close()
        }

        awaitClose {
            worker.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                runtimeExecutor.terminate(processId)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun respondToApproval(
        session: AgentSession,
        approvalId: String,
        approved: Boolean,
    ): Result<Unit> {
        val state = activeSessions[session.sessionId]
            ?: return Result.failure(IllegalStateException("Pi session ${session.sessionId} is not active"))
        val deferred = state.pendingApprovals.remove(approvalId)
            ?: return Result.failure(IllegalStateException("No pending approval $approvalId"))
        deferred.complete(approved)
        return Result.success(Unit)
    }

    override suspend fun stop(session: AgentSession) {
        val state = activeSessions.remove(session.sessionId) ?: return
        state.pendingApprovals.values.forEach { it.complete(false) }
        state.pendingApprovals.clear()
        state.activeProcessId?.let { procId ->
            runtimeExecutor.terminate(procId)
        }
        state.bridge.stop()
        state.sessionDir.deleteRecursively()
    }

    private suspend fun awaitUserApproval(
        sessionState: ActiveSessionState,
        approvalId: String,
        title: String,
        reason: String,
        permissionClass: PermissionClass,
        toolCallId: String?,
        emit: (AgentEvent) -> Unit,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        sessionState.pendingApprovals[approvalId] = deferred
        emit(AgentEvent.StateChanged(TaskState.AWAITING_TOOL_APPROVAL))
        emit(
            AgentEvent.ApprovalRequired(
                approvalId = approvalId,
                title = title,
                reason = reason,
                permissionClass = permissionClass,
                toolCallId = toolCallId,
            )
        )
        val approved = try {
            deferred.await()
        } finally {
            sessionState.pendingApprovals.remove(approvalId)
        }
        if (approved) {
            emit(AgentEvent.StateChanged(TaskState.EXECUTING))
        } else {
            emit(AgentEvent.StatusMessage("Action denied: $title"))
        }
        return approved
    }

    private fun shouldRequireApproval(
        permClass: PermissionClass,
        prefs: AppPreferencesState,
    ): Boolean = when (permClass) {
        PermissionClass.DESTRUCTIVE -> prefs.requireDestructiveApproval
        PermissionClass.EXTERNAL_ACCOUNT_ACTION -> prefs.requirePushApproval
        PermissionClass.PRODUCTION_DEPLOYMENT -> prefs.requireDeployApproval
        else -> false
    }

    private data class StagedAttachment(
        val attachmentId: String,
        val kind: ComposerAttachmentKind,
        val displayName: String,
        val hostPath: String,
        val chrootPath: String,
    )

    private fun stageAttachmentsIntoSession(
        attachments: List<AgentAttachment>,
        sessionDir: File,
        sessionSubpath: String,
    ): List<StagedAttachment> {
        val attachDir = File(sessionDir, "attachments").apply { mkdirs() }
        val result = mutableListOf<StagedAttachment>()
        attachments.forEachIndexed { idx, att ->
            val rawPath = att.localPath ?: return@forEachIndexed
            if (att.kind == ComposerAttachmentKind.SKILLS ||
                att.kind == ComposerAttachmentKind.MCP_SERVERS ||
                att.kind == ComposerAttachmentKind.PLUGINS
            ) {
                return@forEachIndexed
            }
            val src = File(rawPath)
            if (!src.exists() || !src.isFile || !src.canRead()) return@forEachIndexed
            val safeName = att.displayName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "attachment_$idx" }
            val fileName = "${idx}_$safeName"
            val dest = File(attachDir, fileName)
            runCatching { src.copyTo(dest, overwrite = true) }.getOrElse { return@forEachIndexed }
            result += StagedAttachment(
                attachmentId = att.id,
                kind = att.kind,
                displayName = att.displayName,
                hostPath = dest.absolutePath,
                chrootPath = "/$sessionSubpath/attachments/$fileName",
            )
        }
        return result
    }

    private fun buildPromptWithAttachments(
        task: AgentTask,
        stagedAttachments: List<StagedAttachment>,
    ): String = buildString {
        append(task.prompt)
        if (task.attachments.isNotEmpty()) {
            val stagedById = stagedAttachments.associateBy { it.attachmentId }
            appendLine()
            appendLine()
            appendLine("Attached Context:")
            task.attachments.forEach { att ->
                val staged = stagedById[att.id]
                val refPath = staged?.chrootPath ?: att.localPath
                appendLine("- [${att.kind.label}] ${att.displayName}${refPath?.let { " ($it)" }.orEmpty()}")
            }
        }
    }.trim()

    private fun buildPiInvocation(
        piAbsPath: String,
        piRelPath: String,
        sessionSubpath: String,
        modelId: String,
        hasAppendSystem: Boolean,
        stagedAttachments: List<StagedAttachment>,
    ): String {
        val chrootBin = posixQuote("/$piRelPath")
        val absBin = posixQuote(piAbsPath)
        val chrootAgentDir = posixQuote("/$sessionSubpath/pi-agent")
        val chrootModels = posixQuote("/$sessionSubpath/pi-agent/models.json")
        val chrootPrompt = posixQuote("/$sessionSubpath/prompt.txt")
        val chrootAppend = posixQuote("/$sessionSubpath/pi-agent/append_system.md")
        val providerArg = posixQuote(PiConfigAdapter.PROVIDER_ID)
        val modelArg = posixQuote(modelId)

        val chrootAtArgs = stagedAttachments.joinToString(" ") { posixQuote("@${it.chrootPath}") }
        val hostAtArgs = stagedAttachments.joinToString(" ") { posixQuote("@${it.hostPath}") }
        val chrootExtra = if (chrootAtArgs.isNotEmpty()) " $chrootAtArgs" else ""
        val hostExtra = if (hostAtArgs.isNotEmpty()) " $hostAtArgs" else ""
        val chrootAppendFlag = if (hasAppendSystem) " --append-system-prompt $chrootAppend" else ""
        val hostAppendFlag = if (hasAppendSystem) " --append-system-prompt \"\$AGT_PI_APPEND_FILE\"" else ""

        val chrootCmd = if (piRelPath.endsWith(".js")) "node $chrootBin" else chrootBin
        val hostCmd = if (piAbsPath.endsWith(".js")) "node $absBin" else absBin

        return "if [ -n \"\${AGT_IS_PROOT:-}\" ] && [ -e $chrootBin ] && [ -f $chrootModels ]; then " +
            "export PI_CODING_AGENT_DIR=$chrootAgentDir; " +
            "$chrootCmd -p --mode json --no-session --provider $providerArg --model $modelArg$chrootAppendFlag$chrootExtra \"\$(cat $chrootPrompt)\"; " +
            "else " +
            "$hostCmd -p --mode json --no-session --provider $providerArg --model $modelArg$hostAppendFlag$hostExtra \"\$(cat \"\$AGT_PROMPT_FILE\")\"; " +
            "fi"
    }

    private fun posixQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
