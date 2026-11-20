package com.example.agent.orchestrator

import com.example.data.council.CouncilRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilRoundDiff
import com.example.domain.model.CouncilSession
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.KnowledgeCategory
import com.example.domain.model.ProjectInspection
import com.example.domain.model.TaskState
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

internal sealed interface CouncilLoopOutcome {
    data class ProceedToCoding(
        val session: CouncilSession,
        val approvedBlueprintMarkdown: String,
    ) : CouncilLoopOutcome

    data class CompletedWithoutCoding(
        val session: CouncilSession,
        val reason: String,
    ) : CouncilLoopOutcome

    data class Cancelled(val reason: String) : CouncilLoopOutcome
    data class Failed(val reason: String) : CouncilLoopOutcome
}

internal object CouncilOrchestrationHelper {

    suspend fun runInteractiveCouncilLoop(
        taskId: String,
        project: ProjectRef,
        objective: String,
        defaultModelId: String,
        protocolMode: GatewayProtocolMode,
        executionMode: CouncilExecutionMode,
        inspection: ProjectInspection,
        request: OrchestratorTaskRequest,
        councilGraphEngine: CouncilGraphEngine,
        councilRepository: CouncilRepository?,
        intelligenceRepository: DevelopmentIntelligenceRepository?,
        awaitDecision: suspend (CompletableDeferred<CouncilUserDecision>) -> CouncilUserDecision,
        transitionState: suspend (TaskState) -> Unit,
        recordFlight: suspend (
            eventType: FlightRecorderEventType,
            outcome: FlightRecorderOutcome,
            toolName: String?,
            commandOrPath: String?,
            outputExcerpt: String,
        ) -> Unit,
        onKeyDecision: (String) -> Unit,
        onSessionCreated: (String) -> Unit,
        emitEvent: suspend (OrchestratorEvent) -> Unit,
    ): CouncilLoopOutcome {
        val sessionId = "council-${UUID.randomUUID().toString().take(12)}"
        onSessionCreated(sessionId)

        var session = CouncilSession(
            sessionId = sessionId,
            projectId = project.id,
            taskId = taskId,
            objective = objective,
            mode = executionMode,
            currentRoundNumber = 1,
        )
        councilRepository?.saveSession(session)

        var roundNumber = 1
        var userAmendmentNote: String? = null
        var refreshSearch = false

        while (true) {
            val previousRound = session.roundByNumber(roundNumber - 1)
            val initialResearchState = if (roundNumber == 1 || refreshSearch) {
                TaskState.RESEARCHING
            } else {
                TaskState.COUNCIL_DELIBERATING
            }
            transitionState(initialResearchState)

            var completedRound: CouncilRound? = null
            var failureReason: String? = null

            councilGraphEngine.runCouncilRound(
                CouncilGraphRequest(
                    project = project,
                    objective = objective,
                    defaultModelId = defaultModelId,
                    protocolMode = protocolMode,
                    roleModelOverrides = request.roleModelOverrides,
                    enabledSpecialists = request.enabledCouncilRoles,
                    inspection = inspection,
                    roundNumber = roundNumber,
                    priorDossier = previousRound?.dossier,
                    priorEditedSections = previousRound?.sections.orEmpty(),
                    userAmendmentNote = userAmendmentNote,
                    refreshSearch = refreshSearch,
                    maxTokenBudget = request.maxCouncilTokenBudget,
                )
            ).collect { progress ->
                emitEvent(
                    OrchestratorEvent.CouncilProgress(
                        taskId = taskId,
                        sessionId = sessionId,
                        roundNumber = roundNumber,
                        event = progress,
                    )
                )
                when (progress) {
                    is CouncilProgressEvent.StageStarted -> {
                        when (progress.stage) {
                            "RESEARCHING" -> transitionState(TaskState.RESEARCHING)
                            "COUNCIL_DELIBERATING" -> transitionState(TaskState.COUNCIL_DELIBERATING)
                            "SYNTHESIZING" -> transitionState(TaskState.SYNTHESIZING)
                        }
                    }
                    is CouncilProgressEvent.ResearchDossierReady -> {
                        val d = progress.dossier
                        recordFlight(
                            FlightRecorderEventType.COUNCIL_RESEARCH,
                            if (d.hasAnyFindings) FlightRecorderOutcome.PASSED else FlightRecorderOutcome.SKIPPED,
                            "research.dossier",
                            "Round $roundNumber research",
                            "Web: ${d.webFindings.size} · GitHub: ${d.githubFindings.size}" +
                                if (d.notices.isNotEmpty()) "\n${d.notices.joinToString("; ")}" else "",
                        )
                    }
                    is CouncilProgressEvent.SpecialistCompleted -> {
                        val op = progress.opinion
                        recordFlight(
                            FlightRecorderEventType.COUNCIL_OPINION,
                            if (op.status == CouncilAgentStatus.COMPLETED) FlightRecorderOutcome.PASSED else FlightRecorderOutcome.FAILED,
                            op.role.displayName,
                            "${op.modelId} (Round $roundNumber)",
                            op.headline.ifBlank { op.errorMessage.orEmpty() },
                        )
                    }
                    is CouncilProgressEvent.RoundCompleted -> {
                        completedRound = progress.round
                        val overBudget = progress.round.exceedsBudget(request.maxCouncilTokenBudget)
                        recordFlight(
                            FlightRecorderEventType.COUNCIL_SYNTHESIS,
                            if (overBudget) FlightRecorderOutcome.SKIPPED else FlightRecorderOutcome.PASSED,
                            "synthesizer",
                            "Round $roundNumber · ${progress.round.verdict.name}",
                            "Sections=${progress.round.sections.size}; tokens=${progress.round.totalTokens}/${request.maxCouncilTokenBudget}; ${progress.round.durationMs}ms" +
                                if (overBudget) " (budget guardrail exceeded)" else "",
                        )
                    }
                    is CouncilProgressEvent.Failed -> {
                        failureReason = progress.reason
                        recordFlight(
                            FlightRecorderEventType.RUNTIME_ERROR,
                            FlightRecorderOutcome.FAILED,
                            "council",
                            "Round $roundNumber",
                            progress.reason,
                        )
                    }
                    else -> Unit
                }
            }

            val round = completedRound
            if (round == null) {
                return CouncilLoopOutcome.Failed(failureReason ?: "Council synthesis failed without output.")
            }

            session = councilRepository?.saveRound(sessionId, round) ?: session.replaceRound(round)
            val diff: CouncilRoundDiff? = previousRound?.let { round.diffSummaryAgainst(it) }

            onKeyDecision(
                "Council Round $roundNumber synthesized (${round.verdict.label}; " +
                    "${round.opinions.count { it.status == CouncilAgentStatus.COMPLETED }}/${round.opinions.size} specialists)"
            )

            transitionState(TaskState.AWAITING_COUNCIL_APPROVAL)
            emitEvent(
                OrchestratorEvent.CouncilSessionUpdated(
                    session = session,
                    diffFromPrevious = diff,
                    requiresDecision = true,
                )
            )

            val deferred = CompletableDeferred<CouncilUserDecision>()
            val decision = awaitDecision(deferred)

            // Reload freshest session so inline edits performed while waiting are included
            session = councilRepository?.getSession(sessionId) ?: session
            val activeRound = session.roundByNumber(roundNumber) ?: round

            when (decision) {
                is CouncilUserDecision.Cancel -> {
                    recordFlight(
                        FlightRecorderEventType.APPROVAL,
                        FlightRecorderOutcome.REJECTED,
                        "council.approval",
                        "Round $roundNumber",
                        "Council plan cancelled by user.",
                    )
                    return CouncilLoopOutcome.Cancelled("Council session cancelled by user.")
                }

                is CouncilUserDecision.SaveCouncilOnly -> {
                    recordKnowledgeBlueprint(
                        intelligenceRepository = intelligenceRepository,
                        projectId = project.id,
                        taskId = taskId,
                        objective = objective,
                        round = activeRound,
                        stackLabel = inspection.stackLabel,
                    )
                    recordFlight(
                        FlightRecorderEventType.APPROVAL,
                        FlightRecorderOutcome.ALLOWED,
                        "council.save_only",
                        "Round $roundNumber",
                        "Council blueprint saved without modifying workspace files.",
                    )
                    emitEvent(
                        OrchestratorEvent.CouncilSessionUpdated(
                            session = session,
                            diffFromPrevious = diff,
                            requiresDecision = false,
                        )
                    )
                    return CouncilLoopOutcome.CompletedWithoutCoding(
                        session = session,
                        reason = "Council Round $roundNumber blueprint saved (Council Only).",
                    )
                }

                is CouncilUserDecision.ApproveForCoding -> {
                    if (executionMode == CouncilExecutionMode.COUNCIL_ONLY) {
                        recordKnowledgeBlueprint(
                            intelligenceRepository = intelligenceRepository,
                            projectId = project.id,
                            taskId = taskId,
                            objective = objective,
                            round = activeRound,
                            stackLabel = inspection.stackLabel,
                        )
                        recordFlight(
                            FlightRecorderEventType.APPROVAL,
                            FlightRecorderOutcome.ALLOWED,
                            "council.save_only",
                            "Round $roundNumber",
                            "Council Only session completed.",
                        )
                        return CouncilLoopOutcome.CompletedWithoutCoding(
                            session = session,
                            reason = "Council Round $roundNumber blueprint saved (Council Only).",
                        )
                    }

                    val approvedSession = councilRepository?.markApprovedForCoding(sessionId)
                        ?: session.copy(approvedForCoding = true)
                    recordKnowledgeBlueprint(
                        intelligenceRepository = intelligenceRepository,
                        projectId = project.id,
                        taskId = taskId,
                        objective = objective,
                        round = activeRound,
                        stackLabel = inspection.stackLabel,
                    )
                    recordFlight(
                        FlightRecorderEventType.APPROVAL,
                        FlightRecorderOutcome.ALLOWED,
                        "council.approve_coding",
                        "Round $roundNumber (${activeRound.verdict.name})",
                        "Approved Council blueprint for Coder handoff (${activeRound.sections.count { it.isModifiedByUser }} user-edited section(s)).",
                    )
                    onKeyDecision("Council Round $roundNumber approved for Coder handoff")
                    emitEvent(
                        OrchestratorEvent.CouncilSessionUpdated(
                            session = approvedSession,
                            diffFromPrevious = diff,
                            requiresDecision = false,
                        )
                    )
                    return CouncilLoopOutcome.ProceedToCoding(
                        session = approvedSession,
                        approvedBlueprintMarkdown = activeRound.toHandoffMarkdown(),
                    )
                }

                is CouncilUserDecision.ReCouncil -> {
                    val editedCount = activeRound.sections.count { it.isModifiedByUser }
                    recordFlight(
                        FlightRecorderEventType.COUNCIL_USER_EDIT,
                        FlightRecorderOutcome.INFO,
                        "council.recouncil",
                        "Round $roundNumber -> Round ${roundNumber + 1}",
                        buildString {
                            append("Re-Council requested (refreshSearch=${decision.refreshSearch}, editedSections=$editedCount)")
                            decision.userAmendmentNote?.takeIf { it.isNotBlank() }?.let {
                                append("; note: ${it.take(180)}")
                            }
                        },
                    )
                    onKeyDecision("Re-Council triggered for Round ${roundNumber + 1} (editedSections=$editedCount)")
                    userAmendmentNote = decision.userAmendmentNote?.trim()?.ifBlank { null }
                    refreshSearch = decision.refreshSearch
                    roundNumber += 1
                }
            }
        }
    }

    private suspend fun recordKnowledgeBlueprint(
        intelligenceRepository: DevelopmentIntelligenceRepository?,
        projectId: String,
        taskId: String,
        objective: String,
        round: CouncilRound,
        stackLabel: String,
    ) {
        val repo = intelligenceRepository ?: return
        val summary = round.sections
            .firstOrNull { it.sectionId == com.example.domain.model.CouncilSectionId.EXECUTION_PLAN }
            ?.effectiveContent
            .orEmpty()
            .ifBlank { round.toHandoffMarkdown() }
            .take(800)
        repo.recordKnowledgeFact(
            projectId = projectId,
            factKey = "council:${DevelopmentIntelligenceRepository.normalizeFactKey(objective.take(64))}",
            category = KnowledgeCategory.ARCHITECTURAL_DECISION,
            title = "Council R${round.roundNumber} (${round.verdict.name}): ${objective.take(64)}",
            content = summary,
            tags = "$stackLabel council blueprint round${round.roundNumber}",
            sourceTaskId = taskId,
            confidence = 0.9f,
        )
    }
}
