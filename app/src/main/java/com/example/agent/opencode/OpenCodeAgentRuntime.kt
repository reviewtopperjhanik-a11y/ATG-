package com.example.agent.opencode

import android.content.Context
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
import com.example.domain.model.ActiveExtensionPolicy
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.ToolBrokerDecision
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentRuntime
import com.example.domain.contracts.AgentSession
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.contracts.RuntimeAvailability
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.PermissionClass
import com.example.domain.model.TaskState
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
 * First concrete [AgentRuntime] implementation adapting OpenCode CLI + JH GetWay Pro
 * via [OpenCodeGatewayBridgeServer], [OpenCodeConfigAdapter], and [OpenCodeEventParser].
 */
class OpenCodeAgentRuntime(
    context: Context,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val openCodeInstaller: OpenCodeInstallerRepository,
    private val runtimeExecutor: LocalRuntimeExecutor,
    private val gatewayRepository: GatewayRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialVault: GatewayCredentialVault,
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
    private val gitHubCredentialVault: GitHubCredentialVault? = null,
    private val extensionRegistryRepository: ExtensionRegistryRepository? = null,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
) : AgentRuntime {

    override val id: String = "opencode"

    private val appContext = context.applicationContext

    private data class ActiveSessionState(
        val session: AgentSession,
        val sessionDir: File,
        val sessionSubpath: String,
        val bridge: OpenCodeGatewayBridgeServer,
        var activeProcessId: String? = null,
        val pendingApprovals: ConcurrentHashMap<String, CompletableDeferred<Boolean>> = ConcurrentHashMap(),
    )

    private val activeSessions = ConcurrentHashMap<String, ActiveSessionState>()

    override suspend fun checkAvailability(): RuntimeAvailability {
        val baseReady = runtimeExecutor.ensureReady()
        val ocState = openCodeInstaller.refreshStatusSync()
        val isReady = baseReady.isInstalled && ocState.isInstalled
        val detail = when {
            !baseReady.isInstalled -> baseReady.detail
            !ocState.isInstalled -> ocState.statusDetail
            else -> "OpenCode v${ocState.version.orEmpty()} ready (${baseReady.detail})"
        }
        return RuntimeAvailability(
            isInstalled = isReady,
            version = ocState.version,
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

        val sessionId = "oc-${UUID.randomUUID().toString().take(8)}"
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
                    reason = "OpenCode session is not active.",
                    error = AgtError.ProcessStartFailed("OpenCode session is not active."),
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

        val binFile = openCodeInstaller.resolveBinaryFile()
        if (binFile == null) {
            val err = AgtError.RuntimeNotInstalled("OpenCode binary missing inside Linux runtime.")
            trySend(AgentEvent.Failed(err.userMessage, err))
            close()
            return@callbackFlow
        }

        val models = gatewayRepository.discoveredModels.first()
        val modelInfo = models.firstOrNull { it.id == task.modelId }
        val capError = OpenCodeConfigAdapter.validateTaskCapabilities(
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
                attachedPluginIds.isNotEmpty() || cfg.agentTarget != com.example.domain.model.ExtensionAgentTarget.PI
            }
        } else {
            emptyList()
        }
        val activeExtPolicy = extensionRegistryRepository?.buildActiveExtensionPolicy(
            selectedMcpIds = attachedMcpIds.takeIf { it.isNotEmpty() },
            selectedPluginIds = attachedPluginIds.takeIf { it.isNotEmpty() }
                ?: activePlugins.map { it.id }.toSet().takeIf { prefs.allowProotSandboxExtensions },
        ) ?: ActiveExtensionPolicy()

        val configJson = OpenCodeConfigAdapter.buildOpenCodeConfigJson(
            bridgeBaseUrl = sessionState.bridge.bridgeBaseUrl,
            ephemeralSessionNonce = sessionState.bridge.sessionNonce,
            protocolMode = task.protocolMode,
            model = validModel,
            preferences = prefs,
            mcpServers = activeMcpServers,
            plugins = activePlugins,
        )
        val configFile = OpenCodeConfigAdapter.writeSessionConfig(sessionState.sessionDir, configJson)
        extensionRegistryRepository?.syncEnabledSkillsToDirectory(
            skillsTargetDir = File(sessionState.sessionDir, "skills"),
            selectedSkillIds = attachedSkillIds.takeIf { it.isNotEmpty() },
        )
        if (prefs.allowProotSandboxExtensions) {
            extensionRegistryRepository?.syncEnabledPluginsToDirectory(
                pluginsTargetDir = File(sessionState.sessionDir, "plugins"),
                selectedPluginIds = activePlugins.map { it.id }.toSet(),
            )
        }

        val stagedAttachments = stageAttachmentsIntoSession(
            attachments = task.attachments,
            sessionDir = sessionState.sessionDir,
            sessionSubpath = sessionState.sessionSubpath,
        )

        val promptFile = File(sessionState.sessionDir, "prompt.txt")
        promptFile.writeText(buildPromptWithAttachments(task, activeSkills, stagedAttachments))

        val processId = "oc-proc-${UUID.randomUUID().toString().take(8)}"
        sessionState.activeProcessId = processId

        val relBinPath = binFile.canonicalFile
            .relativeTo(runtimeInstallerRepository.activeDir.canonicalFile)
            .path
            .replace('\\', '/')
        val shellCmd = buildOpenCodeInvocation(
            opencodeAbsPath = binFile.absolutePath,
            opencodeRelPath = relBinPath,
            sessionSubpath = sessionState.sessionSubpath,
            modelId = validModel.id,
            stagedAttachments = stagedAttachments,
        )

        val runtimeEnv = buildMap {
            put("OPENCODE_CONFIG", configFile.absolutePath)
            put("OPENCODE_CONFIG_DIR", sessionState.sessionDir.absolutePath)
            put("AGT_PROMPT_FILE", promptFile.absolutePath)
            activeMcpServers.forEach { srv ->
                val envName = srv.envVarTokenName
                val tok = srv.bearerToken
                if (!envName.isNullOrBlank() && !tok.isNullOrBlank()) {
                    put(envName, tok)
                }
            }
        }

        val worker = launch(Dispatchers.IO) {
            trySend(AgentEvent.SessionStarted(session.sessionId, "OpenCode v${availability.version.orEmpty()}"))
            trySend(AgentEvent.StateChanged(TaskState.EXECUTING))

            var promptTokens: Int? = null
            var completionTokens: Int? = null
            var fatalEmitted = false
            val seenToolCalls = LinkedHashSet<String>()
            val seenAuditKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val auditJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
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
                        trySend(AgentEvent.StatusMessage("OpenCode session running (PID ${procEvent.pid ?: "?"})"))
                    }
                    is ProcessEvent.Stdout -> {
                        val parsed = OpenCodeEventParser.parseLine(procEvent.line, knownSecrets)
                        if (parsed == null) {
                            val cleaned = SecretRedactor.redact(procEvent.line, knownSecrets).trim()
                            if (cleaned.isNotEmpty() && !cleaned.startsWith("{")) {
                                trySend(AgentEvent.TextDelta("$cleaned\n"))
                            }
                        } else {
                            when (parsed) {
                                is OpenCodeParsedEvent.Status -> {
                                    trySend(AgentEvent.StatusMessage(parsed.message))
                                }
                                is OpenCodeParsedEvent.TextDelta -> {
                                    trySend(AgentEvent.TextDelta(parsed.text))
                                }
                                is OpenCodeParsedEvent.ToolUse -> {
                                    val isFirstSight = seenToolCalls.add(parsed.callId)
                                    if (isFirstSight) {
                                        trySend(
                                            AgentEvent.ToolRequested(
                                                toolCallId = parsed.callId,
                                                toolName = parsed.toolName,
                                                permissionClass = parsed.permissionClass,
                                                summary = parsed.summary,
                                                argumentsJson = parsed.argumentsJson,
                                            )
                                        )
                                    }
                                    val decision = toolBroker.evaluateToolCall(
                                        toolCallId = parsed.callId,
                                        rawToolName = parsed.toolName,
                                        rawArgumentsJson = parsed.rawArgumentsJson,
                                        preferences = prefs,
                                        alreadyApproved = parsed.status == ToolUseStatus.COMPLETED,
                                    )
                                    if (decision is ToolBrokerDecision.Rejected) {
                                        runtimeExecutor.sendInput(processId, "deny")
                                        trySend(
                                            AgentEvent.ToolCompleted(
                                                toolCallId = parsed.callId,
                                                toolName = parsed.toolName,
                                                success = false,
                                                outputSummary = decision.error.userMessage,
                                            )
                                        )
                                        trySend(AgentEvent.StatusMessage("Tool Broker blocked ${parsed.toolName}: ${decision.error.userMessage}"))
                                        if (parsed.status != ToolUseStatus.ERROR && !fatalEmitted) {
                                            fatalEmitted = true
                                            trySend(AgentEvent.StateChanged(TaskState.FAILED))
                                            trySend(AgentEvent.Failed(decision.error.userMessage, decision.error))
                                            runtimeExecutor.terminate(processId)
                                        }
                                    } else {
                                        when (parsed.status) {
                                            ToolUseStatus.PENDING -> {
                                                if (decision is ToolBrokerDecision.RequiresApproval ||
                                                    shouldRequireApproval(parsed.permissionClass, prefs)
                                                ) {
                                                    val approved = awaitUserApproval(
                                                        sessionState = sessionState,
                                                        processId = processId,
                                                        approvalId = "apr-${parsed.callId}",
                                                        title = "Approve ${parsed.toolName}",
                                                        reason = parsed.summary,
                                                        permissionClass = parsed.permissionClass,
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
                                                    }
                                                }
                                            }
                                            ToolUseStatus.RUNNING -> {
                                                trySend(
                                                    AgentEvent.ToolStarted(
                                                        toolCallId = parsed.callId,
                                                        toolName = parsed.toolName,
                                                        summary = parsed.summary,
                                                    )
                                                )
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
                                                if (parsed.affectedRelativePath != null &&
                                                    parsed.permissionClass == PermissionClass.PROJECT_WRITE
                                                ) {
                                                    trySend(
                                                        AgentEvent.FileChanged(
                                                            relativePath = parsed.affectedRelativePath,
                                                            summary = "${parsed.toolName}: ${parsed.affectedRelativePath}",
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
                                is OpenCodeParsedEvent.PermissionAsked -> {
                                    awaitUserApproval(
                                        sessionState = sessionState,
                                        processId = processId,
                                        approvalId = parsed.approvalId,
                                        title = parsed.title,
                                        reason = parsed.reason,
                                        permissionClass = parsed.permissionClass,
                                        toolCallId = parsed.toolCallId,
                                        emit = { trySend(it) },
                                    )
                                }
                                is OpenCodeParsedEvent.StepFinished -> {
                                    if (parsed.promptTokens != null) promptTokens = parsed.promptTokens
                                    if (parsed.completionTokens != null) completionTokens = parsed.completionTokens
                                }
                                is OpenCodeParsedEvent.ErrorEvent -> {
                                    fatalEmitted = true
                                    trySend(AgentEvent.StateChanged(TaskState.FAILED))
                                    trySend(AgentEvent.Failed(parsed.error.userMessage, parsed.error))
                                }
                            }
                        }
                    }
                    is ProcessEvent.Stderr -> {
                        val safe = SecretRedactor.redact(procEvent.line, knownSecrets).trim()
                        if (safe.isNotEmpty()) {
                            trySend(AgentEvent.StatusMessage("[opencode] $safe"))
                        }
                    }
                    is ProcessEvent.Exited -> {
                        if (!fatalEmitted) {
                            if (procEvent.exitCode == 0) {
                                trySend(AgentEvent.StateChanged(TaskState.COMPLETED))
                                trySend(AgentEvent.Completed(promptTokens, completionTokens))
                            } else {
                                val err = AgtError.ProcessStartFailed("OpenCode exited with code ${procEvent.exitCode}.")
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
            ?: return Result.failure(IllegalStateException("OpenCode session ${session.sessionId} is not active"))
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
        processId: String,
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
        runtimeExecutor.sendInput(processId, if (approved) "allow" else "deny")
        if (approved) {
            emit(AgentEvent.StateChanged(TaskState.EXECUTING))
        } else {
            emit(AgentEvent.StatusMessage("Action denied: $title"))
        }
        return approved
    }

    private fun shouldRequireApproval(
        permClass: PermissionClass,
        prefs: com.example.domain.model.AppPreferencesState,
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
        attachments: List<com.example.domain.model.AgentAttachment>,
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
        activeSkills: List<Pair<ExtensionRecord, String>> = emptyList(),
        stagedAttachments: List<StagedAttachment> = emptyList(),
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
        if (activeSkills.isNotEmpty()) {
            appendLine()
            appendLine("Active Non-Executable Skills (SKILL.md):")
            activeSkills.forEach { (record, markdown) ->
                appendLine()
                appendLine("### Skill: ${record.name} (${record.id}, v${record.version})")
                appendLine(markdown.take(4000))
            }
        }
    }.trim()

    private fun buildOpenCodeInvocation(
        opencodeAbsPath: String,
        opencodeRelPath: String,
        sessionSubpath: String,
        modelId: String,
        stagedAttachments: List<StagedAttachment> = emptyList(),
    ): String {
        val chrootBin = posixQuote("/$opencodeRelPath")
        val absBin = posixQuote(opencodeAbsPath)
        val chrootCfg = posixQuote("/$sessionSubpath/opencode.json")
        val chrootCfgDir = posixQuote("/$sessionSubpath")
        val chrootPrompt = posixQuote("/$sessionSubpath/prompt.txt")
        val modelArg = posixQuote("jh_gateway/$modelId")
        val chrootFileArgs = stagedAttachments.joinToString(" ") { "--file ${posixQuote(it.chrootPath)}" }
        val hostFileArgs = stagedAttachments.joinToString(" ") { "--file ${posixQuote(it.hostPath)}" }
        val chrootExtra = if (chrootFileArgs.isNotEmpty()) " $chrootFileArgs" else ""
        val hostExtra = if (hostFileArgs.isNotEmpty()) " $hostFileArgs" else ""
        return "if [ -n \"\${AGT_IS_PROOT:-}\" ] && [ -x $chrootBin ] && [ -f $chrootCfg ]; then " +
            "export OPENCODE_CONFIG=$chrootCfg OPENCODE_CONFIG_DIR=$chrootCfgDir; " +
            "$chrootBin run --format json --model $modelArg$chrootExtra \"\$(cat $chrootPrompt)\"; " +
            "else " +
            "$absBin run --format json --model $modelArg$hostExtra \"\$(cat \"\$AGT_PROMPT_FILE\")\"; " +
            "fi"
    }

    private fun posixQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
