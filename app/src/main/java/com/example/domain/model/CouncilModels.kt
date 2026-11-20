package com.example.domain.model

import com.example.core.common.AgtError

enum class CouncilExecutionMode(val id: String, val label: String) {
    DIRECT_CODE("direct_code", "Direct Code"),
    COUNCIL_AND_CODE("council_and_code", "Council + Code"),
    COUNCIL_ONLY("council_only", "Council Only"),
}

enum class CouncilRole(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val isSpecialistDebater: Boolean = false,
) {
    DECOMPOSER("decomposer", "Task Decomposer", "Decomposer"),
    WEB_RESEARCHER("web_researcher", "Web Researcher", "Web Search"),
    GITHUB_RESEARCHER("github_researcher", "GitHub Repo Researcher", "GitHub"),
    PLANNER("planner", "Architect / Planner", "Planner", isSpecialistDebater = true),
    COST_ANALYST("cost_analyst", "Cost & Resource Analyst", "Cost", isSpecialistDebater = true),
    REALIST("realist", "Realist / Feasibility", "Realist", isSpecialistDebater = true),
    CRITIC("critic", "Devil's Advocate / Critic", "Critic", isSpecialistDebater = true),
    ALTERNATIVE("alternative", "Alternative Proposer", "Alternative", isSpecialistDebater = true),
    SYNTHESIZER("synthesizer", "Lead Judge / Synthesizer", "Synthesizer");

    companion object {
        val DEFAULT_SPECIALISTS: Set<CouncilRole> = entries.filter { it.isSpecialistDebater }.toSet()

        fun fromId(raw: String?): CouncilRole? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class CouncilVerdict(val label: String) {
    GO("Go — Ready to Build"),
    MODIFY("Modify — Proceed with Changes"),
    RECONSIDER("Reconsider — High Risk / Pivot"),
}

enum class CouncilSectionId(
    val id: String,
    val title: String,
    val description: String,
) {
    SUMMARY_VERDICT(
        id = "summary_verdict",
        title = "Summary & Verdict",
        description = "Executive recommendation, verdict, and core goal alignment",
    ),
    RESEARCH_FINDINGS(
        id = "research_findings",
        title = "Research & Existing Projects",
        description = "Similar GitHub repositories, libraries, and web findings",
    ),
    EXECUTION_PLAN(
        id = "execution_plan",
        title = "Step-by-Step Execution Plan",
        description = "Architecture, implementation steps, and target files",
    ),
    COST_RESOURCES(
        id = "cost_resources",
        title = "Cost & Effort Estimate",
        description = "API, infrastructure, licensing, and engineering effort",
    ),
    RISKS_CRITIQUE(
        id = "risks_critique",
        title = "Risks & Critic Objections",
        description = "Flaws, security concerns, failure modes, and mitigations",
    ),
    ALTERNATIVES(
        id = "alternatives",
        title = "Alternatives & Trade-offs",
        description = "Simpler, cheaper, or safer alternative paths",
    );

    companion object {
        fun fromId(raw: String?): CouncilSectionId? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class CouncilReportSection(
    val sectionId: CouncilSectionId,
    val title: String = sectionId.title,
    val originalContent: String,
    val editedContent: String = originalContent,
    val isModifiedByUser: Boolean = false,
) {
    val effectiveContent: String
        get() = editedContent.ifBlank { originalContent }

    fun withUserEdit(newContent: String): CouncilReportSection {
        val normalizedNew = newContent.trimEnd()
        val modified = normalizedNew.trim() != originalContent.trim()
        return copy(
            editedContent = normalizedNew,
            isModifiedByUser = modified,
        )
    }

    fun revertToOriginal(): CouncilReportSection = copy(
        editedContent = originalContent,
        isModifiedByUser = false,
    )
}

data class CouncilSectionDelta(
    val sectionId: CouncilSectionId,
    val title: String,
    val previousExcerpt: String,
    val currentExcerpt: String,
    val changed: Boolean,
)

data class CouncilRoundDiff(
    val fromRound: Int,
    val toRound: Int,
    val verdictChanged: Boolean,
    val fromVerdict: CouncilVerdict,
    val toVerdict: CouncilVerdict,
    val changedSections: List<CouncilSectionDelta>,
    val userModifiedSectionsInBase: List<CouncilSectionId>,
)

data class CouncilDecomposition(
    val refinedObjective: String,
    val subTasks: List<String> = emptyList(),
    val webQueries: List<String> = emptyList(),
    val githubQueries: List<String> = emptyList(),
    val preferredLanguage: String? = null,
    val rationale: String = "",
)

data class ResearchDossier(
    val webFindings: List<WebSearchResultItem> = emptyList(),
    val githubFindings: List<GitHubSearchResultItem> = emptyList(),
    val webSummary: String = "",
    val githubSummary: String = "",
    val notices: List<String> = emptyList(),
    val webProviderUsed: String? = null,
    val githubFallbackUsed: Boolean = false,
) {
    val hasAnyFindings: Boolean
        get() = webFindings.isNotEmpty() || githubFindings.isNotEmpty()

    fun toPromptContext(): String = buildString {
        if (webSummary.isNotBlank()) {
            appendLine("=== Web Research Summary ===")
            appendLine(webSummary)
        }
        if (webFindings.isNotEmpty()) {
            appendLine("Top Web References:")
            webFindings.take(6).forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title} (${item.url})")
                val excerpt = item.content.ifBlank { item.snippet }.take(320)
                if (excerpt.isNotBlank()) appendLine("   $excerpt")
            }
        }
        if (githubSummary.isNotBlank()) {
            appendLine("=== GitHub Repository Research ===")
            appendLine(githubSummary)
        }
        if (githubFindings.isNotEmpty()) {
            appendLine("Top GitHub Repositories:")
            githubFindings.take(6).forEachIndexed { index, repo ->
                val lang = repo.language ?: "Unknown"
                val lic = repo.license ?: "No license"
                appendLine("${index + 1}. ${repo.fullName} [★${repo.stars} | $lang | $lic] — ${repo.htmlUrl}")
                if (repo.description.isNotBlank()) appendLine("   Desc: ${repo.description.take(220)}")
                if (repo.readmeExcerpt.isNotBlank()) appendLine("   README: ${repo.readmeExcerpt.take(360)}")
            }
        }
        if (notices.isNotEmpty()) {
            appendLine("Research Notices:")
            notices.forEach { appendLine("- $it") }
        }
        if (!hasAnyFindings && notices.isEmpty()) {
            appendLine("No external search results available; rely on repository context and engineering principles.")
        }
    }.trim()
}

enum class CouncilAgentStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class CouncilAgentOpinion(
    val role: CouncilRole,
    val modelId: String,
    val status: CouncilAgentStatus,
    val headline: String = "",
    val summary: String = "",
    val bulletPoints: List<String> = emptyList(),
    val verdictTag: String? = null,
    val rawOutput: String = "",
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
) {
    fun toPromptContext(): String = buildString {
        appendLine("### ${role.displayName} (${status.name}${verdictTag?.let { " · $it" }.orEmpty()})")
        if (headline.isNotBlank()) appendLine("Headline: $headline")
        if (summary.isNotBlank()) appendLine(summary)
        if (bulletPoints.isNotEmpty()) {
            bulletPoints.forEach { appendLine("- $it") }
        }
        if (!errorMessage.isNullOrBlank()) {
            appendLine("Error: $errorMessage")
        }
    }.trim()
}

data class CouncilRound(
    val roundNumber: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val userAmendmentNote: String? = null,
    val refreshedSearch: Boolean = false,
    val decomposition: CouncilDecomposition,
    val dossier: ResearchDossier,
    val opinions: List<CouncilAgentOpinion>,
    val verdict: CouncilVerdict,
    val sections: List<CouncilReportSection>,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val durationMs: Long = 0L,
) {
    val totalTokens: Int
        get() = totalPromptTokens + totalCompletionTokens

    fun exceedsBudget(maxTokens: Int): Boolean =
        maxTokens > 0 && totalTokens > maxTokens

    val hasUserModifications: Boolean
        get() = sections.any { it.isModifiedByUser } || !userAmendmentNote.isNullOrBlank()

    fun updateSection(sectionId: CouncilSectionId, newContent: String): CouncilRound {
        val updated = sections.map { sec ->
            if (sec.sectionId == sectionId) sec.withUserEdit(newContent) else sec
        }
        return copy(sections = updated)
    }

    fun revertSection(sectionId: CouncilSectionId): CouncilRound {
        val updated = sections.map { sec ->
            if (sec.sectionId == sectionId) sec.revertToOriginal() else sec
        }
        return copy(sections = updated)
    }

    fun diffSummaryAgainst(previous: CouncilRound): CouncilRoundDiff {
        val prevMap = previous.sections.associateBy { it.sectionId }
        val deltas = sections.map { curr ->
            val prev = prevMap[curr.sectionId]
            val prevText = prev?.effectiveContent?.trim().orEmpty()
            val currText = curr.effectiveContent.trim()
            CouncilSectionDelta(
                sectionId = curr.sectionId,
                title = curr.title,
                previousExcerpt = prevText.take(180),
                currentExcerpt = currText.take(180),
                changed = prev == null || prevText != currText,
            )
        }
        return CouncilRoundDiff(
            fromRound = previous.roundNumber,
            toRound = roundNumber,
            verdictChanged = previous.verdict != verdict,
            fromVerdict = previous.verdict,
            toVerdict = verdict,
            changedSections = deltas,
            userModifiedSectionsInBase = previous.sections.filter { it.isModifiedByUser }.map { it.sectionId },
        )
    }

    fun toHandoffMarkdown(): String = buildString {
        appendLine("# Approved Council Blueprint (Round $roundNumber · ${verdict.label})")
        if (!userAmendmentNote.isNullOrBlank()) {
            appendLine("User Amendment Applied: $userAmendmentNote")
            appendLine()
        }
        sections.forEach { section ->
            appendLine("## ${section.title}${if (section.isModifiedByUser) " (User Edited)" else ""}")
            appendLine(section.effectiveContent.trim())
            appendLine()
        }
    }.trim()
}

data class CouncilSession(
    val sessionId: String,
    val projectId: String,
    val taskId: String,
    val objective: String,
    val mode: CouncilExecutionMode = CouncilExecutionMode.COUNCIL_AND_CODE,
    val currentRoundNumber: Int = 1,
    val rounds: List<CouncilRound> = emptyList(),
    val approvedForCoding: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val latestRound: CouncilRound?
        get() = rounds.maxByOrNull { it.roundNumber }

    fun roundByNumber(roundNumber: Int): CouncilRound? =
        rounds.firstOrNull { it.roundNumber == roundNumber }

    fun replaceRound(updated: CouncilRound): CouncilSession {
        val replaced = if (rounds.any { it.roundNumber == updated.roundNumber }) {
            rounds.map { if (it.roundNumber == updated.roundNumber) updated else it }
        } else {
            (rounds + updated).sortedBy { it.roundNumber }
        }
        return copy(
            rounds = replaced,
            currentRoundNumber = replaced.maxOfOrNull { it.roundNumber } ?: currentRoundNumber,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun editSection(roundNumber: Int, sectionId: CouncilSectionId, newContent: String): CouncilSession {
        val target = roundByNumber(roundNumber) ?: return this
        return replaceRound(target.updateSection(sectionId, newContent))
    }

    fun revertSection(roundNumber: Int, sectionId: CouncilSectionId): CouncilSession {
        val target = roundByNumber(roundNumber) ?: return this
        return replaceRound(target.revertSection(sectionId))
    }
}

sealed interface CouncilUserDecision {
    data object ApproveForCoding : CouncilUserDecision
    data object SaveCouncilOnly : CouncilUserDecision
    data class ReCouncil(
        val userAmendmentNote: String? = null,
        val refreshSearch: Boolean = false,
    ) : CouncilUserDecision
    data object Cancel : CouncilUserDecision
}

sealed interface CouncilProgressEvent {
    data class StageStarted(val stage: String, val detail: String) : CouncilProgressEvent
    data class DecompositionReady(val decomposition: CouncilDecomposition) : CouncilProgressEvent
    data class ResearchProgress(val role: CouncilRole, val status: CouncilAgentStatus, val detail: String) : CouncilProgressEvent
    data class ResearchDossierReady(val dossier: ResearchDossier) : CouncilProgressEvent
    data class SpecialistStarted(val role: CouncilRole, val modelId: String) : CouncilProgressEvent
    data class SpecialistCompleted(val opinion: CouncilAgentOpinion) : CouncilProgressEvent
    data class SynthesisStarted(val modelId: String) : CouncilProgressEvent
    data class RoundCompleted(val round: CouncilRound) : CouncilProgressEvent
    data class Failed(val reason: String, val error: AgtError? = null) : CouncilProgressEvent
}
