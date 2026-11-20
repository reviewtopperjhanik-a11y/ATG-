package com.example.feature.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.data.github.GitHubRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.GitHubAccountState
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubDeviceCodePrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GitHubConnectUiState(
    val accountState: GitHubAccountState = GitHubAccountState(),
    val isOAuthConfigured: Boolean = false,
    val hasBuildConfigOAuthClientId: Boolean = false,
    val savedOAuthClientId: String = "",
    val configError: String? = null,
    val includePrivateRepos: Boolean = true,
    val isBusy: Boolean = false,
    val pendingLaunchUrl: String? = null,
    val devicePrompt: GitHubDeviceCodePrompt? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class GitHubConnectViewModel(
    private val gitHubRepository: GitHubRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
) : ViewModel() {

    private data class TransientState(
        val includePrivateRepos: Boolean = true,
        val isBusy: Boolean = false,
        val pendingLaunchUrl: String? = null,
        val infoMessage: String? = null,
        val isPendingOAuthInfo: Boolean = false,
        val errorMessage: String? = null,
    )

    private val transient = MutableStateFlow(TransientState())
    private val initialResolution = appConfig.resolveGitHubOAuthClientId()
    private val hasBuildConfigClientId = appConfig.githubOAuthClientId.trim().isNotEmpty()

    val uiState: StateFlow<GitHubConnectUiState> = combine(
        gitHubRepository.accountState,
        preferencesRepository.preferences,
        gitHubRepository.devicePrompt,
        transient,
    ) { account: GitHubAccountState, prefs: AppPreferencesState, prompt: GitHubDeviceCodePrompt?, tr: TransientState ->
        val resolution = appConfig.resolveGitHubOAuthClientId(prefs.githubOAuthClientId)
        val effectiveInfo = if (
            tr.isPendingOAuthInfo &&
            prompt == null &&
            account.status == GitHubAuthStatus.CONNECTED
        ) {
            "Connected to GitHub as @${account.username.orEmpty()}."
        } else {
            tr.infoMessage
        }
        GitHubConnectUiState(
            accountState = account,
            isOAuthConfigured = resolution is ConfigResolution.Configured,
            hasBuildConfigOAuthClientId = hasBuildConfigClientId,
            savedOAuthClientId = prefs.githubOAuthClientId,
            configError = (resolution as? ConfigResolution.MissingOrInvalid)?.userMessage,
            includePrivateRepos = tr.includePrivateRepos,
            isBusy = tr.isBusy,
            pendingLaunchUrl = tr.pendingLaunchUrl,
            devicePrompt = prompt,
            infoMessage = effectiveInfo,
            errorMessage = tr.errorMessage ?: account.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GitHubConnectUiState(
            accountState = gitHubRepository.accountState.value,
            isOAuthConfigured = initialResolution is ConfigResolution.Configured,
            hasBuildConfigOAuthClientId = hasBuildConfigClientId,
            configError = (initialResolution as? ConfigResolution.MissingOrInvalid)?.userMessage,
            devicePrompt = gitHubRepository.devicePrompt.value,
        ),
    )

    fun setIncludePrivateRepos(includePrivate: Boolean) {
        transient.update { it.copy(includePrivateRepos = includePrivate) }
    }

    fun connectWithToken(rawToken: String) {
        viewModelScope.launch {
            transient.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    infoMessage = null,
                    isPendingOAuthInfo = false,
                    pendingLaunchUrl = null,
                )
            }
            gitHubRepository.connectWithToken(rawToken)
                .onSuccess { account ->
                    transient.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = null,
                            infoMessage = "Connected to GitHub as @${account.username.orEmpty()}.",
                            isPendingOAuthInfo = false,
                        )
                    }
                }
                .onFailure { err ->
                    transient.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = err.message ?: "GitHub token validation failed.",
                        )
                    }
                }
        }
    }

    fun startDeviceOAuth(customClientId: String = "") {
        viewModelScope.launch {
            transient.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    infoMessage = null,
                    isPendingOAuthInfo = false,
                    pendingLaunchUrl = null,
                )
            }
            val explicitId = customClientId.trim().takeIf { it.isNotEmpty() && !hasBuildConfigClientId }
            gitHubRepository.beginDeviceFlow(
                clientId = explicitId,
                includePrivateRepos = transient.value.includePrivateRepos,
            ).onSuccess { prompt ->
                transient.update {
                    it.copy(
                        isBusy = false,
                        pendingLaunchUrl = prompt.verificationUriComplete ?: prompt.verificationUri,
                        infoMessage = "Code ${prompt.userCode} copied! Paste it on GitHub in your browser to authorize.",
                        isPendingOAuthInfo = true,
                    )
                }
            }.onFailure { err ->
                transient.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = err.message ?: "Unable to start GitHub OAuth Device Flow.",
                    )
                }
            }
        }
    }

    fun cancelDeviceOAuth() {
        gitHubRepository.cancelDeviceFlow()
        transient.update {
            it.copy(
                isBusy = false,
                pendingLaunchUrl = null,
                infoMessage = null,
                isPendingOAuthInfo = false,
                errorMessage = null,
            )
        }
    }

    fun startOAuth(customClientId: String = "") {
        viewModelScope.launch {
            transient.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    infoMessage = null,
                    isPendingOAuthInfo = false,
                    pendingLaunchUrl = null,
                )
            }
            val explicitId = customClientId.trim().takeIf { it.isNotEmpty() && !hasBuildConfigClientId }
            gitHubRepository.beginOAuthFlow(
                clientId = explicitId,
                includePrivateRepos = transient.value.includePrivateRepos,
            ).onSuccess { req ->
                transient.update {
                    it.copy(
                        isBusy = false,
                        pendingLaunchUrl = req.authorizationUrl,
                        infoMessage = "Opening GitHub authorization in your browser (redirect: ${req.redirectUri}).",
                        isPendingOAuthInfo = true,
                    )
                }
            }.onFailure { err ->
                transient.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = err.message ?: "Unable to start GitHub OAuth flow.",
                    )
                }
            }
        }
    }

    fun consumePendingLaunchUrl() {
        transient.update { it.copy(pendingLaunchUrl = null) }
    }

    fun reportBrowserLaunchFailure(reason: String) {
        transient.update {
            it.copy(
                pendingLaunchUrl = null,
                errorMessage = reason,
            )
        }
    }

    fun notifyCodeCopied(code: String) {
        transient.update {
            it.copy(
                infoMessage = "Verification code $code copied to clipboard.",
                isPendingOAuthInfo = true,
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            gitHubRepository.disconnect()
            transient.update {
                it.copy(
                    infoMessage = "GitHub account disconnected and Keystore token cleared.",
                    isPendingOAuthInfo = false,
                    errorMessage = null,
                )
            }
        }
    }

    class Factory(
        private val gitHubRepository: GitHubRepository,
        private val preferencesRepository: AppPreferencesRepository,
        private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GitHubConnectViewModel(
                gitHubRepository = gitHubRepository,
                preferencesRepository = preferencesRepository,
                appConfig = appConfig,
            ) as T
        }
    }
}
