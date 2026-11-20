package com.example.feature.settings

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.pi.PiInstallerRepository
import com.example.core.common.AgtCrashHandler
import com.example.core.common.CrashReportSummary
import com.example.core.common.DeviceCompatibilityInfo
import com.example.core.common.DeviceEnvironment
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.core.notifications.AgtNotificationManager
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.RuntimeCompatibilityChecker
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.data.search.SearchGatewayRepository
import com.example.domain.model.AgentBackendId
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilRole
import com.example.domain.model.GatewayConnectionSummary
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.GitHubAccountState
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubDeviceCodePrompt
import com.example.domain.model.OpenCodeRuntimeState
import com.example.domain.model.PiRuntimeState
import com.example.domain.model.RuntimeInstallState
import com.example.domain.model.SearchServiceHealth
import com.example.domain.model.SearchServiceKind
import com.example.domain.model.ThemeMode
import com.example.runtime.RuntimeInstallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: AppPreferencesState = AppPreferencesState(),
    val deviceInfo: DeviceCompatibilityInfo? = null,
    val projectCount: Int = 0,
    val taskCount: Int = 0,
    val checkpointCount: Int = 0,
    val workspaceStorageBytes: Long = 0L,
    val runtimeStorageBytes: Long = 0L,
    val hasGatewayToken: Boolean = false,
    val gatewaySummary: GatewayConnectionSummary = GatewayConnectionSummary(),
    val hasWebSearchToken: Boolean = false,
    val webSearchHealth: SearchServiceHealth = SearchServiceHealth(service = SearchServiceKind.WEB_SEARCH),
    val hasGitHubSearchToken: Boolean = false,
    val githubSearchHealth: SearchServiceHealth = SearchServiceHealth(
        service = SearchServiceKind.GITHUB_SEARCH,
        statusMessage = "Not configured (public GitHub fallback available)",
    ),
    val discoveredModels: List<GatewayModelInfo> = emptyList(),
    val runtimeState: RuntimeInstallState = RuntimeInstallState(),
    val openCodeState: OpenCodeRuntimeState = OpenCodeRuntimeState(),
    val piState: PiRuntimeState = PiRuntimeState(),
    val gitHubAccount: GitHubAccountState = GitHubAccountState(),
    val gitHubDevicePrompt: GitHubDeviceCodePrompt? = null,
    val isRefreshingModels: Boolean = false,
    val isGitHubBusy: Boolean = false,
    val pendingGitHubOAuthUrl: String? = null,
    val gatewayErrorBanner: String? = null,
    val errorBannerTitle: String = "Error",
    val savedBanner: String? = null,
    val lastCrashReport: CrashReportSummary? = null,
    val isRuntimeConfigured: Boolean = false,
    val isUpstreamBootstrapMode: Boolean = false,
    val runtimeConfigError: String? = null,
    val isGitHubOAuthConfigured: Boolean = false,
    val hasBuildConfigOAuthClientId: Boolean = false,
    val gitHubConfigError: String? = null,
)

