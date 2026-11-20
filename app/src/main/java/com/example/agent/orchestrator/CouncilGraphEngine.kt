package com.example.agent.orchestrator

import com.example.core.common.AgtError
import com.example.data.gateway.GatewayRepository
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.search.SearchGatewayRepository
import com.example.domain.contracts.GatewayStreamEvent
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.CouncilAgentOpinion
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilDecomposition
import com.example.domain.model.CouncilProgressEvent
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRound
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.GitHubSearchMode
import com.example.domain.model.GitHubSearchRequest
import com.example.domain.model.GitHubSearchResultItem
import com.example.domain.model.ProjectInspection
import com.example.domain.model.ResearchDossier
import com.example.domain.model.SearchOutcome
import com.example.domain.model.WebSearchRequest
import com.example.domain.model.WebSearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

data class CouncilLlmResult(
    val text: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

fun interface CouncilLlmCaller {
    suspend fun complete(
        modelId: String,
        protocolMode: GatewayProtocolMode,
        systemPrompt: String,
        userPrompt: String,
    ): Result<CouncilLlmResult>
}

class GatewayCouncilLlmCaller(
    private val gatewayRepository: GatewayRepository,
) : CouncilLlmCaller {
    override suspend fun complete(
        modelId: String,
        protocolMode: GatewayProtocolMode,
        systemPrompt: String,
        userPrompt: String,
    ): Result<CouncilLlmResult> {
        // JhGatewayHttpClient filters out "system" role for ANTHROPIC_MESSAGES,
        // so embed system instructions in the user turn when Anthropic Messages mode is active.
        val messages = if (protocolMode == GatewayProtocolMode.ANTHROPIC_MESSAGES) {
            listOf("user" to "[Role Instructions]\n$systemPrompt\n\n[Task Context]\n$userPrompt")
        } else {
            listOf(
                "system" to systemPrompt,
                "user" to userPrompt,
            )
        }

        val textBuffer = StringBuilder()
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var streamError: AgtError? = null

        gatewayRepository.streamChat(
            modelId = modelId,
            messages = messages,
            protocolMode = protocolMode,
        ).collect { event ->
            when (event) {
                is GatewayStreamEvent.TextDelta -> textBuffer.append(event.text)
                is GatewayStreamEvent.Completed -> {
                    promptTokens = event.promptTokens
                    completionTokens = event.completionTokens
                }
                is GatewayStreamEvent.Failed -> {
                    streamError = event.error
                }
                is GatewayStreamEvent.ToolCallDelta -> Unit
            }
        }

        val err = streamError
        if (err != null) {
            return Result.failure(IllegalStateException(err.userMessage))
        }
        val output = textBuffer.toString().trim()
        if (output.isEmpty()) {
            return Result.failure(IllegalStateException("Gateway returned an empty response for $modelId."))
        }
        return Result.success(
            CouncilLlmResult(
                text = output,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
        )
    }
}

data class CouncilGraphRequest(
    val project: ProjectRef,
    val objective: String,
    val defaultModelId: String,
    val protocolMode: GatewayProtocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
    val roleModelOverrides: Map<CouncilRole, String> = emptyMap(),
    val enabledSpecialists: Set<CouncilRole> = CouncilRole.DEFAULT_SPECIALISTS,
    val inspection: ProjectInspection? = null,
    val roundNumber: Int = 1,
    val priorDossier: ResearchDossier? = null,
    val priorEditedSections: List<CouncilReportSection> = emptyList(),
    val userAmendmentNote: String? = null,
    val refreshSearch: Boolean = false,
    val maxTokenBudget: Int = 24_000,
)

/**
 * Pure-Kotlin LangGraph-inspired Multi-Agent Council Engine:
 * Decomposer -> Parallel Dual Research (API 2 Web + API 3 GitHub) ->
 * Parallel 5-Role Specialist Debate (Read-Only) -> Lead Synthesizer (6 Editable Sections).
 */
class CouncilGraphEngine(
    private val llmCaller: CouncilLlmCaller,
    private val searchGatewayRepository: SearchGatewayRepository,
    private val intelligenceRepository: DevelopmentIntelligenceRepository? = null,
    private val repoMapEngine: RepoMapEngine = intelligenceRepository?.repoMapEngine ?: RepoMapEngine(),
) {

    fun runCouncilRound(request: CouncilGraphRequest): Flow<CouncilProgressEvent> = channelFlow {
        val roundStartMs = System.currentTimeMillis()
        val isReCouncil = request.roundNumber > 1
        var totalPromptTokens = 0
        var totalCompletionTokens = 0

        fun modelFor(role: CouncilRole): String =
            request.roleModelOverrides[role]?.takeIf { it.isNotBlank() } ?: request.defaultModelId

        // 1. Repository Inspection + Compact Repo Map
        send(CouncilProgressEvent.StageStarted("CONTEXT", "Inspecting workspace & building RepoMap…"))
        val inspection = request.inspection ?: run {
            val raw = RepositoryInspector.inspect(
                projectId = request.project.id,
                projectRootPath = request.project.rootPath,
            ).getOrElse { err ->
                val reason = err.message ?: "Unable to inspect project workspace."
                send(
                    CouncilProgressEvent.Failed(
                        reason = reason,
                        error = AgtError.ProjectPathRejected(userMessage = reason),
                    )
                )
                return@channelFlow
            }
            val mapResult = intelligenceRepository?.getOrBuildRepoMap(
                inspection = raw,
                objectiveHint = request.objective,
            ) ?: repoMapEngine.buildFromInspection(
                inspection = raw,
                objectiveHint = request.objective,
            )
            raw.copy(repoMap = mapResult.renderedMap)
        }

        // 2. Task Decomposer Node
        val decomposerModel = modelFor(CouncilRole.DECOMPOSER)
        send(CouncilProgressEvent.StageStarted("DECOMPOSING", "Breaking task into search queries & analysis angles…"))
        val decomposerRes = llmCaller.complete(
            modelId = decomposerModel,
            protocolMode = request.protocolMode,
            systemPrompt = CouncilRolePrompts.buildDecomposerSystemPrompt(),
            userPrompt = CouncilRolePrompts.buildDecomposerUserPrompt(
                objective = request.objective,
                inspection = inspection,
                userAmendmentNote = request.userAmendmentNote,
            ),
        )
        val decomposition = decomposerRes.fold(
            onSuccess = { llmRes ->
                totalPromptTokens += llmRes.promptTokens ?: 0
                totalCompletionTokens += llmRes.completionTokens ?: 0
                CouncilRolePrompts.parseDecomposition(llmRes.text, request.objective, inspection)
            },
            onFailure = {
                CouncilRolePrompts.fallbackDecomposition(request.objective, inspection)
            },
        )
        send(CouncilProgressEvent.DecompositionReady(decomposition))

        // 3. Parallel Dual Research Node (or reuse prior dossier during fast Re-Council)
        val dossier: ResearchDossier = if (isReCouncil && request.priorDossier != null && !request.refreshSearch) {
            send(
                CouncilProgressEvent.ResearchProgress(
                    role = CouncilRole.WEB_RESEARCHER,
                    status = CouncilAgentStatus.SKIPPED,
                    detail = "Reusing Round 1 research dossier (quota preserved).",
                )
            )
            send(
                CouncilProgressEvent.ResearchProgress(
                    role = CouncilRole.GITHUB_RESEARCHER,
                    status = CouncilAgentStatus.SKIPPED,
                    detail = "Reusing Round 1 GitHub repositories.",
                )
            )
            request.priorDossier
        } else {
            send(CouncilProgressEvent.StageStarted("RESEARCHING", "Running parallel Web Search (API 2) & GitHub Search (API 3)…"))
            runDualResearch(
                decomposition = decomposition,
                onProgress = { role, status, detail ->
                    send(CouncilProgressEvent.ResearchProgress(role, status, detail))
                },
            )
        }
        send(CouncilProgressEvent.ResearchDossierReady(dossier))

        // 4. Parallel Specialist Council (5 Roles)
        val activeSpecialists = request.enabledSpecialists
            .filter { it.isSpecialistDebater }
            .ifEmpty { CouncilRole.DEFAULT_SPECIALISTS.toList() }

        send(
            CouncilProgressEvent.StageStarted(
                "COUNCIL_DELIBERATING",
                "Consulting ${activeSpecialists.size} council specialists in parallel…",
            )
        )

        val specialistUserPrompt = CouncilRolePrompts.buildSpecialistUserPrompt(
            objective = request.objective,
            decomposition = decomposition,
            inspection = inspection,
            dossier = dossier,
            priorEditedSections = request.priorEditedSections,
            userAmendmentNote = request.userAmendmentNote,
        )

        val opinions: List<CouncilAgentOpinion> = coroutineScope {
            activeSpecialists.map { role ->
                async {
                    val roleModel = modelFor(role)
                    send(CouncilProgressEvent.SpecialistStarted(role, roleModel))
                    val startNs = System.nanoTime()
                    val result = llmCaller.complete(
                        modelId = roleModel,
                        protocolMode = request.protocolMode,
                        systemPrompt = CouncilRolePrompts.buildSpecialistSystemPrompt(role, isReCouncil),
                        userPrompt = specialistUserPrompt,
                    )
                    val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                    val opinion = result.fold(
                        onSuccess = { llmRes ->
                            CouncilRolePrompts.parseSpecialistOpinion(
                                role = role,
                                modelId = roleModel,
                                rawText = llmRes.text,
                                promptTokens = llmRes.promptTokens,
                                completionTokens = llmRes.completionTokens,
                                durationMs = durationMs,
                            )
                        },
                        onFailure = { err ->
                            CouncilAgentOpinion(
                                role = role,
                                modelId = roleModel,
                                status = CouncilAgentStatus.FAILED,
                                headline = "${role.shortLabel} unavailable",
                                summary = "",
                                durationMs = durationMs,
                                errorMessage = err.message ?: "Specialist invocation failed.",
                            )
                        },
                    )
                    send(CouncilProgressEvent.SpecialistCompleted(opinion))
                    opinion
                }
            }.awaitAll()
        }

        opinions.forEach { op ->
            totalPromptTokens += op.promptTokens ?: 0
            totalCompletionTokens += op.completionTokens ?: 0
        }

        if (opinions.none { it.status == CouncilAgentStatus.COMPLETED }) {
            val firstErr = opinions.firstNotNullOfOrNull { it.errorMessage }
                ?: "All council specialist calls failed."
            send(
                CouncilProgressEvent.Failed(
                    reason = "Council deliberation failed: $firstErr",
                    error = AgtError.NetworkUnavailable(userMessage = firstErr),
                )
            )
            return@channelFlow
        }

        // 5. Lead Judge / Synthesizer Node
        val synthModel = modelFor(CouncilRole.SYNTHESIZER)
        send(CouncilProgressEvent.StageStarted("SYNTHESIZING", "Synthesizing specialist reports into 6 editable sections…"))
        send(CouncilProgressEvent.SynthesisStarted(synthModel))

        val synthRes = llmCaller.complete(
            modelId = synthModel,
            protocolMode = request.protocolMode,
            systemPrompt = CouncilRolePrompts.buildSynthesizerSystemPrompt(isReCouncil),
            userPrompt = CouncilRolePrompts.buildSynthesizerUserPrompt(
                objective = request.objective,
                decomposition = decomposition,
                dossier = dossier,
                opinions = opinions,
                priorEditedSections = request.priorEditedSections,
                userAmendmentNote = request.userAmendmentNote,
            ).take((request.maxTokenBudget.coerceIn(4_000, 128_000) * 2).coerceAtMost(28_000)),
        )

        val (verdict, sections) = synthRes.fold(
            onSuccess = { llmRes ->
                totalPromptTokens += llmRes.promptTokens ?: 0
                totalCompletionTokens += llmRes.completionTokens ?: 0
                CouncilRolePrompts.parseSynthesis(
                    rawText = llmRes.text,
                    objective = request.objective,
                    dossier = dossier,
                    opinions = opinions,
                )
            },
            onFailure = {
                CouncilRolePrompts.fallbackSynthesis(
                    objective = request.objective,
                    dossier = dossier,
                    opinions = opinions,
                )
            },
        )

        val totalDurationMs = System.currentTimeMillis() - roundStartMs
        val round = CouncilRound(
            roundNumber = request.roundNumber,
            createdAtEpochMs = System.currentTimeMillis(),
            userAmendmentNote = request.userAmendmentNote,
            refreshedSearch = request.refreshSearch,
            decomposition = decomposition,
            dossier = dossier,
            opinions = opinions,
            verdict = verdict,
            sections = sections,
            totalPromptTokens = totalPromptTokens,
            totalCompletionTokens = totalCompletionTokens,
            durationMs = totalDurationMs,
        )
        send(CouncilProgressEvent.RoundCompleted(round))
    }.flowOn(Dispatchers.IO)

    private suspend fun runDualResearch(
        decomposition: CouncilDecomposition,
        onProgress: suspend (CouncilRole, CouncilAgentStatus, String) -> Unit,
    ): ResearchDossier = coroutineScope {
        val notices = mutableListOf<String>()

        val webJob = async {
            val queries = decomposition.webQueries.take(2)
            if (queries.isEmpty()) {
                onProgress(CouncilRole.WEB_RESEARCHER, CouncilAgentStatus.SKIPPED, "No web queries generated.")
                return@async Triple(emptyList<WebSearchResultItem>(), null as String?, emptyList<String>())
            }
            onProgress(CouncilRole.WEB_RESEARCHER, CouncilAgentStatus.RUNNING, "Searching ${queries.size} web query(s)…")
            val collected = LinkedHashMap<String, WebSearchResultItem>()
            val localNotices = mutableListOf<String>()
            var providerUsed: String? = null
            var anySuccess = false

            queries.map { q ->
                async { searchGatewayRepository.executeWebSearch(WebSearchRequest(query = q, maxResults = 4)) }
            }.awaitAll().forEach { outcome ->
                when (outcome) {
                    is SearchOutcome.Success -> {
                        anySuccess = true
                        if (providerUsed == null) providerUsed = outcome.data.providerUsed
                        outcome.data.results.forEach { item ->
                            val key = item.url.ifBlank { item.title }
                            collected.putIfAbsent(key, item)
                        }
                        outcome.notice?.let(localNotices::add)
                    }
                    is SearchOutcome.Degraded -> {
                        localNotices += outcome.reason
                    }
                }
            }
            val status = if (anySuccess) CouncilAgentStatus.COMPLETED else CouncilAgentStatus.SKIPPED
            val summary = if (anySuccess) {
                "Found ${collected.size} web result(s)${providerUsed?.let { " ($it)" }.orEmpty()}"
            } else {
                localNotices.firstOrNull() ?: "Web search unavailable"
            }
            onProgress(CouncilRole.WEB_RESEARCHER, status, summary)
            Triple(collected.values.toList(), providerUsed, localNotices)
        }

        val githubJob = async {
            val queries = decomposition.githubQueries.take(2)
            if (queries.isEmpty()) {
                onProgress(CouncilRole.GITHUB_RESEARCHER, CouncilAgentStatus.SKIPPED, "No GitHub queries generated.")
                return@async Triple(emptyList<GitHubSearchResultItem>(), false, emptyList<String>())
            }
            onProgress(CouncilRole.GITHUB_RESEARCHER, CouncilAgentStatus.RUNNING, "Searching ${queries.size} GitHub query(s)…")
            val collected = LinkedHashMap<String, GitHubSearchResultItem>()
            val localNotices = mutableListOf<String>()
            var fallbackUsed = false
            var anySuccess = false

            queries.map { q ->
                async {
                    searchGatewayRepository.executeGitHubSearch(
                        GitHubSearchRequest(
                            query = q,
                            mode = GitHubSearchMode.REPOSITORIES,
                            language = decomposition.preferredLanguage,
                            maxResults = 4,
                            includeReadme = true,
                        )
                    )
                }
            }.awaitAll().forEach { outcome ->
                when (outcome) {
                    is SearchOutcome.Success -> {
                        anySuccess = true
                        if (outcome.fallbackUsed) fallbackUsed = true
                        outcome.data.results.forEach { repo ->
                            collected.putIfAbsent(repo.fullName, repo)
                        }
                        outcome.notice?.let(localNotices::add)
                    }
                    is SearchOutcome.Degraded -> {
                        localNotices += outcome.reason
                    }
                }
            }
            val status = if (anySuccess) CouncilAgentStatus.COMPLETED else CouncilAgentStatus.SKIPPED
            val summary = if (anySuccess) {
                "Found ${collected.size} GitHub repo(s)${if (fallbackUsed) " (public fallback)" else ""}"
            } else {
                localNotices.firstOrNull() ?: "GitHub search unavailable"
            }
            onProgress(CouncilRole.GITHUB_RESEARCHER, status, summary)
            Triple(collected.values.toList(), fallbackUsed, localNotices)
        }

        val (webItems, providerUsed, webNotices) = webJob.await()
        val (githubItems, githubFallbackUsed, githubNotices) = githubJob.await()
        notices.addAll(webNotices)
        notices.addAll(githubNotices)

        CouncilRolePrompts.formatResearchDossier(
            webResults = webItems,
            githubResults = githubItems,
            notices = notices,
            webProviderUsed = providerUsed,
            githubFallbackUsed = githubFallbackUsed,
        )
    }
}
