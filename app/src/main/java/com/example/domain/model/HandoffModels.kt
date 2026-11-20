package com.example.domain.model

import com.example.agent.orchestrator.RepoMapEngine

data class HandoffCodeSlice(
    val relativePath: String,
    val excerpt: String,
    val lineCount: Int,
)

/**
 * Compact, model-agnostic handoff packet transferred across models (Gemini <-> Grok <-> Claude)
 * and agent backends (OpenCode <-> Pi) without replaying raw UI chat transcripts.
 */
data class ModelHandoffPacket(
    val taskId: String,
    val projectId: String,
    val objective: String,
    val acceptanceCriteria: List<String>,
    val resumedFromState: TaskState,
    val activeStepIndex: Int,
    val completedSteps: List<PlanStep>,
    val remainingSteps: List<PlanStep>,
    val keyDecisions: List<String>,
    val projectConstraints: List<String>,
    val knownFixes: List<KnownFixEntry>,
    val recentDiffSummary: List<String>,
    val unresolvedFailures: List<String>,
    val repoMap: String,
    val targetedCodeSlices: List<HandoffCodeSlice>,
    val sourceModelId: String?,
    val sourceBackendId: String?,
    val targetModelId: String,
    val targetBackendId: String,
    val checkpointId: String?,
    val lastEventSeq: Long,
    val additionalInstruction: String? = null,
    val estimatedTokens: Int = 0,
    val recalledKnowledge: List<ProjectKnowledgeEntry> = emptyList(),
    val approvedCouncilPlan: String? = null,
) {
    fun toPromptContext(tokenBudget: Int = DEFAULT_TOKEN_BUDGET): String {
        val safeBudget = tokenBudget.coerceAtLeast(300)
        val headerAndCore = buildString {
            appendLine("[MODEL HANDOFF PACKET]")
            appendLine(
                "Handoff: ${sourceBackendId ?: "unset"}/${sourceModelId ?: "unset"} -> " +
                    "$targetBackendId/$targetModelId (from state=${resumedFromState.name}, step=${activeStepIndex + 1}, seq=#$lastEventSeq)"
            )
            checkpointId?.let { appendLine("Durable Checkpoint: $it") }
            appendLine()
            appendLine("1. Objective & Acceptance Criteria:")
            appendLine("Objective: $objective")
            additionalInstruction?.takeIf { it.isNotBlank() }?.let {
                appendLine("Resume Note: $it")
            }
            acceptanceCriteria.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("2. Plan Progress (Active Step #${activeStepIndex + 1}):")
            if (completedSteps.isNotEmpty()) {
                appendLine("  Completed:")
                completedSteps.forEach { appendLine("    [x] Step ${it.index + 1}: ${it.title}") }
            }
            if (remainingSteps.isNotEmpty()) {
                appendLine("  Remaining:")
                remainingSteps.forEach { appendLine("    [ ] Step ${it.index + 1} (${it.status.name}): ${it.title}") }
            }
            if (keyDecisions.isNotEmpty() || projectConstraints.isNotEmpty() || knownFixes.isNotEmpty() || recalledKnowledge.isNotEmpty()) {
                appendLine()
                appendLine("3. Architectural Decisions & Project Constraints:")
                keyDecisions.takeLast(8).forEach { appendLine("  - Decision: $it") }
                projectConstraints.take(8).forEach { appendLine("  - Constraint: $it") }
                knownFixes.takeLast(4).forEach { fix ->
                    appendLine("  - Known Fix [${fix.errorSignature}]: ${fix.resolutionNote}")
                }
                recalledKnowledge.take(5).forEach { entry ->
                    appendLine("  - Recalled [${entry.category.label}] ${entry.title}: ${entry.content.take(220)}")
                }
            }
            if (recentDiffSummary.isNotEmpty() || unresolvedFailures.isNotEmpty()) {
                appendLine()
                appendLine("4. Recent Workspace Diff & Unresolved Failures:")
                recentDiffSummary.take(12).forEach { appendLine("  - Diff: $it") }
                unresolvedFailures.take(6).forEach { appendLine("  - Failure: $it") }
            }
        }.trimEnd()

        val sb = StringBuilder(headerAndCore)
        var usedTokens = RepoMapEngine.estimateTokens(sb.toString())

        if (!approvedCouncilPlan.isNullOrBlank() && usedTokens + 48 < safeBudget) {
            val councilBudget = ((safeBudget - usedTokens) / 2).coerceIn(220, 1400)
            val councilHeader = "\n\n5. Approved Multi-Agent Council Blueprint:\n"
            val boundedBlueprint = trimTextToTokens(approvedCouncilPlan.trim(), councilBudget)
            if (usedTokens + RepoMapEngine.estimateTokens(councilHeader + boundedBlueprint) <= safeBudget) {
                sb.append(councilHeader).append(boundedBlueprint)
                usedTokens = RepoMapEngine.estimateTokens(sb.toString())
            }
        }

        val mapSectionIdx = if (!approvedCouncilPlan.isNullOrBlank()) 6 else 5
        if (repoMap.isNotBlank() && usedTokens + 32 < safeBudget) {
            val remainingForMap = ((safeBudget - usedTokens) * 2) / 3
            val mapHeader = "\n\n$mapSectionIdx. Compact Repo Map & Targeted Code Slices:\n"
            val boundedMap = trimTextToTokens(repoMap, remainingForMap.coerceAtLeast(60))
            if (usedTokens + RepoMapEngine.estimateTokens(mapHeader + boundedMap) <= safeBudget) {
                sb.append(mapHeader).append(boundedMap)
                usedTokens = RepoMapEngine.estimateTokens(sb.toString())
            }
        } else if (targetedCodeSlices.isNotEmpty()) {
            val sliceHeader = "\n\n$mapSectionIdx. Targeted Code Slices:\n"
            if (usedTokens + RepoMapEngine.estimateTokens(sliceHeader) < safeBudget) {
                sb.append(sliceHeader)
                usedTokens = RepoMapEngine.estimateTokens(sb.toString())
            }
        }

        for (slice in targetedCodeSlices) {
            val block = "\n--- ${slice.relativePath} (${slice.lineCount} lines) ---\n${slice.excerpt}\n"
            val blockTokens = RepoMapEngine.estimateTokens(block)
            if (usedTokens + blockTokens <= safeBudget) {
                sb.append(block)
                usedTokens += blockTokens
            } else {
                val remainingChars = ((safeBudget - usedTokens) * 4) - 48
                if (remainingChars > 80) {
                    val partial = "\n--- ${slice.relativePath} (truncated) ---\n" +
                        slice.excerpt.take(remainingChars).substringBeforeLast('\n') + "\n"
                    if (usedTokens + RepoMapEngine.estimateTokens(partial) <= safeBudget) {
                        sb.append(partial)
                    }
                }
                break
            }
        }

        return sb.toString().trim()
    }

    companion object {
        const val DEFAULT_TOKEN_BUDGET = 3500

        private fun trimTextToTokens(text: String, maxTokens: Int): String {
            if (RepoMapEngine.estimateTokens(text) <= maxTokens) return text
            val maxChars = (maxTokens * 4).coerceAtLeast(32)
            return text.take(maxChars).substringBeforeLast('\n').ifBlank { text.take(maxChars) }
        }
    }
}