class SettingsViewModel(
    private val appContext: Context,
    private val preferencesRepository: AppPreferencesRepository,
    private val projectRepository: ProjectRepository,
    private val gatewayRepository: GatewayRepository,
    private val gitHubRepository: GitHubRepository,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val openCodeInstallerRepository: OpenCodeInstallerRepository,
    private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
    private val piInstallerRepository: PiInstallerRepository? = null,
    private val notificationManager: AgtNotificationManager? = null,
    private val searchGatewayRepository: SearchGatewayRepository? = null,
) : ViewModel() {

    private data class TransientState(
        val deviceInfo: DeviceCompatibilityInfo? = null,
        val workspaceStorageBytes: Long = 0L,
        val isRefreshingModels: Boolean = false,
        val isGitHubBusy: Boolean = false,
        val pendingGitHubOAuthUrl: String? = null,
        val gatewayErrorBanner: String? = null,
        val errorBannerTitle: String = "Error",
        val savedBanner: String? = null,
        val isPendingGitHubBanner: Boolean = false,
        val lastCrashReport: CrashReportSummary? = null,
    )

    private data class StorageStats(
        val projectCount: Int = 0,
        val taskCount: Int = 0,
        val checkpointCount: Int = 0,
    )

    private data class RuntimeAndGitHub(
        val runtime: RuntimeInstallState,
        val openCode: OpenCodeRuntimeState,
        val pi: PiRuntimeState,
        val gitHub: GitHubAccountState,
        val devicePrompt: GitHubDeviceCodePrompt?,
    )

    private val transientState = MutableStateFlow(
        TransientState(
            deviceInfo = DeviceEnvironment.inspect(appContext),
            lastCrashReport = AgtCrashHandler.readLastCrash(appContext),
        )
    )

    private val storageStatsFlow = combine(
        projectRepository.observeProjectCount(),
        projectRepository.observeTaskCount(),
        projectRepository.observeCheckpointCount(),
    ) { projects, tasks, checkpoints ->
        StorageStats(projects, tasks, checkpoints)
    }

    private val runtimeAndGitHubFlow = combine(
        runtimeInstallerRepository.installState,
        openCodeInstallerRepository.state,
        piInstallerRepository?.state ?: MutableStateFlow(PiRuntimeState()),
        gitHubRepository.accountState,
        gitHubRepository.devicePrompt,
    ) { runtime, openCode, pi, gitHub, devicePrompt ->
        RuntimeAndGitHub(runtime, openCode, pi, gitHub, devicePrompt)
    }

    private data class SearchGatewaySnapshot(
        val hasWebToken: Boolean = false,
        val webHealth: SearchServiceHealth = SearchServiceHealth(service = SearchServiceKind.WEB_SEARCH),
        val hasGitHubToken: Boolean = false,
        val githubHealth: SearchServiceHealth = SearchServiceHealth(
            service = SearchServiceKind.GITHUB_SEARCH,
            statusMessage = "Not configured (public GitHub fallback available)",
        ),
    )

    private val searchGatewaySnapshotFlow = if (searchGatewayRepository != null) {
        combine(
            searchGatewayRepository.hasWebSearchToken,
            searchGatewayRepository.webSearchHealth,
            searchGatewayRepository.hasGitHubSearchToken,
            searchGatewayRepository.githubSearchHealth,
        ) { hasWeb, webHealth, hasGh, ghHealth ->
            SearchGatewaySnapshot(hasWeb, webHealth, hasGh, ghHealth)
        }
    } else {
        MutableStateFlow(SearchGatewaySnapshot())
    }

    private val hasBuildConfigClientId = appConfig.githubOAuthClientId.trim().isNotEmpty()

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferences,
        storageStatsFlow,
        gatewayRepository.hasGatewayToken,
        gatewayRepository.connectionSummary,
        gatewayRepository.discoveredModels,
        runtimeAndGitHubFlow,
        transientState,
        searchGatewaySnapshotFlow,
    ) { values ->
        val prefs = values[0] as AppPreferencesState
        val stats = values[1] as StorageStats
        val hasToken = values[2] as Boolean
        val summary = values[3] as GatewayConnectionSummary
        @Suppress("UNCHECKED_CAST")
        val models = values[4] as List<GatewayModelInfo>
        val runtimeGitHub = values[5] as RuntimeAndGitHub
        val transient = values[6] as TransientState
        val searchSnap = values[7] as SearchGatewaySnapshot

        val runtimeRes = appConfig.resolveRuntimeManifestUrl(prefs.runtimeManifestUrl)
        val upstreamMode = appConfig.isUpstreamBootstrapActive(prefs.runtimeManifestUrl)
        val gitHubRes = appConfig.resolveGitHubOAuthClientId(prefs.githubOAuthClientId)
        val effectiveSavedBanner = if (
            transient.isPendingGitHubBanner &&
            runtimeGitHub.devicePrompt == null &&
            runtimeGitHub.gitHub.status == GitHubAuthStatus.CONNECTED
        ) {
            "Connected to GitHub as @${runtimeGitHub.gitHub.username.orEmpty()}."
        } else {
            transient.savedBanner
        }

        SettingsUiState(
            preferences = prefs.copy(
                hasGatewayToken = hasToken,
                hasWebSearchToken = searchSnap.hasWebToken,
                hasGitHubSearchToken = searchSnap.hasGitHubToken,
            ),
            deviceInfo = transient.deviceInfo,
            projectCount = stats.projectCount,
            taskCount = stats.taskCount,
            checkpointCount = stats.checkpointCount,
            workspaceStorageBytes = transient.workspaceStorageBytes,
            runtimeStorageBytes = runtimeGitHub.runtime.runtimeDiskBytes,
            hasGatewayToken = hasToken,
            gatewaySummary = summary,
            hasWebSearchToken = searchSnap.hasWebToken,
            webSearchHealth = searchSnap.webHealth,
            hasGitHubSearchToken = searchSnap.hasGitHubToken,
            githubSearchHealth = searchSnap.githubHealth,
            discoveredModels = models,
            runtimeState = runtimeGitHub.runtime,
            openCodeState = runtimeGitHub.openCode,
            piState = runtimeGitHub.pi,
            gitHubAccount = runtimeGitHub.gitHub,
            gitHubDevicePrompt = runtimeGitHub.devicePrompt,
            isRefreshingModels = transient.isRefreshingModels,
            isGitHubBusy = transient.isGitHubBusy,
            pendingGitHubOAuthUrl = transient.pendingGitHubOAuthUrl,
            gatewayErrorBanner = transient.gatewayErrorBanner ?: runtimeGitHub.gitHub.errorMessage,
            errorBannerTitle = if (runtimeGitHub.gitHub.errorMessage != null && transient.gatewayErrorBanner == null) {
                "GitHub Error"
            } else {
                transient.errorBannerTitle
            },
            savedBanner = effectiveSavedBanner,
            lastCrashReport = transient.lastCrashReport,
            isRuntimeConfigured = runtimeRes is ConfigResolution.Configured || upstreamMode,
            isUpstreamBootstrapMode = upstreamMode,
            runtimeConfigError = if (upstreamMode) null else (runtimeRes as? ConfigResolution.MissingOrInvalid)?.userMessage,
            isGitHubOAuthConfigured = gitHubRes is ConfigResolution.Configured,
            hasBuildConfigOAuthClientId = hasBuildConfigClientId,
            gitHubConfigError = (gitHubRes as? ConfigResolution.MissingOrInvalid)?.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = run {
            val initRuntimeRes = appConfig.resolveRuntimeManifestUrl()
            val initUpstream = appConfig.isUpstreamBootstrapActive()
            val initGitHubRes = appConfig.resolveGitHubOAuthClientId()
            SettingsUiState(
                deviceInfo = DeviceEnvironment.inspect(appContext),
                runtimeState = runtimeInstallerRepository.installState.value,
                openCodeState = openCodeInstallerRepository.state.value,
                piState = piInstallerRepository?.state?.value ?: PiRuntimeState(),
                gitHubAccount = gitHubRepository.accountState.value,
                gitHubDevicePrompt = gitHubRepository.devicePrompt.value,
                lastCrashReport = AgtCrashHandler.readLastCrash(appContext),
                isRuntimeConfigured = initRuntimeRes is ConfigResolution.Configured || initUpstream,
                isUpstreamBootstrapMode = initUpstream,
                runtimeConfigError = if (initUpstream) null else (initRuntimeRes as? ConfigResolution.MissingOrInvalid)?.userMessage,
                isGitHubOAuthConfigured = initGitHubRes is ConfigResolution.Configured,
                hasBuildConfigOAuthClientId = hasBuildConfigClientId,
                gitHubConfigError = (initGitHubRes as? ConfigResolution.MissingOrInvalid)?.userMessage,
            )
        },
    )

    fun refreshStorageStats() {
        viewModelScope.launch {
            val bytes = projectRepository.calculateWorkspaceStorageBytes()
            transientState.update { it.copy(workspaceStorageBytes = bytes) }
        }
    }

    fun refreshDiagnostics() {
        transientState.update {
            it.copy(lastCrashReport = AgtCrashHandler.readLastCrash(appContext))
        }
    }

    fun clearLastCrashReport() {
        AgtCrashHandler.clearLastCrash(appContext)
        transientState.update {
            it.copy(
                lastCrashReport = null,
                savedBanner = "Diagnostics cleared.",
                gatewayErrorBanner = null,
            )
        }
    }

    fun installOrUpdateRuntime() {
        val compat = RuntimeCompatibilityChecker.evaluate(DeviceEnvironment.inspect(appContext))
        if (!compat.sdkSupported || !compat.storageSupported) {
            transientState.update {
                it.copy(gatewayErrorBanner = compat.blockingReasons.joinToString(" "), savedBanner = null)
            }
            return
        }
        viewModelScope.launch {
            val res = appConfig.resolveRuntimeManifestUrl(uiState.value.preferences.runtimeManifestUrl)
            if (res is ConfigResolution.MissingOrInvalid && !appConfig.allowUpstreamBootstrap) {
                transientState.update {
                    it.copy(
                        errorBannerTitle = "Configuration Required",
                        gatewayErrorBanner = res.userMessage,
                        savedBanner = null,
                    )
                }
                return@launch
            }
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }

            val forceUpdate = uiState.value.runtimeState.isInstalled
            val started = runCatching {
                val intent = RuntimeInstallService.startInstallIntent(
                    context = appContext,
                    forceUpdate = forceUpdate,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(appContext, intent)
                } else {
                    appContext.startService(intent)
                }
            }.isSuccess
            if (!started) {
                runtimeInstallerRepository.startInstall(forceUpdate = forceUpdate)
            }
        }
    }

    fun cancelRuntimeInstall() {
        runtimeInstallerRepository.cancelInstall()
        runCatching {
            appContext.startService(RuntimeInstallService.cancelInstallIntent(appContext))
        }
    }

    fun verifyRuntimeHealth() {
        viewModelScope.launch {
            val report = runtimeInstallerRepository.runHealthCheck()
            transientState.update {
                if (report.isHealthy) {
                    it.copy(savedBanner = report.summary, gatewayErrorBanner = null)
                } else {
                    it.copy(gatewayErrorBanner = report.summary, savedBanner = null)
                }
            }
        }
    }

    fun repairRuntime() {
        viewModelScope.launch {
            runtimeInstallerRepository.repairRuntime()
                .onSuccess { report ->
                    transientState.update {
                        it.copy(savedBanner = "Runtime repaired: ${report.summary}", gatewayErrorBanner = null)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "linux-runtime-repair",
                        title = appContext.getString(com.example.R.string.notif_runtime_ready_title),
                        detail = report.summary,
                    )
                }
                .onFailure { err ->
                    val detail = err.message ?: "Runtime repair failed."
                    transientState.update {
                        it.copy(gatewayErrorBanner = detail, savedBanner = null)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "linux-runtime-repair",
                        title = appContext.getString(com.example.R.string.notif_runtime_failed_title),
                        detail = detail,
                    )
                }
        }
    }

    fun removeRuntime() {
        viewModelScope.launch {
            runtimeInstallerRepository.removeRuntime()
            openCodeInstallerRepository.refreshStatusSync()
            piInstallerRepository?.refreshStatusSync()
            transientState.update {
                it.copy(savedBanner = "Linux runtime removed. Project workspaces preserved.", gatewayErrorBanner = null)
            }
        }
    }

    fun selectActiveAgent(agentId: String) {
        viewModelScope.launch {
            val backend = AgentBackendId.fromId(agentId)
            preferencesRepository.setActiveAgentId(backend.id)
            transientState.update {
                it.copy(
                    savedBanner = "Active coding agent switched to ${backend.displayName}.",
                    gatewayErrorBanner = null,
                )
            }
        }
    }

    fun installOrUpdateOpenCode(manifestUrl: String = "") {
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            val result = if (manifestUrl.isBlank()) {
                openCodeInstallerRepository.installAutomatic(forceUpdate = uiState.value.openCodeState.isInstalled)
            } else {
                openCodeInstallerRepository.installOrUpdate(manifestUrl)
            }
            result
                .onSuccess { st ->
                    transientState.update {
                        it.copy(savedBanner = st.statusDetail, gatewayErrorBanner = null)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "opencode-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_ready_title, "OpenCode"),
                        detail = st.statusDetail,
                    )
                }
                .onFailure { err ->
                    val detail = err.message ?: "OpenCode installation failed."
                    transientState.update {
                        it.copy(gatewayErrorBanner = detail)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "opencode-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_failed_title, "OpenCode"),
                        detail = detail,
                    )
                }
        }
    }

    fun verifyOpenCode() {
        viewModelScope.launch {
            val st = openCodeInstallerRepository.verifyInstallation()
            transientState.update {
                if (st.isInstalled) {
                    it.copy(savedBanner = st.statusDetail, gatewayErrorBanner = null)
                } else {
                    it.copy(gatewayErrorBanner = st.errorMessage ?: st.statusDetail, savedBanner = null)
                }
            }
        }
    }

    fun installOrUpdatePi(manifestUrl: String = "") {
        val installer = piInstallerRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            installer.installOrUpdate(manifestUrl)
                .onSuccess { st ->
                    transientState.update {
                        it.copy(savedBanner = st.statusDetail, gatewayErrorBanner = null)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "pi-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_ready_title, "Pi Agent"),
                        detail = st.statusDetail,
                    )
                }
                .onFailure { err ->
                    val detail = err.message ?: "Pi Coding Agent installation failed."
                    transientState.update {
                        it.copy(gatewayErrorBanner = detail)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "pi-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_failed_title, "Pi Agent"),
                        detail = detail,
                    )
                }
        }
    }

    fun installPiViaNpm() {
        val installer = piInstallerRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            installer.installViaNpm(forceUpdate = uiState.value.piState.isInstalled)
                .onSuccess { st ->
                    transientState.update {
                        it.copy(savedBanner = st.statusDetail, gatewayErrorBanner = null)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "pi-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_ready_title, "Pi Agent"),
                        detail = st.statusDetail,
                    )
                }
                .onFailure { err ->
                    val detail = err.message ?: "Pi npm installation failed."
                    transientState.update {
                        it.copy(gatewayErrorBanner = detail)
                    }
                    notificationManager?.notifyRuntimeEvent(
                        eventKey = "pi-setup",
                        title = appContext.getString(com.example.R.string.notif_agent_setup_failed_title, "Pi Agent"),
                        detail = detail,
                    )
                }
        }
    }

    fun verifyPi() {
        val installer = piInstallerRepository ?: return
        viewModelScope.launch {
            val st = installer.verifyInstallation()
            transientState.update {
                if (st.isInstalled) {
                    it.copy(savedBanner = st.statusDetail, gatewayErrorBanner = null)
                } else {
                    it.copy(gatewayErrorBanner = st.errorMessage ?: st.statusDetail, savedBanner = null)
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun saveGatewayConfiguration(
        url: String,
        protocolMode: GatewayProtocolMode,
        newRawToken: String,
    ) {
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            gatewayRepository.saveConfiguration(url, protocolMode, newRawToken.ifBlank { null })
                .onSuccess {
                    transientState.update {
                        it.copy(savedBanner = "Gateway endpoint and credentials updated.")
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(gatewayErrorBanner = err.message ?: "Invalid gateway configuration.")
                    }
                }
        }
    }

    fun saveAndTestGateway(
        url: String,
        protocolMode: GatewayProtocolMode,
        newRawToken: String,
    ) {
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            val saveRes = gatewayRepository.saveConfiguration(
                baseUrl = url,
                protocolMode = protocolMode,
                newRawToken = newRawToken.ifBlank { null },
            )
            if (saveRes.isFailure) {
                transientState.update {
                    it.copy(gatewayErrorBanner = saveRes.exceptionOrNull()?.message ?: "Invalid endpoint.")
                }
                return@launch
            }
            val health = gatewayRepository.testHealthAndDiscoverModels()
            if (health.error != null) {
                transientState.update { it.copy(gatewayErrorBanner = health.message) }
            } else {
                transientState.update { it.copy(savedBanner = health.message) }
            }
        }
    }

    fun clearGatewayToken() {
        viewModelScope.launch {
            gatewayRepository.clearToken()
            transientState.update {
                it.copy(savedBanner = "Gateway device token removed from Android Keystore.")
            }
        }
    }

    fun saveWebSearchConfiguration(url: String, newRawToken: String) {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            repo.saveWebSearchConfig(url, newRawToken.ifBlank { null })
                .onSuccess {
                    transientState.update {
                        it.copy(savedBanner = "Web Search API configuration saved.")
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(gatewayErrorBanner = err.message ?: "Invalid Web Search endpoint.")
                    }
                }
        }
    }

    fun saveAndTestWebSearch(url: String, newRawToken: String) {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            val saveRes = repo.saveWebSearchConfig(url, newRawToken.ifBlank { null })
            if (saveRes.isFailure) {
                transientState.update {
                    it.copy(gatewayErrorBanner = saveRes.exceptionOrNull()?.message ?: "Invalid Web Search endpoint.")
                }
                return@launch
            }
            val health = repo.testWebSearchHealth()
            if (health.error != null) {
                transientState.update { it.copy(gatewayErrorBanner = health.statusMessage) }
            } else {
                transientState.update { it.copy(savedBanner = "Web Search API: ${health.statusMessage}") }
            }
        }
    }

    fun clearWebSearchToken() {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            repo.clearWebSearchToken()
            transientState.update {
                it.copy(savedBanner = "Web Search token removed from Android Keystore.", gatewayErrorBanner = null)
            }
        }
    }

    fun saveGitHubSearchConfiguration(url: String, newRawToken: String) {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            repo.saveGitHubSearchConfig(url, newRawToken.ifBlank { null })
                .onSuccess {
                    transientState.update {
                        it.copy(savedBanner = "GitHub Search API configuration saved.")
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(gatewayErrorBanner = err.message ?: "Invalid GitHub Search endpoint.")
                    }
                }
        }
    }

    fun saveAndTestGitHubSearch(url: String, newRawToken: String) {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null) }
            val saveRes = repo.saveGitHubSearchConfig(url, newRawToken.ifBlank { null })
            if (saveRes.isFailure) {
                transientState.update {
                    it.copy(gatewayErrorBanner = saveRes.exceptionOrNull()?.message ?: "Invalid GitHub Search endpoint.")
                }
                return@launch
            }
            val health = repo.testGitHubSearchHealth()
            if (health.error != null) {
                transientState.update { it.copy(gatewayErrorBanner = health.statusMessage) }
            } else {
                transientState.update { it.copy(savedBanner = "GitHub Search API: ${health.statusMessage}") }
            }
        }
    }

    fun clearGitHubSearchToken() {
        val repo = searchGatewayRepository ?: return
        viewModelScope.launch {
            repo.clearGitHubSearchToken()
            transientState.update {
                it.copy(savedBanner = "GitHub Search token removed from Android Keystore.", gatewayErrorBanner = null)
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            transientState.update { it.copy(isRefreshingModels = true, gatewayErrorBanner = null) }
            gatewayRepository.refreshModels()
                .onSuccess { models ->
                    transientState.update {
                        it.copy(
                            isRefreshingModels = false,
                            savedBanner = "Discovered ${models.size} model(s) from /v1/models.",
                        )
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isRefreshingModels = false,
                            gatewayErrorBanner = err.message ?: "Failed to query /v1/models.",
                        )
                    }
                }
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            gatewayRepository.setDefaultModel(modelId)
            transientState.update { it.copy(savedBanner = "Default model set to $modelId.") }
        }
    }

    fun saveGitIdentity(defaultBranch: String, authorName: String, authorEmail: String) {
        viewModelScope.launch {
            preferencesRepository.setGitIdentity(defaultBranch, authorName, authorEmail)
            transientState.update { it.copy(savedBanner = "Git configuration saved.", gatewayErrorBanner = null) }
        }
    }

    fun launchGitHubDeviceOAuth(customClientId: String, includePrivateRepos: Boolean) {
        viewModelScope.launch {
            transientState.update {
                it.copy(
                    isGitHubBusy = true,
                    gatewayErrorBanner = null,
                    savedBanner = null,
                    isPendingGitHubBanner = false,
                    pendingGitHubOAuthUrl = null,
                )
            }
            val explicitId = customClientId.trim().takeIf { it.isNotEmpty() && !hasBuildConfigClientId }
            gitHubRepository.beginDeviceFlow(
                clientId = explicitId,
                includePrivateRepos = includePrivateRepos,
            )
                .onSuccess { prompt ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            pendingGitHubOAuthUrl = prompt.verificationUriComplete ?: prompt.verificationUri,
                            savedBanner = "Code ${prompt.userCode} copied! Paste it on GitHub in your browser.",
                            isPendingGitHubBanner = true,
                        )
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            errorBannerTitle = "GitHub Error",
                            gatewayErrorBanner = err.message ?: "Unable to start GitHub Device Flow.",
                        )
                    }
                }
        }
    }

    fun cancelGitHubDeviceOAuth() {
        gitHubRepository.cancelDeviceFlow()
        transientState.update {
            it.copy(
                isGitHubBusy = false,
                pendingGitHubOAuthUrl = null,
                savedBanner = null,
                isPendingGitHubBanner = false,
                gatewayErrorBanner = null,
            )
        }
    }

    fun notifyGitHubCodeCopied(code: String) {
        transientState.update {
            it.copy(
                savedBanner = "Verification code $code copied to clipboard.",
                isPendingGitHubBanner = true,
            )
        }
    }

    fun launchGitHubOAuth(includePrivateRepos: Boolean) {
        viewModelScope.launch {
            transientState.update {
                it.copy(
                    isGitHubBusy = true,
                    gatewayErrorBanner = null,
                    savedBanner = null,
                    isPendingGitHubBanner = false,
                    pendingGitHubOAuthUrl = null,
                )
            }
            gitHubRepository.beginOAuthFlow(clientId = null, includePrivateRepos = includePrivateRepos)
                .onSuccess { req ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            pendingGitHubOAuthUrl = req.authorizationUrl,
                            savedBanner = "Opening browser for GitHub OAuth (${req.redirectUri})…",
                            isPendingGitHubBanner = true,
                        )
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            errorBannerTitle = "GitHub Error",
                            gatewayErrorBanner = err.message ?: "Unable to start GitHub OAuth.",
                        )
                    }
                }
        }
    }

    fun consumePendingGitHubOAuthUrl() {
        transientState.update { it.copy(pendingGitHubOAuthUrl = null) }
    }

    fun reportGitHubBrowserFailure(message: String) {
        transientState.update {
            it.copy(
                pendingGitHubOAuthUrl = null,
                errorBannerTitle = "GitHub Error",
                gatewayErrorBanner = message,
            )
        }
    }

    fun connectGitHubWithToken(rawToken: String) {
        viewModelScope.launch {
            transientState.update {
                it.copy(
                    isGitHubBusy = true,
                    gatewayErrorBanner = null,
                    savedBanner = null,
                    isPendingGitHubBanner = false,
                )
            }
            gitHubRepository.connectWithToken(rawToken)
                .onSuccess { account ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            gatewayErrorBanner = null,
                            savedBanner = "Connected to GitHub as @${account.username.orEmpty()}.",
                            isPendingGitHubBanner = false,
                        )
                    }
                }
                .onFailure { err ->
                    transientState.update {
                        it.copy(
                            isGitHubBusy = false,
                            errorBannerTitle = "GitHub Error",
                            gatewayErrorBanner = err.message ?: "GitHub token validation failed.",
                        )
                    }
                }
        }
    }

    fun disconnectGitHub() {
        viewModelScope.launch {
            gitHubRepository.disconnect()
            transientState.update {
                it.copy(
                    savedBanner = "GitHub account disconnected and Keystore token removed.",
                    isPendingGitHubBanner = false,
                    gatewayErrorBanner = null,
                )
            }
        }
    }

    fun saveAgentPolicy(
        stepLimit: Int,
        timeoutSeconds: Int,
        autoDebug: Boolean,
        checkpoints: Boolean,
        requirePlanApproval: Boolean,
    ) {
        viewModelScope.launch {
            preferencesRepository.setAgentPolicy(
                stepLimit = stepLimit,
                timeoutSeconds = timeoutSeconds,
                autoDebug = autoDebug,
                checkpoints = checkpoints,
                requirePlanApproval = requirePlanApproval,
            )
            transientState.update { it.copy(savedBanner = "Agent policy updated.") }
        }
    }

    fun setCouncilDefaultMode(mode: CouncilExecutionMode) {
        viewModelScope.launch {
            preferencesRepository.setCouncilDefaultMode(mode)
            transientState.update {
                it.copy(savedBanner = "Default execution mode set to ${mode.label}.", gatewayErrorBanner = null)
            }
        }
    }

    fun setCouncilRoleModel(role: CouncilRole, modelId: String?) {
        viewModelScope.launch {
            preferencesRepository.setCouncilRoleModel(role, modelId)
            val label = modelId?.takeIf { it.isNotBlank() } ?: "Default / Composer model"
            transientState.update {
                it.copy(savedBanner = "${role.displayName} routed to $label.", gatewayErrorBanner = null)
            }
        }
    }

    fun setCouncilSpecialistEnabled(role: CouncilRole, enabled: Boolean) {
        viewModelScope.launch {
            val currentEnabled = uiState.value.preferences.councilEnabledSpecialists
            if (!enabled && currentEnabled.size <= 1) {
                transientState.update {
                    it.copy(gatewayErrorBanner = "Keep at least 1 active Council specialist enabled.", savedBanner = null)
                }
                return@launch
            }
            preferencesRepository.setCouncilSpecialistEnabled(role, enabled)
            transientState.update {
                it.copy(
                    savedBanner = "${role.displayName} ${if (enabled) "enabled" else "disabled"} in Council.",
                    gatewayErrorBanner = null,
                )
            }
        }
    }

    fun saveCouncilBudgetAndMode(defaultMode: CouncilExecutionMode, tokenBudget: Int) {
        viewModelScope.launch {
            preferencesRepository.setCouncilPolicy(defaultMode, tokenBudget)
            transientState.update {
                it.copy(
                    savedBanner = "Council policy saved (${defaultMode.label}, budget ${tokenBudget.coerceIn(4_000, 128_000)} tokens).",
                    gatewayErrorBanner = null,
                )
            }
        }
    }

    fun saveSecurityPolicy(
        requireDestructive: Boolean,
        requirePush: Boolean,
        requireDeploy: Boolean,
        secretMasking: Boolean,
    ) {
        viewModelScope.launch {
            preferencesRepository.setSecurityPolicy(
                requireDestructive = requireDestructive,
                requirePush = requirePush,
                requireDeploy = requireDeploy,
                secretMasking = secretMasking,
            )
        }
    }

    fun saveNotifications(
        taskComplete: Boolean,
        taskFailure: Boolean,
        approvals: Boolean,
        runtimeEvents: Boolean,
    ) {
        viewModelScope.launch {
            preferencesRepository.setNotificationPreferences(
                taskComplete = taskComplete,
                taskFailure = taskFailure,
                approvals = approvals,
                runtimeEvents = runtimeEvents,
            )
        }
    }

    fun clearNotices() {
        transientState.update { it.copy(gatewayErrorBanner = null, savedBanner = null, errorBannerTitle = "Error") }
    }

    class Factory(
        private val appContext: Context,
        private val preferencesRepository: AppPreferencesRepository,
        private val projectRepository: ProjectRepository,
        private val gatewayRepository: GatewayRepository,
        private val gitHubRepository: GitHubRepository,
        private val runtimeInstallerRepository: RuntimeInstallerRepository,
        private val openCodeInstallerRepository: OpenCodeInstallerRepository,
        private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
        private val piInstallerRepository: PiInstallerRepository? = null,
        private val notificationManager: AgtNotificationManager? = null,
        private val searchGatewayRepository: SearchGatewayRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                appContext = appContext,
                preferencesRepository = preferencesRepository,
                projectRepository = projectRepository,
                gatewayRepository = gatewayRepository,
                gitHubRepository = gitHubRepository,
                runtimeInstallerRepository = runtimeInstallerRepository,
                openCodeInstallerRepository = openCodeInstallerRepository,
                appConfig = appConfig,
                piInstallerRepository = piInstallerRepository,
                notificationManager = notificationManager,
                searchGatewayRepository = searchGatewayRepository,
            ) as T
        }
    }
}
