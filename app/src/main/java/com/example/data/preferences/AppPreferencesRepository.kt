package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.domain.model.AgentBackendId
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilRole
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private val agtDataStoreCache = ConcurrentHashMap<String, DataStore<Preferences>>()

private fun Context.getOrCreateAgtPreferencesDataStore(): DataStore<Preferences> {
    val targetFile = applicationContext.preferencesDataStoreFile("agt_code_preferences")
    val canonicalKey = runCatching { targetFile.canonicalPath }.getOrDefault(targetFile.absolutePath)
    return agtDataStoreCache.computeIfAbsent(canonicalKey) {
        PreferenceDataStoreFactory.create(
            produceFile = { targetFile }
        )
    }
}

class AppPreferencesRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences>
        get() = context.getOrCreateAgtPreferencesDataStore()

    private suspend fun editPreferences(transform: (MutablePreferences) -> Unit) {
        withContext(Dispatchers.IO) {
            dataStore.edit { transform(it) }
        }
    }

    private object Keys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GATEWAY_BASE_URL = stringPreferencesKey("gateway_base_url")
        val GATEWAY_PROTOCOL = stringPreferencesKey("gateway_protocol")
        val WEB_SEARCH_BASE_URL = stringPreferencesKey("web_search_base_url")
        val GITHUB_SEARCH_BASE_URL = stringPreferencesKey("github_search_base_url")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val RUNTIME_MANIFEST_URL = stringPreferencesKey("runtime_manifest_url")
        val OPENCODE_MANIFEST_URL = stringPreferencesKey("opencode_manifest_url")
        val ACTIVE_AGENT_ID = stringPreferencesKey("active_agent_id")
        val PI_MANIFEST_URL = stringPreferencesKey("pi_manifest_url")
        val GIT_DEFAULT_BRANCH = stringPreferencesKey("git_default_branch")
        val GIT_AUTHOR_NAME = stringPreferencesKey("git_author_name")
        val GIT_AUTHOR_EMAIL = stringPreferencesKey("git_author_email")
        val GITHUB_OAUTH_CLIENT_ID = stringPreferencesKey("github_oauth_client_id")
        val GITHUB_USERNAME = stringPreferencesKey("github_username")
        val GITHUB_SCOPES = stringPreferencesKey("github_scopes")
        val AGENT_STEP_LIMIT = intPreferencesKey("agent_step_limit")
        val COMMAND_TIMEOUT_SECONDS = intPreferencesKey("command_timeout_seconds")
        val AUTO_DEBUG_ENABLED = booleanPreferencesKey("auto_debug_enabled")
        val CHECKPOINTS_ENABLED = booleanPreferencesKey("checkpoints_enabled")
        val REQUIRE_PLAN_APPROVAL = booleanPreferencesKey("require_plan_approval")
        val REQUIRE_DESTRUCTIVE_APPROVAL = booleanPreferencesKey("require_destructive_approval")
        val REQUIRE_PUSH_APPROVAL = booleanPreferencesKey("require_push_approval")
        val REQUIRE_DEPLOY_APPROVAL = booleanPreferencesKey("require_deploy_approval")
        val SECRET_MASKING_ENABLED = booleanPreferencesKey("secret_masking_enabled")
        val NOTIFY_TASK_COMPLETE = booleanPreferencesKey("notify_task_complete")
        val NOTIFY_TASK_FAILURE = booleanPreferencesKey("notify_task_failure")
        val NOTIFY_APPROVALS = booleanPreferencesKey("notify_approvals")
        val NOTIFY_RUNTIME_EVENTS = booleanPreferencesKey("notify_runtime_events")
        val ALLOW_PROOT_SANDBOX_EXTENSIONS = booleanPreferencesKey("allow_proot_sandbox_extensions")
        val COUNCIL_DEFAULT_MODE = stringPreferencesKey("council_default_mode")
        val COUNCIL_ROLE_MODEL_OVERRIDES = stringPreferencesKey("council_role_model_overrides")
        val COUNCIL_ENABLED_SPECIALISTS = stringPreferencesKey("council_enabled_specialists")
        val COUNCIL_TOKEN_BUDGET = intPreferencesKey("council_token_budget")
    }

    val preferences: Flow<AppPreferencesState> = dataStore.data.map { prefs ->
        val themeName = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = ThemeMode.entries.firstOrNull { it.name == themeName } ?: ThemeMode.SYSTEM
        val protocolName = prefs[Keys.GATEWAY_PROTOCOL] ?: GatewayProtocolMode.CHAT_COMPLETIONS.name
        val protocol = GatewayProtocolMode.entries.firstOrNull { it.name == protocolName }
            ?: GatewayProtocolMode.CHAT_COMPLETIONS
        AppPreferencesState(
            hasCompletedOnboarding = prefs[Keys.HAS_COMPLETED_ONBOARDING] ?: false,
            themeMode = themeMode,
            gatewayBaseUrl = prefs[Keys.GATEWAY_BASE_URL] ?: "http://127.0.0.1:8787",
            gatewayProtocol = protocol,
            hasGatewayToken = false,
            webSearchBaseUrl = prefs[Keys.WEB_SEARCH_BASE_URL] ?: "",
            hasWebSearchToken = false,
            githubSearchBaseUrl = prefs[Keys.GITHUB_SEARCH_BASE_URL] ?: "",
            hasGitHubSearchToken = false,
            defaultModelId = prefs[Keys.DEFAULT_MODEL_ID],
            runtimeManifestUrl = prefs[Keys.RUNTIME_MANIFEST_URL] ?: "",
            openCodeManifestUrl = prefs[Keys.OPENCODE_MANIFEST_URL] ?: "",
            activeAgentId = AgentBackendId.fromId(prefs[Keys.ACTIVE_AGENT_ID]).id,
            piManifestUrl = prefs[Keys.PI_MANIFEST_URL] ?: "",
            gitDefaultBranch = prefs[Keys.GIT_DEFAULT_BRANCH] ?: "main",
            gitAuthorName = prefs[Keys.GIT_AUTHOR_NAME] ?: "",
            gitAuthorEmail = prefs[Keys.GIT_AUTHOR_EMAIL] ?: "",
            githubOAuthClientId = prefs[Keys.GITHUB_OAUTH_CLIENT_ID] ?: "",
            githubUsername = prefs[Keys.GITHUB_USERNAME]?.takeIf { it.isNotBlank() },
            githubScopes = prefs[Keys.GITHUB_SCOPES]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            agentStepLimit = prefs[Keys.AGENT_STEP_LIMIT] ?: 20,
            commandTimeoutSeconds = prefs[Keys.COMMAND_TIMEOUT_SECONDS] ?: 120,
            autoDebugEnabled = prefs[Keys.AUTO_DEBUG_ENABLED] ?: true,
            checkpointsEnabled = prefs[Keys.CHECKPOINTS_ENABLED] ?: true,
            requirePlanApproval = prefs[Keys.REQUIRE_PLAN_APPROVAL] ?: false,
            requireDestructiveApproval = prefs[Keys.REQUIRE_DESTRUCTIVE_APPROVAL] ?: true,
            requirePushApproval = prefs[Keys.REQUIRE_PUSH_APPROVAL] ?: true,
            requireDeployApproval = prefs[Keys.REQUIRE_DEPLOY_APPROVAL] ?: true,
            secretMaskingEnabled = prefs[Keys.SECRET_MASKING_ENABLED] ?: true,
            notifyTaskComplete = prefs[Keys.NOTIFY_TASK_COMPLETE] ?: true,
            notifyTaskFailure = prefs[Keys.NOTIFY_TASK_FAILURE] ?: true,
            notifyApprovals = prefs[Keys.NOTIFY_APPROVALS] ?: true,
            notifyRuntimeEvents = prefs[Keys.NOTIFY_RUNTIME_EVENTS] ?: true,
            allowProotSandboxExtensions = prefs[Keys.ALLOW_PROOT_SANDBOX_EXTENSIONS] ?: false,
            councilDefaultMode = prefs[Keys.COUNCIL_DEFAULT_MODE]?.let { raw ->
                CouncilExecutionMode.entries.firstOrNull { it.name == raw || it.id == raw }
            } ?: CouncilExecutionMode.DIRECT_CODE,
            councilRoleModelOverrides = decodeRoleOverrides(prefs[Keys.COUNCIL_ROLE_MODEL_OVERRIDES]),
            councilEnabledSpecialists = decodeEnabledSpecialists(prefs[Keys.COUNCIL_ENABLED_SPECIALISTS]),
            councilTokenBudget = (prefs[Keys.COUNCIL_TOKEN_BUDGET] ?: 24_000).coerceIn(4_000, 128_000),
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        editPreferences { it[Keys.HAS_COMPLETED_ONBOARDING] = completed }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        editPreferences { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setGatewayBaseUrl(url: String) {
        editPreferences { it[Keys.GATEWAY_BASE_URL] = url.trim() }
    }

    suspend fun setGatewayProtocol(protocol: GatewayProtocolMode) {
        editPreferences { it[Keys.GATEWAY_PROTOCOL] = protocol.name }
    }

    suspend fun setWebSearchBaseUrl(url: String) {
        editPreferences { it[Keys.WEB_SEARCH_BASE_URL] = url.trim() }
    }

    suspend fun setGitHubSearchBaseUrl(url: String) {
        editPreferences { it[Keys.GITHUB_SEARCH_BASE_URL] = url.trim() }
    }

    suspend fun setDefaultModelId(modelId: String?) {
        editPreferences {
            if (modelId.isNullOrBlank()) {
                it.remove(Keys.DEFAULT_MODEL_ID)
            } else {
                it[Keys.DEFAULT_MODEL_ID] = modelId
            }
        }
    }

    suspend fun setRuntimeManifestUrl(url: String) {
        editPreferences { it[Keys.RUNTIME_MANIFEST_URL] = url.trim() }
    }

    suspend fun setOpenCodeManifestUrl(url: String) {
        editPreferences { it[Keys.OPENCODE_MANIFEST_URL] = url.trim() }
    }

    suspend fun setActiveAgentId(agentId: String) {
        val normalized = AgentBackendId.fromId(agentId).id
        editPreferences { it[Keys.ACTIVE_AGENT_ID] = normalized }
    }

    suspend fun setPiManifestUrl(url: String) {
        editPreferences { it[Keys.PI_MANIFEST_URL] = url.trim() }
    }

    suspend fun setGitIdentity(defaultBranch: String, authorName: String, authorEmail: String) {
        editPreferences {
            it[Keys.GIT_DEFAULT_BRANCH] = defaultBranch.trim().ifBlank { "main" }
            it[Keys.GIT_AUTHOR_NAME] = authorName.trim()
            it[Keys.GIT_AUTHOR_EMAIL] = authorEmail.trim()
        }
    }

    suspend fun setGitHubOAuthClientId(clientId: String) {
        editPreferences {
            it[Keys.GITHUB_OAUTH_CLIENT_ID] = clientId.trim()
        }
    }

    suspend fun setGitHubAccountMetadata(username: String?, scopes: List<String>) {
        editPreferences {
            if (username.isNullOrBlank()) {
                it.remove(Keys.GITHUB_USERNAME)
                it.remove(Keys.GITHUB_SCOPES)
            } else {
                it[Keys.GITHUB_USERNAME] = username.trim()
                it[Keys.GITHUB_SCOPES] = scopes.joinToString(",")
            }
        }
    }

    suspend fun setAgentPolicy(
        stepLimit: Int,
        timeoutSeconds: Int,
        autoDebug: Boolean,
        checkpoints: Boolean,
        requirePlanApproval: Boolean,
    ) {
        editPreferences {
            it[Keys.AGENT_STEP_LIMIT] = stepLimit.coerceIn(1, 100)
            it[Keys.COMMAND_TIMEOUT_SECONDS] = timeoutSeconds.coerceIn(10, 900)
            it[Keys.AUTO_DEBUG_ENABLED] = autoDebug
            it[Keys.CHECKPOINTS_ENABLED] = checkpoints
            it[Keys.REQUIRE_PLAN_APPROVAL] = requirePlanApproval
        }
    }

    suspend fun setSecurityPolicy(
        requireDestructive: Boolean,
        requirePush: Boolean,
        requireDeploy: Boolean,
        secretMasking: Boolean,
    ) {
        editPreferences {
            it[Keys.REQUIRE_DESTRUCTIVE_APPROVAL] = requireDestructive
            it[Keys.REQUIRE_PUSH_APPROVAL] = requirePush
            it[Keys.REQUIRE_DEPLOY_APPROVAL] = requireDeploy
            it[Keys.SECRET_MASKING_ENABLED] = secretMasking
        }
    }

    suspend fun setNotificationPreferences(
        taskComplete: Boolean,
        taskFailure: Boolean,
        approvals: Boolean,
        runtimeEvents: Boolean,
    ) {
        editPreferences {
            it[Keys.NOTIFY_TASK_COMPLETE] = taskComplete
            it[Keys.NOTIFY_TASK_FAILURE] = taskFailure
            it[Keys.NOTIFY_APPROVALS] = approvals
            it[Keys.NOTIFY_RUNTIME_EVENTS] = runtimeEvents
        }
    }

    suspend fun setAllowProotSandboxExtensions(allowed: Boolean) {
        editPreferences {
            it[Keys.ALLOW_PROOT_SANDBOX_EXTENSIONS] = allowed
        }
    }

    suspend fun setCouncilDefaultMode(mode: CouncilExecutionMode) {
        editPreferences {
            it[Keys.COUNCIL_DEFAULT_MODE] = mode.name
        }
    }

    suspend fun setCouncilRoleModel(role: CouncilRole, modelId: String?) {
        editPreferences { prefs ->
            val current = decodeRoleOverrides(prefs[Keys.COUNCIL_ROLE_MODEL_OVERRIDES]).toMutableMap()
            val cleaned = modelId?.trim().orEmpty()
            if (cleaned.isEmpty()) {
                current.remove(role)
            } else {
                current[role] = cleaned
            }
            prefs[Keys.COUNCIL_ROLE_MODEL_OVERRIDES] = encodeRoleOverrides(current)
        }
    }

    suspend fun setCouncilSpecialistEnabled(role: CouncilRole, enabled: Boolean) {
        if (!role.isSpecialistDebater) return
        editPreferences { prefs ->
            val current = decodeEnabledSpecialists(prefs[Keys.COUNCIL_ENABLED_SPECIALISTS]).toMutableSet()
            if (enabled) {
                current.add(role)
            } else if (current.size > 1) {
                // Keep at least 1 specialist active so Council deliberation remains meaningful
                current.remove(role)
            }
            prefs[Keys.COUNCIL_ENABLED_SPECIALISTS] = current.joinToString(",") { it.id }
        }
    }

    suspend fun setCouncilTokenBudget(budgetTokens: Int) {
        editPreferences {
            it[Keys.COUNCIL_TOKEN_BUDGET] = budgetTokens.coerceIn(4_000, 128_000)
        }
    }

    suspend fun setCouncilPolicy(mode: CouncilExecutionMode, budgetTokens: Int) {
        editPreferences {
            it[Keys.COUNCIL_DEFAULT_MODE] = mode.name
            it[Keys.COUNCIL_TOKEN_BUDGET] = budgetTokens.coerceIn(4_000, 128_000)
        }
    }

    private fun decodeRoleOverrides(raw: String?): Map<CouncilRole, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.keys().forEach { key ->
                val role = CouncilRole.fromId(key)
                val model = obj.optString(key).trim()
                if (role != null && model.isNotEmpty()) {
                    put(role, model)
                }
            }
        }
    }

    private fun encodeRoleOverrides(map: Map<CouncilRole, String>): String {
        val obj = JSONObject()
        map.forEach { (role, modelId) ->
            if (modelId.isNotBlank()) obj.put(role.id, modelId.trim())
        }
        return obj.toString()
    }

    private fun decodeEnabledSpecialists(raw: String?): Set<CouncilRole> {
        if (raw == null) return CouncilRole.DEFAULT_SPECIALISTS
        val parsed = raw.split(',')
            .mapNotNull { CouncilRole.fromId(it) }
            .filter { it.isSpecialistDebater }
            .toSet()
        return parsed.ifEmpty { CouncilRole.DEFAULT_SPECIALISTS }
    }
}
