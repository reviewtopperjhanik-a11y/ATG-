package com.example.agent.orchestrator

import com.example.domain.model.CouncilAgentOpinion
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilDecomposition
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.GitHubSearchResultItem
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ResearchDossier
import com.example.domain.model.WebSearchResultItem
import org.json.JSONArray
import org.json.JSONObject

internal object CouncilRolePrompts {

    fun buildDecomposerSystemPrompt(): String = """
        You are the Task Decomposer for an autonomous software engineering council.
        Analyze the user's objective and project context, then output ONLY a valid JSON object (no markdown prose outside JSON):
        {
          "refined_objective": "clear concise technical goal",
          "sub_tasks": ["step 1", "step 2", "step 3"],
          "web_queries": ["2-3 focused web search queries to check prior art, docs, benchmarks"],
          "github_queries": ["2-3 focused GitHub repository search queries (short keywords, no operators)"],
          "preferred_language": "primary programming language or null",
          "rationale": "brief 1-sentence reason"
        }
        Keep queries concise and practical. Limit web_queries and github_queries to max 3 each.
    """.trimIndent()

    fun buildDecomposerUserPrompt(
        objective: String,
        inspection: ProjectInspection,
        userAmendmentNote: String?,
    ): String = buildString {
        appendLine("Objective: $objective")
        if (!userAmendmentNote.isNullOrBlank()) {
            appendLine("User Amendment / Direction Update: $userAmendmentNote")
        }
        appendLine()
        appendLine("Workspace Context:")
        appendLine(inspection.toPromptContext().take(4500))
    }.trim()

    fun buildSpecialistSystemPrompt(role: CouncilRole, isReCouncil: Boolean): String {
        val roleDirective = when (role) {
            CouncilRole.PLANNER -> """
                You are the Architect & Planner on the engineering council.
                Design a concrete, modular implementation plan grounded in the workspace stack and research dossier.
                Highlight exact components, data flow, and verification steps.
            """.trimIndent()
            CouncilRole.COST_ANALYST -> """
                You are the Cost & Resource Analyst on the engineering council.
                Evaluate API/LLM token usage, third-party service pricing, free-tier limits, quota sustainability,
                licensing constraints, and engineering time cost. Flag hidden recurring expenses.
            """.trimIndent()
            CouncilRole.REALIST -> """
                You are the Realist / Feasibility Pragmatist on the engineering council.
                Ground the discussion in real-world execution constraints (Android runtime, PRoot Linux limits,
                network latency, offline behavior, maintainability, and scope realism).
            """.trimIndent()
            CouncilRole.CRITIC -> """
                You are the Devil's Advocate / Adversarial Critic on the engineering council.
                Actively challenge assumptions, hunt for flaws, security holes, race conditions, UX friction,
                dependency risks, and reasons this plan could fail. Be constructive but uncompromising.
            """.trimIndent()
            CouncilRole.ALTERNATIVE -> """
                You are the Alternative Proposer on the engineering council.
                Propose 2-3 distinct alternative approaches (e.g. a zero-cost MVP path, a simpler architecture,
                or leveraging an existing open-source repository found in research) with clear trade-offs.
            """.trimIndent()
            else -> "You are an engineering council specialist."
        }

        val roundNote = if (isReCouncil) {
            "\nThis is a RE-COUNCIL round after the user edited the prior report. Focus specifically on the user's changes, check whether previous flaws are resolved, and identify any new impacts."
        } else {
            ""
        }

        return """
            $roleDirective$roundNote

            Output ONLY a valid JSON object with this exact schema:
            {
              "headline": "1 crisp summary sentence from your role's perspective",
              "verdict_tag": "SHORT_BADGE (e.g. FEASIBLE, HIGH_COST, RISKY, SIMPLER_MVP, SOLID_PLAN)",
              "summary": "2-4 sentence focused analysis",
              "bullet_points": ["point 1", "point 2", "point 3", "point 4"]
            }
            Keep bullet_points concise (max 6 items). Do not invent file paths or APIs not grounded in context.
        """.trimIndent()
    }

