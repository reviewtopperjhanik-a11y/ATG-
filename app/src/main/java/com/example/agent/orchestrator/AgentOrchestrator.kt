package com.example.agent.orchestrator

import com.example.agent.DelegatingAgentRuntime
import com.example.core.common.AgtError
import com.example.data.council.CouncilRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentRuntime
import com.example.domain.contracts.AgentSession
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.CheckpointManager
import com.example.domain.contracts.ProjectRef
import com.example.domain.contracts.RuntimeExecutor
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRoundDiff
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilSession
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.KnowledgeRetrievalResult
import com.example.domain.model.ModelHandoffPacket
import com.example.domain.model.PlanStep
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectInspection
import com.example.domain.model.TaskExecutionSummary
import com.example.domain.model.TaskPlan
import com.example.domain.model.TaskResumeCursor
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.domain.model.ValidationCheckResult
import com.example.domain.model.ValidationCommand
import com.example.domain.model.ValidationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface OrchestratorEvent {
    data class StateChanged(val state: TaskState) : OrchestratorEvent
    data class InspectionReady(val inspection: ProjectInspection) : OrchestratorEvent
    data class CheckpointCreated(val checkpointId: String, val reason: String, val fileCount: Int) : OrchestratorEvent
    data class PlanReady(val plan: TaskPlan, val requiresApproval: Boolean) : OrchestratorEvent
    data class PlanUpdated(val plan: TaskPlan) : OrchestratorEvent
    data class HandoffReady(val packet: ModelHandoffPacket) : OrchestratorEvent
    data class RuntimeEvent(val event: AgentEvent) : OrchestratorEvent
    data class ValidationStarted(val command: ValidationCommand) : OrchestratorEvent
    data class ValidationCompleted(val result: ValidationCheckResult) : OrchestratorEvent
    data class FixLoopStarted(val attempt: Int, val maxAttempts: Int, val failingLabels: String) : OrchestratorEvent
    data class SummaryReady(val summary: TaskExecutionSummary) : OrchestratorEvent
    data class StatusNotice(val title: String, val detail: String, val isWarning: Boolean = false) : OrchestratorEvent
    data class CouncilProgress(
        val taskId: String,
        val sessionId: String,
        val roundNumber: Int,
        val event: CouncilProgressEvent,
    ) : OrchestratorEvent

    data class CouncilSessionUpdated(
        val session: CouncilSession,
        val diffFromPrevious: CouncilRoundDiff? = null,
        val requiresDecision: Boolean = false,
    ) : OrchestratorEvent
}

data class OrchestratorTaskRequest(
    val taskId: String = UUID.randomUUID().toString(),
    val project: ProjectRef,
    val prompt: String,
    val modelId: String,
    val protocolMode: GatewayProtocolMode,
    val attachments: List<com.example.domain.model.AgentAttachment> = emptyList(),
    val backendId: String? = null,
    val executionMode: CouncilExecutionMode = CouncilExecutionMode.DIRECT_CODE,
    val approvedCouncilSessionId: String? = null,
    val roleModelOverrides: Map<CouncilRole, String> = emptyMap(),
    val enabledCouncilRoles: Set<CouncilRole> = CouncilRole.DEFAULT_SPECIALISTS,
    val maxCouncilTokenBudget: Int = 24_000,
)

data class ResumeTaskRequest(
    val taskId: String,
    val project: ProjectRef,
    val modelId: String,
    val protocolMode: GatewayProtocolMode,
    val attachments: List<com.example.domain.model.AgentAttachment> = emptyList(),
    val backendId: String? = null,
    val additionalInstruction: String? = null,
)

/**
 * Coordinates the end-to-end M7 autonomous software engineering lifecycle and Phase 4
 * durable resume & cross-model/cross-agent handoff, plus Phase 8-9 Multi-Agent Council:
 * Context Loading -> Repository Inspection -> Optional Council Debate / Re-Council ->
 * Planning / ModelHandoffPacket -> Pre-task Checkpoint -> OpenCode/Pi Execution ->
 * Real Validation Loop -> Bounded Auto-Debug Fix Loop -> Task Summary & Persistence.
 */
