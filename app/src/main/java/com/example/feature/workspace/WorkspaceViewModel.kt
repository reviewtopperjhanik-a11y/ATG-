package com.example.feature.workspace

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.DelegatingAgentRuntime
import com.example.agent.opencode.OpenCodeConfigAdapter
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.OrchestratorEvent
import com.example.agent.orchestrator.OrchestratorTaskRequest
import com.example.agent.orchestrator.ResumeTaskRequest
import com.example.agent.pi.PiInstallerRepository
import com.example.core.common.AgtError
import com.example.core.notifications.AgtNotificationManager
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.PreviewServerManager
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentRuntime
import com.example.domain.contracts.CheckpointManager
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.contracts.RuntimeCommand
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AgentBackendId
import com.example.domain.model.AgentTimelineItem
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ChatMessageRecord
import com.example.domain.model.ChatMessageRole
import com.example.domain.model.CheckpointSummary
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilSession
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.FileTreeEntry
import com.example.domain.model.FlightRecorderEvent
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.GitHubAccountState
import com.example.domain.model.GitHubPullRequestResult
import com.example.domain.model.GitWorkspaceStatus
import com.example.domain.model.KnowledgeRetrievalHit
import com.example.domain.model.OpenCodeRuntimeState
import com.example.domain.model.PiRuntimeState
import com.example.domain.model.PreviewConsoleLine
import com.example.domain.model.PreviewServerStatus
import com.example.domain.model.ProjectDiff
import com.example.domain.model.ProjectKnowledgeEntry
import com.example.domain.model.ProjectMemory
import com.example.domain.model.ProjectSummary
import com.example.domain.model.RuntimeInstallState
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.domain.model.TerminalLine
import com.example.domain.model.ValidationCheckResult
import com.example.domain.model.ValidationOutcome
import com.example.domain.model.WorkspaceTab
import com.example.runtime.RuntimeExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class GitWorkflowUiState(
    val showDialog: Boolean = false,
    val isLoadingStatus: Boolean = false,
    val isActionBusy: Boolean = false,
    val status: GitWorkspaceStatus = GitWorkspaceStatus(),
    val gitHubAccount: GitHubAccountState = GitHubAccountState(),
    val commitMessageDraft: String = "",
    val excludeSensitiveFiles: Boolean = true,
    val showDiffPreview: Boolean = false,
    val pendingPushApproval: Boolean = false,
    val showPrForm: Boolean = false,
    val pendingPrApproval: Boolean = false,
    val prTitleDraft: String = "",
    val prBodyDraft: String = "",
    val prHeadBranchDraft: String = "",
    val prBaseBranchDraft: String = "",
    val actionMessage: String? = null,
    val actionError: String? = null,
    val lastPullRequest: GitHubPullRequestResult? = null,
)

data class WorkspaceUiState(
    val project: ProjectSummary? = null,
    val isLoadingProject: Boolean = true,
    val selectedTab: WorkspaceTab = WorkspaceTab.AGENT,
    val composerText: String = "",
    val composerAttachments: List<AgentAttachment> = emptyList(),
    val availableModels: List<GatewayModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val protocolMode: GatewayProtocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
    val isStreaming: Boolean = false,
    val activeTaskState: TaskState? = null,
    val timeline: List<AgentTimelineItem> = emptyList(),
    val activeAgentId: AgentBackendId = AgentBackendId.OPENCODE,
    val activeAgentInstalled: Boolean = false,
    val activeAgentVersion: String? = null,
    val openCodeInstalled: Boolean = false,
    val openCodeVersion: String? = null,
    val checkpoints: List<CheckpointSummary> = emptyList(),
    val showCheckpointsSheet: Boolean = false,
    val selectedDiff: ProjectDiff? = null,
    val diffLoading: Boolean = false,
    val checkpointPendingRestore: CheckpointSummary? = null,
    val checkpointActionMessage: String? = null,
    val recoveredInterruptedCount: Int = 0,
    val currentRelativePath: String = "",
    val files: List<FileTreeEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val filesError: String? = null,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val isEditingFile: Boolean = false,
    val editedFileDraft: String = "",
    val showCreateFileDialog: Boolean = false,
    val newFileNameDraft: String = "",
    val entryPendingDeletion: FileTreeEntry? = null,
    val gitState: GitWorkflowUiState = GitWorkflowUiState(),
    val runtimeInstalled: Boolean = false,
    val runtimeVersionLabel: String? = null,
    val terminalLines: List<TerminalLine> = listOf(
        TerminalLine(
            id = "init-0",
            text = "Linux runtime not installed. Complete Setup / Settings -> Runtime to provision the rootfs.",
            isError = false,
        )
    ),
    val terminalInput: String = "",
    val terminalIsRunning: Boolean = false,
    val activeTerminalProcessId: String? = null,
    val activeTerminalPid: Long? = null,
    val lastTerminalExitCode: Int? = null,
    val previewUrl: String = "",
    val previewCommandDraft: String = "",
    val previewDetectedFramework: String? = null,
    val previewStatus: PreviewServerStatus = PreviewServerStatus.STOPPED,
    val previewStatusMessage: String = "Server stopped",
    val previewConsoleLines: List<PreviewConsoleLine> = emptyList(),
    val previewShowLogs: Boolean = false,
    val previewReloadNonce: Int = 0,
    val previewBrowserActive: Boolean = false,
    val previewUrlError: String? = null,
    val activePreviewProcessId: String? = null,
    val enabledSkills: List<ExtensionRecord> = emptyList(),
    val enabledMcpServers: List<ExtensionRecord> = emptyList(),
    val enabledPlugins: List<ExtensionRecord> = emptyList(),
    val allowProotSandboxExtensions: Boolean = false,
    val pendingExtensionPickerKind: ComposerAttachmentKind? = null,
    val flightRecorderEvents: List<FlightRecorderEvent> = emptyList(),
    val taskSnapshots: List<TaskStateSnapshot> = emptyList(),
    val projectMemory: ProjectMemory? = null,
    val knowledgeEntries: List<ProjectKnowledgeEntry> = emptyList(),
    val knowledgeSearchQuery: String = "",
    val knowledgeSearchResults: List<KnowledgeRetrievalHit> = emptyList(),
    val knowledgeIncludeSuperseded: Boolean = false,
    val knowledgeUsedFts5: Boolean = false,
    val showFlightRecorderSheet: Boolean = false,
    val flightRecorderFilter: FlightRecorderFilter = FlightRecorderFilter.ALL,
    val flightRecorderTaskFilterId: String? = null,
    val selectedExecutionMode: CouncilExecutionMode = CouncilExecutionMode.DIRECT_CODE,
    val hasOverriddenExecutionMode: Boolean = false,
    val councilRoleModelOverrides: Map<CouncilRole, String> = emptyMap(),
    val councilEnabledSpecialists: Set<CouncilRole> = CouncilRole.DEFAULT_SPECIALISTS,
    val councilTokenBudget: Int = 24_000,
) {
    val activeKnowledgeCount: Int
        get() = knowledgeEntries.count { it.isCurrentlyValid }

    val supersededKnowledgeCount: Int
        get() = knowledgeEntries.count { !it.isCurrentlyValid }

    val flightRecorderFailureCount: Int
        get() = flightRecorderEvents.count { it.isFailureOrBlock }

    val latestTaskSnapshot: TaskStateSnapshot?
        get() = flightRecorderTaskFilterId?.let { tid ->
            taskSnapshots.firstOrNull { it.taskId == tid }
        } ?: taskSnapshots.firstOrNull()

    val filteredFlightRecorderEvents: List<FlightRecorderEvent>
        get() = flightRecorderEvents.asSequence()
            .filter { ev ->
                flightRecorderTaskFilterId == null || ev.taskId == flightRecorderTaskFilterId
            }
            .filter { ev ->
                when (flightRecorderFilter) {
                    FlightRecorderFilter.ALL -> true
                    FlightRecorderFilter.FAILURES -> ev.isFailureOrBlock
                    FlightRecorderFilter.TOOLS -> ev.eventType == FlightRecorderEventType.TOOL_CHECK ||
                        ev.eventType == FlightRecorderEventType.TOOL_EXECUTION ||
                        ev.eventType == FlightRecorderEventType.APPROVAL
                    FlightRecorderFilter.VALIDATION -> ev.eventType == FlightRecorderEventType.VALIDATION_RUN ||
                        ev.eventType == FlightRecorderEventType.FIX_LOOP
                    FlightRecorderFilter.STATES -> ev.eventType == FlightRecorderEventType.STATE_TRANSITION ||
                        ev.eventType == FlightRecorderEventType.CHECKPOINT ||
                        ev.eventType == FlightRecorderEventType.PLAN_DECISION
                }
            }
            .toList()
}