    fun buildSpecialistUserPrompt(
        objective: String,
        decomposition: CouncilDecomposition,
        inspection: ProjectInspection,
        dossier: ResearchDossier,
        priorEditedSections: List<CouncilReportSection>,
        userAmendmentNote: String?,
    ): String = buildString {
        appendLine("Objective: ${decomposition.refinedObjective.ifBlank { objective }}")
        if (decomposition.subTasks.isNotEmpty()) {
            appendLine("Sub-tasks:")
            decomposition.subTasks.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Workspace Snapshot:")
        appendLine(inspection.toPromptContext().take(3800))
        appendLine()
        appendLine("Research Dossier:")
        appendLine(dossier.toPromptContext().take(4200))

        if (priorEditedSections.isNotEmpty()) {
            appendLine()
            appendLine("=== Prior Report (User-Edited Baseline for Re-Council) ===")
            priorEditedSections.forEach { sec ->
                appendLine("## ${sec.title}${if (sec.isModifiedByUser) " [USER EDITED]" else ""}")
                appendLine(sec.effectiveContent.take(1200))
            }
        }
        if (!userAmendmentNote.isNullOrBlank()) {
            appendLine()
            appendLine("User Amendment / Feedback Note: $userAmendmentNote")
        }
    }.trim()

    fun buildSynthesizerSystemPrompt(isReCouncil: Boolean): String = """
        You are the Lead Judge & Synthesizer of the Multi-Agent Engineering Council.
        ${if (isReCouncil) "Reconcile the user's edited baseline and amendment note with the updated Re-Council specialist reviews." else "Reconcile all specialist reports and research findings into an actionable, balanced final decision blueprint."}

        Output ONLY a valid JSON object with this exact schema:
        {
          "verdict": "GO | MODIFY | RECONSIDER",
          "sections": {
            "summary_verdict": "Clear verdict rationale and overall recommendation (markdown allowed inside string)",
            "research_findings": "Synthesized prior art from GitHub repos & web references with links/stars",
            "execution_plan": "Numbered step-by-step technical plan incorporating mitigations",
            "cost_resources": "API/token/infra cost breakdown and realistic effort estimate",
            "risks_critique": "Key Critic objections, failure modes, and required guardrails",
            "alternatives": "Viable Plan B / Plan C alternatives and when to pick them"
          }
        }
        Make each section clear, well-structured Markdown, concise, and directly editable by the user.
    """.trimIndent()

    fun buildSynthesizerUserPrompt(
        objective: String,
        decomposition: CouncilDecomposition,
        dossier: ResearchDossier,
        opinions: List<CouncilAgentOpinion>,
        priorEditedSections: List<CouncilReportSection>,
        userAmendmentNote: String?,
    ): String = buildString {
        appendLine("Original Objective: $objective")
        appendLine("Refined Objective: ${decomposition.refinedObjective}")
        if (!userAmendmentNote.isNullOrBlank()) {
            appendLine("User Amendment Note: $userAmendmentNote")
        }
        if (priorEditedSections.isNotEmpty()) {
            appendLine()
            appendLine("=== Prior User-Edited Sections ===")
            priorEditedSections.forEach { sec ->
                if (sec.isModifiedByUser) {
                    appendLine("## ${sec.title} [EDITED BY USER]")
                    appendLine(sec.effectiveContent.take(1200))
                }
            }
        }
        appendLine()
        appendLine("=== Research Dossier ===")
        appendLine(dossier.toPromptContext().take(3500))
        appendLine()
        appendLine("=== Specialist Council Opinions ===")
        opinions.forEach { op ->
            appendLine(op.toPromptContext())
            appendLine()
        }
    }.trim()

    fun parseDecomposition(
        rawText: String,
        objective: String,
        inspection: ProjectInspection,
    ): CouncilDecomposition {
        val json = extractJsonObject(rawText)
        if (json != null) {
            val refined = json.optString("refined_objective").trim().ifBlank { objective }
            val subTasks = parseStringList(json.optJSONArray("sub_tasks"))
            val webQueries = parseStringList(json.optJSONArray("web_queries")).take(3)
            val githubQueries = parseStringList(json.optJSONArray("github_queries")).take(3)
            val preferredLanguage = json.optString("preferred_language").trim()
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            val rationale = json.optString("rationale").trim()
            return CouncilDecomposition(
                refinedObjective = refined,
                subTasks = subTasks.ifEmpty { listOf("Inspect constraints", "Design architecture", "Validate feasibility") },
                webQueries = webQueries.ifEmpty { defaultWebQueries(objective, inspection) },
                githubQueries = githubQueries.ifEmpty { defaultGitHubQueries(objective, inspection) },
                preferredLanguage = preferredLanguage ?: inferLanguageFromStack(inspection),
                rationale = rationale,
            )
        }
        return fallbackDecomposition(objective, inspection)
    }

    fun fallbackDecomposition(objective: String, inspection: ProjectInspection): CouncilDecomposition =
        CouncilDecomposition(
            refinedObjective = objective.trim(),
            subTasks = listOf(
                "Research existing implementations & libraries",
                "Design modular implementation plan",
                "Evaluate cost, feasibility, risks & alternatives",
            ),
            webQueries = defaultWebQueries(objective, inspection),
            githubQueries = defaultGitHubQueries(objective, inspection),
            preferredLanguage = inferLanguageFromStack(inspection),
            rationale = "Heuristic task decomposition fallback.",
        )

    fun parseSpecialistOpinion(
        role: CouncilRole,
        modelId: String,
        rawText: String,
        promptTokens: Int?,
        completionTokens: Int?,
        durationMs: Long,
    ): CouncilAgentOpinion {
        val cleaned = rawText.trim()
        val json = extractJsonObject(cleaned)
        if (json != null) {
            val headline = json.optString("headline").trim()
            val verdictTag = json.optString("verdict_tag").trim().ifBlank { null }
            val summary = json.optString("summary").trim()
            val bullets = parseStringList(json.optJSONArray("bullet_points"))
            return CouncilAgentOpinion(
                role = role,
                modelId = modelId,
                status = CouncilAgentStatus.COMPLETED,
                headline = headline.ifBlank { bullets.firstOrNull().orEmpty().take(120) },
                summary = summary.ifBlank { cleaned.take(600) },
                bulletPoints = bullets,
                verdictTag = verdictTag,
                rawOutput = cleaned,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                durationMs = durationMs,
            )
        }

        // Resilient plain-text / markdown fallback
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.removePrefix("- ").removePrefix("* ").trim() }
            .take(6)
        return CouncilAgentOpinion(
            role = role,
            modelId = modelId,
            status = CouncilAgentStatus.COMPLETED,
            headline = lines.firstOrNull()?.take(120).orEmpty(),
            summary = cleaned.take(900),
            bulletPoints = bullets,
            verdictTag = null,
            rawOutput = cleaned,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            durationMs = durationMs,
        )
    }

