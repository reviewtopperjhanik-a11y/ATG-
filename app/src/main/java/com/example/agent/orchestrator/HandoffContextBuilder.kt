package com.example.agent.orchestrator

import com.example.core.common.ProjectPathValidator
import com.example.core.security.SecretRedactor
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.domain.contracts.CheckpointManager
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.HandoffCodeSlice
import com.example.domain.model.ModelHandoffPacket
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.ProjectInspection
import com.example.domain.model.TaskStateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Assembles a compact, deterministic [ModelHandoffPacket] for seamless continuation across
 * models (Gemini <-> Grok <-> Claude) and agent backends (OpenCode <-> Pi) without context bloat.
 */
class HandoffContextBuilder(
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
    private val checkpointManager: CheckpointManager? = null,
    private val repoMapEngine: RepoMapEngine = RepoMapEngine(),
    private val knownSecretsProvider: () -> Collection<String> = { emptyList() },
) {
    suspend fun buildPacket(
        snapshot: TaskStateSnapshot,
        inspection: ProjectInspection,
        targetModelId: String,
        targetBackendId: String,
        additionalInstruction: String? = null,
        tokenBudget: Int = ModelHandoffPacket.DEFAULT_TOKEN_BUDGET,
        approvedCouncilPlan: String? = null,
    ): ModelHandoffPacket = withContext(Dispatchers.IO) {
        val secrets = knownSecretsProvider()
        val memory = intelligenceRepository?.observeProjectMemory(snapshot.projectId)?.first()
        val flightEvents = intelligenceRepository?.getFlightEventsForTask(snapshot.taskId).orEmpty()

        // 1. Acceptance criteria from workspace inspection & safety confinement
        val acceptanceCriteria = buildList {
            add("Changes remain strictly confined within ${inspection.projectRootPath}.")
            if (inspection.validationCommands.isNotEmpty()) {
                add("Detected checks pass: ${inspection.validationCommands.joinToString("; ") { it.command }}")
            } else {
                add("Modified files build cleanly with no runtime or policy errors.")
            }
        }

        // 2. Plan progress breakdown
        val completedSteps = snapshot.planSteps.filter { it.status == PlanStepStatus.COMPLETED }
        val remainingSteps = snapshot.planSteps.filter { it.status != PlanStepStatus.COMPLETED }

        // 3. Project constraints from curated memory + validation metadata
        val constraints = buildList {
            memory?.curatedRules?.forEach { add(SecretRedactor.redact(it, secrets)) }
            if (isEmpty() && inspection.validationCommands.isNotEmpty()) {
                add("Stack: ${inspection.stackLabel}")
            }
            memory?.validationCommands?.forEach { add("Check: $it") }
        }.distinct()

        // 4. Recent workspace diff (from checkpoint manager) & unresolved failures (from flight recorder)
        val checkpointId = snapshot.resumeCursor.checkpointId
        val diffResult = checkpointId?.let { chkId ->
            checkpointManager?.diff(chkId)?.getOrNull()
        }

        val affectedPaths = LinkedHashSet<String>()
        diffResult?.entries?.forEach { entry ->
            affectedPaths += entry.relativePath
        }
        snapshot.modifiedFiles.forEach { raw ->
            val cleaned = raw.substringAfter(": ").trim()
            if (cleaned.isNotBlank() && !cleaned.contains(" ")) {
                affectedPaths += cleaned
            }
        }

        val diffSummary = buildList {
            if (diffResult != null && diffResult.entries.isNotEmpty()) {
                diffResult.entries.take(10).forEach { entry ->
                    val diffHead = entry.unifiedDiff
                        .lineSequence()
                        .filter { it.startsWith("+") || it.startsWith("-") }
                        .take(4)
                        .joinToString(" | ")
                    val line = if (diffHead.isNotBlank()) {
                        "${entry.changeType.label} ${entry.relativePath}: ${SecretRedactor.redact(diffHead.take(180), secrets)}"
                    } else {
                        "${entry.changeType.label} ${entry.relativePath}"
                    }
                    add(line)
                }
            } else {
                snapshot.modifiedFiles.take(10).forEach { add(it) }
            }
        }

        val unresolvedFailures = buildList {
            snapshot.resumeCursor.lastError?.takeIf { it.isNotBlank() }?.let {
                add(SecretRedactor.redact(it, secrets))
            }
            flightEvents
                .filter {
                    it.outcome == FlightRecorderOutcome.FAILED ||
                        it.outcome == FlightRecorderOutcome.TIMED_OUT ||
                        it.outcome == FlightRecorderOutcome.REJECTED ||
                        it.eventType == FlightRecorderEventType.RUNTIME_ERROR
                }
                .takeLast(5)
                .forEach { ev ->
                    val summary = buildString {
                        append("[#${ev.seq} ${ev.eventType.label}] ")
                        ev.commandOrPath?.takeIf { it.isNotBlank() }?.let { append("$it: ") }
                        ev.exitCode?.let { append("(exit=$it) ") }
                        append(ev.outputExcerpt.take(260).replace('\n', ' '))
                    }.trim()
                    val safe = SecretRedactor.redact(summary, secrets)
                    if (safe.isNotBlank() && safe !in this) {
                        add(safe)
                    }
                }
        }.distinct()

        // 4b. Phase 5 Long-Term Project Knowledge Recall (FTS5 + temporal active filter)
        val knowledgeQuery = buildString {
            append(snapshot.objective)
            if (unresolvedFailures.isNotEmpty()) {
                append(' ')
                append(unresolvedFailures.joinToString(" "))
            }
        }
        val recalledKnowledge = intelligenceRepository
            ?.retrieveRelevantKnowledge(
                projectId = snapshot.projectId,
                query = knowledgeQuery,
                limit = 5,
                tokenBudget = 350,
            )
            ?.hits
            ?.map { it.entry }
            .orEmpty()

        // 5. Compact RepoMap + Targeted Code Slices for affected files
        val repoMapText = when {
            inspection.repoMap.isNotBlank() -> inspection.repoMap
            memory != null && memory.repoMapCache.isNotBlank() && memory.repoMapUpdatedAtEpochMs > 0L ->
                memory.repoMapCache
            intelligenceRepository != null ->
                intelligenceRepository.getOrBuildRepoMap(
                    inspection = inspection,
                    recentlyEditedPaths = affectedPaths,
                    objectiveHint = snapshot.objective,
                    tokenBudget = (tokenBudget / 2).coerceIn(400, 1800),
                ).renderedMap
            else ->
                repoMapEngine.buildFromInspection(
                    inspection = inspection,
                    recentlyEditedPaths = affectedPaths,
                    objectiveHint = snapshot.objective,
                    tokenBudget = (tokenBudget / 2).coerceIn(400, 1800),
                ).renderedMap
        }

        // Also include files mentioned in error excerpts if they exist inside the workspace
        val errorMentionedPaths = inspection.directoryMap.filter { rel ->
            val fileName = rel.substringAfterLast('/')
            unresolvedFailures.any { err -> rel in err || (fileName.length >= 4 && fileName in err) }
        }
        val sliceCandidates = (affectedPaths.toList() + errorMentionedPaths + inspection.entryPoints)
            .distinct()
            .filter { !RepositoryInspector.isSecretOrExcludedFile(it) }
            .take(MAX_CODE_SLICES)

        val rootDir = File(inspection.projectRootPath).canonicalFile
        val codeSlices = sliceCandidates.mapNotNull { relPath ->
            val resolved = ProjectPathValidator.resolveWithinRoot(rootDir, relPath).getOrNull()
                ?: return@mapNotNull null
            if (!resolved.isFile || resolved.length() > MAX_SLICE_FILE_BYTES) return@mapNotNull null
            val lines = runCatching { resolved.readLines() }.getOrNull() ?: return@mapNotNull null
            val excerpt = lines.take(MAX_SLICE_LINES)
                .joinToString("\n")
                .let { SecretRedactor.redact(it, secrets) }
                .take(MAX_SLICE_CHARS)
            if (excerpt.isBlank()) return@mapNotNull null
            HandoffCodeSlice(
                relativePath = relPath,
                excerpt = excerpt,
                lineCount = lines.size,
            )
        }

        val draftPacket = ModelHandoffPacket(
            taskId = snapshot.taskId,
            projectId = snapshot.projectId,
            objective = SecretRedactor.redact(snapshot.objective, secrets),
            acceptanceCriteria = acceptanceCriteria,
            resumedFromState = snapshot.currentState,
            activeStepIndex = snapshot.activeStepIndex,
            completedSteps = completedSteps,
            remainingSteps = remainingSteps,
            keyDecisions = snapshot.keyDecisions.map { SecretRedactor.redact(it, secrets) },
            projectConstraints = constraints,
            knownFixes = memory?.knownFixes.orEmpty(),
            recentDiffSummary = diffSummary,
            unresolvedFailures = unresolvedFailures,
            repoMap = SecretRedactor.redact(repoMapText, secrets),
            targetedCodeSlices = codeSlices,
            sourceModelId = snapshot.lastModelId,
            sourceBackendId = snapshot.lastBackendId,
            targetModelId = targetModelId,
            targetBackendId = targetBackendId,
            checkpointId = checkpointId,
            lastEventSeq = snapshot.resumeCursor.lastEventSeq,
            additionalInstruction = additionalInstruction?.let { SecretRedactor.redact(it, secrets) },
            recalledKnowledge = recalledKnowledge,
            approvedCouncilPlan = approvedCouncilPlan?.let { SecretRedactor.redact(it, secrets) },
        )
        val renderedPrompt = draftPacket.toPromptContext(tokenBudget)
        draftPacket.copy(estimatedTokens = RepoMapEngine.estimateTokens(renderedPrompt))
    }

    companion object {
        private const val MAX_CODE_SLICES = 5
        private const val MAX_SLICE_LINES = 50
        private const val MAX_SLICE_CHARS = 1400
        private const val MAX_SLICE_FILE_BYTES = 256 * 1024L
    }
}
