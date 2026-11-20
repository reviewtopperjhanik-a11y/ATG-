package com.example.data.search

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.data.gateway.GatewayEndpointConfig
import com.example.data.gateway.JhGatewayHttpClient
import com.example.domain.contracts.GitHubSearchClient
import com.example.domain.contracts.WebSearchClient
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GitHubSearchMode
import com.example.domain.model.GitHubSearchRequest
import com.example.domain.model.GitHubSearchResponse
import com.example.domain.model.GitHubSearchResultItem
import com.example.domain.model.SearchServiceHealth
import com.example.domain.model.SearchServiceKind
import com.example.domain.model.WebSearchRequest
import com.example.domain.model.WebSearchResponse
import com.example.domain.model.WebSearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class SearchGatewayApiException(val agtError: AgtError) : Exception(agtError.userMessage)

internal object SearchGatewayHttpSupport {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun resolveServiceBaseUrl(rawUrl: String, servicePathSuffix: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(
                SearchGatewayApiException(
                    AgtError.PermissionDenied(userMessage = "Endpoint URL is not configured.")
                )
            )
        }
        return when (val validation = GatewayUrlValidator.validate(trimmed)) {
            is GatewayUrlValidation.Invalid -> Result.failure(
                SearchGatewayApiException(
                    AgtError.PermissionDenied(userMessage = validation.reason)
                )
            )
            is GatewayUrlValidation.Valid -> {
                val normalized = validation.normalizedBaseUrl
                    .removeSuffix("/health")
                    .removeSuffix(servicePathSuffix)
                    .trimEnd('/')
                Result.success(normalized)
            }
        }
    }

    fun buildGetRequest(url: String, token: String?): Request {
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${token.trim()}")
        }
        return builder.build()
    }

    fun buildPostJsonRequest(url: String, body: JSONObject, token: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${token.trim()}")
        }
        return builder.build()
    }

    fun mapHttpError(code: Int, rawBody: String, token: String?, serviceLabel: String): AgtError {
        val rawDetail = runCatching {
            val obj = JSONObject(rawBody)
            obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: obj.optString("message").ifBlank { null }
        }.getOrNull() ?: rawBody.take(240)
        val safeDetail = SecretRedactor.redact(rawDetail, listOfNotNull(token)).ifBlank { null }

        return when (code) {
            401 -> AgtError.GatewayUnauthorized(
                userMessage = "$serviceLabel unauthorized (401). Check your scoped bearer token.",
                diagnosticDetail = safeDetail,
            )
            403 -> AgtError.PermissionDenied(
                userMessage = "$serviceLabel scope or permission denied (403).",
                diagnosticDetail = safeDetail,
            )
            404, 405, 501 -> AgtError.UnsupportedCapability(
                userMessage = "$serviceLabel endpoint unsupported (HTTP $code).",
                diagnosticDetail = safeDetail,
            )
            429 -> AgtError.NetworkUnavailable(
                userMessage = "$serviceLabel rate-limited (429). All rotated keys are temporarily throttled.",
                diagnosticDetail = safeDetail,
            )
            else -> AgtError.NetworkUnavailable(
                userMessage = "$serviceLabel returned HTTP $code.",
                diagnosticDetail = safeDetail,
            )
        }
    }

    fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}

/**
 * HTTP client for JH GetWay Pro API 2: Rotated Web Search Gateway
 * (`GET /v1/search/web/health`, `POST /v1/search/web`).
 */
