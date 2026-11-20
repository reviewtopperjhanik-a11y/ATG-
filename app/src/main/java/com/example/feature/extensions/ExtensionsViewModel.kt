package com.example.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallProgress
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionPackageFormat
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.McpTransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtensionsUiState(
    val selectedCategory: ExtensionCategory = ExtensionCategory.SKILLS,
    val selectedAgentFilter: ExtensionAgentTarget? = null,
    val searchQuery: String = "",
    val items: List<ExtensionRecord> = emptyList(),
    val inspectedExtension: ExtensionRecord? = null,
    val isBusy: Boolean = false,
    val installProgress: ExtensionInstallProgress? = null,
    val actionNotice: String? = null,
    val actionError: String? = null,
    val allowProotSandboxExtensions: Boolean = false,
    val showAddSkillDialog: Boolean = false,
    val skillSourceDraft: String = "",
    val skillExpectedShaDraft: String = "",
    val showAddPluginDialog: Boolean = false,
    val pluginSourceDraft: String = "",
    val pluginExpectedShaDraft: String = "",
    val showAddMcpDialog: Boolean = false,
    val mcpTransportDraft: McpTransportType = McpTransportType.HTTP_SSE,
    val mcpExplicitIdDraft: String? = null,
    val mcpPublisherDraft: String? = null,
    val mcpLicenseDraft: String? = null,
    val mcpAgentTargetDraft: ExtensionAgentTarget = ExtensionAgentTarget.UNIVERSAL,
    val mcpNameDraft: String = "",
    val mcpEndpointDraft: String = "",
    val mcpCommandDraft: String = "",
    val mcpDescriptionDraft: String = "",
    val mcpBearerTokenDraft: String = "",
    val mcpRequiresBearerHint: Boolean = false,
    val showSyncCatalogDialog: Boolean = false,
    val catalogUrlDraft: String = "",
) {
    val visibleItems: List<ExtensionRecord>
        get() {
            val q = searchQuery.trim().lowercase()
            return items.filter { item ->
                if (item.category != selectedCategory) return@filter false
                if (selectedAgentFilter != null && item.agentTarget != selectedAgentFilter) {
                    return@filter false
                }
                if (q.isNotEmpty()) {
                    val hay = buildString {
                        append(item.name).append(' ')
                        append(item.description).append(' ')
                        append(item.publisher).append(' ')
                        append(item.id).append(' ')
                        append(item.tags.joinToString(" "))
                    }.lowercase()
                    if (!hay.contains(q)) return@filter false
                }
                true
            }
        }
}

