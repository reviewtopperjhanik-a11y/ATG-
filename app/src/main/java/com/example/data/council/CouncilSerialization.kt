package com.example.data.council

import com.example.core.security.SecretRedactor
import com.example.data.database.CouncilRoundEntity
import com.example.data.database.CouncilSessionEntity
import com.example.domain.model.CouncilAgentOpinion
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilDecomposition
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilSession
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.GitHubSearchResultItem
import com.example.domain.model.ResearchDossier
import com.example.domain.model.WebSearchResultItem
import org.json.JSONArray
import org.json.JSONObject

internal object CouncilSerialization {

    fun toSessionEntity(
        session: CouncilSession,
        secrets: Collection<String>,
    ): CouncilSessionEntity = CouncilSessionEntity(
        sessionId = session.sessionId,
        projectId = session.projectId,
        taskId = session.taskId,
        objective = SecretRedactor.redact(session.objective, secrets),
        mode = session.mode.name,
        currentRoundNumber = session.currentRoundNumber,
        approvedForCoding = session.approvedForCoding,
        createdAtEpochMs = session.createdAtEpochMs,
        updatedAtEpochMs = session.updatedAtEpochMs,
    )

    fun toRoundEntity(
        sessionId: String,
        round: CouncilRound,
        secrets: Collection<String>,
    ): CouncilRoundEntity = CouncilRoundEntity(
        id = "${sessionId}_r${round.roundNumber}",
        sessionId = sessionId,
        roundNumber = round.roundNumber,
        userAmendmentNote = round.userAmendmentNote?.let { SecretRedactor.redact(it, secrets) },
        refreshedSearch = round.refreshedSearch,
        decompositionJson = encodeDecomposition(round.decomposition, secrets),
        dossierJson = encodeDossier(round.dossier, secrets),
        opinionsJson = encodeOpinions(round.opinions, secrets),
        verdict = round.verdict.name,
        sectionsJson = encodeSections(round.sections, secrets),
        totalPromptTokens = round.totalPromptTokens,
        totalCompletionTokens = round.totalCompletionTokens,
        durationMs = round.durationMs,
        createdAtEpochMs = round.createdAtEpochMs,
    )

    fun toDomainSession(
        entity: CouncilSessionEntity,
        rounds: List<CouncilRoundEntity>,
    ): CouncilSession = CouncilSession(
        sessionId = entity.sessionId,
        projectId = entity.projectId,
        taskId = entity.taskId,
        objective = entity.objective,
        mode = runCatching { CouncilExecutionMode.valueOf(entity.mode) }
            .getOrDefault(CouncilExecutionMode.COUNCIL_AND_CODE),
        currentRoundNumber = entity.currentRoundNumber,
        rounds = rounds.mapNotNull { toDomainRound(it) }.sortedBy { it.roundNumber },
        approvedForCoding = entity.approvedForCoding,
        createdAtEpochMs = entity.createdAtEpochMs,
        updatedAtEpochMs = entity.updatedAtEpochMs,
    )

    fun toDomainRound(entity: CouncilRoundEntity): CouncilRound? = runCatching {
        CouncilRound(
            roundNumber = entity.roundNumber,
            createdAtEpochMs = entity.createdAtEpochMs,
            userAmendmentNote = entity.userAmendmentNote,
            refreshedSearch = entity.refreshedSearch,
            decomposition = decodeDecomposition(entity.decompositionJson),
            dossier = decodeDossier(entity.dossierJson),
            opinions = decodeOpinions(entity.opinionsJson),
            verdict = runCatching { CouncilVerdict.valueOf(entity.verdict) }
                .getOrDefault(CouncilVerdict.MODIFY),
            sections = decodeSections(entity.sectionsJson),
            totalPromptTokens = entity.totalPromptTokens,
            totalCompletionTokens = entity.totalCompletionTokens,
            durationMs = entity.durationMs,
        )
    }.getOrNull()

    fun encodeDecomposition(
        d: CouncilDecomposition,
        secrets: Collection<String>,
    ): String = JSONObject()
        .put("refinedObjective", SecretRedactor.redact(d.refinedObjective, secrets))
        .put("subTasks", stringListToJson(d.subTasks, secrets))
        .put("webQueries", stringListToJson(d.webQueries, secrets))
        .put("githubQueries", stringListToJson(d.githubQueries, secrets))
        .put("preferredLanguage", d.preferredLanguage ?: "")
        .put("rationale", SecretRedactor.redact(d.rationale, secrets))
        .toString()