    fun parseSynthesis(
        rawText: String,
        objective: String,
        dossier: ResearchDossier,
        opinions: List<CouncilAgentOpinion>,
    ): Pair<CouncilVerdict, List<CouncilReportSection>> {
        val json = extractJsonObject(rawText)
        if (json != null) {
            val verdictStr = json.optString("verdict").trim().uppercase()
            val verdict = when {
                verdictStr.contains("RECONSIDER") -> CouncilVerdict.RECONSIDER
                verdictStr.contains("MODIFY") -> CouncilVerdict.MODIFY
                verdictStr.contains("GO") -> CouncilVerdict.GO
                else -> inferVerdictFromOpinions(opinions)
            }
            val sectionsObj = json.optJSONObject("sections")
                ?: json.takeIf { root -> CouncilSectionId.entries.any { root.has(it.id) } }
            if (sectionsObj != null) {
                val parsedSections = CouncilSectionId.entries.map { secId ->
                    val content = sectionsObj.optString(secId.id).trim()
                        .ifBlank { fallbackSectionContent(secId, objective, dossier, opinions) }
                    CouncilReportSection(
                        sectionId = secId,
                        title = secId.title,
                        originalContent = content,
                        editedContent = content,
                    )
                }
                return verdict to parsedSections
            }
            return fallbackSynthesis(objective, dossier, opinions, rawText, explicitVerdict = verdict)
        }
        return fallbackSynthesis(objective, dossier, opinions, rawText)
    }

    fun fallbackSynthesis(
        objective: String,
        dossier: ResearchDossier,
        opinions: List<CouncilAgentOpinion>,
        rawSynthesizerText: String = "",
        explicitVerdict: CouncilVerdict? = null,
    ): Pair<CouncilVerdict, List<CouncilReportSection>> {
        val verdict = explicitVerdict ?: inferVerdictFromOpinions(opinions)
        val sections = CouncilSectionId.entries.map { secId ->
            val content = if (secId == CouncilSectionId.SUMMARY_VERDICT && rawSynthesizerText.isNotBlank()) {
                rawSynthesizerText.trim().take(1500)
            } else {
                fallbackSectionContent(secId, objective, dossier, opinions)
            }
            CouncilReportSection(
                sectionId = secId,
                title = secId.title,
                originalContent = content,
                editedContent = content,
            )
        }
        return verdict to sections
    }