class AgentOrchestrator(
    private val projectRepository: ProjectRepository,
    private val checkpointManager: CheckpointManager,
    private val preferencesRepository: AppPreferencesRepository,
    private val runtimeExecutor: LocalRuntimeExecutorFacade,
    private val agentRuntime: AgentRuntime,
    private val maxFixRetries: Int = 2,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
    private val handoffContextBuilder: HandoffContextBuilder? = null,
    private val councilGraphEngine: CouncilGraphEngine? = null,
    private val councilRepository: CouncilRepository? = null,
) {
    /**
     * Thin wrapper so callers can supply either [RuntimeExecutor] or a test double seamlessly.
     */
    class LocalRuntimeExecutorFacade(val executor: RuntimeExecutor)

    private val effectiveHandoffBuilder: HandoffContextBuilder by lazy {
        handoffContextBuilder ?: HandoffContextBuilder(
            intelligenceRepository = intelligenceRepository,
            checkpointManager = checkpointManager,
            repoMapEngine = intelligenceRepository?.repoMapEngine ?: RepoMapEngine(),
        )
    }

    private data class ActiveRun(
        val taskId: String,
        val projectId: String,
        val modelId: String,
        val backendId: String,
        var activeSession: AgentSession? = null,
        var planApproval: CompletableDeferred<Boolean>? = null,
        var councilSessionId: String? = null,
        var councilDecision: CompletableDeferred<CouncilUserDecision>? = null,
        @Volatile var pauseRequested: Boolean = false,
    )

    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()

    fun executeTask(request: OrchestratorTaskRequest): Flow<OrchestratorEvent> =
        runOrchestratorFlow(newRequest = request, resumeRequest = null)

    fun resumeTask(request: ResumeTaskRequest): Flow<OrchestratorEvent> =
        runOrchestratorFlow(newRequest = null, resumeRequest = request)

    private fun runOrchestratorFlow(
        newRequest: OrchestratorTaskRequest?,
        resumeRequest: ResumeTaskRequest?,
    ): Flow<OrchestratorEvent> = callbackFlow {
        require((newRequest != null) xor (resumeRequest != null)) {
            "Either newRequest or resumeRequest must be provided."
        }
        val taskId = newRequest?.taskId ?: resumeRequest!!.taskId
        val project = newRequest?.project ?: resumeRequest!!.project
        val modelId = newRequest?.modelId ?: resumeRequest!!.modelId
        val protocolMode = newRequest?.protocolMode ?: resumeRequest!!.protocolMode
        val attachments = if (newRequest != null) newRequest.attachments else resumeRequest!!.attachments
        val requestedBackendId = if (newRequest != null) newRequest.backendId else resumeRequest!!.backendId
        val validationRunner = ValidationRunner(runtimeExecutor.executor)

        val worker = launch(Dispatchers.IO) {
            val prefs = preferencesRepository.preferences.first()
            val effectiveBackendId = requestedBackendId ?: prefs.activeAgentId
            val runState = ActiveRun(
                taskId = taskId,
                projectId = project.id,
                modelId = modelId,
                backendId = effectiveBackendId,
            )
            activeRuns[taskId] = runState

            var objective = newRequest?.prompt.orEmpty()
            var stateMachine = TaskStateMachine(TaskState.CREATED)
            var preTaskCheckpointId: String? = null
            var currentPlan: TaskPlan? = null
            var latestChecks: List<ValidationCheckResult> = emptyList()
            var fixAttempts = 0
            var activeStepIndex = 0
            var lastError: String? = null
            var lastEventSeq = 0L
            var pendingApprovalId: String? = null
            var pendingApprovalTitle: String? = null
            var lastFailingLabels: String? = null
            val keyDecisions = mutableListOf<String>()
            val changedFiles = LinkedHashSet<String>()
            val unresolvedIssues = mutableListOf<String>()
            var runtimeFailedReason: String? = null
            var resumedSnapshot: TaskStateSnapshot? = null
            var approvedCouncilPlanMarkdown: String? = null
            val executionMode = newRequest?.executionMode ?: CouncilExecutionMode.DIRECT_CODE

            fun syntheticRequest(): OrchestratorTaskRequest = OrchestratorTaskRequest(
                taskId = taskId,
                project = project,
                prompt = objective.ifBlank { "Resumed task $taskId" },
                modelId = modelId,
                protocolMode = protocolMode,
                attachments = attachments,
                backendId = effectiveBackendId,
            )

            suspend fun persistSnapshot() {
                intelligenceRepository?.saveTaskSnapshot(
                    TaskStateSnapshot(
                        taskId = taskId,
                        projectId = project.id,
                        objective = objective,
                        currentState = stateMachine.currentState,
                        activeStepIndex = activeStepIndex,
                        planSteps = currentPlan?.steps.orEmpty(),
                        keyDecisions = keyDecisions.toList(),
                        modifiedFiles = changedFiles.toList(),
                        resumeCursor = TaskResumeCursor(
                            checkpointId = preTaskCheckpointId,
                            fixAttempts = fixAttempts,
                            lastError = lastError,
                            lastEventSeq = lastEventSeq,
                            pendingApprovalId = pendingApprovalId,
                            pendingApprovalTitle = pendingApprovalTitle,
                        ),
                        lastModelId = modelId,
                        lastBackendId = effectiveBackendId,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }

            suspend fun recordFlight(
                eventType: FlightRecorderEventType,
                outcome: FlightRecorderOutcome,
                toolName: String? = null,
                commandOrPath: String? = null,
                exitCode: Int? = null,
                outputExcerpt: String = "",
                checkpointId: String? = preTaskCheckpointId,
            ) {
                val repo = intelligenceRepository ?: return
                lastEventSeq = repo.recordFlightEvent(
                    projectId = project.id,
                    taskId = taskId,
                    eventType = eventType,
                    outcome = outcome,
                    toolName = toolName,
                    commandOrPath = commandOrPath,
                    exitCode = exitCode,
                    outputExcerpt = outputExcerpt,
                    modelId = modelId,
                    backendId = effectiveBackendId,
                    stepIndex = activeStepIndex,
                    checkpointId = checkpointId,
                )
            }

            suspend fun transition(next: TaskState) {
                val prev = stateMachine.currentState
                stateMachine.transitionTo(next).getOrThrow()
                projectRepository.updateTaskState(taskId, next)
                val outcome = when (next) {
                    TaskState.COMPLETED -> FlightRecorderOutcome.PASSED
                    TaskState.FAILED -> FlightRecorderOutcome.FAILED
                    TaskState.CANCELLED -> FlightRecorderOutcome.CANCELLED
                    else -> FlightRecorderOutcome.INFO
                }
                recordFlight(
                    eventType = FlightRecorderEventType.STATE_TRANSITION,
                    outcome = outcome,
                    commandOrPath = "${prev.name} -> ${next.name}",
                )
                persistSnapshot()
                trySend(OrchestratorEvent.StateChanged(next))
            }

            try {
                if (resumeRequest != null) {
                    val savedSnapshot = intelligenceRepository?.getTaskSnapshot(taskId)
                        ?: projectRepository.getTaskById(taskId)?.let { entity ->
                            TaskStateSnapshot(
                                taskId = entity.id,
                                projectId = entity.projectId,
                                objective = entity.prompt,
                                currentState = runCatching { TaskState.valueOf(entity.state) }
                                    .getOrDefault(TaskState.PAUSED),
                                activeStepIndex = 0,
                                planSteps = emptyList(),
                                keyDecisions = emptyList(),
                                modifiedFiles = emptyList(),
                                resumeCursor = TaskResumeCursor(),
                                lastModelId = entity.modelId,
                                lastBackendId = null,
                                updatedAtEpochMs = entity.updatedAtEpochMs,
                            )
                        }
                    if (savedSnapshot == null) {
                        val reason = "No saved task state found to resume task $taskId."
                        val summary = buildFailureSummary(
                            request = syntheticRequest(),
                            finalState = TaskState.FAILED,
                            reason = reason,
                            checkpointId = null,
                        )
                        trySend(OrchestratorEvent.SummaryReady(summary))
                        close()
                        return@launch
                    }
                    if (savedSnapshot.currentState == TaskState.COMPLETED) {
                        val reason = "Task $taskId is already COMPLETED."
                        trySend(
                            OrchestratorEvent.StatusNotice(
                                title = "Already Completed",
                                detail = reason,
                            )
                        )
                        val summary = buildFailureSummary(
                            request = syntheticRequest().copy(prompt = savedSnapshot.objective),
                            finalState = TaskState.COMPLETED,
                            reason = reason,
                            checkpointId = savedSnapshot.resumeCursor.checkpointId,
                            filesChanged = savedSnapshot.modifiedFiles,
                        )
                        trySend(OrchestratorEvent.SummaryReady(summary))
                        close()
                        return@launch
                    }

                    resumedSnapshot = savedSnapshot
                    objective = savedSnapshot.objective
                    preTaskCheckpointId = savedSnapshot.resumeCursor.checkpointId
                    fixAttempts = savedSnapshot.resumeCursor.fixAttempts
                    activeStepIndex = savedSnapshot.activeStepIndex
                    lastError = savedSnapshot.resumeCursor.lastError
                    lastEventSeq = savedSnapshot.resumeCursor.lastEventSeq
                    keyDecisions += savedSnapshot.keyDecisions
                    changedFiles.addAll(savedSnapshot.modifiedFiles)
                    stateMachine = TaskStateMachine(TaskState.PAUSED)

                    projectRepository.updateTaskState(taskId, TaskState.PAUSED)
                    recordFlight(
                        eventType = FlightRecorderEventType.STATE_TRANSITION,
                        outcome = FlightRecorderOutcome.INFO,
                        commandOrPath = "${savedSnapshot.currentState.name} -> RESUME_PAUSED",
                        outputExcerpt = "Resuming with $effectiveBackendId / $modelId from step ${activeStepIndex + 1}",
                    )
                    persistSnapshot()
                    trySend(OrchestratorEvent.StateChanged(TaskState.PAUSED))
                } else {
                    projectRepository.recordSubmittedTask(
                        projectId = project.id,
                        prompt = objective,
                        modelId = modelId,
                        state = TaskState.CREATED,
                        taskId = taskId,
                    )
                    recordFlight(
                        eventType = FlightRecorderEventType.STATE_TRANSITION,
                        outcome = FlightRecorderOutcome.INFO,
                        commandOrPath = "CREATED",
                        outputExcerpt = objective.take(400),
                    )
                    persistSnapshot()
                    trySend(OrchestratorEvent.StateChanged(TaskState.CREATED))
                }

                // 1. Context loading & repository inspection + Aider-style Repo Map
                transition(TaskState.CONTEXT_LOADING)
                val rawInspection = RepositoryInspector.inspect(
                    projectId = project.id,
                    projectRootPath = project.rootPath,
                ).getOrElse { err ->
                    val reason = err.message ?: "Failed to inspect repository."
                    lastError = reason
                    recordFlight(
                        eventType = FlightRecorderEventType.RUNTIME_ERROR,
                        outcome = FlightRecorderOutcome.FAILED,
                        commandOrPath = project.rootPath,
                        outputExcerpt = reason,
                    )
                    transition(TaskState.FAILED)
                    val summary = buildFailureSummary(
                        request = syntheticRequest(),
                        finalState = TaskState.FAILED,
                        reason = reason,
                        checkpointId = preTaskCheckpointId,
                        filesChanged = changedFiles.toList(),
                    )
                    projectRepository.completeTaskWithSummary(taskId, summary)
                    trySend(OrchestratorEvent.SummaryReady(summary))
                    close()
                    return@launch
                }
                val recentCheckpointFiles = runCatching {
                    val chkId = preTaskCheckpointId ?: checkpointManager.observeForProject(project.id).first().firstOrNull()?.id
                    if (chkId != null) {
                        checkpointManager.diff(chkId).getOrNull()?.entries?.map { it.relativePath }.orEmpty()
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())
                val repoMapResult = intelligenceRepository?.getOrBuildRepoMap(
                    inspection = rawInspection,
                    recentlyEditedPaths = (recentCheckpointFiles + changedFiles.map { it.substringAfter(": ") }).distinct(),
                    objectiveHint = objective,
                ) ?: RepoMapEngine().buildFromInspection(
                    inspection = rawInspection,
                    recentlyEditedPaths = (recentCheckpointFiles + changedFiles.map { it.substringAfter(": ") }).distinct(),
                    objectiveHint = objective,
                )
                val inspection = rawInspection.copy(repoMap = repoMapResult.renderedMap)
                intelligenceRepository?.upsertProjectMemoryFromInspection(inspection)
                keyDecisions += "Detected stack ${inspection.stackLabel} (${inspection.validationCommands.size} validation check(s)); RepoMap ${repoMapResult.includedFilesCount}/${repoMapResult.totalFilesIndexed} files (~${repoMapResult.estimatedTokens} tok)"
                recordFlight(
                    eventType = FlightRecorderEventType.PLAN_DECISION,
                    outcome = FlightRecorderOutcome.INFO,
                    commandOrPath = "Inspect ${inspection.stackLabel}",
                    outputExcerpt = "${inspection.directoryMap.size} files indexed; RepoMap ${repoMapResult.includedFilesCount}/${repoMapResult.totalFilesIndexed} (${repoMapResult.totalSymbolsExtracted} symbols, ~${repoMapResult.estimatedTokens} tokens${if (repoMapResult.cacheHit) ", cache hit" else ""}); checks: ${
                        inspection.validationCommands.joinToString(", ") { it.command }.ifBlank { "none" }
                    }",
                )
                persistSnapshot()
                trySend(OrchestratorEvent.InspectionReady(inspection))

                if (resumeRequest == null) {
                    transition(TaskState.ANALYZING)

                    // 2. Pre-task checkpoint when enabled (skipped for Council Only mode)
                    if (prefs.checkpointsEnabled && executionMode != CouncilExecutionMode.COUNCIL_ONLY) {
                        checkpointManager.create(
                            projectId = project.id,
                            reason = "Pre-task: ${objective.take(48)}",
                        ).onSuccess { chk ->
                            preTaskCheckpointId = chk.id
                            keyDecisions += "Pre-task checkpoint ${chk.id.take(12)} (${inspection.directoryMap.size} files)"
                            recordFlight(
                                eventType = FlightRecorderEventType.CHECKPOINT,
                                outcome = FlightRecorderOutcome.PASSED,
                                commandOrPath = chk.reason,
                                outputExcerpt = "${inspection.directoryMap.size} file(s) snapshotted",
                                checkpointId = chk.id,
                            )
                            persistSnapshot()
                            trySend(
                                OrchestratorEvent.CheckpointCreated(
                                    checkpointId = chk.id,
                                    reason = chk.reason,
                                    fileCount = inspection.directoryMap.size,
                                )
                            )
                        }.onFailure { err ->
                            val detail = err.message ?: "Unable to snapshot workspace before task."
                            recordFlight(
                                eventType = FlightRecorderEventType.CHECKPOINT,
                                outcome = FlightRecorderOutcome.FAILED,
                                commandOrPath = "Pre-task checkpoint",
                                outputExcerpt = detail,
                            )
                            trySend(
                                OrchestratorEvent.StatusNotice(
                                    title = "Checkpoint Warning",
                                    detail = detail,
                                    isWarning = true,
                                )
                            )
                        }
                    }

                    // 2b. Phase 9 Interactive Multi-Agent Council & Re-Council Loop
                    if (executionMode != CouncilExecutionMode.DIRECT_CODE && councilGraphEngine != null) {
                        val councilOutcome = CouncilOrchestrationHelper.runInteractiveCouncilLoop(
                            taskId = taskId,
                            project = project,
                            objective = objective,
                            defaultModelId = modelId,
                            protocolMode = protocolMode,
                            executionMode = executionMode,
                            inspection = inspection,
                            request = newRequest!!,
                            councilGraphEngine = councilGraphEngine,
                            councilRepository = councilRepository,
                            intelligenceRepository = intelligenceRepository,
                            awaitDecision = { deferred ->
                                runState.councilDecision = deferred
                                pendingApprovalId = "council-$taskId"
                                pendingApprovalTitle = "Review Council Blueprint"
                                try {
                                    deferred.await()
                                } finally {
                                    runState.councilDecision = null
                                    pendingApprovalId = null
                                    pendingApprovalTitle = null
                                }
                            },
                            transitionState = { next -> transition(next) },
                            recordFlight = { type, out, tool, cmd, excerpt ->
                                recordFlight(
                                    eventType = type,
                                    outcome = out,
                                    toolName = tool,
                                    commandOrPath = cmd,
                                    outputExcerpt = excerpt,
                                )
                            },
                            onKeyDecision = { note -> keyDecisions += note },
                            onSessionCreated = { sid -> runState.councilSessionId = sid },
                            emitEvent = { ev -> trySend(ev) },
                        )

                        when (councilOutcome) {
                            is CouncilLoopOutcome.ProceedToCoding -> {
                                approvedCouncilPlanMarkdown = councilOutcome.approvedBlueprintMarkdown
                            }
                            is CouncilLoopOutcome.CompletedWithoutCoding -> {
                                transition(TaskState.COMPLETED)
                                val summary = buildCompletedSummary(
                                    request = syntheticRequest(),
                                    finalState = TaskState.COMPLETED,
                                    checkpointId = preTaskCheckpointId,
                                    changedFiles = emptyList(),
                                    checks = emptyList(),
                                    fixAttempts = 0,
                                    inspection = inspection,
                                    unresolvedIssues = emptyList(),
                                ).copy(gitStatusNote = councilOutcome.reason)
                                projectRepository.completeTaskWithSummary(taskId, summary)
                                persistSnapshot()
                                trySend(OrchestratorEvent.SummaryReady(summary))
                                close()
                                return@launch
                            }
                            is CouncilLoopOutcome.Cancelled -> {
                                transition(TaskState.CANCELLED)
                                val cancelledSummary = buildFailureSummary(
                                    request = syntheticRequest(),
                                    finalState = TaskState.CANCELLED,
                                    reason = councilOutcome.reason,
                                    checkpointId = preTaskCheckpointId,
                                )
                                projectRepository.completeTaskWithSummary(taskId, cancelledSummary)
                                trySend(OrchestratorEvent.SummaryReady(cancelledSummary))
                                close()
                                return@launch
                            }
                            is CouncilLoopOutcome.Failed -> {
                                lastError = councilOutcome.reason
                                transition(TaskState.FAILED)
                                val failedSummary = buildFailureSummary(
                                    request = syntheticRequest(),
                                    finalState = TaskState.FAILED,
                                    reason = councilOutcome.reason,
                                    checkpointId = preTaskCheckpointId,
                                )
                                projectRepository.completeTaskWithSummary(taskId, failedSummary)
                                trySend(OrchestratorEvent.SummaryReady(failedSummary))
                                close()
                                return@launch
                            }
                        }
                    } else if (!newRequest?.approvedCouncilSessionId.isNullOrBlank()) {
                        val savedCouncilId = newRequest!!.approvedCouncilSessionId!!
                        val savedSession = councilRepository?.getSession(savedCouncilId)
                        if (savedSession != null) {
                            councilRepository.markApprovedForCoding(savedCouncilId)
                            runState.councilSessionId = savedCouncilId
                            approvedCouncilPlanMarkdown = savedSession.latestRound?.toHandoffMarkdown()
                            if (!approvedCouncilPlanMarkdown.isNullOrBlank()) {
                                keyDecisions += "Loaded approved Council blueprint (${savedSession.sessionId}, Round ${savedSession.currentRoundNumber})"
                            }
                        }
                    }
                } else if (preTaskCheckpointId == null && prefs.checkpointsEnabled) {
                    checkpointManager.create(
                        projectId = project.id,
                        reason = "Pre-resume: ${objective.take(46)}",
                    ).onSuccess { chk ->
                        preTaskCheckpointId = chk.id
                        keyDecisions += "Pre-resume checkpoint ${chk.id.take(12)} (${inspection.directoryMap.size} files)"
                        recordFlight(
                            eventType = FlightRecorderEventType.CHECKPOINT,
                            outcome = FlightRecorderOutcome.PASSED,
                            commandOrPath = chk.reason,
                            outputExcerpt = "${inspection.directoryMap.size} file(s) snapshotted",
                            checkpointId = chk.id,
                        )
                        trySend(
                            OrchestratorEvent.CheckpointCreated(
                                checkpointId = chk.id,
                                reason = chk.reason,
                                fileCount = inspection.directoryMap.size,
                            )
                        )
                    }
                }

                // 3. Planning + Acceptance criteria (or Restore plan + ModelHandoffPacket)
                transition(TaskState.PLANNING)
                val baseRequest = syntheticRequest()
                val initialPlan = buildInitialPlan(baseRequest, inspection)
                val plan = if (resumedSnapshot != null && resumedSnapshot.planSteps.isNotEmpty()) {
                    initialPlan.copy(
                        steps = resumedSnapshot.planSteps.map { step ->
                            if (step.status == PlanStepStatus.FAILED || step.status == PlanStepStatus.IN_PROGRESS) {
                                step.copy(status = PlanStepStatus.PENDING)
                            } else {
                                step
                            }
                        }
                    )
                } else {
                    initialPlan
                }
                currentPlan = plan

                var handoffPacket: ModelHandoffPacket? = null
                if (resumedSnapshot != null) {
                    val snapshotForHandoff = resumedSnapshot.copy(
                        planSteps = plan.steps,
                        keyDecisions = keyDecisions.toList(),
                        modifiedFiles = changedFiles.toList(),
                        resumeCursor = resumedSnapshot.resumeCursor.copy(
                            checkpointId = preTaskCheckpointId,
                            fixAttempts = fixAttempts,
                            lastError = lastError,
                            lastEventSeq = lastEventSeq,
                        ),
                    )
                    val packet = effectiveHandoffBuilder.buildPacket(
                        snapshot = snapshotForHandoff,
                        inspection = inspection,
                        targetModelId = modelId,
                        targetBackendId = effectiveBackendId,
                        additionalInstruction = resumeRequest?.additionalInstruction,
                        approvedCouncilPlan = approvedCouncilPlanMarkdown,
                    )
                    handoffPacket = packet
                    keyDecisions += "Model Handoff: ${resumedSnapshot.lastBackendId ?: "unset"}/${resumedSnapshot.lastModelId ?: "unset"} -> $effectiveBackendId/$modelId (step #${activeStepIndex + 1}, ~${packet.estimatedTokens} tok)"
                    recordFlight(
                        eventType = FlightRecorderEventType.PLAN_DECISION,
                        outcome = FlightRecorderOutcome.INFO,
                        commandOrPath = "Handoff -> $effectiveBackendId/$modelId",
                        outputExcerpt = "Packet ~${packet.estimatedTokens} tokens; slices=${packet.targetedCodeSlices.size}; remaining=${packet.remainingSteps.size}",
                    )
                    persistSnapshot()
                    trySend(OrchestratorEvent.HandoffReady(packet))
                    trySend(OrchestratorEvent.PlanReady(plan, requiresApproval = false))
                } else if (approvedCouncilPlanMarkdown != null) {
                    val snapshotForHandoff = TaskStateSnapshot(
                        taskId = taskId,
                        projectId = project.id,
                        objective = objective,
                        currentState = TaskState.PLANNING,
                        activeStepIndex = 0,
                        planSteps = plan.steps,
                        keyDecisions = keyDecisions.toList(),
                        modifiedFiles = changedFiles.toList(),
                        resumeCursor = TaskResumeCursor(
                            checkpointId = preTaskCheckpointId,
                            lastEventSeq = lastEventSeq,
                        ),
                        lastModelId = modelId,
                        lastBackendId = effectiveBackendId,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    val packet = effectiveHandoffBuilder.buildPacket(
                        snapshot = snapshotForHandoff,
                        inspection = inspection,
                        targetModelId = modelId,
                        targetBackendId = effectiveBackendId,
                        approvedCouncilPlan = approvedCouncilPlanMarkdown,
                    )
                    handoffPacket = packet
                    recordFlight(
                        eventType = FlightRecorderEventType.PLAN_DECISION,
                        outcome = FlightRecorderOutcome.INFO,
                        commandOrPath = "Council Handoff -> $effectiveBackendId/$modelId",
                        outputExcerpt = "Approved Council Blueprint (~${packet.estimatedTokens} tokens) handed off to Coder",
                    )
                    persistSnapshot()
                    trySend(OrchestratorEvent.HandoffReady(packet))
                    trySend(OrchestratorEvent.PlanReady(plan, requiresApproval = false))
                } else {
                    recordFlight(
                        eventType = FlightRecorderEventType.PLAN_DECISION,
                        outcome = if (prefs.requirePlanApproval) {
                            FlightRecorderOutcome.REQUIRES_APPROVAL
                        } else {
                            FlightRecorderOutcome.INFO
                        },
                        commandOrPath = "Plan (${plan.steps.size} steps)",
                        outputExcerpt = plan.steps.joinToString("\n") { "${it.index + 1}. ${it.title}" },
                    )
                    persistSnapshot()

                    if (prefs.requirePlanApproval) {
                        val deferred = CompletableDeferred<Boolean>()
                        runState.planApproval = deferred
                        pendingApprovalId = "plan-$taskId"
                        pendingApprovalTitle = "Approve Execution Plan"
                        transition(TaskState.AWAITING_PLAN_APPROVAL)
                        trySend(OrchestratorEvent.PlanReady(plan, requiresApproval = true))
                        val approved = deferred.await()
                        runState.planApproval = null
                        pendingApprovalId = null
                        pendingApprovalTitle = null
                        recordFlight(
                            eventType = FlightRecorderEventType.APPROVAL,
                            outcome = if (approved) FlightRecorderOutcome.ALLOWED else FlightRecorderOutcome.REJECTED,
                            toolName = "plan.approval",
                            commandOrPath = "Execution Plan",
                            outputExcerpt = if (approved) "Plan approved by user" else "Plan declined by user",
                        )
                        if (!approved) {
                            keyDecisions += "Execution plan declined by user"
                            transition(TaskState.CANCELLED)
                            val cancelledSummary = buildFailureSummary(
                                request = baseRequest,
                                finalState = TaskState.CANCELLED,
                                reason = "Execution plan declined by user.",
                                checkpointId = preTaskCheckpointId,
                            )
                            projectRepository.completeTaskWithSummary(taskId, cancelledSummary)
                            trySend(OrchestratorEvent.SummaryReady(cancelledSummary))
                            close()
                            return@launch
                        }
                        keyDecisions += "Execution plan approved by user"
                    } else {
                        trySend(OrchestratorEvent.PlanReady(plan, requiresApproval = false))
                    }
                }

                // 4. Execute task via active/target AgentRuntime (skip step 0 on resume only if already completed cleanly)
                val step0AlreadyCompleted = resumedSnapshot != null &&
                    resumedSnapshot.planSteps.firstOrNull { it.index == 0 }?.status == PlanStepStatus.COMPLETED &&
                    activeStepIndex >= 1 &&
                    lastError.isNullOrBlank() &&
                    resumeRequest?.additionalInstruction.isNullOrBlank()

                if (step0AlreadyCompleted) {
                    keyDecisions += "Step 1 already completed in prior run; resuming directly at validation step"
                    trySend(
                        OrchestratorEvent.StatusNotice(
                            title = "Resuming at Validation",
                            detail = "Implementation step already completed; continuing directly with validation checks.",
                        )
                    )
                } else {
                    activeStepIndex = 0
                    transition(TaskState.EXECUTING)
                    val inProgressPlan = markStep(plan, stepIndex = 0, status = PlanStepStatus.IN_PROGRESS)
                    currentPlan = inProgressPlan
                    persistSnapshot()
                    trySend(OrchestratorEvent.PlanUpdated(inProgressPlan))

                    val recalledKnowledge = if (handoffPacket == null) {
                        intelligenceRepository?.retrieveRelevantKnowledge(
                            projectId = project.id,
                            query = objective,
                            limit = 6,
                            tokenBudget = KnowledgeRetrievalResult.DEFAULT_KNOWLEDGE_TOKEN_BUDGET,
                        )
                    } else null
                    if (recalledKnowledge != null && recalledKnowledge.hits.isNotEmpty()) {
                        keyDecisions += "Recalled ${recalledKnowledge.hits.size} long-term knowledge item(s) (~${recalledKnowledge.estimatedTokens} tok${if (recalledKnowledge.usedFts5) ", FTS5" else ""})"
                    }

                    val promptToSend = handoffPacket?.toPromptContext()
                        ?: buildExecutionPrompt(objective, inspection, inProgressPlan, recalledKnowledge)
                    val execOutcome = runSingleAgentPass(
                        runState = runState,
                        request = baseRequest,
                        promptToSend = promptToSend,
                        stateMachine = stateMachine,
                        onStateSync = { next ->
                            val prev = stateMachine.currentState
                            projectRepository.updateTaskState(taskId, next)
                            recordFlight(
                                eventType = FlightRecorderEventType.STATE_TRANSITION,
                                outcome = FlightRecorderOutcome.INFO,
                                commandOrPath = "${prev.name} -> ${next.name}",
                            )
                            persistSnapshot()
                            trySend(OrchestratorEvent.StateChanged(next))
                        },
                        onRuntimeEvent = { ev ->
                            when (ev) {
                                is AgentEvent.FileChanged -> {
                                    changedFiles += ev.relativePath
                                    intelligenceRepository?.invalidateRepoMap(project.id, listOf(ev.relativePath))
                                    persistSnapshot()
                                }
                                is AgentEvent.ApprovalRequired -> {
                                    pendingApprovalId = ev.approvalId
                                    pendingApprovalTitle = ev.title
                                    recordFlight(
                                        eventType = FlightRecorderEventType.APPROVAL,
                                        outcome = FlightRecorderOutcome.REQUIRES_APPROVAL,
                                        toolName = ev.title,
                                        commandOrPath = ev.reason,
                                        outputExcerpt = "Permission: ${ev.permissionClass.name}",
                                    )
                                    persistSnapshot()
                                }
                                is AgentEvent.ToolCompleted -> {
                                    recordFlight(
                                        eventType = FlightRecorderEventType.TOOL_EXECUTION,
                                        outcome = if (ev.success) FlightRecorderOutcome.PASSED else FlightRecorderOutcome.FAILED,
                                        toolName = ev.toolName,
                                        commandOrPath = ev.toolCallId,
                                        outputExcerpt = ev.outputSummary,
                                    )
                                }
                                is AgentEvent.Failed -> {
                                    if (ev.error !is AgtError.ProcessCancelled) {
                                        lastError = ev.reason
                                        recordFlight(
                                            eventType = FlightRecorderEventType.RUNTIME_ERROR,
                                            outcome = FlightRecorderOutcome.FAILED,
                                            commandOrPath = effectiveBackendId,
                                            outputExcerpt = ev.reason,
                                        )
                                    }
                                }
                                else -> Unit
                            }
                            trySend(OrchestratorEvent.RuntimeEvent(ev))
                        },
                    )

                    pendingApprovalId = null
                    pendingApprovalTitle = null

                    if (execOutcome.cancelled) {
                        val interruptedState = if (runState.pauseRequested) TaskState.PAUSED else TaskState.CANCELLED
                        if (!TaskStateMachine.isTerminal(stateMachine.currentState)) {
                            transition(interruptedState)
                        }
                        val interruptedSummary = buildFailureSummary(
                            request = baseRequest,
                            finalState = interruptedState,
                            reason = if (runState.pauseRequested) "Paused by user for resume/handoff." else "Cancelled by user.",
                            checkpointId = preTaskCheckpointId,
                            filesChanged = changedFiles.toList(),
                        )
                        if (interruptedState == TaskState.CANCELLED) {
                            projectRepository.completeTaskWithSummary(taskId, interruptedSummary)
                        } else {
                            projectRepository.updateTaskState(taskId, TaskState.PAUSED)
                            persistSnapshot()
                        }
                        trySend(OrchestratorEvent.SummaryReady(interruptedSummary))
                        close()
                        return@launch
                    }

                    if (!execOutcome.success) {
                        runtimeFailedReason = execOutcome.failureMessage ?: "Agent execution failed."
                        lastError = runtimeFailedReason
                        unresolvedIssues += runtimeFailedReason
                        val failedPlan = markStep(currentPlan, stepIndex = 0, status = PlanStepStatus.FAILED)
                        currentPlan = failedPlan
                        persistSnapshot()
                        trySend(OrchestratorEvent.PlanUpdated(failedPlan))
                        transition(TaskState.FAILED)
                        val failedSummary = buildCompletedSummary(
                            request = baseRequest,
                            finalState = TaskState.FAILED,
                            checkpointId = preTaskCheckpointId,
                            changedFiles = collectFinalChangedFiles(preTaskCheckpointId, changedFiles),
                            checks = emptyList(),
                            fixAttempts = fixAttempts,
                            inspection = inspection,
                            unresolvedIssues = unresolvedIssues,
                        )
                        projectRepository.completeTaskWithSummary(taskId, failedSummary)
                        trySend(OrchestratorEvent.SummaryReady(failedSummary))
                        close()
                        return@launch
                    }

                    val completedExecPlan = markStep(currentPlan, stepIndex = 0, status = PlanStepStatus.COMPLETED)
                    currentPlan = completedExecPlan
                    persistSnapshot()
                    trySend(OrchestratorEvent.PlanUpdated(completedExecPlan))
                }

                // 5. Validation + Bounded Auto-Debug Fix Loop
                val effectiveMaxFixes = maxFixRetries.coerceAtMost((prefs.agentStepLimit / 3).coerceAtLeast(1))
                var sessionFixAttempts = 0
                activeStepIndex = 1
                transition(TaskState.VALIDATING)
                val validatingPlan = markStep(currentPlan, stepIndex = 1, status = PlanStepStatus.IN_PROGRESS)
                currentPlan = validatingPlan
                persistSnapshot()
                trySend(OrchestratorEvent.PlanUpdated(validatingPlan))

                suspend fun runAndRecordValidations(): List<ValidationCheckResult> {
                    return validationRunner.runChecks(
                        workingDirectory = project.rootPath,
                        commands = inspection.validationCommands,
                        timeoutSeconds = prefs.commandTimeoutSeconds,
                        onStarted = { cmd -> trySend(OrchestratorEvent.ValidationStarted(cmd)) },
                        onFinished = { res ->
                            val frOutcome = when (res.status) {
                                ValidationOutcome.PASSED -> FlightRecorderOutcome.PASSED
                                ValidationOutcome.FAILED -> FlightRecorderOutcome.FAILED
                                ValidationOutcome.TIMED_OUT -> FlightRecorderOutcome.TIMED_OUT
                                ValidationOutcome.CANCELLED -> FlightRecorderOutcome.CANCELLED
                                ValidationOutcome.SKIPPED -> FlightRecorderOutcome.SKIPPED
                            }
                            if (res.status == ValidationOutcome.FAILED || res.status == ValidationOutcome.TIMED_OUT) {
                                lastError = "${res.label} (${res.command}) failed (exit=${res.exitCode ?: "?"})"
                            }
                            recordFlight(
                                eventType = FlightRecorderEventType.VALIDATION_RUN,
                                outcome = frOutcome,
                                toolName = res.label,
                                commandOrPath = res.command,
                                exitCode = res.exitCode,
                                outputExcerpt = buildString {
                                    append("${res.durationMs}ms")
                                    if (res.outputExcerpt.isNotBlank()) {
                                        append('\n')
                                        append(res.outputExcerpt)
                                    }
                                },
                            )
                            persistSnapshot()
                            trySend(OrchestratorEvent.ValidationCompleted(res))
                        },
                    )
                }

                if (inspection.validationCommands.isEmpty()) {
                    recordFlight(
                        eventType = FlightRecorderEventType.VALIDATION_RUN,
                        outcome = FlightRecorderOutcome.SKIPPED,
                        toolName = "Validation",
                        commandOrPath = inspection.stackLabel,
                        outputExcerpt = "No automated build/test scripts detected for ${inspection.stackLabel}.",
                    )
                    trySend(
                        OrchestratorEvent.StatusNotice(
                            title = "Validation Checks",
                            detail = "No automated build/test scripts detected for ${inspection.stackLabel}.",
                        )
                    )
                } else {
                    latestChecks = runAndRecordValidations()

                    while (hasFailingChecks(latestChecks) &&
                        prefs.autoDebugEnabled &&
                        sessionFixAttempts < effectiveMaxFixes
                    ) {
                        sessionFixAttempts++
                        fixAttempts++
                        val failing = latestChecks.filter {
                            it.status == ValidationOutcome.FAILED || it.status == ValidationOutcome.TIMED_OUT
                        }
                        val failingLabels = failing.joinToString(", ") { it.label }
                        lastFailingLabels = failingLabels
                        keyDecisions += "Auto-debug pass #$fixAttempts triggered for: $failingLabels"
                        transition(TaskState.FIXING)
                        recordFlight(
                            eventType = FlightRecorderEventType.FIX_LOOP,
                            outcome = FlightRecorderOutcome.INFO,
                            commandOrPath = "Auto-debug attempt $sessionFixAttempts/$effectiveMaxFixes (total #$fixAttempts)",
                            outputExcerpt = "Failing checks: $failingLabels",
                        )
                        trySend(
                            OrchestratorEvent.FixLoopStarted(
                                attempt = sessionFixAttempts,
                                maxAttempts = effectiveMaxFixes,
                                failingLabels = failingLabels,
                            )
                        )

                        val fixPrompt = buildAutoDebugPrompt(
                            originalObjective = objective,
                            attempt = sessionFixAttempts,
                            maxAttempts = effectiveMaxFixes,
                            failingChecks = failing,
                        )
                        val fixOutcome = runSingleAgentPass(
                            runState = runState,
                            request = baseRequest,
                            promptToSend = fixPrompt,
                            stateMachine = stateMachine,
                            onStateSync = { next ->
                                val prev = stateMachine.currentState
                                projectRepository.updateTaskState(taskId, next)
                                recordFlight(
                                    eventType = FlightRecorderEventType.STATE_TRANSITION,
                                    outcome = FlightRecorderOutcome.INFO,
                                    commandOrPath = "${prev.name} -> ${next.name}",
                                )
                                persistSnapshot()
                                trySend(OrchestratorEvent.StateChanged(next))
                            },
                            onRuntimeEvent = { ev ->
                                when (ev) {
                                    is AgentEvent.FileChanged -> {
                                        changedFiles += ev.relativePath
                                        intelligenceRepository?.invalidateRepoMap(project.id, listOf(ev.relativePath))
                                        persistSnapshot()
                                    }
                                    is AgentEvent.ApprovalRequired -> {
                                        pendingApprovalId = ev.approvalId
                                        pendingApprovalTitle = ev.title
                                        recordFlight(
                                            eventType = FlightRecorderEventType.APPROVAL,
                                            outcome = FlightRecorderOutcome.REQUIRES_APPROVAL,
                                            toolName = ev.title,
                                            commandOrPath = ev.reason,
                                            outputExcerpt = "Permission: ${ev.permissionClass.name}",
                                        )
                                        persistSnapshot()
                                    }
                                    is AgentEvent.ToolCompleted -> {
                                        recordFlight(
                                            eventType = FlightRecorderEventType.TOOL_EXECUTION,
                                            outcome = if (ev.success) FlightRecorderOutcome.PASSED else FlightRecorderOutcome.FAILED,
                                            toolName = ev.toolName,
                                            commandOrPath = ev.toolCallId,
                                            outputExcerpt = ev.outputSummary,
                                        )
                                    }
                                    is AgentEvent.Failed -> {
                                        if (ev.error !is AgtError.ProcessCancelled) {
                                            lastError = ev.reason
                                            recordFlight(
                                                eventType = FlightRecorderEventType.RUNTIME_ERROR,
                                                outcome = FlightRecorderOutcome.FAILED,
                                                commandOrPath = effectiveBackendId,
                                                outputExcerpt = ev.reason,
                                            )
                                        }
                                    }
                                    else -> Unit
                                }
                                trySend(OrchestratorEvent.RuntimeEvent(ev))
                            },
                        )

                        pendingApprovalId = null
                        pendingApprovalTitle = null

                        if (fixOutcome.cancelled) {
                            val interruptedState = if (runState.pauseRequested) TaskState.PAUSED else TaskState.CANCELLED
                            transition(interruptedState)
                            val interruptedSummary = buildFailureSummary(
                                request = baseRequest,
                                finalState = interruptedState,
                                reason = if (runState.pauseRequested) "Paused during auto-debug fix loop." else "Cancelled during auto-debug fix loop.",
                                checkpointId = preTaskCheckpointId,
                                filesChanged = collectFinalChangedFiles(preTaskCheckpointId, changedFiles),
                            )
                            if (interruptedState == TaskState.CANCELLED) {
                                projectRepository.completeTaskWithSummary(taskId, interruptedSummary)
                            } else {
                                projectRepository.updateTaskState(taskId, TaskState.PAUSED)
                                persistSnapshot()
                            }
                            trySend(OrchestratorEvent.SummaryReady(interruptedSummary))
                            close()
                            return@launch
                        }

                        if (!fixOutcome.success) {
                            val errMsg = "Auto-debug pass #$fixAttempts failed: ${fixOutcome.failureMessage ?: "error"}"
                            lastError = errMsg
                            unresolvedIssues += errMsg
                            recordFlight(
                                eventType = FlightRecorderEventType.RUNTIME_ERROR,
                                outcome = FlightRecorderOutcome.FAILED,
                                commandOrPath = "Auto-debug pass #$fixAttempts",
                                outputExcerpt = errMsg,
                            )
                            break
                        }

                        transition(TaskState.VALIDATING)
                        latestChecks = runAndRecordValidations()
                    }
                }

                val stillFailing = hasFailingChecks(latestChecks)
                if (stillFailing) {
                    latestChecks.filter {
                        it.status == ValidationOutcome.FAILED || it.status == ValidationOutcome.TIMED_OUT
                    }.forEach { chk ->
                        unresolvedIssues += "${chk.label} (${chk.command}) exited with ${chk.exitCode ?: chk.status.label}"
                    }
                } else {
                    lastError = null
                    lastFailingLabels?.let { failingSignature ->
                        if (fixAttempts > 0) {
                            intelligenceRepository?.recordKnownFix(
                                projectId = project.id,
                                errorSignature = failingSignature,
                                resolutionNote = "Resolved in auto-debug pass #$fixAttempts ($effectiveBackendId/$modelId) for: ${objective.take(100)}",
                                stackLabel = inspection.stackLabel,
                            )
                        }
                    }
                }

                val step1DonePlan = markStep(
                    currentPlan,
                    stepIndex = 1,
                    status = if (stillFailing) PlanStepStatus.FAILED else PlanStepStatus.COMPLETED,
                )
                activeStepIndex = 2
                val finalPlan = markStep(
                    step1DonePlan,
                    stepIndex = 2,
                    status = if (stillFailing) PlanStepStatus.FAILED else PlanStepStatus.COMPLETED,
                )
                currentPlan = finalPlan
                trySend(OrchestratorEvent.PlanUpdated(finalPlan))

                val finalState = if (stillFailing) TaskState.FAILED else TaskState.COMPLETED
                transition(finalState)

                val allChangedFiles = collectFinalChangedFiles(preTaskCheckpointId, changedFiles)
                changedFiles.addAll(allChangedFiles)
                if (!stillFailing) {
                    intelligenceRepository?.indexFlightRecorderResolutionsForTask(
                        projectId = project.id,
                        taskId = taskId,
                        stackLabel = inspection.stackLabel,
                        modelId = modelId,
                        backendId = effectiveBackendId,
                        finalChecks = latestChecks,
                        fixAttempts = fixAttempts,
                    )
                    intelligenceRepository?.recordTaskCompletionKnowledge(
                        projectId = project.id,
                        taskId = taskId,
                        objective = objective,
                        keyDecisions = keyDecisions,
                        changedFiles = allChangedFiles,
                        stackLabel = inspection.stackLabel,
                        modelId = modelId,
                        backendId = effectiveBackendId,
                    )
                }
                if (changedFiles.isNotEmpty()) {
                    val cleanChangedPaths = changedFiles.map { it.substringAfter(": ") }
                    intelligenceRepository?.invalidateRepoMap(project.id, cleanChangedPaths)
                    intelligenceRepository?.rebuildRepoMap(
                        projectId = project.id,
                        projectRootPath = project.rootPath,
                        recentlyEditedPaths = cleanChangedPaths,
                        objectiveHint = objective,
                    )
                }
                persistSnapshot()

                val summary = buildCompletedSummary(
                    request = baseRequest,
                    finalState = finalState,
                    checkpointId = preTaskCheckpointId,
                    changedFiles = allChangedFiles,
                    checks = latestChecks,
                    fixAttempts = fixAttempts,
                    inspection = inspection,
                    unresolvedIssues = unresolvedIssues,
                )
                projectRepository.completeTaskWithSummary(taskId, summary)
                trySend(OrchestratorEvent.SummaryReady(summary))
                close()
            } catch (ce: CancellationException) {
                runCatching {
                    val targetState = if (runState.pauseRequested) TaskState.PAUSED else TaskState.CANCELLED
                    if (!TaskStateMachine.isTerminal(stateMachine.currentState) && stateMachine.currentState != targetState) {
                        projectRepository.updateTaskState(taskId, targetState)
                        intelligenceRepository?.recordFlightEvent(
                            projectId = project.id,
                            taskId = taskId,
                            eventType = FlightRecorderEventType.STATE_TRANSITION,
                            outcome = if (runState.pauseRequested) FlightRecorderOutcome.INFO else FlightRecorderOutcome.CANCELLED,
                            commandOrPath = "${stateMachine.currentState.name} -> ${targetState.name}",
                            modelId = modelId,
                            backendId = effectiveBackendId,
                            stepIndex = activeStepIndex,
                            checkpointId = preTaskCheckpointId,
                        )
                        intelligenceRepository?.saveTaskSnapshot(
                            TaskStateSnapshot(
                                taskId = taskId,
                                projectId = project.id,
                                objective = objective,
                                currentState = targetState,
                                activeStepIndex = activeStepIndex,
                                planSteps = currentPlan?.steps.orEmpty(),
                                keyDecisions = keyDecisions.toList(),
                                modifiedFiles = changedFiles.toList(),
                                resumeCursor = TaskResumeCursor(
                                    checkpointId = preTaskCheckpointId,
                                    fixAttempts = fixAttempts,
                                    lastError = lastError,
                                    lastEventSeq = lastEventSeq,
                                    pendingApprovalId = null,
                                    pendingApprovalTitle = null,
                                ),
                                lastModelId = modelId,
                                lastBackendId = effectiveBackendId,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                throw ce
            } catch (t: Throwable) {
                val reason = t.message ?: "Orchestrator encountered an unexpected failure."
                lastError = reason
                runCatching {
                    recordFlight(
                        eventType = FlightRecorderEventType.RUNTIME_ERROR,
                        outcome = FlightRecorderOutcome.FAILED,
                        commandOrPath = stateMachine.currentState.name,
                        outputExcerpt = reason,
                    )
                    if (!TaskStateMachine.isTerminal(stateMachine.currentState)) {
                        transition(TaskState.FAILED)
                    }
                }
                val summary = buildFailureSummary(
                    request = syntheticRequest(),
                    finalState = TaskState.FAILED,
                    reason = reason,
                    checkpointId = preTaskCheckpointId,
                    filesChanged = changedFiles.toList(),
                )
                runCatching { projectRepository.completeTaskWithSummary(taskId, summary) }
                trySend(OrchestratorEvent.SummaryReady(summary))
                close()
            } finally {
                val active = activeRuns.remove(taskId)
                active?.activeSession?.let { sess ->
                    CoroutineScope(Dispatchers.IO).launch { agentRuntime.stop(sess) }
                }
            }
        }

        awaitClose {
            worker.cancel()
            val active = activeRuns.remove(taskId)
            active?.planApproval?.complete(false)
            active?.councilDecision?.complete(CouncilUserDecision.Cancel)
            active?.activeSession?.let { sess ->
                CoroutineScope(Dispatchers.IO).launch { agentRuntime.stop(sess) }
            }
        }
    }.flowOn(Dispatchers.IO)

    fun respondToPlanApproval(taskId: String, approved: Boolean): Boolean {
        val run = activeRuns[taskId] ?: return false
        return run.planApproval?.complete(approved) == true
    }

    fun submitCouncilDecision(taskId: String, decision: CouncilUserDecision): Boolean {
        val run = activeRuns[taskId] ?: return false
        return run.councilDecision?.complete(decision) == true
    }

    suspend fun editCouncilSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
        newContent: String,
    ): Result<CouncilSession> {
        val repo = councilRepository
            ?: return Result.failure(IllegalStateException("CouncilRepository is not configured."))
        return repo.editSection(sessionId, roundNumber, sectionId, newContent)
    }

    suspend fun revertCouncilSection(
        sessionId: String,
        roundNumber: Int,
        sectionId: CouncilSectionId,
    ): Result<CouncilSession> {
        val repo = councilRepository
            ?: return Result.failure(IllegalStateException("CouncilRepository is not configured."))
        return repo.revertSection(sessionId, roundNumber, sectionId)
    }

    suspend fun respondToToolApproval(taskId: String, approvalId: String, approved: Boolean): Result<Unit> {
        val run = activeRuns[taskId]
        val session = run?.activeSession
            ?: return Result.failure(IllegalStateException("No active agent session for task $taskId"))
        intelligenceRepository?.recordFlightEvent(
            projectId = run.projectId,
            taskId = taskId,
            eventType = FlightRecorderEventType.APPROVAL,
            outcome = if (approved) FlightRecorderOutcome.ALLOWED else FlightRecorderOutcome.REJECTED,
            toolName = "tool.approval",
            commandOrPath = approvalId,
            outputExcerpt = if (approved) "Tool action approved by user" else "Tool action denied by user",
            modelId = run.modelId,
            backendId = run.backendId,
        )
        return agentRuntime.respondToApproval(session, approvalId, approved)
    }

    suspend fun pauseTask(taskId: String) = stopTask(taskId, pauseOnly = true)

    suspend fun stopTask(taskId: String, pauseOnly: Boolean = false) {
        val run = activeRuns[taskId]
        if (run != null) {
            run.pauseRequested = pauseOnly
            run.planApproval?.complete(false)
            run.councilDecision?.complete(CouncilUserDecision.Cancel)
            run.activeSession?.let { agentRuntime.stop(it) }
        }
        if (!pauseOnly) {
            activeRuns.remove(taskId)
        }
        val targetState = if (pauseOnly) TaskState.PAUSED else TaskState.CANCELLED
        if (run != null) {
            intelligenceRepository?.recordFlightEvent(
                projectId = run.projectId,
                taskId = taskId,
                eventType = FlightRecorderEventType.STATE_TRANSITION,
                outcome = if (pauseOnly) FlightRecorderOutcome.INFO else FlightRecorderOutcome.CANCELLED,
                commandOrPath = if (pauseOnly) "PAUSED_BY_USER" else "STOPPED_BY_USER",
                modelId = run.modelId,
                backendId = run.backendId,
            )
            val existingSnap = intelligenceRepository?.getTaskSnapshot(taskId)
            if (existingSnap != null && !TaskStateMachine.isTerminal(existingSnap.currentState)) {
                intelligenceRepository.saveTaskSnapshot(
                    existingSnap.copy(
                        currentState = targetState,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        }
        projectRepository.updateTaskState(taskId, targetState)
    }

    private data class AgentPassOutcome(
        val success: Boolean,
        val cancelled: Boolean = false,
        val failureMessage: String? = null,
    )

    private suspend fun runSingleAgentPass(
        runState: ActiveRun,
        request: OrchestratorTaskRequest,
        promptToSend: String,
        stateMachine: TaskStateMachine,
        onStateSync: suspend (TaskState) -> Unit,
        onRuntimeEvent: suspend (AgentEvent) -> Unit,
    ): AgentPassOutcome {
        val session = if (agentRuntime is DelegatingAgentRuntime) {
            agentRuntime.startSessionForBackend(request.project, runState.backendId)
        } else {
            agentRuntime.startSession(request.project)
        }.getOrElse { err ->
            return AgentPassOutcome(success = false, failureMessage = err.message ?: "Unable to start agent session.")
        }
        runState.activeSession = session
        var passSucceeded = false
        var passCancelled = false
        var failureMsg: String? = null

        try {
            agentRuntime.submitTask(
                session = session,
                task = AgentTask(
                    taskId = request.taskId,
                    prompt = promptToSend,
                    modelId = request.modelId,
                    protocolMode = request.protocolMode,
                    attachments = request.attachments,
                    requiresToolCalling = true,
                ),
            ).collect { ev ->
                when (ev) {
                    is AgentEvent.StateChanged -> {
                        // Keep orchestrator state machine authoritative; only sync EXECUTING <-> AWAITING_TOOL_APPROVAL
                        if (ev.state == TaskState.AWAITING_TOOL_APPROVAL || ev.state == TaskState.EXECUTING) {
                            if (stateMachine.canTransitionTo(ev.state)) {
                                stateMachine.transitionTo(ev.state)
                                onStateSync(ev.state)
                            }
                        }
                    }
                    is AgentEvent.Completed -> {
                        passSucceeded = true
                        onRuntimeEvent(ev)
                    }
                    is AgentEvent.Failed -> {
                        if (ev.error is AgtError.ProcessCancelled) {
                            passCancelled = true
                        } else {
                            failureMsg = ev.reason
                        }
                        onRuntimeEvent(ev)
                    }
                    else -> onRuntimeEvent(ev)
                }
            }
        } finally {
            runState.activeSession = null
            agentRuntime.stop(session)
        }
        return AgentPassOutcome(
            success = passSucceeded && failureMsg == null && !passCancelled,
            cancelled = passCancelled,
            failureMessage = failureMsg,
        )
    }

    private fun buildInitialPlan(
        request: OrchestratorTaskRequest,
        inspection: ProjectInspection,
    ): TaskPlan {
        val checksDesc = if (inspection.validationCommands.isNotEmpty()) {
            inspection.validationCommands.joinToString(", ") { it.label }
        } else {
            "Workspace integrity check"
        }
        return TaskPlan(
            taskId = request.taskId,
            objective = request.prompt,
            steps = listOf(
                PlanStep(0, "Implement changes for objective in ${inspection.stackLabel} workspace"),
                PlanStep(1, "Run validation checks ($checksDesc) and fix failures if needed"),
                PlanStep(2, "Verify workspace diff and generate task completion summary"),
            ),
            acceptanceCriteria = listOf(
                "Changes remain confined within ${request.project.name} workspace root.",
                if (inspection.validationCommands.isNotEmpty()) {
                    "Detected checks pass: ${inspection.validationCommands.joinToString("; ") { it.command }}"
                } else {
                    "Files written cleanly without syntax or path-policy errors."
                },
            ),
            validationChecks = inspection.validationCommands.map { "${it.label}: ${it.command}" },
        )
    }

    private fun buildExecutionPrompt(
        objective: String,
        inspection: ProjectInspection,
        plan: TaskPlan,
        recalledKnowledge: KnowledgeRetrievalResult? = null,
    ): String = buildString {
        appendLine("Objective:")
        appendLine(objective)
        appendLine()
        appendLine("Repository Context:")
        appendLine(inspection.toPromptContext())
        val knowledgeText = recalledKnowledge?.toPromptContext().orEmpty()
        if (knowledgeText.isNotBlank()) {
            appendLine()
            appendLine(knowledgeText)
        }
        appendLine()
        appendLine("Acceptance Criteria:")
        plan.acceptanceCriteria.forEach { appendLine("- $it") }
    }.trim()

    private fun buildAutoDebugPrompt(
        originalObjective: String,
        attempt: Int,
        maxAttempts: Int,
        failingChecks: List<ValidationCheckResult>,
    ): String = buildString {
        appendLine("Validation failed after changes for objective: $originalObjective")
        appendLine("Auto-debug fix attempt $attempt of $maxAttempts.")
        appendLine("Fix the underlying issue so the failing checks pass:")
        failingChecks.forEach { chk ->
            appendLine()
            appendLine("Command: ${chk.command}")
            appendLine("Status: ${chk.status.label} (exitCode=${chk.exitCode ?: "?"})")
            if (chk.outputExcerpt.isNotBlank()) {
                appendLine("Output:")
                appendLine(chk.outputExcerpt)
            }
        }
    }.trim()

    private fun markStep(plan: TaskPlan, stepIndex: Int, status: PlanStepStatus): TaskPlan =
        plan.copy(
            steps = plan.steps.map { step ->
                if (step.index == stepIndex) step.copy(status = status) else step
            }
        )

    private fun hasFailingChecks(checks: List<ValidationCheckResult>): Boolean =
        checks.any { it.status == ValidationOutcome.FAILED || it.status == ValidationOutcome.TIMED_OUT }

    private suspend fun collectFinalChangedFiles(
        checkpointId: String?,
        eventChangedFiles: Set<String>,
    ): List<String> {
        val diffFiles = checkpointId?.let { id ->
            checkpointManager.diff(id).getOrNull()?.entries?.map { "${it.changeType.label}: ${it.relativePath}" }
        }.orEmpty()
        return if (diffFiles.isNotEmpty()) {
            diffFiles
        } else {
            eventChangedFiles.toList()
        }
    }

    private fun buildCompletedSummary(
        request: OrchestratorTaskRequest,
        finalState: TaskState,
        checkpointId: String?,
        changedFiles: List<String>,
        checks: List<ValidationCheckResult>,
        fixAttempts: Int,
        inspection: ProjectInspection,
        unresolvedIssues: List<String>,
    ): TaskExecutionSummary {
        val gitNote = if (inspection.isGitRepo) {
            "Git HEAD ${inspection.gitHead ?: "unknown"} (changes uncommitted locally)"
        } else {
            "Local workspace (no Git repository initialized)"
        }
        return TaskExecutionSummary(
            taskId = request.taskId,
            objective = request.prompt,
            finalState = finalState,
            filesChanged = changedFiles,
            checksExecuted = checks,
            fixAttempts = fixAttempts,
            checkpointId = checkpointId,
            gitStatusNote = gitNote,
            unresolvedIssues = unresolvedIssues,
        )
    }

    private fun buildFailureSummary(
        request: OrchestratorTaskRequest,
        finalState: TaskState,
        reason: String,
        checkpointId: String?,
        filesChanged: List<String> = emptyList(),
    ): TaskExecutionSummary = TaskExecutionSummary(
        taskId = request.taskId,
        objective = request.prompt,
        finalState = finalState,
        filesChanged = filesChanged,
        checksExecuted = emptyList(),
        fixAttempts = 0,
        checkpointId = checkpointId,
        gitStatusNote = "Local workspace (not committed)",
        unresolvedIssues = listOf(reason),
    )
}
