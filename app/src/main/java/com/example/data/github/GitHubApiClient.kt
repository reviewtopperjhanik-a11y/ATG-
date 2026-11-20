package com.example.data.github

import com.example.core.common.AgtError
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import com.example.domain.model.GitHubDeviceCodePrompt
import com.example.domain.model.GitHubPullRequestResult
import com.example.domain.model.GitHubRepoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubUserProfile(
    val login: String,
    val displayName: String?,
    val scopes: List<String>,
)

data class GitHubOAuthTokenResponse(
    val accessToken: String,
    val scopes: List<String>,
    val tokenType: String,
)

sealed interface GitHubDevicePollResult {
    data class Authorized(val tokenResponse: GitHubOAuthTokenResponse) : GitHubDevicePollResult
    data class Pending(val retryAfterSeconds: Int) : GitHubDevicePollResult
    data class Failed(val error: AgtError) : GitHubDevicePollResult
}

sealed interface GitRemoteUrlValidation {
    data class Valid(val cleanCloneUrl: String, val isLoopback: Boolean) : GitRemoteUrlValidation
    data class Invalid(val reason: String) : GitRemoteUrlValidation
}

class GitHubApiClient(
    private val httpClient: OkHttpClient = defaultOkHttpClient(),
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val oauthBaseUrl: String = DEFAULT_OAUTH_BASE_URL,
) {
    fun getNormalizedOAuthBaseUrl(): Result<String> = validateBaseUrl(oauthBaseUrl)
    fun getNormalizedApiBaseUrl(): Result<String> = validateBaseUrl(apiBaseUrl)

    suspend fun requestDeviceCode(
        clientId: String,
        scopes: List<String>,
    ): Result<GitHubDeviceCodePrompt> = withContext(Dispatchers.IO) {
        val base = getNormalizedOAuthBaseUrl().getOrElse { return@withContext Result.failure(it) }
        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("scope", scopes.joinToString(" "))
            .build()
        val request = Request.Builder()
            .url("$base/login/device/code")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching {
                        val errJson = JSONObject(rawBody)
                        errJson.optString("error_description")
                            .ifBlank { errJson.optString("error") }
                    }.getOrNull().orEmpty()
                    val msg = if (detail.contains("device", ignoreCase = true) || resp.code == 400 || resp.code == 403) {
                        "GitHub Device Flow request failed (HTTP ${resp.code}): ${detail.ifBlank { "Ensure 'Enable Device Flow' is checked in your GitHub OAuth App settings." }}"
                    } else {
                        "GitHub Device Flow request failed (HTTP ${resp.code})."
                    }
                    throw GitHubApiException(
                        AgtError.NetworkUnavailable(
                            userMessage = msg,
                            diagnosticDetail = rawBody.take(240),
                        )
                    )
                }
                val json = JSONObject(rawBody)
                val err = json.optString("error").trim()
                if (err.isNotBlank()) {
                    val desc = json.optString("error_description").ifBlank { err }
                    val friendly = if (err == "device_flow_disabled") {
                        "Device Flow is not enabled for this GitHub OAuth App. Enable 'Device Flow' in GitHub App settings or use a Personal Access Token."
                    } else {
                        "GitHub Device Flow error: $desc"
                    }
                    throw GitHubApiException(
                        AgtError.GatewayUnauthorized(
                            userMessage = friendly,
                            diagnosticDetail = err,
                        )
                    )
                }
                val deviceCode = json.optString("device_code").trim()
                val userCode = json.optString("user_code").trim()
                val verificationUri = json.optString("verification_uri").trim()
                    .ifBlank { "$base/login/device" }
                val verificationUriComplete = json.optString("verification_uri_complete").trim()
                    .ifBlank { null }
                if (deviceCode.isEmpty() || userCode.isEmpty()) {
                    throw GitHubApiException(
                        AgtError.GatewayUnauthorized("GitHub did not return a valid device verification code.")
                    )
                }
                GitHubDeviceCodePrompt(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    verificationUriComplete = verificationUriComplete,
                    expiresInSeconds = json.optInt("expires_in", 900).coerceAtLeast(30),
                    intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
                    scopes = scopes,
                )
            }
        }
    }

    suspend fun pollDeviceAccessToken(
        clientId: String,
        deviceCode: String,
        currentIntervalSeconds: Int,
    ): GitHubDevicePollResult = withContext(Dispatchers.IO) {
        val base = getNormalizedOAuthBaseUrl().getOrElse { ex ->
            return@withContext GitHubDevicePollResult.Failed(
                AgtError.NetworkUnavailable(ex.message ?: "Invalid GitHub OAuth URL.")
            )
        }
        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("device_code", deviceCode.trim())
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val request = Request.Builder()
            .url("$base/login/oauth/access_token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@runCatching GitHubDevicePollResult.Failed(
                        AgtError.NetworkUnavailable(
                            userMessage = "GitHub Device Flow polling failed (HTTP ${resp.code}).",
                            diagnosticDetail = rawBody.take(240),
                        )
                    )
                }
                val json = JSONObject(rawBody)
                val accessToken = json.optString("access_token").trim()
                if (accessToken.isNotEmpty()) {
                    val scopes = parseScopeString(json.optString("scope"))
                    return@runCatching GitHubDevicePollResult.Authorized(
                        GitHubOAuthTokenResponse(
                            accessToken = accessToken,
                            scopes = scopes,
                            tokenType = json.optString("token_type", "bearer"),
                        )
                    )
                }
                when (val err = json.optString("error").trim()) {
                    "authorization_pending" -> GitHubDevicePollResult.Pending(currentIntervalSeconds.coerceAtLeast(1))
                    "slow_down" -> {
                        val nextInterval = json.optInt("interval", currentIntervalSeconds + 5)
                            .coerceAtLeast(currentIntervalSeconds + 5)
                        GitHubDevicePollResult.Pending(nextInterval)
                    }
                    "expired_token" -> GitHubDevicePollResult.Failed(
                        AgtError.GatewayUnauthorized("GitHub verification code expired. Start again to get a new code.")
                    )
                    "access_denied" -> GitHubDevicePollResult.Failed(
                        AgtError.GatewayUnauthorized("GitHub authorization was cancelled or denied.")
                    )
                    "incorrect_client_credentials" -> GitHubDevicePollResult.Failed(
                        AgtError.GatewayUnauthorized("GitHub rejected the Client ID. Verify your OAuth App Client ID or use a Personal Access Token.")
                    )
                    else -> {
                        val desc = json.optString("error_description").ifBlank { err.ifBlank { "Unknown GitHub authorization error." } }
                        GitHubDevicePollResult.Failed(
                            AgtError.GatewayUnauthorized(userMessage = desc, diagnosticDetail = err)
                        )
                    }
                }
            }
        }.getOrElse { ex ->
            GitHubDevicePollResult.Failed(
                AgtError.NetworkUnavailable(ex.message ?: "Network error polling GitHub authorization.")
            )
        }
    }

    suspend fun exchangeOAuthCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<GitHubOAuthTokenResponse> = withContext(Dispatchers.IO) {
        val base = getNormalizedOAuthBaseUrl().getOrElse { return@withContext Result.failure(it) }
        val form = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("code", code.trim())
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url("$base/login/oauth/access_token")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw GitHubApiException(
                        AgtError.NetworkUnavailable(
                            userMessage = "GitHub OAuth token exchange failed (HTTP ${resp.code}).",
                            diagnosticDetail = rawBody.take(240),
                        )
                    )
                }
                val json = JSONObject(rawBody)
                val err = json.optString("error")
                if (err.isNotBlank()) {
                    val desc = json.optString("error_description").ifBlank { err }
                    throw GitHubApiException(
                        AgtError.GatewayUnauthorized(
                            userMessage = "GitHub authorization error: $desc",
                            diagnosticDetail = err,
                        )
                    )
                }
                val accessToken = json.optString("access_token").trim()
                if (accessToken.isEmpty()) {
                    throw GitHubApiException(
                        AgtError.GatewayUnauthorized("GitHub OAuth did not return an access token.")
                    )
                }
                val scopes = parseScopeString(json.optString("scope"))
                GitHubOAuthTokenResponse(
                    accessToken = accessToken,
                    scopes = scopes,
                    tokenType = json.optString("token_type", "bearer"),
                )
            }
        }
    }

    suspend fun fetchAuthenticatedUser(token: String): Result<GitHubUserProfile> =
        withContext(Dispatchers.IO) {
            val base = getNormalizedApiBaseUrl().getOrElse { return@withContext Result.failure(it) }
            val cleanToken = token.trim()
            if (cleanToken.isEmpty()) {
                return@withContext Result.failure(
                    GitHubApiException(AgtError.GatewayUnauthorized("GitHub token is empty."))
                )
            }
            val request = Request.Builder()
                .url("$base/user")
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $cleanToken")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (resp.code == 401 || resp.code == 403) {
                        throw GitHubApiException(
                            AgtError.GatewayUnauthorized(
                                userMessage = extractApiErrorMessage(rawBody, cleanToken)
                                    ?: "GitHub rejected credentials (HTTP ${resp.code}).",
                                diagnosticDetail = "HTTP ${resp.code}",
                            )
                        )
                    }
                    if (!resp.isSuccessful) {
                        throw GitHubApiException(
                            AgtError.NetworkUnavailable(
                                userMessage = extractApiErrorMessage(rawBody, cleanToken)
                                    ?: "GitHub user verification failed (HTTP ${resp.code}).",
                                diagnosticDetail = "HTTP ${resp.code}",
                            )
                        )
                    }
                    val json = JSONObject(rawBody)
                    val login = json.optString("login").trim()
                    if (login.isEmpty()) {
                        throw GitHubApiException(
                            AgtError.GatewayUnauthorized("GitHub /user response did not include a username.")
                        )
                    }
                    val displayName = json.optString("name").trim().ifBlank { null }
                    val headerScopes = parseScopeString(resp.header("X-OAuth-Scopes").orEmpty())
                    GitHubUserProfile(
                        login = login,
                        displayName = displayName,
                        scopes = headerScopes,
                    )
                }
            }.recoverCatching { throwable ->
                if (throwable is GitHubApiException) throw throwable
                if (throwable is IOException) {
                    throw GitHubApiException(
                        AgtError.NetworkUnavailable(
                            userMessage = "Unable to reach GitHub: ${SecretRedactor.redact(throwable.message.orEmpty(), listOf(cleanToken))}"
                        )
                    )
                }
                throw throwable
            }
        }

    suspend fun listRepositories(token: String): Result<List<GitHubRepoSummary>> =
        withContext(Dispatchers.IO) {
            val base = getNormalizedApiBaseUrl().getOrElse { return@withContext Result.failure(it) }
            val cleanToken = token.trim()
            val request = Request.Builder()
                .url("$base/user/repos?sort=updated&per_page=50&affiliation=owner,collaborator,organization_member")
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $cleanToken")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            runCatching {
                httpClient.newCall(request).execute().use { resp ->
                    val rawBody = resp.body?.string().orEmpty()
                    if (resp.code == 401 || resp.code == 403) {
                        throw GitHubApiException(
                            AgtError.GatewayUnauthorized(
                                extractApiErrorMessage(rawBody, cleanToken)
                                    ?: "GitHub token lacks permission to list repositories (HTTP ${resp.code})."
                            )
                        )
                    }
                    if (!resp.isSuccessful) {
                        throw GitHubApiException(
                            AgtError.NetworkUnavailable(
                                extractApiErrorMessage(rawBody, cleanToken)
                                    ?: "Failed to list repositories (HTTP ${resp.code})."
                            )
                        )
                    }
                    val arr = JSONArray(rawBody)
                    buildList {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val fullName = obj.optString("full_name").trim()
                            val name = obj.optString("name").trim()
                            val cloneUrl = obj.optString("clone_url").trim()
                            if (fullName.isEmpty() || name.isEmpty() || cloneUrl.isEmpty()) continue
                            val ownerLogin = obj.optJSONObject("owner")?.optString("login")?.trim()
                                ?: fullName.substringBefore('/')
                            add(
                                GitHubRepoSummary(
                                    id = obj.optLong("id"),
                                    name = name,
                                    fullName = fullName,
                                    ownerLogin = ownerLogin,
                                    description = obj.optString("description").trim()
                                        .takeIf { it.isNotEmpty() && it != "null" },
                                    cloneUrl = cloneUrl,
                                    htmlUrl = obj.optString("html_url").trim().ifBlank { cloneUrl },
                                    defaultBranch = obj.optString("default_branch", "main").trim().ifBlank { "main" },
                                    isPrivate = obj.optBoolean("private", false),
                                    updatedAtIso = obj.optString("updated_at", ""),
                                )
                            )
                        }
                    }
                }
            }
        }

    suspend fun createPullRequest(
        token: String,
        owner: String,
        repo: String,
        title: String,
        body: String,
        headBranch: String,
        baseBranch: String,
    ): Result<GitHubPullRequestResult> = withContext(Dispatchers.IO) {
        val base = getNormalizedApiBaseUrl().getOrElse { return@withContext Result.failure(it) }
        val cleanToken = token.trim()
        val payload = JSONObject()
            .put("title", title.trim())
            .put("body", body.trim())
            .put("head", headBranch.trim())
            .put("base", baseBranch.trim())
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$base/repos/${owner.trim()}/${repo.trim()}/pulls")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $cleanToken")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
            .post(payload)
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) {
                    throw GitHubApiException(
                        AgtError.GatewayUnauthorized(
                            extractApiErrorMessage(rawBody, cleanToken)
                                ?: "GitHub rejected pull request creation (HTTP ${resp.code})."
                        )
                    )
                }
                if (!resp.isSuccessful) {
                    throw GitHubApiException(
                        AgtError.NetworkUnavailable(
                            extractApiErrorMessage(rawBody, cleanToken)
                                ?: "Pull request creation failed (HTTP ${resp.code})."
                        )
                    )
                }
                val obj = JSONObject(rawBody)
                GitHubPullRequestResult(
                    number = obj.optInt("number"),
                    title = obj.optString("title", title),
                    htmlUrl = obj.optString("html_url"),
                    state = obj.optString("state", "open"),
                    headBranch = headBranch,
                    baseBranch = baseBranch,
                )
            }
        }
    }

    private fun validateBaseUrl(rawUrl: String): Result<String> {
        return when (val v = GatewayUrlValidator.validate(rawUrl)) {
            is GatewayUrlValidation.Valid -> Result.success(v.normalizedBaseUrl)
            is GatewayUrlValidation.Invalid -> Result.failure(IllegalArgumentException(v.reason))
        }
    }

    private fun extractApiErrorMessage(rawBody: String, secretToRedact: String): String? {
        val msg = runCatching {
            val obj = JSONObject(rawBody)
            val baseMsg = obj.optString("message").trim()
            val errs = obj.optJSONArray("errors")
            val detail = if (errs != null && errs.length() > 0) {
                errs.optJSONObject(0)?.optString("message")?.trim().orEmpty()
            } else {
                ""
            }
            listOf(baseMsg, detail).filter { it.isNotBlank() }.joinToString(": ").ifBlank { null }
        }.getOrNull() ?: return null
        return SecretRedactor.redact(msg, listOf(secretToRedact))
    }

    class GitHubApiException(val agtError: AgtError) : IllegalStateException(agtError.userMessage)

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.github.com"
        const val DEFAULT_OAUTH_BASE_URL = "https://github.com"
        const val OAUTH_REDIRECT_URI = "agtcode://oauth/github"
        private const val API_VERSION = "2022-11-28"
        private const val USER_AGENT = "AGT-Code-Android/1.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(GatewayUrlValidator.ssrfGuardDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()

        fun parseScopeString(raw: String): List<String> =
            raw.split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        fun validateGitRemoteUrl(rawUrl: String): GitRemoteUrlValidation {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return GitRemoteUrlValidation.Invalid("Repository URL cannot be empty.")
            }
            val parsed = trimmed.toHttpUrlOrNull()
                ?: return GitRemoteUrlValidation.Invalid("Enter a valid HTTPS repository URL (e.g. https://github.com/owner/repo.git).")
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                return GitRemoteUrlValidation.Invalid("Do not embed credentials inside repository URLs.")
            }
            val host = parsed.host.lowercase()
            val isLoopback = host in LOOPBACK_HOSTS
            if (parsed.scheme == "http" && !isLoopback) {
                return GitRemoteUrlValidation.Invalid("Remote repository URLs must use HTTPS.")
            }
            return GitRemoteUrlValidation.Valid(
                cleanCloneUrl = parsed.toString(),
                isLoopback = isLoopback,
            )
        }

        fun stripUrlCredentials(rawUrl: String): String {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return rawUrl.trim()
            return parsed.newBuilder().username("").password("").build().toString()
        }

        fun parseGitHubOwnerRepo(remoteUrl: String): Pair<String, String>? {
            val parsed = remoteUrl.trim().toHttpUrlOrNull() ?: return null
            val host = parsed.host.lowercase()
            if (host != "github.com" && host != "www.github.com" && host !in LOOPBACK_HOSTS) {
                return null
            }
            val segments = parsed.pathSegments.filter { it.isNotBlank() }
            if (segments.size < 2) return null
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            if (owner.isBlank() || repo.isBlank()) return null
            return owner to repo
        }
    }
}