    fun formatResearchDossier(
        webResults: List<WebSearchResultItem>,
        githubResults: List<GitHubSearchResultItem>,
        notices: List<String>,
        webProviderUsed: String?,
        githubFallbackUsed: Boolean,
    ): ResearchDossier {
        val webSummary = if (webResults.isEmpty()) {
            "No live web search results retrieved."
        } else {
            "Retrieved ${webResults.size} web reference(s)${webProviderUsed?.let { " via $it" }.orEmpty()}."
        }
        val githubSummary = if (githubResults.isEmpty()) {
            "No matching GitHub repositories retrieved."
        } else {
            val topStars = githubResults.maxOfOrNull { it.stars } ?: 0
            "Retrieved ${githubResults.size} GitHub repository match(es) (top ★$topStars)."
        }
        return ResearchDossier(
            webFindings = webResults,
            githubFindings = githubResults,
            webSummary = webSummary,
            githubSummary = githubSummary,
            notices = notices.distinct(),
            webProviderUsed = webProviderUsed,
            githubFallbackUsed = githubFallbackUsed,
        )
    }

    private fun fallbackSectionContent(
        secId: CouncilSectionId,
        objective: String,
        dossier: ResearchDossier,
        opinions: List<CouncilAgentOpinion>,
    ): String {
        fun roleText(role: CouncilRole): String {
            val op = opinions.firstOrNull { it.role == role }
                ?: return "${role.displayName} did not submit a report."
            return op.toPromptContext()
        }
        return when (secId) {
            CouncilSectionId.SUMMARY_VERDICT -> buildString {
                appendLine("Objective: $objective")
                appendLine()
                opinions.filter { it.status == CouncilAgentStatus.COMPLETED }.forEach {
                    appendLine("- **${it.role.shortLabel}**: ${it.headline.ifBlank { it.summary.take(120) }}")
                }
            }.trim()
            CouncilSectionId.RESEARCH_FINDINGS -> dossier.toPromptContext()
            CouncilSectionId.EXECUTION_PLAN -> roleText(CouncilRole.PLANNER)
            CouncilSectionId.COST_RESOURCES -> roleText(CouncilRole.COST_ANALYST)
            CouncilSectionId.RISKS_CRITIQUE -> buildString {
                appendLine(roleText(CouncilRole.CRITIC))
                appendLine()
                appendLine(roleText(CouncilRole.REALIST))
            }.trim()
            CouncilSectionId.ALTERNATIVES -> roleText(CouncilRole.ALTERNATIVE)
        }
    }

    private fun inferVerdictFromOpinions(opinions: List<CouncilAgentOpinion>): CouncilVerdict {
        val criticTag = opinions.firstOrNull { it.role == CouncilRole.CRITIC }?.verdictTag.orEmpty().uppercase()
        return when {
            "REJECT" in criticTag -> CouncilVerdict.RECONSIDER
            "RISK" in criticTag || "CHANGE" in criticTag || "WARN" in criticTag -> CouncilVerdict.MODIFY
            opinions.count { it.status == CouncilAgentStatus.COMPLETED } == 0 -> CouncilVerdict.RECONSIDER
            else -> CouncilVerdict.GO
        }
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        runCatching { return JSONObject(trimmed) }
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fenced = fenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrBlank()) {
            runCatching { return JSONObject(fenced) }
        }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val slice = trimmed.substring(firstBrace, lastBrace + 1)
            runCatching { return JSONObject(slice) }
        }
        return null
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }

    private fun defaultWebQueries(objective: String, inspection: ProjectInspection): List<String> {
        val core = objective.trim().take(80)
        return listOf(
            "$core ${inspection.stackLabel} architecture best practices",
            "$core open source library comparison",
        )
    }

    private fun defaultGitHubQueries(objective: String, inspection: ProjectInspection): List<String> {
        val keywords = objective.trim()
            .replace(Regex("[^a-zA-Z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .take(5)
            .joinToString(" ")
            .ifBlank { inspection.stackLabel }
        return listOf(keywords)
    }

    private fun inferLanguageFromStack(inspection: ProjectInspection): String? = when (inspection.stack) {
        com.example.domain.model.ProjectStack.GRADLE_JVM -> "Kotlin"
        com.example.domain.model.ProjectStack.NODE_JS -> "TypeScript"
        com.example.domain.model.ProjectStack.PYTHON -> "Python"
        com.example.domain.model.ProjectStack.RUST -> "Rust"
        com.example.domain.model.ProjectStack.GO -> "Go"
        else -> null
    }
}