class WorkspaceViewModel(
    appContext: Context,
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val checkpointManager: CheckpointManager,
    private val gatewayRepository: GatewayRepository,
    private val gitHubRepository: GitHubRepository,
    private val gitRepositoryService: GitRepositoryService,
    private val preferencesRepository: AppPreferencesRepository,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val openCodeInstallerRepository: OpenCodeInstallerRepository,
    private val runtimeExecutor: LocalRuntimeExecutor,
    private val agentRuntime: AgentRuntime,
    private val agentOrchestrator: AgentOrchestrator,
    private val extensionRegistryRepository: ExtensionRegistryRepository? = null,
    private val piInstallerRepository: PiInstallerRepository? = null,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
    private val notificationManager: AgtNotificationManager? = null,
) : ViewModel() {

    private val applicationContext = appContext.applicationContext
    private val localState = MutableStateFlow(WorkspaceUiState())
    private var activeStreamJob: Job? = null
    private var activeTerminalJob: Job? = null
    private var activePreviewJob: Job? = null
    private var activeTaskId: String? = null

    private data class IntelligenceBundle(
        val flightEvents: List<FlightRecorderEvent> = emptyList(),
        val taskSnapshots: List<TaskStateSnapshot> = emptyList(),
        val projectMemory: ProjectMemory? = null,
        val chatMessages: List<ChatMessageRecord> = emptyList(),
        val knowledgeEntries: List<ProjectKnowledgeEntry> = emptyList(),
    )

    private data class RuntimeAndGitBundle(
        val runtime: RuntimeInstallState,
        val openCode: OpenCodeRuntimeState,
        val pi: PiRuntimeState,
        val checkpoints: List<CheckpointSummary>,
        val gitHubAccount: GitHubAccountState,
        val extensions: List<ExtensionRecord>,
        val intelligence: IntelligenceBundle,
    )

    private val intelligenceFlow = if (intelligenceRepository != null) {
        kotlinx.coroutines.flow.combine(
            intelligenceRepository.observeFlightEventsForProject(projectId),
            intelligenceRepository.observeTaskSnapshots(projectId),
            intelligenceRepository.observeProjectMemory(projectId),
            intelligenceRepository.observeChatMessages(projectId),
            intelligenceRepository.observeAllKnowledge(projectId),
        ) { events, snapshots, memory, chats, knowledge ->
            IntelligenceBundle(
                flightEvents = events,
                taskSnapshots = snapshots,
                projectMemory = memory,
                chatMessages = chats,
                knowledgeEntries = knowledge,
            )
        }
    } else {
        kotlinx.coroutines.flow.flowOf(IntelligenceBundle())
    }

    private val runtimePairFlow = combine(
        combine(
            runtimeInstallerRepository.installState,
            openCodeInstallerRepository.state,
            piInstallerRepository?.state ?: MutableStateFlow(PiRuntimeState()),
        ) { linux, oc, pi -> Triple(linux, oc, pi) },
        checkpointManager.observeForProject(projectId),
        gitHubRepository.accountState,
        extensionRegistryRepository?.observeExtensions()
            ?: kotlinx.coroutines.flow.flowOf(emptyList()),
        intelligenceFlow,
    ) { agentPair, checkpoints, gh, extensions, intelligence ->
        RuntimeAndGitBundle(
            runtime = agentPair.first,
            openCode = agentPair.second,
            pi = agentPair.third,
            checkpoints = checkpoints,
            gitHubAccount = gh,
            extensions = extensions,
            intelligence = intelligence,
        )
    }

    val uiState: StateFlow<WorkspaceUiState> = combine(
        projectRepository.observeProject(projectId),
        projectRepository.observeTasks(projectId),
        gatewayRepository.discoveredModels,
        preferencesRepository.preferences,
        runtimePairFlow,
        localState,
    ) { values ->
        val project = values[0] as ProjectSummary?
        @Suppress("UNCHECKED_CAST")
        val persistedTasks = values[1] as List<com.example.data.database.TaskEntity>
        @Suppress("UNCHECKED_CAST")
        val models = values[2] as List<GatewayModelInfo>
        val prefs = values[3] as AppPreferencesState
        val runtimeBundle = values[4] as RuntimeAndGitBundle
        val runtime = runtimeBundle.runtime
        val ocState = runtimeBundle.openCode
        val piState = runtimeBundle.pi
        val activeBackend = AgentBackendId.fromId(prefs.activeAgentId)
        val activeInstalled = when (activeBackend) {
            AgentBackendId.OPENCODE -> ocState.isInstalled
            AgentBackendId.PI -> piState.isInstalled
        }
        val activeVersion = when (activeBackend) {
            AgentBackendId.OPENCODE -> ocState.version
            AgentBackendId.PI -> piState.version
        }
        val checkpoints = runtimeBundle.checkpoints
        val state = values[5] as WorkspaceUiState

        val effectiveModelId = state.selectedModelId
            ?.takeIf { sel -> models.any { it.id == sel } }
            ?: prefs.defaultModelId?.takeIf { def -> models.any { it.id == def } }
            ?: models.firstOrNull()?.id

        val intel = runtimeBundle.intelligence
        val baseTimeline = if (state.timeline.isEmpty() && intel.chatMessages.isNotEmpty()) {
            val summaryByTaskId = persistedTasks.associate { task ->
                task.id to projectRepository.decodeTaskSummary(task.summaryJson)
            }
            val appendedSummaries = mutableSetOf<String>()
            intel.chatMessages.flatMap { msg ->
                val item = when (msg.role) {
                    ChatMessageRole.USER -> AgentTimelineItem.UserPrompt(
                        id = msg.id,
                        timestampMs = msg.createdAtEpochMs,
                        text = msg.content,
                        modelId = msg.modelId,
                    )
                    ChatMessageRole.ASSISTANT -> AgentTimelineItem.AssistantResponse(
                        id = msg.id,
                        timestampMs = msg.createdAtEpochMs,
                        text = msg.content,
                        modelId = msg.modelId,
                        isStreaming = false,
                        promptTokens = msg.promptTokens,
                        completionTokens = msg.completionTokens,
                    )
                    ChatMessageRole.SYSTEM_NOTICE -> AgentTimelineItem.SystemNotice(
                        id = msg.id,
                        timestampMs = msg.createdAtEpochMs,
                        title = msg.title ?: "Notice",
                        detail = msg.content,
                        isWarning = msg.isWarning,
                    )
                    ChatMessageRole.SUMMARY -> null
                }
                val summary = msg.taskId?.let { tid ->
                    if (msg.role == ChatMessageRole.ASSISTANT && appendedSummaries.add(tid)) {
                        summaryByTaskId[tid]
                    } else null
                }
                buildList {
                    if (item != null) add(item)
                    if (summary != null) {
                        add(
                            AgentTimelineItem.TaskSummaryCard(
                                id = "${msg.taskId}-summary",
                                timestampMs = summary.completedAtEpochMs,
                                summary = summary,
                            )
                        )
                    }
                }
            }
        } else if (state.timeline.isEmpty() && persistedTasks.isNotEmpty()) {
            persistedTasks.flatMap { task ->
                val promptItem = AgentTimelineItem.UserPrompt(
                    id = task.id,
                    timestampMs = task.createdAtEpochMs,
                    text = task.prompt,
                    modelId = task.modelId,
                )
                val decodedSummary = projectRepository.decodeTaskSummary(task.summaryJson)
                if (decodedSummary != null) {
                    listOf(
                        promptItem,
                        AgentTimelineItem.TaskSummaryCard(
                            id = "${task.id}-summary",
                            timestampMs = decodedSummary.completedAtEpochMs,
                            summary = decodedSummary,
                        ),
                    )
                } else {
                    listOf(
                        promptItem,
                        AgentTimelineItem.SystemNotice(
                            id = "${task.id}-status",
                            timestampMs = task.updatedAtEpochMs,
                            title = "Saved Task (${task.state})",
                            detail = if (task.state == TaskState.PAUSED.name) {
                                "Interrupted task recovered in PAUSED state. Open Flight Recorder to inspect resume cursor."
                            } else {
                                "Task history persisted locally."
                            },
                            isWarning = task.state == TaskState.PAUSED.name,
                        ),
                    )
                }
            }
        } else {
            state.timeline
        }
        val effectiveTerminalLines = if (runtime.isInstalled &&
            state.terminalLines.size == 1 &&
            state.terminalLines[0].id == "init-0"
        ) {
            listOf(
                TerminalLine(
                    id = "init-0",
                    text = "Linux runtime v${runtime.installedVersion.orEmpty()} active (${runtime.installedAbi.orEmpty()}). Interactive shell ready.",
                    isError = false,
                )
            )
        } else {
            state.terminalLines
        }
        val enabledSkills = runtimeBundle.extensions.filter {
            it.category == ExtensionCategory.SKILLS && it.installState == ExtensionInstallState.INSTALLED_ENABLED
        }
        val enabledMcps = runtimeBundle.extensions.filter {
            it.category == ExtensionCategory.MCP_SERVERS &&
                it.installState == ExtensionInstallState.INSTALLED_ENABLED &&
                (it.mcpTransportType != com.example.domain.model.McpTransportType.LOCAL_STDIO || prefs.allowProotSandboxExtensions)
        }
        val enabledPlugins = if (prefs.allowProotSandboxExtensions) {
            runtimeBundle.extensions.filter {
                it.category == ExtensionCategory.PLUGINS && it.installState == ExtensionInstallState.INSTALLED_ENABLED
            }
        } else {
            emptyList()
        }
        val effectiveExecutionMode = if (state.hasOverriddenExecutionMode) {
            state.selectedExecutionMode
        } else {
            prefs.councilDefaultMode
        }
        state.copy(
            project = project,
            isLoadingProject = false,
            availableModels = models,
            selectedModelId = effectiveModelId,
            protocolMode = prefs.gatewayProtocol,
            timeline = baseTimeline,
            activeAgentId = activeBackend,
            activeAgentInstalled = activeInstalled,
            activeAgentVersion = activeVersion,
            openCodeInstalled = ocState.isInstalled,
            openCodeVersion = ocState.version,
            checkpoints = checkpoints,
            gitState = state.gitState.copy(gitHubAccount = runtimeBundle.gitHubAccount),
            runtimeInstalled = runtime.isInstalled,
            runtimeVersionLabel = runtime.installedVersion,
            terminalLines = effectiveTerminalLines,
            enabledSkills = enabledSkills,
            enabledMcpServers = enabledMcps,
            enabledPlugins = enabledPlugins,
            allowProotSandboxExtensions = prefs.allowProotSandboxExtensions,
            flightRecorderEvents = intel.flightEvents,
            taskSnapshots = intel.taskSnapshots,
            projectMemory = intel.projectMemory,
            knowledgeEntries = intel.knowledgeEntries,
            selectedExecutionMode = effectiveExecutionMode,
            councilRoleModelOverrides = prefs.councilRoleModelOverrides,
            councilEnabledSpecialists = prefs.councilEnabledSpecialists,
            councilTokenBudget = prefs.councilTokenBudget,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WorkspaceUiState(),
    )

    init {
        viewModelScope.launch {
            val recovered = projectRepository.recoverInterruptedTasks(projectId)
            val pausedSnapshots = intelligenceRepository?.markActiveSnapshotsPaused(projectId) ?: 0
            if (recovered > 0 || pausedSnapshots > 0) {
                localState.update { it.copy(recoveredInterruptedCount = maxOf(recovered, pausedSnapshots)) }
            }
            projectRepository.markOpened(projectId)
            openCodeInstallerRepository.refreshStatusSync()
            piInstallerRepository?.refreshStatusSync()
            refreshFiles("")
            detectPreviewConfiguration()
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        localState.update { it.copy(selectedTab = tab) }
        if (tab == WorkspaceTab.FILES) {
            refreshFiles(localState.value.currentRelativePath)
        } else if (tab == WorkspaceTab.PREVIEW) {
            detectPreviewConfiguration()
        }
    }

    fun updateComposerText(text: String) {
        localState.update { it.copy(composerText = text) }
    }

    fun selectModel(modelId: String) {
        localState.update { it.copy(selectedModelId = modelId) }
    }

    fun selectExecutionMode(mode: CouncilExecutionMode) {
        localState.update { it.copy(selectedExecutionMode = mode, hasOverriddenExecutionMode = true) }
    }

    fun attachDocumentUri(uri: Uri, kind: ComposerAttachmentKind) {
        viewModelScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                val resolver = applicationContext.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: "${kind.name.lowercase()}_${System.currentTimeMillis()}"
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val attachDir = File(applicationContext.filesDir, "runtime/attachments").apply { mkdirs() }
                val dest = File(attachDir, "${UUID.randomUUID().toString().take(6)}_$safeName")
                var totalCopied = 0L
                val maxBytes = 4L * 1024 * 1024
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            totalCopied += r
                            if (totalCopied > maxBytes) break
                            out.write(buf, 0, r)
                        }
                    }
                }
                AgentAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    displayName = name,
                    localPath = if (dest.exists()) dest.absolutePath else null,
                    mimeType = resolver.getType(uri),
                )
            }
            localState.update { state ->
                state.copy(composerAttachments = state.composerAttachments + attachment)
            }
        }
    }

    fun attachCameraPreview(bitmap: Bitmap) {
        viewModelScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                val attachDir = File(applicationContext.filesDir, "runtime/attachments").apply { mkdirs() }
                val file = File(attachDir, "camera_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                AgentAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = ComposerAttachmentKind.CAMERA,
                    displayName = file.name,
                    localPath = file.absolutePath,
                    mimeType = "image/jpeg",
                )
            }
            localState.update { state ->
                state.copy(composerAttachments = state.composerAttachments + attachment)
            }
        }
    }

    fun removeAttachment(id: String) {
        localState.update { state ->
            state.copy(composerAttachments = state.composerAttachments.filterNot { it.id == id })
        }
    }

    fun onExtensionAttachmentSelected(kind: ComposerAttachmentKind) {
        val current = uiState.value
        val now = System.currentTimeMillis()
        when (kind) {
            ComposerAttachmentKind.SKILLS -> {
                if (current.enabledSkills.isEmpty()) {
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = now,
                                title = "No Enabled Skills",
                                detail = "Install and enable a Skill in the Extensions tab to bind SKILL.md guidance to tasks.",
                            )
                        )
                    }
                } else {
                    localState.update { it.copy(pendingExtensionPickerKind = ComposerAttachmentKind.SKILLS) }
                }
            }
            ComposerAttachmentKind.MCP_SERVERS -> {
                if (current.enabledMcpServers.isEmpty()) {
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = now,
                                title = "No Enabled MCP Servers",
                                detail = "Connect and enable an HTTP/SSE MCP Server in the Extensions tab to attach its tools here.",
                            )
                        )
                    }
                } else {
                    localState.update { it.copy(pendingExtensionPickerKind = ComposerAttachmentKind.MCP_SERVERS) }
                }
            }
            ComposerAttachmentKind.PLUGINS -> {
                if (!current.allowProotSandboxExtensions) {
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = now,
                                title = "Sandboxed Plugin Host Off",
                                detail = "Enable the Sandboxed PRoot Extension Host toggle in Extensions -> Plugins to run JS/TS plugins inside OpenCode or Pi.",
                            )
                        )
                    }
                } else if (current.enabledPlugins.isEmpty()) {
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = now,
                                title = "No Enabled Plugins",
                                detail = "Install and enable a sandboxed JS/TS plugin in Extensions -> Plugins to attach it here.",
                            )
                        )
                    }
                } else {
                    localState.update { it.copy(pendingExtensionPickerKind = ComposerAttachmentKind.PLUGINS) }
                }
            }
            else -> Unit
        }
    }

    fun attachExtensionRecord(record: ExtensionRecord) {
        if (record.installState != ExtensionInstallState.INSTALLED_ENABLED) {
            dismissExtensionPicker()
            return
        }
        val kind = when (record.category) {
            ExtensionCategory.SKILLS -> ComposerAttachmentKind.SKILLS
            ExtensionCategory.MCP_SERVERS -> ComposerAttachmentKind.MCP_SERVERS
            ExtensionCategory.PLUGINS -> ComposerAttachmentKind.PLUGINS
        }
        val attachment = AgentAttachment(
            id = record.id,
            kind = kind,
            displayName = record.name,
            localPath = record.endpointUrl ?: record.sourceUri,
        )
        localState.update { state ->
            val deduped = state.composerAttachments.filterNot { it.id == attachment.id }
            state.copy(
                composerAttachments = deduped + attachment,
                pendingExtensionPickerKind = null,
            )
        }
    }

    fun dismissExtensionPicker() {
        localState.update { it.copy(pendingExtensionPickerKind = null) }
    }

    fun submitPrompt() {
        val currentUi = uiState.value
        val prompt = currentUi.composerText.trim()
        if (prompt.isEmpty() || currentUi.isStreaming) return

        val selectedModel = currentUi.selectedModelId
        val modelInfo = currentUi.availableModels.firstOrNull { it.id == selectedModel }
        val attachments = currentUi.composerAttachments
        val now = System.currentTimeMillis()
        val userItem = AgentTimelineItem.UserPrompt(
            id = UUID.randomUUID().toString(),
            timestampMs = now,
            text = prompt,
            modelId = selectedModel,
        )

        if (selectedModel == null) {
            val noticeItem = AgentTimelineItem.SystemNotice(
                id = UUID.randomUUID().toString(),
                timestampMs = now + 1,
                title = "Gateway & Model Required",
                detail = "Configure JH GetWay Pro in Settings → Gateway and discover models from /v1/models before submitting tasks.",
                isWarning = true,
            )
            localState.update { state ->
                state.copy(
                    composerText = "",
                    composerAttachments = emptyList(),
                    timeline = currentUi.timeline + userItem + noticeItem,
                )
            }
            viewModelScope.launch {
                projectRepository.recordSubmittedTask(projectId, prompt, null, TaskState.PAUSED)
            }
            return
        }

        val executionMode = currentUi.selectedExecutionMode
        val requiresCliAndTools = executionMode != CouncilExecutionMode.COUNCIL_ONLY
        val capabilityErr = OpenCodeConfigAdapter.validateTaskCapabilities(
            modelInfo = modelInfo,
            requiresToolCalling = requiresCliAndTools,
            attachments = attachments,
        )
        if (capabilityErr != null) {
            val noticeItem = AgentTimelineItem.SystemNotice(
                id = UUID.randomUUID().toString(),
                timestampMs = now + 1,
                title = "Unsupported Model Capability",
                detail = capabilityErr.userMessage,
                isWarning = true,
            )
            localState.update { state ->
                state.copy(
                    composerText = "",
                    composerAttachments = emptyList(),
                    timeline = currentUi.timeline + userItem + noticeItem,
                )
            }
            viewModelScope.launch {
                projectRepository.recordSubmittedTask(projectId, prompt, selectedModel, TaskState.FAILED)
            }
            return
        }

        val project = currentUi.project ?: return
        val taskId = UUID.randomUUID().toString()
        val assistantId = UUID.randomUUID().toString()
        val initialCards = if (executionMode == CouncilExecutionMode.DIRECT_CODE) {
            listOf(
                AgentTimelineItem.AssistantResponse(
                    id = assistantId,
                    timestampMs = now + 2,
                    text = "",
                    modelId = selectedModel,
                    isStreaming = true,
                )
            )
        } else {
            listOf(
                AgentTimelineItem.CouncilSessionCard(
                    id = "council-card-$taskId",
                    timestampMs = now + 2,
                    taskId = taskId,
                    objective = prompt,
                    executionMode = executionMode,
                    currentRoundNumber = 1,
                    isRunning = true,
                    activeStage = "RESEARCHING",
                    activeStageDetail = "Inspecting workspace & launching Multi-Agent Council…",
                    roleModels = currentUi.councilRoleModelOverrides,
                    tokenBudget = currentUi.councilTokenBudget,
                )
            )
        }

        localState.update { state ->
            state.copy(
                composerText = "",
                composerAttachments = emptyList(),
                isStreaming = true,
                activeTaskState = TaskState.CREATED,
                timeline = currentUi.timeline + userItem + initialCards,
            )
        }

        activeStreamJob?.cancel()
        activeStreamJob = viewModelScope.launch {
            activeTaskId = taskId
            projectRepository.recordSubmittedTask(projectId, prompt, selectedModel, TaskState.CREATED, taskId)
            intelligenceRepository?.appendChatMessage(
                ChatMessageRecord(
                    id = userItem.id,
                    projectId = projectId,
                    taskId = taskId,
                    role = ChatMessageRole.USER,
                    content = prompt,
                    modelId = selectedModel,
                    backendId = currentUi.activeAgentId.id,
                    createdAtEpochMs = now,
                )
            )

            if (requiresCliAndTools) {
                val availability = agentRuntime.checkAvailability()
                if (!availability.isInstalled) {
                    val activeLabel = currentUi.activeAgentId.shortLabel
                    projectRepository.updateTaskState(taskId, TaskState.PAUSED)
                    updateAssistantItem(assistantId) {
                        it.copy(text = availability.detail, isStreaming = false)
                    }
                    intelligenceRepository?.appendChatMessage(
                        ChatMessageRecord(
                            id = assistantId,
                            projectId = projectId,
                            taskId = taskId,
                            role = ChatMessageRole.ASSISTANT,
                            content = availability.detail,
                            modelId = selectedModel,
                            backendId = currentUi.activeAgentId.id,
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                    localState.update { state ->
                        state.copy(
                            isStreaming = false,
                            activeTaskState = TaskState.PAUSED,
                            timeline = state.timeline.map { item ->
                                if (item is AgentTimelineItem.CouncilSessionCard && item.taskId == taskId) {
                                    item.copy(isRunning = false, activeStage = "PAUSED")
                                } else {
                                    item
                                }
                            } + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                title = "$activeLabel Runtime Required",
                                detail = "${availability.detail} Open Settings → Agent to install or verify $activeLabel.",
                                isWarning = true,
                            ),
                        )
                    }
                    activeTaskId = null
                    return@launch
                }
            }

            runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    RuntimeExecutionService.startIntent(applicationContext),
                )
            }

            val projectRef = ProjectRef(
                id = project.id,
                name = project.name,
                rootPath = project.workspacePath,
            )
            collectOrchestratorEvents(
                flow = agentOrchestrator.executeTask(
                    OrchestratorTaskRequest(
                        taskId = taskId,
                        project = projectRef,
                        prompt = prompt,
                        modelId = selectedModel,
                        protocolMode = currentUi.protocolMode,
                        attachments = attachments,
                        backendId = currentUi.activeAgentId.id,
                        executionMode = executionMode,
                        roleModelOverrides = currentUi.councilRoleModelOverrides,
                        enabledCouncilRoles = currentUi.councilEnabledSpecialists,
                        maxCouncilTokenBudget = currentUi.councilTokenBudget,
                    )
                ),
                project = project,
                taskId = taskId,
                selectedModel = selectedModel,
                backendId = currentUi.activeAgentId.id,
                assistantId = assistantId,
            )
        }
    }

    private suspend fun collectOrchestratorEvents(
        flow: kotlinx.coroutines.flow.Flow<OrchestratorEvent>,
        project: ProjectSummary,
        taskId: String,
        selectedModel: String,
        backendId: String,
        assistantId: String,
    ) {
        val textAccum = StringBuilder()
        try {
            flow.collect { orchEvent ->
                when (orchEvent) {
                    is OrchestratorEvent.StateChanged -> {
                        localState.update { it.copy(activeTaskState = orchEvent.state) }
                    }
                    is OrchestratorEvent.InspectionReady -> {
                        val insp = orchEvent.inspection
                        val checksText = if (insp.validationCommands.isNotEmpty()) {
                            "Checks: ${insp.validationCommands.joinToString(", ") { it.label }}"
                        } else {
                            "No build/test scripts detected"
                        }
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    title = "Repository Inspected (${insp.stackLabel})",
                                    detail = "${insp.directoryMap.size} file(s) indexed · $checksText",
                                )
                            )
                        }
                    }
                    is OrchestratorEvent.CheckpointCreated -> {
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.CheckpointCard(
                                    id = "chk-card-${orchEvent.checkpointId}",
                                    timestampMs = System.currentTimeMillis(),
                                    checkpointId = orchEvent.checkpointId,
                                    reason = orchEvent.reason,
                                    fileCount = orchEvent.fileCount,
                                )
                            )
                        }
                    }
                    is OrchestratorEvent.HandoffReady -> {
                        val pkt = orchEvent.packet
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    title = "Model Handoff Ready (~${pkt.estimatedTokens} tokens)",
                                    detail = "${pkt.sourceBackendId ?: "unset"}/${pkt.sourceModelId ?: "unset"} → ${pkt.targetBackendId}/${pkt.targetModelId} · ${pkt.targetedCodeSlices.size} code slice(s) · ${pkt.remainingSteps.size} remaining step(s)",
                                )
                            )
                        }
                    }
                    is OrchestratorEvent.PlanReady -> {
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.PlanCard(
                                    id = "plan-${orchEvent.plan.taskId}-${System.currentTimeMillis()}",
                                    timestampMs = System.currentTimeMillis(),
                                    plan = orchEvent.plan,
                                    requiresApproval = orchEvent.requiresApproval,
                                )
                            )
                        }
                        if (orchEvent.requiresApproval) {
                            notificationManager?.notifyPlanApprovalRequired(
                                projectId = project.id,
                                projectName = project.name,
                                taskId = orchEvent.plan.taskId,
                                objective = orchEvent.plan.objective,
                            )
                        }
                    }
                    is OrchestratorEvent.PlanUpdated -> {
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline.map { item ->
                                    if (item is AgentTimelineItem.PlanCard && item.plan.taskId == orchEvent.plan.taskId) {
                                        item.copy(plan = orchEvent.plan)
                                    } else {
                                        item
                                    }
                                }
                            )
                        }
                    }
                    is OrchestratorEvent.ValidationStarted -> {
                        val cmd = orchEvent.command
                        val card = AgentTimelineItem.ValidationCard(
                            id = "val-$taskId-${cmd.id}",
                            timestampMs = System.currentTimeMillis(),
                            result = ValidationCheckResult(
                                commandId = cmd.id,
                                label = cmd.label,
                                command = cmd.command,
                                status = ValidationOutcome.SKIPPED,
                                outputExcerpt = "Running ${cmd.command}…",
                            ),
                        )
                        upsertValidationCard(card)
                    }
                    is OrchestratorEvent.ValidationCompleted -> {
                        val res = orchEvent.result
                        upsertValidationCard(
                            AgentTimelineItem.ValidationCard(
                                id = "val-$taskId-${res.commandId}",
                                timestampMs = System.currentTimeMillis(),
                                result = res,
                            )
                        )
                    }
                    is OrchestratorEvent.FixLoopStarted -> {
                        val activeLabel = uiState.value.activeAgentId.shortLabel
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    title = "Auto-Debug Pass ${orchEvent.attempt}/${orchEvent.maxAttempts}",
                                    detail = "Re-invoking $activeLabel to fix: ${orchEvent.failingLabels}",
                                    isWarning = true,
                                )
                            )
                        }
                    }
                    is OrchestratorEvent.StatusNotice -> {
                        localState.update { state ->
                            state.copy(
                                timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                    id = UUID.randomUUID().toString(),
                                    timestampMs = System.currentTimeMillis(),
                                    title = orchEvent.title,
                                    detail = orchEvent.detail,
                                    isWarning = orchEvent.isWarning,
                                )
                            )
                        }
                    }
                    is OrchestratorEvent.SummaryReady -> {
                        val summary = orchEvent.summary
                        val finalAssistantText = textAccum.toString().ifBlank {
                            if (summary.finalState == TaskState.COMPLETED) {
                                "Task completed."
                            } else {
                                summary.unresolvedIssues.firstOrNull() ?: "Task finished (${summary.finalState.name})."
                            }
                        }
                        updateAssistantItem(assistantId) {
                            it.copy(
                                text = finalAssistantText,
                                isStreaming = false,
                            )
                        }
                        intelligenceRepository?.appendChatMessage(
                            ChatMessageRecord(
                                id = assistantId,
                                projectId = projectId,
                                taskId = taskId,
                                role = ChatMessageRole.ASSISTANT,
                                content = finalAssistantText,
                                modelId = selectedModel,
                                backendId = backendId,
                                createdAtEpochMs = summary.completedAtEpochMs,
                            )
                        )
                        localState.update { state ->
                            state.copy(
                                isStreaming = false,
                                activeTaskState = summary.finalState,
                                timeline = state.timeline + AgentTimelineItem.TaskSummaryCard(
                                    id = "summary-${summary.taskId}-${summary.completedAtEpochMs}",
                                    timestampMs = summary.completedAtEpochMs,
                                    summary = summary,
                                ),
                            )
                        }
                        refreshFiles(localState.value.currentRelativePath)
                        notificationManager?.notifyTaskResult(
                            projectId = project.id,
                            projectName = project.name,
                            summary = summary,
                        )
                    }
                    is OrchestratorEvent.RuntimeEvent -> {
                        handleRuntimeEvent(
                            event = orchEvent.event,
                            project = project,
                            taskId = taskId,
                            assistantId = assistantId,
                            textAccum = textAccum,
                        )
                    }
                    is OrchestratorEvent.CouncilProgress -> {
                        handleCouncilProgressEvent(orchEvent)
                    }
                    is OrchestratorEvent.CouncilSessionUpdated -> {
                        val session = orchEvent.session
                        upsertCouncilCard(taskId = session.taskId, objective = session.objective, mode = session.mode) { card ->
                            card.copy(
                                currentRoundNumber = session.currentRoundNumber,
                                isRunning = false,
                                activeStage = if (orchEvent.requiresDecision) "AWAITING REVIEW" else null,
                                activeStageDetail = null,
                                session = session,
                                diffFromPrevious = orchEvent.diffFromPrevious,
                                requiresDecision = orchEvent.requiresDecision,
                                liveDossier = session.latestRound?.dossier ?: card.liveDossier,
                                liveOpinions = session.latestRound?.opinions ?: card.liveOpinions,
                            )
                        }
                    }
                }
            }
        } finally {
            activeTaskId = null
            localState.update { state ->
                if (state.isStreaming) state.copy(isStreaming = false) else state
            }
        }
    }

    private fun handleRuntimeEvent(
        event: AgentEvent,
        project: ProjectSummary,
        taskId: String,
        assistantId: String,
        textAccum: StringBuilder,
    ) {
        when (event) {
            is AgentEvent.SessionStarted -> {
                localState.update { state ->
                    state.copy(
                        timeline = state.timeline + AgentTimelineItem.SystemNotice(
                            id = UUID.randomUUID().toString(),
                            timestampMs = System.currentTimeMillis(),
                            title = event.backendLabel,
                            detail = "Session ${event.sessionId} started in ${project.name}.",
                        )
                    )
                }
            }
            is AgentEvent.StateChanged -> {
                localState.update { it.copy(activeTaskState = event.state) }
            }
            is AgentEvent.StatusMessage -> {
                val activeLabel = uiState.value.activeAgentId.shortLabel
                localState.update { state ->
                    state.copy(
                        timeline = state.timeline + AgentTimelineItem.SystemNotice(
                            id = UUID.randomUUID().toString(),
                            timestampMs = System.currentTimeMillis(),
                            title = activeLabel,
                            detail = event.text,
                        )
                    )
                }
            }
            is AgentEvent.TextDelta -> {
                textAccum.append(event.text)
                updateAssistantItem(assistantId) {
                    it.copy(text = textAccum.toString(), isStreaming = true)
                }
            }
            is AgentEvent.ToolRequested -> {
                upsertToolCard(
                    AgentTimelineItem.ToolCallCard(
                        id = event.toolCallId,
                        timestampMs = System.currentTimeMillis(),
                        toolName = event.toolName,
                        status = "Requested",
                        permissionClass = event.permissionClass,
                        summary = event.summary,
                    )
                )
            }
            is AgentEvent.ToolStarted -> {
                updateToolCard(event.toolCallId) {
                    it.copy(status = "Running", summary = event.summary)
                }
            }
            is AgentEvent.ToolCompleted -> {
                updateToolCard(event.toolCallId) {
                    it.copy(
                        status = if (event.success) "Completed" else "Failed",
                        outputSummary = event.outputSummary,
                    )
                }
            }
            is AgentEvent.ApprovalRequired -> {
                localState.update { state ->
                    state.copy(
                        timeline = state.timeline + AgentTimelineItem.ApprovalPrompt(
                            id = event.approvalId,
                            timestampMs = System.currentTimeMillis(),
                            title = event.title,
                            reason = event.reason,
                            permissionClass = event.permissionClass,
                            resolved = false,
                            toolCallId = event.toolCallId,
                        )
                    )
                }
                viewModelScope.launch {
                    notificationManager?.notifyToolApprovalRequired(
                        projectId = project.id,
                        projectName = project.name,
                        taskId = taskId,
                        approvalId = event.approvalId,
                        title = event.title,
                        reason = event.reason,
                    )
                }
            }
            is AgentEvent.FileChanged -> {
                refreshFiles(localState.value.currentRelativePath)
            }
            is AgentEvent.Completed -> {
                updateAssistantItem(assistantId) {
                    it.copy(
                        text = textAccum.toString().ifBlank { "(Agent pass completed)" },
                        isStreaming = false,
                        promptTokens = event.promptTokens,
                        completionTokens = event.completionTokens,
                    )
                }
            }
            is AgentEvent.Failed -> {
                val activeLabel = uiState.value.activeAgentId.shortLabel
                updateAssistantItem(assistantId) {
                    it.copy(
                        text = textAccum.toString().ifBlank { event.reason },
                        isStreaming = false,
                    )
                }
                localState.update { state ->
                    state.copy(
                        timeline = state.timeline + AgentTimelineItem.SystemNotice(
                            id = UUID.randomUUID().toString(),
                            timestampMs = System.currentTimeMillis(),
                            title = if (event.error is AgtError.ProcessCancelled) "Stopped" else "$activeLabel Error",
                            detail = event.reason,
                            isWarning = event.error !is AgtError.ProcessCancelled,
                        )
                    )
                }
            }
        }
    }

    fun resolvePlanApproval(taskId: String, approved: Boolean) {
        notificationManager?.cancelPlanApprovalNotification(taskId)
        localState.update { state ->
            state.copy(
                timeline = state.timeline.map { item ->
                    if (item is AgentTimelineItem.PlanCard && item.plan.taskId == taskId) {
                        item.copy(approvalResolved = true, approved = approved)
                    } else {
                        item
                    }
                }
            )
        }
        agentOrchestrator.respondToPlanApproval(taskId, approved)
    }

    fun resolveApproval(approvalId: String, approved: Boolean) {
        notificationManager?.cancelToolApprovalNotification(approvalId)
        localState.update { state ->
            state.copy(
                timeline = state.timeline.map { item ->
                    if (item is AgentTimelineItem.ApprovalPrompt && item.id == approvalId) {
                        item.copy(resolved = true, approved = approved)
                    } else {
                        item
                    }
                }
            )
        }
        val taskId = activeTaskId ?: return
        viewModelScope.launch {
            agentOrchestrator.respondToToolApproval(taskId, approvalId, approved)
        }
    }

    fun pauseActiveStream() = stopActiveStreamInternal(pauseOnly = true)

    fun stopActiveStream() = stopActiveStreamInternal(pauseOnly = false)

    private fun stopActiveStreamInternal(pauseOnly: Boolean) {
        val taskIdToStop = activeTaskId
        if (taskIdToStop != null) {
            notificationManager?.cancelApprovalNotificationsForTask(taskIdToStop)
        }
        activeTaskId = null
        activeStreamJob?.cancel()
        activeStreamJob = null

        val targetState = if (pauseOnly) TaskState.PAUSED else TaskState.CANCELLED
        localState.update { state ->
            val updated = state.timeline.map { item ->
                when {
                    item is AgentTimelineItem.AssistantResponse && item.isStreaming ->
                        item.copy(isStreaming = false)
                    item is AgentTimelineItem.ApprovalPrompt && !item.resolved ->
                        item.copy(resolved = true, approved = false)
                    item is AgentTimelineItem.PlanCard && item.requiresApproval && !item.approvalResolved ->
                        item.copy(approvalResolved = true, approved = false)
                    item is AgentTimelineItem.CouncilSessionCard && (item.isRunning || item.requiresDecision) ->
                        item.copy(isRunning = false, requiresDecision = false, activeStage = if (pauseOnly) "PAUSED" else "CANCELLED")
                    else -> item
                }
            }
            state.copy(
                isStreaming = false,
                activeTaskState = targetState,
                timeline = updated + AgentTimelineItem.SystemNotice(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    title = if (pauseOnly) "Paused" else "Stopped",
                    detail = if (pauseOnly) {
                        "Paused active task at durable checkpoint. Resume anytime with the same or another model."
                    } else {
                        "Cancelled active autonomous task."
                    },
                    isWarning = false,
                ),
            )
        }

        if (taskIdToStop != null) {
            CoroutineScope(Dispatchers.IO).launch {
                agentOrchestrator.stopTask(taskIdToStop, pauseOnly = pauseOnly)
            }
        }
    }

    fun resumeTask(
        taskId: String,
        targetModelId: String? = null,
        targetBackendId: String? = null,
        additionalInstruction: String? = null,
    ) {
        val currentUi = uiState.value
        if (currentUi.isStreaming) return
        val project = currentUi.project ?: return

        val effectiveModelId = targetModelId?.takeIf { it.isNotBlank() } ?: currentUi.selectedModelId
        val now = System.currentTimeMillis()
        if (effectiveModelId == null) {
            localState.update { state ->
                state.copy(
                    showFlightRecorderSheet = false,
                    timeline = state.timeline + AgentTimelineItem.SystemNotice(
                        id = UUID.randomUUID().toString(),
                        timestampMs = now,
                        title = "Gateway & Model Required",
                        detail = "Configure JH GetWay Pro in Settings → Gateway and select a model before resuming.",
                        isWarning = true,
                    ),
                )
            }
            return
        }

        val modelInfo = currentUi.availableModels.firstOrNull { it.id == effectiveModelId }
        val capabilityErr = OpenCodeConfigAdapter.validateTaskCapabilities(
            modelInfo = modelInfo,
            requiresToolCalling = true,
            attachments = currentUi.composerAttachments,
        )
        if (capabilityErr != null) {
            localState.update { state ->
                state.copy(
                    showFlightRecorderSheet = false,
                    timeline = state.timeline + AgentTimelineItem.SystemNotice(
                        id = UUID.randomUUID().toString(),
                        timestampMs = now,
                        title = "Unsupported Model Capability",
                        detail = capabilityErr.userMessage,
                        isWarning = true,
                    ),
                )
            }
            return
        }

        val targetBackend = targetBackendId?.takeIf { it.isNotBlank() }
            ?.let { AgentBackendId.fromId(it) }
            ?: currentUi.activeAgentId
        val attachments = currentUi.composerAttachments
        val assistantId = UUID.randomUUID().toString()
        val assistantPlaceholder = AgentTimelineItem.AssistantResponse(
            id = assistantId,
            timestampMs = now + 2,
            text = "",
            modelId = effectiveModelId,
            isStreaming = true,
        )
        val resumeNotice = AgentTimelineItem.SystemNotice(
            id = UUID.randomUUID().toString(),
            timestampMs = now + 1,
            title = "Resuming Task (${targetBackend.shortLabel} · $effectiveModelId)",
            detail = additionalInstruction?.takeIf { it.isNotBlank() }
                ?.let { "Building compact ModelHandoffPacket… Note: $it" }
                ?: "Building compact ModelHandoffPacket from durable snapshot and RepoMap…",
        )

        localState.update { state ->
            state.copy(
                selectedModelId = effectiveModelId,
                composerAttachments = emptyList(),
                showFlightRecorderSheet = false,
                isStreaming = true,
                activeTaskState = TaskState.CONTEXT_LOADING,
                timeline = state.timeline + resumeNotice + assistantPlaceholder,
            )
        }

        activeStreamJob?.cancel()
        activeStreamJob = viewModelScope.launch {
            activeTaskId = taskId
            if (targetBackend != currentUi.activeAgentId) {
                preferencesRepository.setActiveAgentId(targetBackend.id)
            }

            val availability = if (agentRuntime is DelegatingAgentRuntime) {
                agentRuntime.checkAvailabilityForBackend(targetBackend.id)
            } else {
                agentRuntime.checkAvailability()
            }
            if (!availability.isInstalled) {
                projectRepository.updateTaskState(taskId, TaskState.PAUSED)
                updateAssistantItem(assistantId) {
                    it.copy(text = availability.detail, isStreaming = false)
                }
                localState.update { state ->
                    state.copy(
                        isStreaming = false,
                        activeTaskState = TaskState.PAUSED,
                        timeline = state.timeline + AgentTimelineItem.SystemNotice(
                            id = UUID.randomUUID().toString(),
                            timestampMs = System.currentTimeMillis(),
                            title = "${targetBackend.shortLabel} Runtime Required",
                            detail = "${availability.detail} Open Settings → Agent to install or verify ${targetBackend.shortLabel}.",
                            isWarning = true,
                        ),
                    )
                }
                activeTaskId = null
                return@launch
            }

            runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    RuntimeExecutionService.startIntent(applicationContext),
                )
            }

            val projectRef = ProjectRef(
                id = project.id,
                name = project.name,
                rootPath = project.workspacePath,
            )
            collectOrchestratorEvents(
                flow = agentOrchestrator.resumeTask(
                    ResumeTaskRequest(
                        taskId = taskId,
                        project = projectRef,
                        modelId = effectiveModelId,
                        protocolMode = currentUi.protocolMode,
                        attachments = attachments,
                        backendId = targetBackend.id,
                        additionalInstruction = additionalInstruction?.trim()?.takeIf { it.isNotEmpty() },
                    )
                ),
                project = project,
                taskId = taskId,
                selectedModel = effectiveModelId,
                backendId = targetBackend.id,
                assistantId = assistantId,
            )
        }
    }

    fun openFlightRecorder() {
        localState.update { it.copy(showFlightRecorderSheet = true) }
        val currentMem = uiState.value.projectMemory
        if (currentMem == null || currentMem.repoMapCache.isBlank() || currentMem.repoMapUpdatedAtEpochMs == 0L) {
            refreshRepoMap()
        }
    }

    fun refreshRepoMap() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val repo = intelligenceRepository ?: return
        viewModelScope.launch {
            val recentEdits = uiState.value.taskSnapshots.firstOrNull()?.modifiedFiles.orEmpty()
            repo.invalidateRepoMap(projectId)
            repo.rebuildRepoMap(
                projectId = projectId,
                projectRootPath = rootPath,
                recentlyEditedPaths = recentEdits,
            )
        }
    }

    fun dismissFlightRecorder() {
        localState.update { it.copy(showFlightRecorderSheet = false) }
    }

    fun setFlightRecorderFilter(filter: FlightRecorderFilter) {
        localState.update { it.copy(flightRecorderFilter = filter) }
    }

    fun setFlightRecorderTaskFilter(taskId: String?) {
        localState.update { it.copy(flightRecorderTaskFilterId = taskId) }
    }

    fun searchKnowledge(query: String) {
        localState.update { it.copy(knowledgeSearchQuery = query) }
        refreshKnowledgeSearch(query = query, includeSuperseded = localState.value.knowledgeIncludeSuperseded)
    }

    fun toggleKnowledgeIncludeSuperseded(include: Boolean) {
        localState.update { it.copy(knowledgeIncludeSuperseded = include) }
        val q = localState.value.knowledgeSearchQuery
        if (q.isNotBlank()) {
            refreshKnowledgeSearch(query = q, includeSuperseded = include)
        }
    }

    private fun refreshKnowledgeSearch(query: String, includeSuperseded: Boolean) {
        val repo = intelligenceRepository ?: return
        if (query.isBlank()) {
            localState.update { it.copy(knowledgeSearchResults = emptyList(), knowledgeUsedFts5 = false) }
            return
        }
        viewModelScope.launch {
            val result = repo.retrieveRelevantKnowledge(
                projectId = projectId,
                query = query,
                limit = 16,
                tokenBudget = 2400,
                includeSuperseded = includeSuperseded,
            )
            val snap = localState.value
            if (snap.knowledgeSearchQuery == query && snap.knowledgeIncludeSuperseded == includeSuperseded) {
                localState.update {
                    it.copy(
                        knowledgeSearchResults = result.hits,
                        knowledgeUsedFts5 = result.usedFts5,
                    )
                }
            }
        }
    }

    fun openCheckpointsSheet() {
        localState.update { it.copy(showCheckpointsSheet = true, checkpointActionMessage = null) }
    }

    fun dismissCheckpointsSheet() {
        localState.update {
            it.copy(
                showCheckpointsSheet = false,
                selectedDiff = null,
                checkpointPendingRestore = null,
                checkpointActionMessage = null,
            )
        }
    }

    fun createManualCheckpoint(reason: String = "Manual snapshot") {
        viewModelScope.launch {
            checkpointManager.create(projectId, reason)
                .onSuccess { chk ->
                    localState.update {
                        it.copy(checkpointActionMessage = "Created checkpoint ${chk.id.take(16)}.")
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(checkpointActionMessage = err.message ?: "Failed to create checkpoint.")
                    }
                }
        }
    }

    fun previewCheckpointDiff(checkpointId: String) {
        viewModelScope.launch {
            localState.update { it.copy(diffLoading = true, showCheckpointsSheet = true) }
            checkpointManager.diff(checkpointId)
                .onSuccess { diff ->
                    localState.update { it.copy(selectedDiff = diff, diffLoading = false) }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            diffLoading = false,
                            checkpointActionMessage = err.message ?: "Unable to compute diff.",
                        )
                    }
                }
        }
    }

    fun dismissDiffPreview() {
        localState.update { it.copy(selectedDiff = null) }
    }

    fun requestRestoreCheckpoint(checkpoint: CheckpointSummary) {
        viewModelScope.launch {
            localState.update { it.copy(diffLoading = true, checkpointPendingRestore = checkpoint) }
            checkpointManager.diff(checkpoint.id)
                .onSuccess { diff ->
                    localState.update { it.copy(selectedDiff = diff, diffLoading = false) }
                }
                .onFailure {
                    localState.update { it.copy(diffLoading = false) }
                }
        }
    }

    fun dismissRestoreCheckpoint() {
        localState.update { it.copy(checkpointPendingRestore = null) }
    }

    fun confirmRestoreCheckpoint() {
        val target = localState.value.checkpointPendingRestore ?: return
        viewModelScope.launch {
            checkpointManager.restore(target.id)
                .onSuccess {
                    localState.update { state ->
                        state.copy(
                            checkpointPendingRestore = null,
                            selectedDiff = null,
                            showCheckpointsSheet = false,
                            checkpointActionMessage = "Restored workspace to '${target.reason}'.",
                            timeline = state.timeline.map { item ->
                                if (item is AgentTimelineItem.CheckpointCard && item.checkpointId == target.id) {
                                    item.copy(restored = true)
                                } else {
                                    item
                                }
                            },
                        )
                    }
                    refreshFiles(localState.value.currentRelativePath)
                    intelligenceRepository?.invalidateRepoMap(projectId)
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            checkpointPendingRestore = null,
                            checkpointActionMessage = err.message ?: "Checkpoint restore failed.",
                        )
                    }
                }
        }
    }

    fun editCouncilSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
        newContent: String,
    ) {
        viewModelScope.launch {
            agentOrchestrator.editCouncilSection(sessionId, roundNumber, sectionId, newContent)
                .onSuccess { updatedSession ->
                    updateCouncilSessionInTimeline(updatedSession)
                }
                .onFailure { err ->
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                title = "Council Edit Failed",
                                detail = err.message ?: "Unable to update Council report section.",
                                isWarning = true,
                            )
                        )
                    }
                }
        }
    }

    fun revertCouncilSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
    ) {
        viewModelScope.launch {
            agentOrchestrator.revertCouncilSection(sessionId, roundNumber, sectionId)
                .onSuccess { updatedSession ->
                    updateCouncilSessionInTimeline(updatedSession)
                }
                .onFailure { err ->
                    localState.update { state ->
                        state.copy(
                            timeline = state.timeline + AgentTimelineItem.SystemNotice(
                                id = UUID.randomUUID().toString(),
                                timestampMs = System.currentTimeMillis(),
                                title = "Council Revert Failed",
                                detail = err.message ?: "Unable to revert Council report section.",
                                isWarning = true,
                            )
                        )
                    }
                }
        }
    }

    fun submitCouncilDecision(taskId: String, decision: CouncilUserDecision) {
        localState.update { state ->
            state.copy(
                timeline = state.timeline.map { item ->
                    if (item is AgentTimelineItem.CouncilSessionCard && item.taskId == taskId) {
                        when (decision) {
                            is CouncilUserDecision.ReCouncil -> item.copy(
                                requiresDecision = false,
                                isRunning = true,
                                activeStage = "RE_COUNCIL",
                                activeStageDetail = decision.userAmendmentNote ?: "Running revised Council round…",
                                roleStatuses = emptyMap(),
                                liveOpinions = emptyList(),
                            )
                            is CouncilUserDecision.ApproveForCoding,
                            is CouncilUserDecision.SaveCouncilOnly,
                            is CouncilUserDecision.Cancel -> item.copy(
                                requiresDecision = false,
                                isRunning = false,
                            )
                        }
                    } else {
                        item
                    }
                }
            )
        }
        agentOrchestrator.submitCouncilDecision(taskId, decision)
    }

    private fun handleCouncilProgressEvent(orchEvent: OrchestratorEvent.CouncilProgress) {
        upsertCouncilCard(taskId = orchEvent.taskId) { card ->
            when (val ev = orchEvent.event) {
                is CouncilProgressEvent.StageStarted -> {
                    val resetRound = orchEvent.roundNumber != card.currentRoundNumber || ev.stage == "DECOMPOSING"
                    card.copy(
                        currentRoundNumber = orchEvent.roundNumber,
                        isRunning = true,
                        activeStage = ev.stage,
                        activeStageDetail = ev.detail,
                        requiresDecision = false,
                        roleStatuses = if (resetRound) mapOf(CouncilRole.DECOMPOSER to CouncilAgentStatus.RUNNING) else card.roleStatuses,
                        liveOpinions = if (resetRound) emptyList() else card.liveOpinions,
                    )
                }
                is CouncilProgressEvent.DecompositionReady -> card.copy(
                    roleStatuses = card.roleStatuses + (CouncilRole.DECOMPOSER to CouncilAgentStatus.COMPLETED),
                )
                is CouncilProgressEvent.ResearchProgress -> card.copy(
                    roleStatuses = card.roleStatuses + (ev.role to ev.status),
                    activeStageDetail = ev.detail,
                )
                is CouncilProgressEvent.ResearchDossierReady -> card.copy(
                    liveDossier = ev.dossier,
                )
                is CouncilProgressEvent.SpecialistStarted -> card.copy(
                    roleStatuses = card.roleStatuses + (ev.role to CouncilAgentStatus.RUNNING),
                    roleModels = card.roleModels + (ev.role to ev.modelId),
                )
                is CouncilProgressEvent.SpecialistCompleted -> card.copy(
                    roleStatuses = card.roleStatuses + (ev.opinion.role to ev.opinion.status),
                    liveOpinions = card.liveOpinions.filterNot { it.role == ev.opinion.role } + ev.opinion,
                )
                is CouncilProgressEvent.SynthesisStarted -> card.copy(
                    activeStage = "SYNTHESIZING",
                    roleStatuses = card.roleStatuses + (CouncilRole.SYNTHESIZER to CouncilAgentStatus.RUNNING),
                    roleModels = card.roleModels + (CouncilRole.SYNTHESIZER to ev.modelId),
                )
                is CouncilProgressEvent.RoundCompleted -> card.copy(
                    currentRoundNumber = ev.round.roundNumber,
                    roleStatuses = card.roleStatuses + (CouncilRole.SYNTHESIZER to CouncilAgentStatus.COMPLETED),
                    activeStage = null,
                    activeStageDetail = null,
                )
                is CouncilProgressEvent.Failed -> card.copy(
                    isRunning = false,
                    activeStage = "FAILED",
                    activeStageDetail = ev.reason,
                )
            }
        }
    }

    private fun updateCouncilSessionInTimeline(updatedSession: CouncilSession) {
        localState.update { state ->
            state.copy(
                timeline = state.timeline.map { item ->
                    if (item is AgentTimelineItem.CouncilSessionCard &&
                        (item.session?.sessionId == updatedSession.sessionId || item.taskId == updatedSession.taskId)
                    ) {
                        item.copy(session = updatedSession)
                    } else {
                        item
                    }
                }
            )
        }
    }

    private fun upsertCouncilCard(
        taskId: String,
        objective: String = "",
        mode: CouncilExecutionMode = localState.value.selectedExecutionMode,
        transform: (AgentTimelineItem.CouncilSessionCard) -> AgentTimelineItem.CouncilSessionCard,
    ) {
        val cardId = "council-card-$taskId"
        localState.update { state ->
            val budget = uiState.value.councilTokenBudget
            val existing = state.timeline.firstOrNull {
                it is AgentTimelineItem.CouncilSessionCard && (it.id == cardId || it.taskId == taskId)
            } as? AgentTimelineItem.CouncilSessionCard
            if (existing != null) {
                state.copy(
                    timeline = state.timeline.map { item ->
                        if (item is AgentTimelineItem.CouncilSessionCard && item.id == existing.id) {
                            transform(item.copy(tokenBudget = budget))
                        } else {
                            item
                        }
                    }
                )
            } else {
                val created = transform(
                    AgentTimelineItem.CouncilSessionCard(
                        id = cardId,
                        timestampMs = System.currentTimeMillis(),
                        taskId = taskId,
                        objective = objective,
                        executionMode = mode,
                        tokenBudget = budget,
                    )
                )
                state.copy(timeline = state.timeline + created)
            }
        }
    }

    private fun updateAssistantItem(
        id: String,
        transform: (AgentTimelineItem.AssistantResponse) -> AgentTimelineItem.AssistantResponse,
    ) {
        localState.update { state ->
            val exists = state.timeline.any { it is AgentTimelineItem.AssistantResponse && it.id == id }
            val updated = if (exists) {
                state.timeline.map { item ->
                    if (item is AgentTimelineItem.AssistantResponse && item.id == id) {
                        transform(item)
                    } else {
                        item
                    }
                }
            } else {
                val base = AgentTimelineItem.AssistantResponse(
                    id = id,
                    timestampMs = System.currentTimeMillis(),
                    text = "",
                    modelId = state.selectedModelId,
                    isStreaming = true,
                )
                state.timeline + transform(base)
            }
            state.copy(timeline = updated)
        }
    }

    private fun upsertToolCard(card: AgentTimelineItem.ToolCallCard) {
        localState.update { state ->
            val exists = state.timeline.any { it is AgentTimelineItem.ToolCallCard && it.id == card.id }
            val updated = if (exists) {
                state.timeline.map { item ->
                    if (item is AgentTimelineItem.ToolCallCard && item.id == card.id) card else item
                }
            } else {
                state.timeline + card
            }
            state.copy(timeline = updated)
        }
    }

    private fun upsertValidationCard(card: AgentTimelineItem.ValidationCard) {
        localState.update { state ->
            val exists = state.timeline.any { it is AgentTimelineItem.ValidationCard && it.id == card.id }
            val updated = if (exists) {
                state.timeline.map { item ->
                    if (item is AgentTimelineItem.ValidationCard && item.id == card.id) card else item
                }
            } else {
                state.timeline + card
            }
            state.copy(timeline = updated)
        }
    }

    private fun updateToolCard(
        callId: String,
        transform: (AgentTimelineItem.ToolCallCard) -> AgentTimelineItem.ToolCallCard,
    ) {
        localState.update { state ->
            state.copy(
                timeline = state.timeline.map { item ->
                    if (item is AgentTimelineItem.ToolCallCard && item.id == callId) {
                        transform(item)
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun refreshFiles(relativePath: String) {
        viewModelScope.launch {
            val rootPath = uiState.value.project?.workspacePath ?: return@launch
            localState.update { it.copy(filesLoading = true, filesError = null) }
            projectRepository.list(rootPath, relativePath)
                .onSuccess { entries ->
                    localState.update {
                        it.copy(
                            currentRelativePath = relativePath,
                            files = entries,
                            filesLoading = false,
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            filesLoading = false,
                            filesError = err.message ?: "Unable to read workspace directory.",
                        )
                    }
                }
        }
    }

    fun openEntry(entry: FileTreeEntry) {
        if (entry.isDirectory) {
            refreshFiles(entry.relativePath)
            return
        }
        val rootPath = uiState.value.project?.workspacePath ?: return
        viewModelScope.launch {
            projectRepository.readText(rootPath, entry.relativePath)
                .onSuccess { text ->
                    localState.update {
                        it.copy(
                            openedFilePath = entry.relativePath,
                            openedFileContent = text,
                            editedFileDraft = text,
                            isEditingFile = false,
                            filesError = null,
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(filesError = err.message ?: "Unable to open file.")
                    }
                }
        }
    }

    fun toggleEditMode(editing: Boolean) {
        localState.update {
            it.copy(
                isEditingFile = editing,
                editedFileDraft = if (editing) it.openedFileContent.orEmpty() else it.editedFileDraft,
            )
        }
    }

    fun updateEditedFileDraft(content: String) {
        localState.update { it.copy(editedFileDraft = content) }
    }

    fun saveOpenedFile() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val relPath = localState.value.openedFilePath ?: return
        val content = localState.value.editedFileDraft
        viewModelScope.launch {
            projectRepository.writeText(rootPath, relPath, content)
                .onSuccess {
                    projectRepository.markOpened(projectId)
                    localState.update {
                        it.copy(
                            openedFileContent = content,
                            isEditingFile = false,
                            filesError = null,
                        )
                    }
                    refreshFiles(localState.value.currentRelativePath)
                    intelligenceRepository?.invalidateRepoMap(projectId, listOf(relPath))
                }
                .onFailure { err ->
                    localState.update { it.copy(filesError = err.message ?: "Failed to save file.") }
                }
        }
    }

    fun closeOpenedFile() {
        localState.update {
            it.copy(openedFilePath = null, openedFileContent = null, isEditingFile = false)
        }
    }

    fun openCreateFileDialog() {
        localState.update { it.copy(showCreateFileDialog = true, newFileNameDraft = "", filesError = null) }
    }

    fun dismissCreateFileDialog() {
        localState.update { it.copy(showCreateFileDialog = false) }
    }

    fun updateNewFileNameDraft(name: String) {
        localState.update { it.copy(newFileNameDraft = name) }
    }

    fun confirmCreateFile() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val rawName = localState.value.newFileNameDraft.trim()
        if (rawName.isEmpty()) {
            localState.update { it.copy(filesError = "File name cannot be empty.") }
            return
        }
        val current = localState.value.currentRelativePath
        val targetRel = if (current.isEmpty()) rawName else "$current/$rawName"
        viewModelScope.launch {
            projectRepository.writeText(rootPath, targetRel, "")
                .onSuccess {
                    projectRepository.markOpened(projectId)
                    localState.update { it.copy(showCreateFileDialog = false, filesError = null) }
                    refreshFiles(current)
                    intelligenceRepository?.invalidateRepoMap(projectId, listOf(targetRel))
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            showCreateFileDialog = false,
                            filesError = err.message ?: "Rejected invalid file path.",
                        )
                    }
                }
        }
    }

    fun requestDeleteEntry(entry: FileTreeEntry) {
        localState.update { it.copy(entryPendingDeletion = entry) }
    }

    fun dismissDeleteEntry() {
        localState.update { it.copy(entryPendingDeletion = null) }
    }

    fun confirmDeleteEntry() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val target = localState.value.entryPendingDeletion ?: return
        viewModelScope.launch {
            projectRepository.delete(rootPath, target.relativePath)
                .onSuccess {
                    localState.update { it.copy(entryPendingDeletion = null, filesError = null) }
                    refreshFiles(localState.value.currentRelativePath)
                    intelligenceRepository?.invalidateRepoMap(projectId, listOf(target.relativePath))
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            entryPendingDeletion = null,
                            filesError = err.message ?: "Failed to delete entry.",
                        )
                    }
                }
        }
    }

    fun navigateUpDirectory() {
        val current = localState.value.currentRelativePath
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        refreshFiles(parent)
    }

    fun updateTerminalInput(value: String) {
        localState.update { it.copy(terminalInput = value) }
    }

    fun submitTerminalCommand() {
        val rawInput = localState.value.terminalInput
        val activeProcId = localState.value.activeTerminalProcessId
        if (localState.value.terminalIsRunning && activeProcId != null) {
            if (rawInput.isEmpty()) return
            localState.update { it.copy(terminalInput = "") }
            appendTerminalLine("> $rawInput", isError = false)
            viewModelScope.launch {
                runtimeExecutor.sendInput(activeProcId, rawInput)
                    .onFailure { err ->
                        appendTerminalLine(
                            text = "[stdin error] ${err.message ?: "Unable to write to process"}",
                            isError = true,
                        )
                    }
            }
            return
        }

        val cmd = rawInput.trim()
        if (cmd.isEmpty()) return
        val workDir = uiState.value.project?.workspacePath ?: return

        val processId = UUID.randomUUID().toString()
        localState.update { state ->
            state.copy(
                terminalInput = "",
                terminalIsRunning = true,
                activeTerminalProcessId = processId,
                activeTerminalPid = null,
                lastTerminalExitCode = null,
            )
        }
        appendTerminalLine("$ $cmd", isError = false)

        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                RuntimeExecutionService.startIntent(applicationContext),
            )
        }

        activeTerminalJob?.cancel()
        activeTerminalJob = viewModelScope.launch {
            val timeoutSeconds = runCatching {
                preferencesRepository.preferences.first().commandTimeoutSeconds
            }.getOrDefault(120)

            runtimeExecutor.execute(
                RuntimeCommand(
                    command = cmd,
                    workingDirectory = workDir,
                    timeoutSeconds = timeoutSeconds,
                    processId = processId,
                    maxOutputLines = MAX_TERMINAL_UI_LINES,
                )
            ).collect { event ->
                when (event) {
                    is ProcessEvent.Started -> {
                        localState.update { it.copy(activeTerminalPid = event.pid) }
                    }
                    is ProcessEvent.Stdout -> {
                        appendTerminalLine(event.line, isError = false)
                    }
                    is ProcessEvent.Stderr -> {
                        appendTerminalLine(event.line, isError = true)
                    }
                    is ProcessEvent.Exited -> {
                        localState.update {
                            it.copy(
                                terminalIsRunning = false,
                                activeTerminalProcessId = null,
                                activeTerminalPid = null,
                                lastTerminalExitCode = event.exitCode,
                            )
                        }
                        if (event.exitCode != 0) {
                            appendTerminalLine("[exited with code ${event.exitCode}]", isError = true)
                        }
                        refreshFiles(localState.value.currentRelativePath)
                    }
                    is ProcessEvent.Failed -> {
                        localState.update {
                            it.copy(
                                terminalIsRunning = false,
                                activeTerminalProcessId = null,
                                activeTerminalPid = null,
                            )
                        }
                        appendTerminalLine(event.error.userMessage, isError = true)
                        refreshFiles(localState.value.currentRelativePath)
                    }
                }
            }

            localState.update {
                if (it.activeTerminalProcessId == processId) {
                    it.copy(
                        terminalIsRunning = false,
                        activeTerminalProcessId = null,
                        activeTerminalPid = null,
                    )
                } else {
                    it
                }
            }
        }
    }

    fun stopTerminalCommand() {
        val processId = localState.value.activeTerminalProcessId
        activeTerminalJob?.cancel()
        activeTerminalJob = null
        localState.update {
            it.copy(
                terminalIsRunning = false,
                activeTerminalProcessId = null,
                activeTerminalPid = null,
            )
        }
        appendTerminalLine("[stopped by user]", isError = true)
        if (processId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runtimeExecutor.terminate(processId)
            }
        }
    }

    fun clearTerminalOutput() {
        if (localState.value.terminalIsRunning) return
        localState.update {
            it.copy(
                terminalLines = emptyList(),
                lastTerminalExitCode = null,
            )
        }
    }

    private fun appendTerminalLine(text: String, isError: Boolean) {
        val newLine = TerminalLine(
            id = UUID.randomUUID().toString(),
            text = text,
            isError = isError,
        )
        localState.update { state ->
            val updated = (state.terminalLines + newLine).let { lines ->
                if (lines.size > MAX_TERMINAL_UI_LINES) {
                    lines.takeLast(MAX_TERMINAL_UI_LINES)
                } else {
                    lines
                }
            }
            state.copy(terminalLines = updated)
        }
    }

    fun updatePreviewUrl(url: String) {
        localState.update { it.copy(previewUrl = url, previewUrlError = null) }
    }

    fun updatePreviewCommandDraft(command: String) {
        localState.update { it.copy(previewCommandDraft = command, previewUrlError = null) }
    }

    fun togglePreviewLogs(show: Boolean) {
        localState.update { it.copy(previewShowLogs = show) }
    }

    fun detectPreviewConfiguration() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val activeDir = runtimeInstallerRepository.activeDir
            val hasPython3 = listOf("usr/local/bin/python3", "usr/bin/python3", "bin/python3")
                .any { File(activeDir, it).exists() }
            val detected = PreviewServerManager.detectPreviewServer(
                projectRoot = File(rootPath),
                hasPython3 = hasPython3,
            )
            if (detected != null) {
                localState.update { state ->
                    state.copy(
                        previewDetectedFramework = detected.frameworkLabel,
                        previewCommandDraft = state.previewCommandDraft.ifBlank { detected.command },
                        previewUrl = state.previewUrl.ifBlank { detected.suggestedUrl },
                    )
                }
            }
        }
    }

    fun startPreviewServer() {
        val workDir = uiState.value.project?.workspacePath ?: return
        val cmd = localState.value.previewCommandDraft.trim()
        if (cmd.isEmpty()) {
            localState.update {
                it.copy(
                    previewStatus = PreviewServerStatus.ERROR,
                    previewStatusMessage = "Enter a dev server command (e.g. npm run dev or python3 -m http.server 8080).",
                )
            }
            return
        }

        val previousProcId = localState.value.activePreviewProcessId
        activePreviewJob?.cancel()
        if (previousProcId != null) {
            CoroutineScope(Dispatchers.IO).launch { runtimeExecutor.terminate(previousProcId) }
        }

        val processId = "preview-${UUID.randomUUID().toString().take(8)}"
        localState.update { state ->
            state.copy(
                previewStatus = PreviewServerStatus.STARTING,
                previewStatusMessage = "Starting dev server…",
                previewUrlError = null,
                activePreviewProcessId = processId,
                previewConsoleLines = listOf(
                    PreviewConsoleLine(
                        id = UUID.randomUUID().toString(),
                        text = "$ $cmd",
                        source = "SERVER",
                    )
                ),
            )
        }

        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                RuntimeExecutionService.startIntent(applicationContext),
            )
        }

        activePreviewJob = viewModelScope.launch {
            runtimeExecutor.execute(
                RuntimeCommand(
                    command = cmd,
                    workingDirectory = workDir,
                    timeoutSeconds = 3_600,
                    processId = processId,
                    maxOutputLines = MAX_TERMINAL_UI_LINES,
                )
            ).collect { event ->
                when (event) {
                    is ProcessEvent.Started -> {
                        val fallbackUrl = localState.value.previewUrl.trim()
                        val validFallback = PreviewServerManager.validatePreviewUrl(fallbackUrl).getOrNull()
                        localState.update { state ->
                            state.copy(
                                previewStatusMessage = "Process started (PID ${event.pid ?: "?"}) — waiting for local port…",
                                previewUrl = validFallback ?: state.previewUrl,
                            )
                        }
                    }
                    is ProcessEvent.Stdout -> {
                        handlePreviewServerOutput(event.line, isError = false)
                    }
                    is ProcessEvent.Stderr -> {
                        handlePreviewServerOutput(event.line, isError = true)
                    }
                    is ProcessEvent.Exited -> {
                        val abnormal = event.exitCode != 0
                        localState.update { state ->
                            if (state.activePreviewProcessId != processId) {
                                state
                            } else {
                                state.copy(
                                    activePreviewProcessId = null,
                                    previewStatus = if (abnormal) PreviewServerStatus.ERROR else PreviewServerStatus.STOPPED,
                                    previewStatusMessage = if (abnormal) {
                                        "Dev server exited with code ${event.exitCode}"
                                    } else {
                                        "Server stopped"
                                    },
                                )
                            }
                        }
                        if (abnormal) {
                            appendPreviewConsoleLine("Process exited with code ${event.exitCode}", isError = true, source = "SERVER")
                        }
                    }
                    is ProcessEvent.Failed -> {
                        val cancelled = event.error is AgtError.ProcessCancelled
                        localState.update { state ->
                            if (state.activePreviewProcessId != processId) {
                                state
                            } else {
                                state.copy(
                                    activePreviewProcessId = null,
                                    previewStatus = if (cancelled) PreviewServerStatus.STOPPED else PreviewServerStatus.ERROR,
                                    previewStatusMessage = if (cancelled) "Server stopped" else event.error.userMessage,
                                )
                            }
                        }
                        if (!cancelled) {
                            appendPreviewConsoleLine(event.error.userMessage, isError = true, source = "SERVER")
                        }
                    }
                }
            }
        }
    }

    private fun handlePreviewServerOutput(line: String, isError: Boolean) {
        appendPreviewConsoleLine(line, isError = isError, source = "SERVER")
        val discoveredUrl = PreviewServerManager.extractLocalUrlFromOutput(line)
        if (discoveredUrl != null) {
            localState.update { state ->
                val urlChanged = state.previewUrl != discoveredUrl
                state.copy(
                    previewUrl = discoveredUrl,
                    previewStatus = PreviewServerStatus.RUNNING,
                    previewStatusMessage = "Running at $discoveredUrl",
                    previewBrowserActive = true,
                    previewUrlError = null,
                    previewReloadNonce = if (urlChanged || !state.previewBrowserActive) state.previewReloadNonce + 1 else state.previewReloadNonce,
                )
            }
        } else if (localState.value.previewStatus == PreviewServerStatus.STARTING) {
            val existingValid = PreviewServerManager.validatePreviewUrl(localState.value.previewUrl).getOrNull()
            if (existingValid != null && ("listening" in line.lowercase() || "ready" in line.lowercase() || "serving" in line.lowercase())) {
                localState.update { state ->
                    state.copy(
                        previewUrl = existingValid,
                        previewStatus = PreviewServerStatus.RUNNING,
                        previewStatusMessage = "Running at $existingValid",
                        previewBrowserActive = true,
                        previewReloadNonce = state.previewReloadNonce + 1,
                    )
                }
            }
        }
    }

    fun stopPreviewServer() {
        val processId = localState.value.activePreviewProcessId
        activePreviewJob?.cancel()
        activePreviewJob = null
        localState.update {
            it.copy(
                activePreviewProcessId = null,
                previewStatus = PreviewServerStatus.STOPPED,
                previewStatusMessage = "Server stopped",
            )
        }
        appendPreviewConsoleLine("[preview server stopped]", isError = false, source = "SERVER")
        if (processId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runtimeExecutor.terminate(processId)
            }
        }
    }

    fun openOrReloadPreviewUrl() {
        val raw = localState.value.previewUrl
        PreviewServerManager.validatePreviewUrl(raw)
            .onSuccess { normalized ->
                localState.update { state ->
                    state.copy(
                        previewUrl = normalized,
                        previewUrlError = null,
                        previewBrowserActive = true,
                        previewReloadNonce = state.previewReloadNonce + 1,
                    )
                }
            }
            .onFailure { err ->
                localState.update {
                    it.copy(previewUrlError = err.message ?: "Invalid local preview URL.")
                }
            }
    }

    fun reportPreviewWebViewError(message: String, isConsoleError: Boolean) {
        appendPreviewConsoleLine(
            text = message,
            isError = true,
            source = if (isConsoleError) "CONSOLE" else "WEBVIEW",
        )
        if (!isConsoleError) {
            localState.update {
                it.copy(previewUrlError = message)
            }
        }
    }

    private fun appendPreviewConsoleLine(text: String, isError: Boolean, source: String) {
        val line = PreviewConsoleLine(
            id = UUID.randomUUID().toString(),
            text = text,
            isError = isError,
            source = source,
        )
        localState.update { state ->
            val updated = (state.previewConsoleLines + line).takeLast(MAX_PREVIEW_LOG_LINES)
            state.copy(previewConsoleLines = updated)
        }
    }

    fun openGitDialog() {
        localState.update {
            it.copy(
                gitState = it.gitState.copy(
                    showDialog = true,
                    actionError = null,
                    actionMessage = null,
                    pendingPushApproval = false,
                    pendingPrApproval = false,
                )
            )
        }
        refreshGitStatus()
    }

    fun dismissGitDialog() {
        if (localState.value.gitState.isActionBusy) return
        localState.update {
            it.copy(
                gitState = it.gitState.copy(
                    showDialog = false,
                    pendingPushApproval = false,
                    pendingPrApproval = false,
                )
            )
        }
    }

    fun refreshGitStatus() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        viewModelScope.launch {
            localState.update {
                it.copy(gitState = it.gitState.copy(isLoadingStatus = true, actionError = null))
            }
            val status = gitRepositoryService.inspectStatus(rootPath)
            localState.update {
                it.copy(
                    gitState = it.gitState.copy(
                        isLoadingStatus = false,
                        status = status,
                    )
                )
            }
        }
    }

    fun initGitRepository() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        viewModelScope.launch {
            localState.update {
                it.copy(gitState = it.gitState.copy(isActionBusy = true, actionError = null, actionMessage = null))
            }
            gitRepositoryService.initRepository(projectId, rootPath)
                .onSuccess { branch ->
                    val updated = gitRepositoryService.inspectStatus(rootPath)
                    localState.update {
                        it.copy(
                            gitState = it.gitState.copy(
                                isActionBusy = false,
                                status = updated,
                                actionMessage = "Initialized Git repository on branch '$branch'.",
                            )
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            gitState = it.gitState.copy(
                                isActionBusy = false,
                                actionError = err.message ?: "Failed to initialize Git repository.",
                            )
                        )
                    }
                }
        }
    }

    fun updateGitCommitMessage(value: String) {
        localState.update {
            it.copy(gitState = it.gitState.copy(commitMessageDraft = value, actionError = null))
        }
    }

    fun toggleExcludeSensitiveFiles(exclude: Boolean) {
        localState.update {
            it.copy(gitState = it.gitState.copy(excludeSensitiveFiles = exclude))
        }
    }

    fun toggleGitDiffPreview(show: Boolean) {
        localState.update {
            it.copy(gitState = it.gitState.copy(showDiffPreview = show))
        }
    }

    fun commitGitChanges() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val gs = localState.value.gitState
        viewModelScope.launch {
            localState.update {
                it.copy(gitState = it.gitState.copy(isActionBusy = true, actionError = null, actionMessage = null))
            }
            gitRepositoryService.commitChanges(
                projectId = projectId,
                projectRootPath = rootPath,
                commitMessage = gs.commitMessageDraft,
                excludeSensitiveFiles = gs.excludeSensitiveFiles,
            ).onSuccess { result ->
                val updated = gitRepositoryService.inspectStatus(rootPath)
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            status = updated,
                            commitMessageDraft = "",
                            showDiffPreview = false,
                            actionMessage = "Committed ${result.filesCommittedCount} file(s) on ${result.branchName} (${result.commitHash}).",
                        )
                    )
                }
            }.onFailure { err ->
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            actionError = err.message ?: "Git commit failed.",
                        )
                    )
                }
            }
        }
    }

    fun requestGitPushApproval() {
        localState.update {
            it.copy(
                gitState = it.gitState.copy(
                    pendingPushApproval = true,
                    actionError = null,
                    actionMessage = null,
                )
            )
        }
    }

    fun cancelGitPushApproval() {
        localState.update {
            it.copy(gitState = it.gitState.copy(pendingPushApproval = false))
        }
    }

    fun confirmGitPush() {
        val rootPath = uiState.value.project?.workspacePath ?: return
        val branch = localState.value.gitState.status.branchName
        viewModelScope.launch {
            localState.update {
                it.copy(
                    gitState = it.gitState.copy(
                        isActionBusy = true,
                        pendingPushApproval = false,
                        actionError = null,
                        actionMessage = null,
                    )
                )
            }
            gitRepositoryService.pushBranch(
                projectId = projectId,
                projectRootPath = rootPath,
                branchName = branch,
                userApproved = true,
            ).onSuccess { result ->
                val updated = gitRepositoryService.inspectStatus(rootPath)
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            status = updated,
                            actionMessage = result.outputSummary,
                        )
                    )
                }
            }.onFailure { err ->
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            actionError = err.message ?: "Git push failed.",
                        )
                    )
                }
            }
        }
    }

    fun openPullRequestForm() {
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val headBranch = localState.value.gitState.status.branchName ?: prefs.gitDefaultBranch
            localState.update {
                it.copy(
                    gitState = it.gitState.copy(
                        showPrForm = true,
                        pendingPrApproval = false,
                        prHeadBranchDraft = it.gitState.prHeadBranchDraft.ifBlank { headBranch },
                        prBaseBranchDraft = it.gitState.prBaseBranchDraft.ifBlank { prefs.gitDefaultBranch },
                        actionError = null,
                    )
                )
            }
        }
    }

    fun closePullRequestForm() {
        localState.update {
            it.copy(gitState = it.gitState.copy(showPrForm = false, pendingPrApproval = false))
        }
    }

    fun updatePrTitle(value: String) {
        localState.update { it.copy(gitState = it.gitState.copy(prTitleDraft = value, actionError = null)) }
    }

    fun updatePrBody(value: String) {
        localState.update { it.copy(gitState = it.gitState.copy(prBodyDraft = value)) }
    }

    fun updatePrHeadBranch(value: String) {
        localState.update { it.copy(gitState = it.gitState.copy(prHeadBranchDraft = value, actionError = null)) }
    }

    fun updatePrBaseBranch(value: String) {
        localState.update { it.copy(gitState = it.gitState.copy(prBaseBranchDraft = value, actionError = null)) }
    }

    fun requestCreatePullRequestApproval() {
        val gs = localState.value.gitState
        if (gs.prTitleDraft.isBlank()) {
            localState.update {
                it.copy(gitState = it.gitState.copy(actionError = "Enter a pull request title."))
            }
            return
        }
        if (gs.prHeadBranchDraft.isBlank() || gs.prBaseBranchDraft.isBlank()) {
            localState.update {
                it.copy(gitState = it.gitState.copy(actionError = "Head and base branches are required."))
            }
            return
        }
        localState.update {
            it.copy(gitState = it.gitState.copy(pendingPrApproval = true, actionError = null))
        }
    }

    fun cancelCreatePullRequestApproval() {
        localState.update {
            it.copy(gitState = it.gitState.copy(pendingPrApproval = false))
        }
    }

    fun confirmCreatePullRequest() {
        val gs = localState.value.gitState
        val remoteUrl = gs.status.remoteUrl ?: uiState.value.project?.remoteUrl
        if (remoteUrl.isNullOrBlank()) {
            localState.update {
                it.copy(
                    gitState = it.gitState.copy(
                        pendingPrApproval = false,
                        actionError = "No GitHub remote URL configured for this project.",
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            localState.update {
                it.copy(
                    gitState = it.gitState.copy(
                        isActionBusy = true,
                        pendingPrApproval = false,
                        actionError = null,
                        actionMessage = null,
                    )
                )
            }
            gitHubRepository.createPullRequest(
                remoteUrl = remoteUrl,
                title = gs.prTitleDraft,
                body = gs.prBodyDraft,
                headBranch = gs.prHeadBranchDraft,
                baseBranch = gs.prBaseBranchDraft,
                userApproved = true,
            ).onSuccess { pr ->
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            showPrForm = false,
                            prTitleDraft = "",
                            prBodyDraft = "",
                            lastPullRequest = pr,
                            actionMessage = "Created PR #${pr.number}: ${pr.title}",
                        )
                    )
                }
            }.onFailure { err ->
                localState.update {
                    it.copy(
                        gitState = it.gitState.copy(
                            isActionBusy = false,
                            actionError = err.message ?: "Failed to create pull request.",
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        val processId = localState.value.activeTerminalProcessId
        val previewProcessId = localState.value.activePreviewProcessId
        val taskId = activeTaskId
        activeTerminalJob?.cancel()
        activePreviewJob?.cancel()
        activeStreamJob?.cancel()
        if (processId != null || previewProcessId != null || taskId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                if (processId != null) runtimeExecutor.terminate(processId)
                if (previewProcessId != null) runtimeExecutor.terminate(previewProcessId)
                if (taskId != null) agentOrchestrator.stopTask(taskId)
            }
        }
        super.onCleared()
    }

    private companion object {
        const val MAX_TERMINAL_UI_LINES = 1000
        const val MAX_PREVIEW_LOG_LINES = 400
    }

    class Factory(
        private val appContext: Context,
        private val projectId: String,
        private val projectRepository: ProjectRepository,
        private val checkpointManager: CheckpointManager,
        private val gatewayRepository: GatewayRepository,
        private val gitHubRepository: GitHubRepository,
        private val gitRepositoryService: GitRepositoryService,
        private val preferencesRepository: AppPreferencesRepository,
        private val runtimeInstallerRepository: RuntimeInstallerRepository,
        private val openCodeInstallerRepository: OpenCodeInstallerRepository,
        private val runtimeExecutor: LocalRuntimeExecutor,
        private val agentRuntime: AgentRuntime,
        private val agentOrchestrator: AgentOrchestrator,
        private val extensionRegistryRepository: ExtensionRegistryRepository? = null,
        private val piInstallerRepository: PiInstallerRepository? = null,
        private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
        private val notificationManager: AgtNotificationManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WorkspaceViewModel(
                appContext = appContext,
                projectId = projectId,
                projectRepository = projectRepository,
                checkpointManager = checkpointManager,
                gatewayRepository = gatewayRepository,
                gitHubRepository = gitHubRepository,
                gitRepositoryService = gitRepositoryService,
                preferencesRepository = preferencesRepository,
                runtimeInstallerRepository = runtimeInstallerRepository,
                openCodeInstallerRepository = openCodeInstallerRepository,
                runtimeExecutor = runtimeExecutor,
                agentRuntime = agentRuntime,
                agentOrchestrator = agentOrchestrator,
                extensionRegistryRepository = extensionRegistryRepository,
                piInstallerRepository = piInstallerRepository,
                intelligenceRepository = intelligenceRepository,
                notificationManager = notificationManager,
            ) as T
        }
    }
}
