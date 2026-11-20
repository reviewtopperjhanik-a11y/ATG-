package com.example.feature.setup

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.DeviceCompatibilityInfo
import com.example.core.common.DeviceEnvironment
import com.example.core.config.AgtAppConfig
import com.example.core.config.ConfigResolution
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.RuntimeCompatibilityChecker
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.model.RuntimeCompatibilityReport
import com.example.domain.model.RuntimeInstallState
import com.example.runtime.RuntimeInstallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SetupUiState(
    val deviceInfo: DeviceCompatibilityInfo,
    val compatibilityReport: RuntimeCompatibilityReport,
    val runtimeState: RuntimeInstallState = RuntimeInstallState(),
    val isRuntimeConfigured: Boolean = false,
    val isUpstreamBootstrapMode: Boolean = false,
    val configError: String? = null,
    val errorNotice: String? = null,
)

class SetupViewModel(
    private val appContext: Context,
    private val preferencesRepository: AppPreferencesRepository,
    private val runtimeInstallerRepository: RuntimeInstallerRepository,
    private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
    private val useForegroundService: Boolean = true,
) : ViewModel() {

    private val deviceInfo = DeviceEnvironment.inspect(appContext)
    private val compatReport = RuntimeCompatibilityChecker.evaluate(deviceInfo)
    private val localError = MutableStateFlow<String?>(null)

    private val initialResolution = appConfig.resolveRuntimeManifestUrl()
    private val initialUpstreamMode = appConfig.isUpstreamBootstrapActive()

    val uiState: StateFlow<SetupUiState> = combine(
        runtimeInstallerRepository.installState,
        preferencesRepository.preferences,
        localError,
    ) { runtimeState, prefs, transientErr ->
        val resolution = appConfig.resolveRuntimeManifestUrl(prefs.runtimeManifestUrl)
        val upstreamMode = appConfig.isUpstreamBootstrapActive(prefs.runtimeManifestUrl)
        val configErrorMsg = if (upstreamMode) {
            null
        } else {
            (resolution as? ConfigResolution.MissingOrInvalid)?.userMessage
        }
        SetupUiState(
            deviceInfo = deviceInfo,
            compatibilityReport = compatReport,
            runtimeState = runtimeState,
            isRuntimeConfigured = resolution is ConfigResolution.Configured || upstreamMode,
            isUpstreamBootstrapMode = upstreamMode,
            configError = configErrorMsg,
            errorNotice = transientErr ?: runtimeState.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SetupUiState(
            deviceInfo = deviceInfo,
            compatibilityReport = compatReport,
            runtimeState = runtimeInstallerRepository.installState.value,
            isRuntimeConfigured = initialResolution is ConfigResolution.Configured || initialUpstreamMode,
            isUpstreamBootstrapMode = initialUpstreamMode,
            configError = if (initialUpstreamMode) null else (initialResolution as? ConfigResolution.MissingOrInvalid)?.userMessage,
        ),
    )

    fun startRuntimeInstall() {
        val currentCompat = RuntimeCompatibilityChecker.evaluate(DeviceEnvironment.inspect(appContext))
        if (!currentCompat.sdkSupported || !currentCompat.storageSupported) {
            localError.value = currentCompat.blockingReasons.joinToString(" ")
            return
        }
        viewModelScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val resolution = appConfig.resolveRuntimeManifestUrl(prefs.runtimeManifestUrl)
            if (resolution is ConfigResolution.MissingOrInvalid && !appConfig.allowUpstreamBootstrap) {
                localError.value = resolution.userMessage
                return@launch
            }
            localError.value = null

            val serviceStarted = if (useForegroundService) {
                runCatching {
                    val intent = RuntimeInstallService.startInstallIntent(appContext)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(appContext, intent)
                    } else {
                        appContext.startService(intent)
                    }
                }.isSuccess
            } else {
                false
            }

            if (!serviceStarted) {
                runtimeInstallerRepository.startInstall()
            }
        }
    }

    fun cancelRuntimeInstall() {
        runtimeInstallerRepository.cancelInstall()
        runCatching {
            appContext.startService(RuntimeInstallService.cancelInstallIntent(appContext))
        }
    }

    fun runHealthCheck() {
        viewModelScope.launch {
            runtimeInstallerRepository.runHealthCheck()
        }
    }

    fun dismissNotices() {
        localError.value = null
    }

    fun markSetupDeferred(onComplete: () -> Unit) {
        onComplete()
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(false)
        }
    }

    fun markSetupCompleted(onComplete: () -> Unit) {
        onComplete()
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
        }
    }

    class Factory(
        private val appContext: Context,
        private val preferencesRepository: AppPreferencesRepository,
        private val runtimeInstallerRepository: RuntimeInstallerRepository,
        private val appConfig: AgtAppConfig = AgtAppConfig.fromBuildConfig(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SetupViewModel(
                appContext = appContext,
                preferencesRepository = preferencesRepository,
                runtimeInstallerRepository = runtimeInstallerRepository,
                appConfig = appConfig,
            ) as T
        }
    }
}