    fun decodeDecomposition(raw: String): CouncilDecomposition {
        if (raw.isBlank()) return CouncilDecomposition(refinedObjective = "")
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return CouncilDecomposition(refinedObjective = "")
        return CouncilDecomposition(
            refinedObjective = obj.optString("refinedObjective"),
            subTasks = jsonToStringList(obj.optJSONArray("subTasks")),
            webQueries = jsonToStringList(obj.optJSONArray("webQueries")),
            githubQueries = jsonToStringList(obj.optJSONArray("githubQueries")),
            preferredLanguage = obj.optString("preferredLanguage").ifBlank { null },
            rationale = obj.optString("rationale"),
        )
    }

    fun encodeDossier(
        dossier: ResearchDossier,
        secrets: Collection<String>,
    ): String {
        val webArr = JSONArray()
        dossier.webFindings.forEach { w ->
            webArr.put(
                JSONObject()
                    .put("title", SecretRedactor.redact(w.title, secrets))
                    .put("url", SecretRedactor.redact(w.url, secrets))
                    .put("snippet", SecretRedactor.redact(w.snippet, secrets))
                    .put("content", SecretRedactor.redact(w.content, secrets))
                    .put("publishedDate", w.publishedDate ?: "")
                    .put("score", w.score ?: JSONObject.NULL)
            )
        }
        val ghArr = JSONArray()
        dossier.githubFindings.forEach { g ->
            ghArr.put(
                JSONObject()
                    .put("fullName", SecretRedactor.redact(g.fullName, secrets))
                    .put("htmlUrl", SecretRedactor.redact(g.htmlUrl, secrets))
                    .put("description", SecretRedactor.redact(g.description, secrets))
                    .put("stars", g.stars)
                    .put("forks", g.forks)
                    .put("language", g.language ?: "")
                    .put("license", g.license ?: "")
                    .put("updatedAt", g.updatedAt ?: "")
                    .put("topics", stringListToJson(g.topics, secrets))
                    .put("readmeExcerpt", SecretRedactor.redact(g.readmeExcerpt, secrets))
            )
        }
        return JSONObject()
            .put("webFindings", webArr)
            .put("githubFindings", ghArr)
            .put("webSummary", SecretRedactor.redact(dossier.webSummary, secrets))
            .put("githubSummary", SecretRedactor.redact(dossier.githubSummary, secrets))
            .put("notices", stringListToJson(dossier.notices, secrets))
            .put("webProviderUsed", dossier.webProviderUsed ?: "")
            .put("githubFallbackUsed", dossier.githubFallbackUsed)
            .toString()
    }

    fun decodeDossier(raw: String): ResearchDossier {
        if (raw.isBlank()) return ResearchDossier()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ResearchDossier()
        val webArr = obj.optJSONArray("webFindings") ?: JSONArray()
        val webFindings = (0 until webArr.length()).mapNotNull { i ->
            val item = webArr.optJSONObject(i) ?: return@mapNotNull null
            WebSearchResultItem(
                title = item.optString("title"),
                url = item.optString("url"),
                snippet = item.optString("snippet"),
                content = item.optString("content"),
                publishedDate = item.optString("publishedDate").ifBlank { null },
                score = if (item.has("score") && !item.isNull("score")) item.optDouble("score") else null,
            )
        }
        val ghArr = obj.optJSONArray("githubFindings") ?: JSONArray()
        val githubFindings = (0 until ghArr.length()).mapNotNull { i ->
            val item = ghArr.optJSONObject(i) ?: return@mapNotNull null
            GitHubSearchResultItem(
                fullName = item.optString("fullName"),
                htmlUrl = item.optString("htmlUrl"),
                description = item.optString("description"),
                stars = item.optInt("stars", 0),
                forks = item.optInt("forks", 0),
                language = item.optString("language").ifBlank { null },
                license = item.optString("license").ifBlank { null },
                updatedAt = item.optString("updatedAt").ifBlank { null },
                topics = jsonToStringList(item.optJSONArray("topics")),
                readmeExcerpt = item.optString("readmeExcerpt"),
            )
        }
        return ResearchDossier(
            webFindings = webFindings,
            githubFindings = githubFindings,
            webSummary = obj.optString("webSummary"),
            githubSummary = obj.optString("githubSummary"),
            notices = jsonToStringList(obj.optJSONArray("notices")),
            webProviderUsed = obj.optString("webProviderUsed").ifBlank { null },
            githubFallbackUsed = obj.optBoolean("githubFallbackUsed", false),
        )
    }