class ExtensionsViewModel(
    private val extensionRegistryRepository: ExtensionRegistryRepository,
    private val preferencesRepository: AppPreferencesRepository? = null,
) : ViewModel() {

    private val localState = MutableStateFlow(ExtensionsUiState())

    val uiState: StateFlow<ExtensionsUiState> = combine(
        extensionRegistryRepository.observeExtensions(),
        extensionRegistryRepository.installProgress,
        preferencesRepository?.preferences ?: flowOf(AppPreferencesState()),
        localState,
    ) { records, progress, prefs, local ->
        val updatedInspected = local.inspectedExtension?.let { current ->
            records.firstOrNull { it.id == current.id } ?: current
        }
        local.copy(
            items = records,
            installProgress = progress,
            allowProotSandboxExtensions = prefs.allowProotSandboxExtensions,
            inspectedExtension = updatedInspected,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExtensionsUiState(),
    )

    fun selectCategory(category: ExtensionCategory) {
        localState.update { it.copy(selectedCategory = category, actionNotice = null, actionError = null) }
    }

    fun selectAgentFilter(target: ExtensionAgentTarget?) {
        localState.update { it.copy(selectedAgentFilter = target) }
    }

    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchQuery = query) }
    }

    fun inspectExtension(record: ExtensionRecord) {
        localState.update { it.copy(inspectedExtension = record, actionError = null) }
    }

    fun dismissInspection() {
        localState.update { it.copy(inspectedExtension = null) }
    }

    fun toggleSandboxHost(enabled: Boolean) {
        val repo = preferencesRepository ?: run {
            localState.update {
                it.copy(actionError = "Preferences repository unavailable in this view.")
            }
            return
        }
        viewModelScope.launch {
            repo.setAllowProotSandboxExtensions(enabled)
            localState.update {
                it.copy(
                    actionError = null,
                    actionNotice = if (enabled) {
                        "Sandboxed PRoot Extension Host enabled for OpenCode & Pi plugins and local stdio MCP servers."
                    } else {
                        "Sandboxed PRoot Extension Host disabled. Plugins and local stdio MCP execution paused."
                    },
                )
            }
        }
    }

    fun requestInstall(record: ExtensionRecord) {
        if (record.category == ExtensionCategory.PLUGINS && !uiState.value.allowProotSandboxExtensions) {
            localState.update {
                it.copy(
                    inspectedExtension = null,
                    actionError = ExtensionRegistryRepository.SANDBOX_GATE_ERROR,
                )
            }
            return
        }
        if (record.category == ExtensionCategory.MCP_SERVERS &&
            (record.packageFormat == ExtensionPackageFormat.REMOTE_MCP_TEMPLATE ||
                record.packageFormat == ExtensionPackageFormat.LOCAL_STDIO_MCP_TEMPLATE ||
                record.endpointUrl != null ||
                record.localMcpCommand.isNotEmpty()) &&
            record.installState == ExtensionInstallState.NOT_INSTALLED
        ) {
            openMcpPresetDialog(record)
            return
        }

        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            val result = if (record.isBuiltIn && record.category == ExtensionCategory.SKILLS) {
                extensionRegistryRepository.installBuiltInSkill(record.id)
            } else {
                extensionRegistryRepository.installCatalogExtension(record.id)
            }
            result
                .onSuccess { installed ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            inspectedExtension = installed,
                            actionNotice = "Installed and enabled \"${installed.name}\".",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            actionError = err.message ?: "Failed to install extension.",
                        )
                    }
                }
        }
    }

    fun setExtensionEnabled(record: ExtensionRecord, enabled: Boolean) {
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.setEnabled(record.id, enabled)
                .onSuccess {
                    localState.update {
                        it.copy(
                            isBusy = false,
                            inspectedExtension = record.copy(
                                installState = if (enabled) {
                                    ExtensionInstallState.INSTALLED_ENABLED
                                } else {
                                    ExtensionInstallState.INSTALLED_DISABLED
                                }
                            ),
                            actionNotice = if (enabled) {
                                "Enabled \"${record.name}\"."
                            } else {
                                "Disabled \"${record.name}\". Permissions and tools paused."
                            },
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(isBusy = false, actionError = err.message ?: "Unable to update extension state.")
                    }
                }
        }
    }

    fun updateExtension(record: ExtensionRecord) {
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.updateExtension(record.id)
                .onSuccess { updated ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            inspectedExtension = updated,
                            actionNotice = "Updated \"${updated.name}\" to v${updated.version}.",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(isBusy = false, actionError = err.message ?: "Failed to update extension.")
                    }
                }
        }
    }

    fun testMcpConnection(record: ExtensionRecord) {
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.testMcpServerConnection(record.id)
                .onSuccess { updated ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            inspectedExtension = updated,
                            actionNotice = updated.healthMessage ?: "MCP server verified.",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(isBusy = false, actionError = err.message ?: "MCP server connection check failed.")
                    }
                }
        }
    }

    fun uninstallExtension(record: ExtensionRecord) {
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.uninstall(record.id)
                .onSuccess {
                    localState.update {
                        it.copy(
                            isBusy = false,
                            inspectedExtension = null,
                            actionNotice = "Uninstalled \"${record.name}\" and revoked all granted tools and credentials.",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(isBusy = false, actionError = err.message ?: "Failed to uninstall extension.")
                    }
                }
        }
    }

    fun openAddSkillDialog() {
        localState.update {
            it.copy(
                showAddSkillDialog = true,
                skillSourceDraft = "",
                skillExpectedShaDraft = "",
                actionError = null,
            )
        }
    }

    fun dismissAddSkillDialog() {
        if (localState.value.isBusy) return
        localState.update { it.copy(showAddSkillDialog = false) }
    }

    fun updateSkillSourceDraft(value: String) {
        localState.update { it.copy(skillSourceDraft = value, actionError = null) }
    }

    fun updateSkillExpectedShaDraft(value: String) {
        localState.update { it.copy(skillExpectedShaDraft = value, actionError = null) }
    }

    fun confirmImportSkill() {
        val src = localState.value.skillSourceDraft
        val sha = localState.value.skillExpectedShaDraft.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.importSkillPackage(
                rawSourceOrContent = src,
                expectedSha256 = sha,
            )
                .onSuccess { installed ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            showAddSkillDialog = false,
                            skillSourceDraft = "",
                            skillExpectedShaDraft = "",
                            actionNotice = "Imported Skill \"${installed.name}\" (${installed.sha256.take(12)}…).",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            actionError = err.message ?: "Skill import failed.",
                        )
                    }
                }
        }
    }

    fun openAddPluginDialog() {
        if (!uiState.value.allowProotSandboxExtensions) {
            localState.update {
                it.copy(actionError = ExtensionRegistryRepository.SANDBOX_GATE_ERROR)
            }
            return
        }
        localState.update {
            it.copy(
                showAddPluginDialog = true,
                pluginSourceDraft = "",
                pluginExpectedShaDraft = "",
                actionError = null,
            )
        }
    }

    fun dismissAddPluginDialog() {
        if (localState.value.isBusy) return
        localState.update { it.copy(showAddPluginDialog = false) }
    }

    fun updatePluginSourceDraft(value: String) {
        localState.update { it.copy(pluginSourceDraft = value, actionError = null) }
    }

    fun updatePluginExpectedShaDraft(value: String) {
        localState.update { it.copy(pluginExpectedShaDraft = value, actionError = null) }
    }

    fun confirmImportPlugin() {
        val src = localState.value.pluginSourceDraft
        val sha = localState.value.pluginExpectedShaDraft.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.importPluginPackage(
                rawSourceOrContent = src,
                expectedSha256 = sha,
            )
                .onSuccess { installed ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            showAddPluginDialog = false,
                            pluginSourceDraft = "",
                            pluginExpectedShaDraft = "",
                            actionNotice = "Installed sandboxed plugin \"${installed.name}\" (${installed.sha256.take(12)}…).",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            actionError = err.message ?: "Plugin import failed.",
                        )
                    }
                }
        }
    }

    fun openAddMcpDialog() {
        localState.update {
            it.copy(
                showAddMcpDialog = true,
                mcpTransportDraft = McpTransportType.HTTP_SSE,
                mcpExplicitIdDraft = null,
                mcpPublisherDraft = null,
                mcpLicenseDraft = null,
                mcpAgentTargetDraft = ExtensionAgentTarget.UNIVERSAL,
                mcpNameDraft = "",
                mcpEndpointDraft = "",
                mcpCommandDraft = "",
                mcpDescriptionDraft = "",
                mcpBearerTokenDraft = "",
                mcpRequiresBearerHint = false,
                actionError = null,
            )
        }
    }

    private fun openMcpPresetDialog(preset: ExtensionRecord) {
        localState.update {
            it.copy(
                inspectedExtension = null,
                showAddMcpDialog = true,
                mcpTransportDraft = preset.mcpTransportType,
                mcpExplicitIdDraft = preset.id,
                mcpPublisherDraft = preset.publisher,
                mcpLicenseDraft = preset.license,
                mcpAgentTargetDraft = preset.agentTarget,
                mcpNameDraft = preset.name,
                mcpEndpointDraft = preset.endpointUrl.orEmpty(),
                mcpCommandDraft = preset.localMcpCommand.joinToString(" "),
                mcpDescriptionDraft = preset.description,
                mcpBearerTokenDraft = "",
                mcpRequiresBearerHint = preset.requiresBearerToken,
                actionError = null,
            )
        }
    }

    fun dismissAddMcpDialog() {
        if (localState.value.isBusy) return
        localState.update { it.copy(showAddMcpDialog = false) }
    }

    fun updateMcpTransportDraft(transport: McpTransportType) {
        localState.update { it.copy(mcpTransportDraft = transport, actionError = null) }
    }

    fun updateMcpNameDraft(value: String) {
        localState.update { it.copy(mcpNameDraft = value, actionError = null) }
    }

    fun updateMcpEndpointDraft(value: String) {
        localState.update { it.copy(mcpEndpointDraft = value, actionError = null) }
    }

    fun updateMcpCommandDraft(value: String) {
        localState.update { it.copy(mcpCommandDraft = value, actionError = null) }
    }

    fun updateMcpDescriptionDraft(value: String) {
        localState.update { it.copy(mcpDescriptionDraft = value, actionError = null) }
    }

    fun updateMcpBearerTokenDraft(value: String) {
        localState.update { it.copy(mcpBearerTokenDraft = value, actionError = null) }
    }

    fun confirmRegisterMcpServer() {
        val state = localState.value
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            val result = if (state.mcpTransportDraft == McpTransportType.LOCAL_STDIO) {
                extensionRegistryRepository.registerLocalMcpServer(
                    name = state.mcpNameDraft,
                    commandLine = state.mcpCommandDraft,
                    description = state.mcpDescriptionDraft,
                    publisher = state.mcpPublisherDraft ?: "Local PRoot MCP",
                    license = state.mcpLicenseDraft ?: "Package-Provided",
                    envToken = state.mcpBearerTokenDraft.takeIf { it.isNotBlank() },
                    verifyConnection = true,
                    explicitId = state.mcpExplicitIdDraft,
                    agentTarget = state.mcpAgentTargetDraft,
                )
            } else {
                extensionRegistryRepository.registerMcpServer(
                    name = state.mcpNameDraft,
                    endpointUrl = state.mcpEndpointDraft,
                    description = state.mcpDescriptionDraft,
                    publisher = state.mcpPublisherDraft ?: "Remote MCP",
                    license = state.mcpLicenseDraft ?: "Server-Provided",
                    bearerToken = state.mcpBearerTokenDraft.takeIf { it.isNotBlank() },
                    verifyConnection = true,
                    explicitId = state.mcpExplicitIdDraft,
                    agentTarget = state.mcpAgentTargetDraft,
                )
            }
            result
                .onSuccess { record ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            showAddMcpDialog = false,
                            mcpTransportDraft = McpTransportType.HTTP_SSE,
                            mcpExplicitIdDraft = null,
                            mcpPublisherDraft = null,
                            mcpLicenseDraft = null,
                            mcpNameDraft = "",
                            mcpEndpointDraft = "",
                            mcpCommandDraft = "",
                            mcpDescriptionDraft = "",
                            mcpBearerTokenDraft = "",
                            mcpRequiresBearerHint = false,
                            actionNotice = record.healthMessage ?: "Connected MCP server \"${record.name}\".",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            actionError = err.message ?: "Failed to connect MCP server.",
                        )
                    }
                }
        }
    }

    fun openSyncCatalogDialog() {
        localState.update {
            it.copy(
                showSyncCatalogDialog = true,
                actionError = null,
            )
        }
    }

    fun dismissSyncCatalogDialog() {
        if (localState.value.isBusy) return
        localState.update { it.copy(showSyncCatalogDialog = false) }
    }

    fun updateCatalogUrlDraft(value: String) {
        localState.update { it.copy(catalogUrlDraft = value, actionError = null) }
    }

    fun confirmSyncCatalog() {
        val url = localState.value.catalogUrlDraft.trim()
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, actionError = null, actionNotice = null) }
            extensionRegistryRepository.syncRemoteCatalog(url)
                .onSuccess { count ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            showSyncCatalogDialog = false,
                            actionNotice = "Synced $count extension(s) from remote catalog.",
                        )
                    }
                }
                .onFailure { err ->
                    localState.update {
                        it.copy(
                            isBusy = false,
                            actionError = err.message ?: "Failed to sync remote extension catalog.",
                        )
                    }
                }
        }
    }

    class Factory(
        private val extensionRegistryRepository: ExtensionRegistryRepository,
        private val preferencesRepository: AppPreferencesRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ExtensionsViewModel(
                extensionRegistryRepository = extensionRegistryRepository,
                preferencesRepository = preferencesRepository,
            ) as T
        }
    }
}
