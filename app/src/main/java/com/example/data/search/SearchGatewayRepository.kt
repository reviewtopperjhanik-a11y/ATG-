package com.example.data.search

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SearchCredentialVault
import com.example.core.security.SecretRedactor
import com.example.data.gateway.GatewayEndpointConfig
import com.example.data.gateway.JhGatewayHttpClient
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.contracts.GitHubSearchClient
import com.example.domain.contracts.WebSearchClient
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GitHubSearchMode
import com.example.domain.model.GitHubSearchRequest
import com.example.domain.model.GitHubSearchResponse
import com.example.domain.model.GitHubSearchResultItem
import com.example.domain.model.SearchOutcome
import com.example.domain.model.SearchServiceHealth
import com.example.domain.model.SearchServiceKind
import com.example.domain.model.WebSearchRequest
import com.example.domain.model.WebSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates API 2 (Web Search Gateway) and API 3 (GitHub Search Gateway) configuration,
 * Keystore-backed credentials, health checks, and graceful fallback search execution.
 */
class SearchGatewayRepository(
    private val preferencesRepository: AppPreferencesRepository,
    private val searchCredentialVault: SearchCredentialVault,
    private val gitHubCredentialVault: GitHubCredentialVault? = null,
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
    private val webSearchClientOverride: WebSearchClient? = null,
    private val githubSearchClientOverride: GitHubSearchClient? = null,
    private val publicGitHubApiBaseUrl: String = DEFAULT_PUBLIC_GITHUB_API_BASE_URL,
) {
    private val webSearchClient: WebSearchClient = webSearchClientOverride ?: GatewayWebSearchClient(
        configProvider = {
            val prefs = preferencesRepository.preferences.first()
            val token = searchCredentialVault.readWebSearchToken().getOrNull()
            GatewayEndpointConfig(
                baseUrl = prefs.webSearchBaseUrl,
                bearerToken = token,
            )
        },
        okHttpClient = okHttpClient,
    )

    private val githubSearchClient: GitHubSearchClient = githubSearchClientOverride ?: GatewayGitHubSearchClient(
        configProvider = {
            val prefs = preferencesRepository.preferences.first()
            val token = searchCredentialVault.readGitHubSearchToken().getOrNull()
            GatewayEndpointConfig(
                baseUrl = prefs.githubSearchBaseUrl,
                bearerToken = token,
            )
        },
        okHttpClient = okHttpClient,
    )

    val hasWebSearchToken: StateFlow<Boolean> = searchCredentialVault.hasWebSearchToken
    val hasGitHubSearchToken: StateFlow<Boolean> = searchCredentialVault.hasGitHubSearchToken

    private val _webSearchHealth = MutableStateFlow(
        SearchServiceHealth(service = SearchServiceKind.WEB_SEARCH)
    )
    val webSearchHealth: StateFlow<SearchServiceHealth> = _webSearchHealth.asStateFlow()

    private val _githubSearchHealth = MutableStateFlow(
        SearchServiceHealth(
            service = SearchServiceKind.GITHUB_SEARCH,
            statusMessage = "Not configured (public GitHub fallback available)",
        )
    )
    val githubSearchHealth: StateFlow<SearchServiceHealth> = _githubSearchHealth.asStateFlow()

    suspend fun saveWebSearchConfig(baseUrl: String, newRawToken: String?): Result<Unit> {
        val trimmedUrl = baseUrl.trim()
        if (trimmedUrl.isNotEmpty()) {
            val validation = GatewayUrlValidator.validate(trimmedUrl)
            if (validation is GatewayUrlValidation.Invalid) {
                return Result.failure(IllegalArgumentException(validation.reason))
            }
            val clean = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
                .removeSuffix("/health")
                .removeSuffix(GatewayWebSearchClient.SERVICE_PATH)
                .trimEnd('/')
            preferencesRepository.setWebSearchBaseUrl(clean)
        } else {
            preferencesRepository.setWebSearchBaseUrl("")
        }
        if (!newRawToken.isNullOrBlank()) {
            val saveToken = searchCredentialVault.saveWebSearchToken(newRawToken)
            if (saveToken.isFailure) return saveToken
        }
        return Result.success(Unit)
    }

    suspend fun clearWebSearchToken() {
        searchCredentialVault.clearWebSearchToken()
        _webSearchHealth.value = SearchServiceHealth(
            service = SearchServiceKind.WEB_SEARCH,
            status = GatewayHealthStatus.UNCONFIGURED,
            statusMessage = "Web Search token cleared",
        )
    }

    suspend fun saveGitHubSearchConfig(baseUrl: String, newRawToken: String?): Result<Unit> {
        val trimmedUrl = baseUrl.trim()
        if (trimmedUrl.isNotEmpty()) {
            val validation = GatewayUrlValidator.validate(trimmedUrl)
            if (validation is GatewayUrlValidation.Invalid) {
                return Result.failure(IllegalArgumentException(validation.reason))
            }
            val clean = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
                .removeSuffix("/health")
                .removeSuffix(GatewayGitHubSearchClient.SERVICE_PATH)
                .trimEnd('/')
            preferencesRepository.setGitHubSearchBaseUrl(clean)
        } else {
            preferencesRepository.setGitHubSearchBaseUrl("")
        }
        if (!newRawToken.isNullOrBlank()) {
            val saveToken = searchCredentialVault.saveGitHubSearchToken(newRawToken)
            if (saveToken.isFailure) return saveToken
        }
        return Result.success(Unit)
    }

    suspend fun clearGitHubSearchToken() {
        searchCredentialVault.clearGitHubSearchToken()
        _githubSearchHealth.value = SearchServiceHealth(
            service = SearchServiceKind.GITHUB_SEARCH,
            status = GatewayHealthStatus.UNCONFIGURED,
            statusMessage = "GitHub Search token cleared (public fallback available)",
        )
    }

    suspend fun testWebSearchHealth(): SearchServiceHealth {
        _webSearchHealth.update {
            it.copy(status = GatewayHealthStatus.CHECKING, statusMessage = "Checking Web Search API…", error = null)
        }
        val result = webSearchClient.health()
        val prevSuccess = _webSearchHealth.value.lastSuccessfulEpochMs
        val merged = if (result.lastSuccessfulEpochMs == null && prevSuccess != null) {
            result.copy(lastSuccessfulEpochMs = prevSuccess)
        } else {
            result
        }
        _webSearchHealth.value = merged
        return merged
    }

    suspend fun testGitHubSearchHealth(): SearchServiceHealth {
        _githubSearchHealth.update {
            it.copy(status = GatewayHealthStatus.CHECKING, statusMessage = "Checking GitHub Search API…", error = null)
        }
        val result = githubSearchClient.health()
        val prevSuccess = _githubSearchHealth.value.lastSuccessfulEpochMs
        val merged = if (result.lastSuccessfulEpochMs == null && prevSuccess != null) {
            result.copy(lastSuccessfulEpochMs = prevSuccess)
        } else {
            result
        }
        _githubSearchHealth.value = merged
        return merged
    }

    /**
     * Executes API 2 Web Search with graceful degradation so callers (e.g. CouncilGraphEngine)
     * never crash if Web Search is unconfigured, offline, or temporarily rate-limited.
     */
    suspend fun executeWebSearch(request: WebSearchRequest): SearchOutcome<WebSearchResponse> {
        val prefs = preferencesRepository.preferences.first()
        if (prefs.webSearchBaseUrl.isBlank() && webSearchClientOverride == null) {
            return SearchOutcome.Degraded(
                reason = "Web Search API is not configured in Settings → Gateway. Skipping live web search.",
            )
        }
        return webSearchClient.search(request).fold(
            onSuccess = { SearchOutcome.Success(it) },
            onFailure = { err ->
                val agtErr = (err as? SearchGatewayApiException)?.agtError
                SearchOutcome.Degraded(
                    reason = agtErr?.userMessage ?: err.message ?: "Web search unavailable.",
                    error = agtErr,
                )
            },
        )
    }

    /**
     * Executes API 3 GitHub Search via JH GetWay Pro when configured, and falls back gracefully
     * to direct public/authenticated GitHub REST repository search if API 3 is unconfigured.
     */
    suspend fun executeGitHubSearch(
        request: GitHubSearchRequest,
        allowPublicFallback: Boolean = true,
    ): SearchOutcome<GitHubSearchResponse> {
        val prefs = preferencesRepository.preferences.first()
        val hasGatewayConfigured = prefs.githubSearchBaseUrl.isNotBlank() || githubSearchClientOverride != null

        if (hasGatewayConfigured) {
            val gatewayRes = githubSearchClient.search(request)
            if (gatewayRes.isSuccess) {
                return SearchOutcome.Success(gatewayRes.getOrThrow())
            }
            val err = gatewayRes.exceptionOrNull()
            val agtErr = (err as? SearchGatewayApiException)?.agtError
            if (!allowPublicFallback) {
                return SearchOutcome.Degraded(
                    reason = agtErr?.userMessage ?: err?.message ?: "GitHub search unavailable.",
                    error = agtErr,
                )
            }
            val fallback = executeDirectGitHubFallback(request)
            return if (fallback != null) {
                SearchOutcome.Success(
                    data = fallback,
                    fallbackUsed = true,
                    notice = "GitHub Search Gateway unavailable (${agtErr?.userMessage ?: "error"}); used direct GitHub API fallback.",
                )
            } else {
                SearchOutcome.Degraded(
                    reason = agtErr?.userMessage ?: err?.message ?: "GitHub search unavailable.",
                    error = agtErr,
                )
            }
        }

        if (!allowPublicFallback) {
            return SearchOutcome.Degraded(
                reason = "GitHub Search API is not configured in Settings → Gateway.",
            )
        }

        val fallback = executeDirectGitHubFallback(request)
        return if (fallback != null) {
            SearchOutcome.Success(
                data = fallback,
                fallbackUsed = true,
                notice = "Used direct GitHub repository search fallback (configure API 3 for rotated PAT pool & README summaries).",
            )
        } else {
            SearchOutcome.Degraded(
                reason = "GitHub Search API is not configured and direct GitHub fallback could not reach api.github.com.",
            )
        }
    }

    private suspend fun executeDirectGitHubFallback(request: GitHubSearchRequest): GitHubSearchResponse? =
        withContext(Dispatchers.IO) {
            if (request.mode != GitHubSearchMode.REPOSITORIES) {
                // Unauthenticated /search/code is rejected by GitHub; require PAT or API 3 for code search.
                return@withContext null
            }
            val base = publicGitHubApiBaseUrl.trimEnd('/').toHttpUrlOrNull() ?: return@withContext null
            val q = buildString {
                append(request.query.trim())
                if (!request.language.isNullOrBlank()) {
                    append(" language:")
                    append(request.language.trim())
                }
            }
            val url = base.newBuilder()
                .addPathSegments("search/repositories")
                .addQueryParameter("q", q)
                .addQueryParameter("sort", request.sort.ifBlank { "stars" })
                .addQueryParameter("order", "desc")
                .addQueryParameter("per_page", request.maxResults.coerceIn(1, 10).toString())
                .build()

            val token = runCatching { gitHubCredentialVault?.readToken()?.getOrNull() }.getOrNull()
            val httpReq = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AGT-Code-Android")
                .apply {
                    if (!token.isNullOrBlank()) {
                        header("Authorization", "Bearer ${token.trim()}")
                    }
                }
                .get()
                .build()

            val startNs = System.nanoTime()
            runCatching {
                okHttpClient.newCall(httpReq).execute().use { resp ->
                    val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val safe = SecretRedactor.redact(body.take(200), listOfNotNull(token))
                        if (safe.isNotBlank()) return@use null
                        return@use null
                    }
                    val root = JSONObject(body)
                    val itemsArr = root.optJSONArray("items") ?: JSONArray()
                    val items = buildList(itemsArr.length()) {
                        for (i in 0 until itemsArr.length()) {
                            val obj = itemsArr.optJSONObject(i) ?: continue
                            val fullName = obj.optString("full_name").trim()
                            val htmlUrl = obj.optString("html_url").trim()
                            if (fullName.isEmpty() || htmlUrl.isEmpty()) continue
                            val licenseName = obj.optJSONObject("license")?.optString("spdx_id")
                                ?.takeIf { it.isNotBlank() && it != "NOASSERTION" }
                            add(
                                GitHubSearchResultItem(
                                    fullName = fullName,
                                    htmlUrl = htmlUrl,
                                    description = obj.optString("description").trim(),
                                    stars = obj.optInt("stargazers_count", 0),
                                    forks = obj.optInt("forks_count", 0),
                                    language = obj.optString("language").trim().ifBlank { null },
                                    license = licenseName,
                                    updatedAt = obj.optString("updated_at").trim().ifBlank { null },
                                    topics = SearchGatewayHttpSupport.parseStringArray(obj.optJSONArray("topics")),
                                    readmeExcerpt = "",
                                )
                            )
                        }
                    }
                    GitHubSearchResponse(
                        query = request.query.trim(),
                        mode = GitHubSearchMode.REPOSITORIES,
                        cached = false,
                        latencyMs = latencyMs,
                        totalCount = root.optInt("total_count", items.size),
                        results = items,
                    )
                }
            }.getOrNull()
        }

    companion object {
        const val DEFAULT_PUBLIC_GITHUB_API_BASE_URL = "https://api.github.com"
    }
}
