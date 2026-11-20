package com.example.data.github

import android.net.Uri
import android.util.Base64
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.core.config.ConfigSource
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SecretRedactor
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.GitHubAccountState
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubDeviceCodePrompt
import com.example.domain.model.GitHubOAuthRequest
import com.example.domain.model.GitHubPullRequestResult
import com.example.domain.model.GitHubRepoSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom

class GitHubRepository(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialVault: GitHubCredentialVault,
    private val apiClient: GitHubApiClient = GitHubApiClient(),
    private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private var devicePollJob: Job? = null

    private data class PendingOAuthSession(
        val state: String,
        val codeVerifier: String,
        val clientId: String,
        val requestedScopes: List<String>,
        val createdAtEpochMs: Long,
    )

    private var pendingSession: PendingOAuthSession? = null

    private val transientAuthStatus = MutableStateFlow<GitHubAuthStatus?>(null)
    private val transientAuthError = MutableStateFlow<String?>(null)

    private val _devicePrompt = MutableStateFlow<GitHubDeviceCodePrompt?>(null)
    val devicePrompt: StateFlow<GitHubDeviceCodePrompt?> = _devicePrompt.asStateFlow()

    private val _repositories = MutableStateFlow<List<GitHubRepoSummary>>(emptyList())
    val repositories: StateFlow<List<GitHubRepoSummary>> = _repositories.asStateFlow()

    private val _isLoadingRepos = MutableStateFlow(false)
    val isLoadingRepos: StateFlow<Boolean> = _isLoadingRepos.asStateFlow()

    private val _repoError = MutableStateFlow<String?>(null)
    val repoError: StateFlow<String?> = _repoError.asStateFlow()

    val accountState: StateFlow<GitHubAccountState> = combine(
        credentialVault.hasToken,
        preferencesRepository.preferences,
        transientAuthStatus,
        transientAuthError,
    ) { hasToken, prefs, overrideStatus, errorMsg ->
        val effectiveStatus = when {
            overrideStatus != null -> overrideStatus
            hasToken && !prefs.githubUsername.isNullOrBlank() -> GitHubAuthStatus.CONNECTED
            errorMsg != null -> GitHubAuthStatus.ERROR
            else -> GitHubAuthStatus.NOT_CONNECTED
        }
        GitHubAccountState(
            status = effectiveStatus,
            username = if (hasToken) prefs.githubUsername else null,
            scopes = if (hasToken) prefs.githubScopes else emptyList(),
            errorMessage = errorMsg,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = GitHubAccountState(),
    )

    suspend fun beginOAuthFlow(
        clientId: String? = null,
        includePrivateRepos: Boolean = true,
    ): Result<GitHubOAuthRequest> {
        val prefs = preferencesRepository.preferences.first()
        val cleanClientId = if (clientId != null) {
            val explicit = clientId.trim()
            if (explicit.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException(
                        "GitHub OAuth Client ID is not configured (AGT_GITHUB_OAUTH_CLIENT_ID)."
                    )
                )
            }
            explicit
        } else {
            when (val resolution = appConfig.resolveGitHubOAuthClientId(prefs.githubOAuthClientId)) {
                is ConfigResolution.MissingOrInvalid -> {
                    return Result.failure(IllegalStateException(resolution.userMessage))
                }
                is ConfigResolution.Configured -> {
                    if (resolution.source == ConfigSource.BUILD_CONFIG && prefs.githubOAuthClientId.isNotBlank()) {
                        // Clear stale legacy manual override when official build config Client ID is present.
                        preferencesRepository.setGitHubOAuthClientId("")
                    }
                    resolution.value
                }
            }
        }
        val oauthBase = apiClient.getNormalizedOAuthBaseUrl()
            .getOrElse { return Result.failure(it) }

        val requestedScopes = if (includePrivateRepos) {
            listOf("read:user", "repo")
        } else {
            listOf("read:user", "public_repo")
        }
        val verifier = generateRandomUrlSafeToken(32)
        val challenge = sha256Base64Url(verifier)
        val csrfState = generateRandomUrlSafeToken(24)

        pendingSession = PendingOAuthSession(
            state = csrfState,
            codeVerifier = verifier,
            clientId = cleanClientId,
            requestedScopes = requestedScopes,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        transientAuthError.value = null
        transientAuthStatus.value = GitHubAuthStatus.AUTHORIZING

        val authUrl = "$oauthBase/login/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", cleanClientId)
            .addQueryParameter("redirect_uri", GitHubApiClient.OAUTH_REDIRECT_URI)
            .addQueryParameter("scope", requestedScopes.joinToString(" "))
            .addQueryParameter("state", csrfState)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        return Result.success(
            GitHubOAuthRequest(
                authorizationUrl = authUrl,
                state = csrfState,
                redirectUri = GitHubApiClient.OAUTH_REDIRECT_URI,
                scopes = requestedScopes,
            )
        )
    }

    suspend fun handleOAuthCallback(uri: Uri): Result<GitHubAccountState> = withContext(Dispatchers.IO) {
        authMutex.withLock {
        if (uri.scheme != "agtcode" || uri.host != "oauth") {
            return@withContext Result.failure(IllegalArgumentException("Unsupported OAuth callback URI."))
        }

        val err = uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            val desc = uri.getQueryParameter("error_description").orEmpty().ifBlank { err }
            pendingSession = null
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "GitHub authorization declined: $desc"
            return@withLock Result.failure(IllegalStateException(transientAuthError.value))
        }

        val pending = pendingSession
        pendingSession = null
        if (pending == null) {
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "No active OAuth session. Start GitHub sign-in again."
            return@withLock Result.failure(IllegalStateException(transientAuthError.value))
        }

        val ageMs = System.currentTimeMillis() - pending.createdAtEpochMs
        if (ageMs > OAUTH_SESSION_TTL_MS) {
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "OAuth session expired. Start GitHub sign-in again."
            return@withLock Result.failure(IllegalStateException(transientAuthError.value))
        }

        val returnedState = uri.getQueryParameter("state").orEmpty()
        if (returnedState != pending.state) {
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "OAuth state mismatch (CSRF check failed)."
            return@withLock Result.failure(SecurityException(transientAuthError.value))
        }

        val code = uri.getQueryParameter("code").orEmpty().trim()
        if (code.isEmpty()) {
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "GitHub callback did not include an authorization code."
            return@withLock Result.failure(IllegalArgumentException(transientAuthError.value))
        }

        transientAuthStatus.value = GitHubAuthStatus.AUTHORIZING
        transientAuthError.value = null

        val tokenResp = apiClient.exchangeOAuthCode(
            clientId = pending.clientId,
            code = code,
            codeVerifier = pending.codeVerifier,
            redirectUri = GitHubApiClient.OAUTH_REDIRECT_URI,
        ).getOrElse { ex ->
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = ex.message ?: "OAuth token exchange failed."
            return@withLock Result.failure(ex)
        }

        return@withContext validateAndPersistToken(
            rawToken = tokenResp.accessToken,
            fallbackScopes = tokenResp.scopes.ifEmpty { pending.requestedScopes },
        )
        }
    }

    suspend fun beginDeviceFlow(
        clientId: String? = null,
        includePrivateRepos: Boolean = true,
        pollDelayOverrideMs: Long? = null,
    ): Result<GitHubDeviceCodePrompt> = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.preferences.first()
        val cleanClientId = resolveClientIdForFlow(clientId, prefs.githubOAuthClientId)
            .getOrElse { return@withContext Result.failure(it) }

        val requestedScopes = if (includePrivateRepos) {
            listOf("read:user", "repo")
        } else {
            listOf("read:user", "public_repo")
        }

        devicePollJob?.cancel()
        devicePollJob = null
        _devicePrompt.value = null
        transientAuthError.value = null
        transientAuthStatus.value = GitHubAuthStatus.AUTHORIZING

        val prompt = apiClient.requestDeviceCode(
            clientId = cleanClientId,
            scopes = requestedScopes,
        ).getOrElse { ex ->
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = ex.message ?: "Unable to start GitHub Device Flow."
            return@withContext Result.failure(ex)
        }

        _devicePrompt.value = prompt
        devicePollJob = scope.launch {
            var intervalSec = prompt.intervalSeconds.coerceAtLeast(1)
            val deadlineMs = System.currentTimeMillis() + prompt.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadlineMs) {
                val sleepMs = pollDelayOverrideMs ?: (intervalSec * 1000L)
                delay(sleepMs)
                if (!isActive) break
                when (val poll = apiClient.pollDeviceAccessToken(cleanClientId, prompt.deviceCode, intervalSec)) {
                    is GitHubDevicePollResult.Pending -> {
                        intervalSec = poll.retryAfterSeconds.coerceAtLeast(1)
                    }
                    is GitHubDevicePollResult.Authorized -> {
                        authMutex.withLock {
                            _devicePrompt.value = null
                            validateAndPersistToken(
                                rawToken = poll.tokenResponse.accessToken,
                                fallbackScopes = poll.tokenResponse.scopes.ifEmpty { prompt.scopes },
                            )
                        }
                        return@launch
                    }
                    is GitHubDevicePollResult.Failed -> {
                        _devicePrompt.value = null
                        transientAuthStatus.value = GitHubAuthStatus.ERROR
                        transientAuthError.value = poll.error.userMessage
                        return@launch
                    }
                }
            }
            if (isActive) {
                _devicePrompt.value = null
                transientAuthStatus.value = GitHubAuthStatus.ERROR
                transientAuthError.value = "GitHub verification code expired. Request a new code and try again."
            }
        }
        return@withContext Result.success(prompt)
    }

    private suspend fun resolveClientIdForFlow(
        explicitClientId: String?,
        savedPreferenceClientId: String,
    ): Result<String> {
        if (explicitClientId != null) {
            val trimmed = explicitClientId.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("Enter a GitHub OAuth App Client ID (e.g. Ov23li…) or use a Personal Access Token above.")
                )
            }
            when (val check = appConfig.validateClientIdFormat(trimmed)) {
                is ConfigResolution.MissingOrInvalid -> return Result.failure(IllegalArgumentException(check.userMessage))
                is ConfigResolution.Configured -> {
                    if (appConfig.githubOAuthClientId.isBlank() && trimmed != savedPreferenceClientId) {
                        preferencesRepository.setGitHubOAuthClientId(trimmed)
                    }
                    return Result.success(check.value)
                }
            }
        }
        return when (val resolution = appConfig.resolveGitHubOAuthClientId(savedPreferenceClientId)) {
            is ConfigResolution.MissingOrInvalid -> Result.failure(
                IllegalStateException("Enter a backup GitHub OAuth Client ID first, or connect using a Personal Access Token.")
            )
            is ConfigResolution.Configured -> {
                if (resolution.source == ConfigSource.BUILD_CONFIG && savedPreferenceClientId.isNotBlank()) {
                    preferencesRepository.setGitHubOAuthClientId("")
                }
                Result.success(resolution.value)
            }
        }
    }

    suspend fun connectWithToken(rawToken: String): Result<GitHubAccountState> = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val clean = rawToken.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (clean.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Enter a GitHub Personal Access Token."))
            }
            if (clean.any(Char::isWhitespace)) {
                return@withContext Result.failure(IllegalArgumentException("GitHub Personal Access Token cannot contain spaces or line breaks."))
            }
            devicePollJob?.cancel()
            devicePollJob = null
            _devicePrompt.value = null
            pendingSession = null
            transientAuthStatus.value = GitHubAuthStatus.AUTHORIZING
            transientAuthError.value = null
            return@withContext validateAndPersistToken(rawToken = clean, fallbackScopes = emptyList())
        }
    }

    private suspend fun validateAndPersistToken(
        rawToken: String,
        fallbackScopes: List<String>,
    ): Result<GitHubAccountState> {
        val profile = apiClient.fetchAuthenticatedUser(rawToken).getOrElse { ex ->
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = SecretRedactor.redact(
                ex.message ?: "GitHub credential validation failed.",
                listOf(rawToken),
            )
            return Result.failure(ex)
        }

        val effectiveScopes = profile.scopes.ifEmpty { fallbackScopes }
        credentialVault.saveToken(rawToken).getOrElse { ex ->
            transientAuthStatus.value = GitHubAuthStatus.ERROR
            transientAuthError.value = "Failed to encrypt GitHub token in Keystore."
            return Result.failure(ex)
        }
        preferencesRepository.setGitHubAccountMetadata(
            username = profile.login,
            scopes = effectiveScopes,
        )
        transientAuthStatus.value = null
        transientAuthError.value = null

        val connectedState = GitHubAccountState(
            status = GitHubAuthStatus.CONNECTED,
            username = profile.login,
            scopes = effectiveScopes,
            errorMessage = null,
        )
        refreshRepositoriesInternal(rawToken)
        return Result.success(connectedState)
    }

    suspend fun refreshRepositories(): Result<List<GitHubRepoSummary>> {
        val token = credentialVault.readToken().getOrNull()
        if (token.isNullOrBlank()) {
            _repoError.value = "Connect GitHub first to list your repositories."
            return Result.failure(IllegalStateException(_repoError.value))
        }
        return refreshRepositoriesInternal(token)
    }

    private suspend fun refreshRepositoriesInternal(token: String): Result<List<GitHubRepoSummary>> {
        _isLoadingRepos.value = true
        _repoError.value = null
        val res = apiClient.listRepositories(token)
        res.onSuccess { list ->
            _repositories.value = list
            _isLoadingRepos.value = false
        }.onFailure { err ->
            _repoError.value = SecretRedactor.redact(
                err.message ?: "Failed to load repositories.",
                listOf(token),
            )
            _isLoadingRepos.value = false
        }
        return res
    }

    suspend fun createPullRequest(
        remoteUrl: String,
        title: String,
        body: String,
        headBranch: String,
        baseBranch: String,
        userApproved: Boolean,
    ): Result<GitHubPullRequestResult> {
        val prefs = preferencesRepository.preferences.first()
        if (prefs.requirePushApproval && !userApproved) {
            return Result.failure(
                IllegalStateException("Pull request creation requires explicit user approval.")
            )
        }
        val token = credentialVault.readToken().getOrNull()
        if (token.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException("Connect GitHub in Settings → GitHub before creating a pull request.")
            )
        }
        val (owner, repo) = GitHubApiClient.parseGitHubOwnerRepo(remoteUrl)
            ?: return Result.failure(
                IllegalArgumentException("Remote '$remoteUrl' is not a recognized GitHub HTTPS repository.")
            )
        if (title.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("Pull request title cannot be empty."))
        }
        return apiClient.createPullRequest(
            token = token,
            owner = owner,
            repo = repo,
            title = title,
            body = body,
            headBranch = headBranch,
            baseBranch = baseBranch.ifBlank { prefs.gitDefaultBranch },
        )
    }

    suspend fun disconnect() {
        devicePollJob?.cancel()
        devicePollJob = null
        _devicePrompt.value = null
        pendingSession = null
        credentialVault.clearToken()
        preferencesRepository.setGitHubAccountMetadata(username = null, scopes = emptyList())
        _repositories.value = emptyList()
        _repoError.value = null
        transientAuthStatus.value = null
        transientAuthError.value = null
    }

    suspend fun readStoredTokenForGit(): String? = credentialVault.readToken().getOrNull()

    fun cancelDeviceFlow() {
        devicePollJob?.cancel()
        devicePollJob = null
        _devicePrompt.value = null
        if (transientAuthStatus.value == GitHubAuthStatus.AUTHORIZING) {
            transientAuthStatus.value = null
        }
    }

    fun cancelPendingOAuth() {
        cancelDeviceFlow()
        pendingSession = null
        if (transientAuthStatus.value == GitHubAuthStatus.AUTHORIZING) {
            transientAuthStatus.value = null
        }
    }

    private fun generateRandomUrlSafeToken(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val OAUTH_SESSION_TTL_MS = 10 * 60 * 1000L
    }
}