class GatewayWebSearchClient(
    private val configProvider: suspend () -> GatewayEndpointConfig,
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
) : WebSearchClient {

    override suspend fun health(): SearchServiceHealth = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val now = System.currentTimeMillis()
        if (cfg.baseUrl.isBlank()) {
            return@withContext SearchServiceHealth(
                service = SearchServiceKind.WEB_SEARCH,
                status = GatewayHealthStatus.UNCONFIGURED,
                lastCheckedEpochMs = now,
                statusMessage = "Web Search URL not configured",
            )
        }
        val baseUrl = SearchGatewayHttpSupport.resolveServiceBaseUrl(cfg.baseUrl, SERVICE_PATH)
            .getOrElse { err ->
                val agtErr = (err as? SearchGatewayApiException)?.agtError
                    ?: AgtError.PermissionDenied(err.message ?: "Invalid Web Search URL.")
                return@withContext SearchServiceHealth(
                    service = SearchServiceKind.WEB_SEARCH,
                    status = GatewayHealthStatus.ERROR,
                    lastCheckedEpochMs = now,
                    statusMessage = agtErr.userMessage,
                    error = agtErr,
                )
            }

        val req = SearchGatewayHttpSupport.buildGetRequest(
            url = "$baseUrl$HEALTH_PATH",
            token = cfg.bearerToken,
        )
        val startNs = System.nanoTime()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val mapped = SearchGatewayHttpSupport.mapHttpError(
                        code = resp.code,
                        rawBody = rawBody,
                        token = cfg.bearerToken,
                        serviceLabel = "Web Search API",
                    )
                    return@withContext SearchServiceHealth(
                        service = SearchServiceKind.WEB_SEARCH,
                        status = GatewayHealthStatus.ERROR,
                        latencyMs = latencyMs,
                        lastCheckedEpochMs = now,
                        statusMessage = mapped.userMessage,
                        error = mapped,
                    )
                }

                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                val providers = SearchGatewayHttpSupport.parseStringArray(json?.optJSONArray("active_providers"))
                val keysCount = json?.optInt("available_keys_count", -1)?.takeIf { it >= 0 }
                val cacheEnabled = if (json?.has("cache_enabled") == true) json.optBoolean("cache_enabled") else null
                val summaryBits = buildList {
                    add("Connected · ${latencyMs}ms")
                    if (providers.isNotEmpty()) add("Providers: ${providers.joinToString(", ")}")
                    if (keysCount != null) add("$keysCount key(s)")
                    if (cacheEnabled == true) add("Cache on")
                }
                SearchServiceHealth(
                    service = SearchServiceKind.WEB_SEARCH,
                    status = GatewayHealthStatus.CONNECTED,
                    latencyMs = latencyMs,
                    lastCheckedEpochMs = now,
                    lastSuccessfulEpochMs = now,
                    statusMessage = summaryBits.joinToString(" · "),
                    activeProviders = providers,
                    availableKeysCount = keysCount,
                    cacheEnabled = cacheEnabled,
                )
            }
        } catch (io: IOException) {
            val safeMsg = SecretRedactor.redact(
                io.message ?: "Unable to reach Web Search API.",
                listOfNotNull(cfg.bearerToken),
            )
            SearchServiceHealth(
                service = SearchServiceKind.WEB_SEARCH,
                status = GatewayHealthStatus.ERROR,
                lastCheckedEpochMs = now,
                statusMessage = safeMsg,
                error = AgtError.NetworkUnavailable(userMessage = safeMsg, diagnosticDetail = safeMsg),
            )
        }
    }

    override suspend fun search(request: WebSearchRequest): Result<WebSearchResponse> = withContext(Dispatchers.IO) {
        val trimmedQuery = request.query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.failure(
                SearchGatewayApiException(AgtError.PermissionDenied("Web search query cannot be empty."))
            )
        }
        val cfg = configProvider()
        val baseUrl = SearchGatewayHttpSupport.resolveServiceBaseUrl(cfg.baseUrl, SERVICE_PATH)
            .getOrElse { return@withContext Result.failure(it) }

        val payload = JSONObject()
            .put("query", trimmedQuery)
            .put("max_results", request.maxResults.coerceIn(1, 20))
            .put("search_depth", request.searchDepth)
            .put("include_content", request.includeContent)
            .put("freshness_days", request.freshnessDays.coerceAtLeast(1))

        val httpReq = SearchGatewayHttpSupport.buildPostJsonRequest(
            url = "$baseUrl$SERVICE_PATH",
            body = payload,
            token = cfg.bearerToken,
        )

        try {
            val startNs = System.nanoTime()
            okHttpClient.newCall(httpReq).execute().use { resp ->
                val measuredLatency = (System.nanoTime() - startNs) / 1_000_000L
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val mapped = SearchGatewayHttpSupport.mapHttpError(
                        code = resp.code,
                        rawBody = rawBody,
                        token = cfg.bearerToken,
                        serviceLabel = "Web Search API",
                    )
                    return@withContext Result.failure(SearchGatewayApiException(mapped))
                }
                val root = JSONObject(rawBody)
                val resultsArr = root.optJSONArray("results") ?: JSONArray()
                val items = buildList(resultsArr.length()) {
                    for (i in 0 until resultsArr.length()) {
                        val item = resultsArr.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val url = item.optString("url").trim()
                        if (title.isEmpty() && url.isEmpty()) continue
                        add(
                            WebSearchResultItem(
                                title = title.ifBlank { url },
                                url = url,
                                snippet = item.optString("snippet").trim(),
                                content = item.optString("content").trim().take(MAX_CONTENT_EXCERPT_CHARS),
                                publishedDate = item.optString("published_date").trim().ifBlank { null },
                                score = if (item.has("score") && !item.isNull("score")) item.optDouble("score") else null,
                            )
                        )
                    }
                }
                Result.success(
                    WebSearchResponse(
                        query = root.optString("query").ifBlank { trimmedQuery },
                        providerUsed = root.optString("provider_used").ifBlank { "gateway" },
                        cached = root.optBoolean("cached", false),
                        latencyMs = root.optLong("latency_ms", measuredLatency),
                        results = items,
                    )
                )
            }
        } catch (e: Exception) {
            val safeMsg = SecretRedactor.redact(
                e.message ?: "Web search request failed.",
                listOfNotNull(cfg.bearerToken),
            )
            Result.failure(
                SearchGatewayApiException(
                    AgtError.NetworkUnavailable(userMessage = safeMsg, diagnosticDetail = safeMsg)
                )
            )
        }
    }

    companion object {
        const val SERVICE_PATH = "/v1/search/web"
        const val HEALTH_PATH = "/v1/search/web/health"
        private const val MAX_CONTENT_EXCERPT_CHARS = 1800
    }
}

