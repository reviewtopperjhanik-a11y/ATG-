package com.example.data.gateway

import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.data.database.CachedModelDao
import com.example.data.database.CachedModelEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.contracts.GatewayChatRequest
import com.example.domain.contracts.GatewayClient
import com.example.domain.contracts.GatewayHealth
import com.example.domain.contracts.GatewayStreamEvent
import com.example.domain.model.GatewayConnectionSummary
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

class GatewayRepository(
    private val preferencesRepository: AppPreferencesRepository,
    private val credentialVault: GatewayCredentialVault,
    private val cachedModelDao: CachedModelDao,
    okHttpClient: OkHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
) {
    private val client: GatewayClient = JhGatewayHttpClient(
        configProvider = {
            val prefs = preferencesRepository.preferences.first()
            val token = credentialVault.readToken().getOrNull()
            GatewayEndpointConfig(
                baseUrl = prefs.gatewayBaseUrl,
                bearerToken = token,
            )
        },
        okHttpClient = okHttpClient,
    )

    private val _connectionSummary = MutableStateFlow(GatewayConnectionSummary())
    val connectionSummary: StateFlow<GatewayConnectionSummary> = _connectionSummary.asStateFlow()

    val hasGatewayToken: StateFlow<Boolean> = credentialVault.hasToken

    val discoveredModels: Flow<List<GatewayModelInfo>> = cachedModelDao.observeModels().map { list ->
        list.map { entity ->
            GatewayModelInfo(
                id = entity.id,
                provider = entity.provider,
                supportsStreaming = entity.supportsStreaming,
                supportsToolCalling = entity.supportsToolCalling,
                supportsVision = entity.supportsVision,
                contextWindow = entity.contextWindow,
                lastSyncedEpochMs = entity.lastSyncedEpochMs,
            )
        }
    }

    suspend fun saveConfiguration(
        baseUrl: String,
        protocolMode: GatewayProtocolMode,
        newRawToken: String?,
    ): Result<Unit> {
        val validation = GatewayUrlValidator.validate(baseUrl)
        if (validation is GatewayUrlValidation.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val normalized = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
        preferencesRepository.setGatewayBaseUrl(normalized)
        preferencesRepository.setGatewayProtocol(protocolMode)

        if (!newRawToken.isNullOrBlank()) {
            val tokenSave = credentialVault.saveToken(newRawToken)
            if (tokenSave.isFailure) {
                return tokenSave
            }
        }
        return Result.success(Unit)
    }

    suspend fun clearToken() {
        credentialVault.clearToken()
        cachedModelDao.clearModels()
        preferencesRepository.setDefaultModelId(null)
        _connectionSummary.value = GatewayConnectionSummary(
            status = GatewayHealthStatus.UNCONFIGURED,
            statusMessage = "Device token cleared",
        )
    }

    suspend fun testHealthAndDiscoverModels(): GatewayHealth {
        _connectionSummary.update {
            it.copy(
                status = GatewayHealthStatus.CHECKING,
                statusMessage = "Checking gateway health…",
            )
        }
        val health = client.health()
        val now = System.currentTimeMillis()
        if (health.status != GatewayHealthStatus.CONNECTED) {
            _connectionSummary.update { prev ->
                prev.copy(
                    status = GatewayHealthStatus.ERROR,
                    latencyMs = health.latencyMs,
                    lastCheckedEpochMs = now,
                    statusMessage = health.message,
                )
            }
            return health
        }

        val modelsResult = client.listModels()
        return modelsResult.fold(
            onSuccess = { models ->
                cachedModelDao.replaceModels(
                    models.map {
                        CachedModelEntity(
                            id = it.id,
                            provider = it.provider,
                            supportsStreaming = it.supportsStreaming,
                            supportsToolCalling = it.supportsToolCalling,
                            supportsVision = it.supportsVision,
                            contextWindow = it.contextWindow,
                            lastSyncedEpochMs = it.lastSyncedEpochMs,
                        )
                    }
                )
                val currentDefault = preferencesRepository.preferences.first().defaultModelId
                if (currentDefault != null && models.none { it.id == currentDefault }) {
                    preferencesRepository.setDefaultModelId(models.firstOrNull()?.id)
                } else if (currentDefault == null && models.isNotEmpty()) {
                    preferencesRepository.setDefaultModelId(models.first().id)
                }
                _connectionSummary.value = GatewayConnectionSummary(
                    status = GatewayHealthStatus.CONNECTED,
                    latencyMs = health.latencyMs,
                    lastCheckedEpochMs = now,
                    lastSuccessfulEpochMs = now,
                    statusMessage = "${health.message} · ${models.size} model(s)",
                )
                health
            },
            onFailure = { err ->
                val agtErr = (err as? GatewayApiException)?.agtError
                    ?: AgtError.NetworkUnavailable(userMessage = err.message ?: "Model discovery failed.")
                _connectionSummary.update { prev ->
                    prev.copy(
                        status = GatewayHealthStatus.ERROR,
                        latencyMs = health.latencyMs,
                        lastCheckedEpochMs = now,
                        statusMessage = agtErr.userMessage,
                    )
                }
                GatewayHealth(
                    status = GatewayHealthStatus.ERROR,
                    latencyMs = health.latencyMs,
                    message = agtErr.userMessage,
                    error = agtErr,
                )
            },
        )
    }

    suspend fun refreshModels(): Result<List<GatewayModelInfo>> {
        val result = client.listModels()
        result.onSuccess { models ->
            cachedModelDao.replaceModels(
                models.map {
                    CachedModelEntity(
                        id = it.id,
                        provider = it.provider,
                        supportsStreaming = it.supportsStreaming,
                        supportsToolCalling = it.supportsToolCalling,
                        supportsVision = it.supportsVision,
                        contextWindow = it.contextWindow,
                        lastSyncedEpochMs = it.lastSyncedEpochMs,
                    )
                }
            )
            val now = System.currentTimeMillis()
            _connectionSummary.update { prev ->
                prev.copy(
                    status = GatewayHealthStatus.CONNECTED,
                    lastCheckedEpochMs = now,
                    lastSuccessfulEpochMs = now,
                    statusMessage = "Synced ${models.size} model(s)",
                )
            }
        }
        return result
    }

    suspend fun setDefaultModel(modelId: String) {
        preferencesRepository.setDefaultModelId(modelId)
    }

    fun streamChat(
        modelId: String,
        messages: List<Pair<String, String>>,
        protocolMode: GatewayProtocolMode,
    ): Flow<GatewayStreamEvent> {
        return client.streamChat(
            GatewayChatRequest(
                modelId = modelId,
                messages = messages,
                stream = true,
                protocolMode = protocolMode,
            )
        )
    }
}