    fun encodeOpinions(
        opinions: List<CouncilAgentOpinion>,
        secrets: Collection<String>,
    ): String {
        val arr = JSONArray()
        opinions.forEach { op ->
            arr.put(
                JSONObject()
                    .put("role", op.role.id)
                    .put("modelId", op.modelId)
                    .put("status", op.status.name)
                    .put("headline", SecretRedactor.redact(op.headline, secrets))
                    .put("summary", SecretRedactor.redact(op.summary, secrets))
                    .put("bulletPoints", stringListToJson(op.bulletPoints, secrets))
                    .put("verdictTag", op.verdictTag ?: "")
                    .put("rawOutput", SecretRedactor.redact(op.rawOutput.take(2400), secrets))
                    .put("promptTokens", op.promptTokens ?: JSONObject.NULL)
                    .put("completionTokens", op.completionTokens ?: JSONObject.NULL)
                    .put("durationMs", op.durationMs)
                    .put("errorMessage", op.errorMessage?.let { SecretRedactor.redact(it, secrets) } ?: "")
            )
        }
        return arr.toString()
    }

    fun decodeOpinions(raw: String): List<CouncilAgentOpinion> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val role = CouncilRole.fromId(obj.optString("role")) ?: return@mapNotNull null
            val status = runCatching { CouncilAgentStatus.valueOf(obj.optString("status")) }
                .getOrDefault(CouncilAgentStatus.COMPLETED)
            CouncilAgentOpinion(
                role = role,
                modelId = obj.optString("modelId"),
                status = status,
                headline = obj.optString("headline"),
                summary = obj.optString("summary"),
                bulletPoints = jsonToStringList(obj.optJSONArray("bulletPoints")),
                verdictTag = obj.optString("verdictTag").ifBlank { null },
                rawOutput = obj.optString("rawOutput"),
                promptTokens = if (obj.has("promptTokens") && !obj.isNull("promptTokens")) obj.optInt("promptTokens") else null,
                completionTokens = if (obj.has("completionTokens") && !obj.isNull("completionTokens")) obj.optInt("completionTokens") else null,
                durationMs = obj.optLong("durationMs", 0L),
                errorMessage = obj.optString("errorMessage").ifBlank { null },
            )
        }
    }

    fun encodeSections(
        sections: List<CouncilReportSection>,
        secrets: Collection<String>,
    ): String {
        val arr = JSONArray()
        sections.forEach { sec ->
            arr.put(
                JSONObject()
                    .put("sectionId", sec.sectionId.id)
                    .put("title", SecretRedactor.redact(sec.title, secrets))
                    .put("originalContent", SecretRedactor.redact(sec.originalContent, secrets))
                    .put("editedContent", SecretRedactor.redact(sec.editedContent, secrets))
                    .put("isModifiedByUser", sec.isModifiedByUser)
            )
        }
        return arr.toString()
    }

    fun decodeSections(raw: String): List<CouncilReportSection> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val sectionId = CouncilSectionId.fromId(obj.optString("sectionId")) ?: return@mapNotNull null
            val original = obj.optString("originalContent")
            val edited = obj.optString("editedContent", original)
            CouncilReportSection(
                sectionId = sectionId,
                title = obj.optString("title").ifBlank { sectionId.title },
                originalContent = original,
                editedContent = edited,
                isModifiedByUser = obj.optBoolean("isModifiedByUser", false),
            )
        }
    }

    private fun stringListToJson(items: List<String>, secrets: Collection<String>): JSONArray {
        val arr = JSONArray()
        items.forEach { arr.put(SecretRedactor.redact(it, secrets)) }
        return arr
    }

    private fun jsonToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }
}