/**
 * HTTP client for JH GetWay Pro API 3: Rotated GitHub Search Gateway
 * (`GET /v1/search/github/health`, `POST /v1/search/github`).
 */
class GatewayGitHubSearchClient(
    private val configProvider: suspend () -> GatewayEndpointConfig,
    private val okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
) : GitHubSearchClient {

    override suspend fun health(): SearchServiceHealth = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        val now = System.currentTimeMillis()
        if (cfg.baseUrl.isBlank()) {
            return@withContext SearchServiceHealth(
                service = SearchServiceKind.GITHUB_SEARCH,
                status = GatewayHealthStatus.UNCONFIGURED,
                lastCheckedEpochMs = now,
                statusMessage = "GitHub Search Gateway URL not set (public fallback available)",
            )
        }
        val baseUrl = SearchGatewayHttpSupport.resolveServiceBaseUrl(cfg.baseUrl, SERVICE_PATH)
            .getOrElse { err ->
                val agtErr = (err as? SearchGatewayApiException)?.agtError
                    ?: AgtError.PermissionDenied(err.message ?: "Invalid GitHub Search URL.")
                return@withContext SearchServiceHealth(
                    service = SearchServiceKind.GITHUB_SEARCH,
                    status = GatewayHealthStatus.ERROR,
                    lastCheckedEpochMs = now,
                    statusMessage = agtErr.userMessage,
                    error = agtErr,
                )
            }

        val req = SearchGatewayHttpSupport.buildGetRequest(
            url = "$baseUrl$HEALTH_PATH",
            token = cfg.bearerToken,
        )
        val startNs = System.nanoTime()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val mapped = SearchGatewayHttpSupport.mapHttpError(
                        code = resp.code,
                        rawBody = rawBody,
                        token = cfg.bearerToken,
                        serviceLabel = "GitHub Search API",
                    )
                    return@withContext SearchServiceHealth(
                        service = SearchServiceKind.GITHUB_SEARCH,
                        status = GatewayHealthStatus.ERROR,
                        latencyMs = latencyMs,
                        lastCheckedEpochMs = now,
                        statusMessage = mapped.userMessage,
                        error = mapped,
                    )
                }

                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                val poolSize = json?.optInt("pat_pool_size", -1)?.takeIf { it >= 0 }
                val healthyTokens = json?.optInt("healthy_tokens", -1)?.takeIf { it >= 0 }
                val cacheEnabled = if (json?.has("cache_enabled") == true) json.optBoolean("cache_enabled") else null
                val summaryBits = buildList {
                    add("Connected · ${latencyMs}ms")
                    if (healthyTokens != null && poolSize != null) {
                        add("$healthyTokens/$poolSize PATs healthy")
                    } else if (poolSize != null) {
                        add("$poolSize PAT(s)")
                    }
                    if (cacheEnabled == true) add("Cache on")
                }
                SearchServiceHealth(
                    service = SearchServiceKind.GITHUB_SEARCH,
                    status = GatewayHealthStatus.CONNECTED,
                    latencyMs = latencyMs,
                    lastCheckedEpochMs = now,
                    lastSuccessfulEpochMs = now,
                    statusMessage = summaryBits.joinToString(" · "),
                    availableKeysCount = healthyTokens ?: poolSize,
                    cacheEnabled = cacheEnabled,
                )
            }
        } catch (io: IOException) {
            val safeMsg = SecretRedactor.redact(
                io.message ?: "Unable to reach GitHub Search API.",
                listOfNotNull(cfg.bearerToken),
            )
            SearchServiceHealth(
                service = SearchServiceKind.GITHUB_SEARCH,
                status = GatewayHealthStatus.ERROR,
                lastCheckedEpochMs = now,
                statusMessage = safeMsg,
                error = AgtError.NetworkUnavailable(userMessage = safeMsg, diagnosticDetail = safeMsg),
            )
        }
    }

    override suspend fun search(request: GitHubSearchRequest): Result<GitHubSearchResponse> = withContext(Dispatchers.IO) {
        val trimmedQuery = request.query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.failure(
                SearchGatewayApiException(AgtError.PermissionDenied("GitHub search query cannot be empty."))
            )
        }
        val cfg = configProvider()
        val baseUrl = SearchGatewayHttpSupport.resolveServiceBaseUrl(cfg.baseUrl, SERVICE_PATH)
            .getOrElse { return@withContext Result.failure(it) }

        val payload = JSONObject()
            .put("query", trimmedQuery)
            .put("mode", request.mode.wireValue)
            .put("sort", request.sort)
            .put("max_results", request.maxResults.coerceIn(1, 20))
            .put("include_readme", request.includeReadme)
        if (!request.language.isNullOrBlank()) {
            payload.put("language", request.language.trim())
        }

        val httpReq = SearchGatewayHttpSupport.buildPostJsonRequest(
            url = "$baseUrl$SERVICE_PATH",
            body = payload,
            token = cfg.bearerToken,
        )

        try {
            val startNs = System.nanoTime()
            okHttpClient.newCall(httpReq).execute().use { resp ->
                val measuredLatency = (System.nanoTime() - startNs) / 1_000_000L
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val mapped = SearchGatewayHttpSupport.mapHttpError(
                        code = resp.code,
                        rawBody = rawBody,
                        token = cfg.bearerToken,
                        serviceLabel = "GitHub Search API",
                    )
                    return@withContext Result.failure(SearchGatewayApiException(mapped))
                }
                val root = JSONObject(rawBody)
                val resultsArr = root.optJSONArray("results") ?: JSONArray()
                val items = buildList(resultsArr.length()) {
                    for (i in 0 until resultsArr.length()) {
                        val item = resultsArr.optJSONObject(i) ?: continue
                        val fullName = item.optString("full_name").trim()
                        val htmlUrl = item.optString("html_url").trim()
                        if (fullName.isEmpty() && htmlUrl.isEmpty()) continue
                        add(
                            GitHubSearchResultItem(
                                fullName = fullName.ifBlank { htmlUrl },
                                htmlUrl = htmlUrl,
                                description = item.optString("description").trim(),
                                stars = item.optInt("stars", 0),
                                forks = item.optInt("forks", 0),
                                language = item.optString("language").trim().ifBlank { null },
                                license = item.optString("license").trim().ifBlank { null },
                                updatedAt = item.optString("updated_at").trim().ifBlank { null },
                                topics = SearchGatewayHttpSupport.parseStringArray(item.optJSONArray("topics")),
                                readmeExcerpt = item.optString("readme_excerpt").trim().take(MAX_README_EXCERPT_CHARS),
                            )
                        )
                    }
                }
                val parsedMode = if (root.optString("mode").equals("code", ignoreCase = true)) {
                    GitHubSearchMode.CODE
                } else {
                    request.mode
                }
                Result.success(
                    GitHubSearchResponse(
                        query = root.optString("query").ifBlank { trimmedQuery },
                        mode = parsedMode,
                        cached = root.optBoolean("cached", false),
                        latencyMs = root.optLong("latency_ms", measuredLatency),
                        totalCount = root.optInt("total_count", items.size),
                        results = items,
                    )
                )
            }
        } catch (e: Exception) {
            val safeMsg = SecretRedactor.redact(
                e.message ?: "GitHub search request failed.",
                listOfNotNull(cfg.bearerToken),
            )
            Result.failure(
                SearchGatewayApiException(
                    AgtError.NetworkUnavailable(userMessage = safeMsg, diagnosticDetail = safeMsg)
                )
            )
        }
    }

    companion object {
        const val SERVICE_PATH = "/v1/search/github"
        const val HEALTH_PATH = "/v1/search/github/health"
        private const val MAX_README_EXCERPT_CHARS = 2200
    }
}
