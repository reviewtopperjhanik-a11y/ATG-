package com.example.feature.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.ExtensionAgentTarget
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallProgress
import com.example.domain.model.ExtensionInstallStage
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.McpTransportType
import com.example.ui.theme.CodeTextStyle

@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedCategory) {
        listState.scrollToItem(0)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.extensions_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(
                        onClick = viewModel::openSyncCatalogDialog,
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ext_sync_catalog_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = stringResource(R.string.ext_sync_catalog),
                        )
                    }
                    when (state.selectedCategory) {
                        ExtensionCategory.SKILLS -> OutlinedButton(
                            onClick = viewModel::openAddSkillDialog,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("ext_add_skill_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ext_add_skill))
                        }
                        ExtensionCategory.MCP_SERVERS -> OutlinedButton(
                            onClick = viewModel::openAddMcpDialog,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("ext_add_mcp_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ext_add_mcp))
                        }
                        ExtensionCategory.PLUGINS -> if (state.allowProotSandboxExtensions) {
                            OutlinedButton(
                                onClick = viewModel::openAddPluginDialog,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("ext_add_plugin_button"),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.ext_add_plugin))
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExtensionCategory.entries.forEach { category ->
                    val selected = category == state.selectedCategory
                    val label = when (category) {
                        ExtensionCategory.SKILLS -> stringResource(R.string.ext_skills)
                        ExtensionCategory.MCP_SERVERS -> stringResource(R.string.ext_mcp)
                        ExtensionCategory.PLUGINS -> stringResource(R.string.ext_plugins)
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .minimumInteractiveComponentSize()
                            .clickable { viewModel.selectCategory(category) }
                            .testTag("ext_tab_${category.name.lowercase()}"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (state.selectedCategory != ExtensionCategory.PLUGINS || state.allowProotSandboxExtensions) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    placeholder = { Text(stringResource(R.string.ext_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("ext_search_input"),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedAgentFilter == null,
                        onClick = { viewModel.selectAgentFilter(null) },
                        label = { Text(stringResource(R.string.ext_filter_all)) },
                        modifier = Modifier.testTag("ext_agent_filter_all"),
                    )
                    FilterChip(
                        selected = state.selectedAgentFilter == ExtensionAgentTarget.OPENCODE,
                        onClick = { viewModel.selectAgentFilter(ExtensionAgentTarget.OPENCODE) },
                        label = { Text(ExtensionAgentTarget.OPENCODE.label) },
                        modifier = Modifier.testTag("ext_agent_filter_opencode"),
                    )
                    FilterChip(
                        selected = state.selectedAgentFilter == ExtensionAgentTarget.PI,
                        onClick = { viewModel.selectAgentFilter(ExtensionAgentTarget.PI) },
                        label = { Text(ExtensionAgentTarget.PI.label) },
                        modifier = Modifier.testTag("ext_agent_filter_pi"),
                    )
                    FilterChip(
                        selected = state.selectedAgentFilter == ExtensionAgentTarget.UNIVERSAL,
                        onClick = { viewModel.selectAgentFilter(ExtensionAgentTarget.UNIVERSAL) },
                        label = { Text(ExtensionAgentTarget.UNIVERSAL.label) },
                        modifier = Modifier.testTag("ext_agent_filter_universal"),
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.installProgress?.let { prog ->
                    item(key = "ext_install_progress") {
                        InstallProgressCard(progress = prog)
                    }
                }
                if (state.isBusy && state.installProgress == null) {
                    item(key = "ext_loading_row") {
                        AgtLoadingRow(label = stringResource(R.string.ext_updating_registry))
                    }
                }
                if (state.actionError != null) {
                    item(key = "ext_action_error") {
                        AgtInfoBanner(
                            title = stringResource(R.string.ext_error_title),
                            message = state.actionError.orEmpty(),
                            isError = true,
                        )
                    }
                }
                if (state.actionNotice != null) {
                    item(key = "ext_action_notice") {
                        AgtInfoBanner(
                            title = stringResource(R.string.ext_updated_title),
                            message = state.actionNotice.orEmpty(),
                        )
                    }
                }

                if (state.selectedCategory == ExtensionCategory.PLUGINS) {
                    item(key = "ext_plugins_sandbox_card") {
                        AgtCard(modifier = Modifier.testTag("ext_plugins_sandbox_card")) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = if (state.allowProotSandboxExtensions) {
                                            stringResource(R.string.ext_plugins_sandbox_active_title)
                                        } else {
                                            stringResource(R.string.ext_plugins_sandbox_title)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Switch(
                                    checked = state.allowProotSandboxExtensions,
                                    onCheckedChange = viewModel::toggleSandboxHost,
                                    modifier = Modifier.testTag("ext_plugins_sandbox_switch"),
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (state.allowProotSandboxExtensions) {
                                    stringResource(R.string.ext_plugins_sandbox_active_body)
                                } else {
                                    stringResource(R.string.ext_plugins_sandbox_body)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (state.visibleItems.isEmpty()) {
                    item(key = "ext_empty_state") {
                        val (title, desc, icon) = when (state.selectedCategory) {
                            ExtensionCategory.SKILLS -> Triple(
                                stringResource(R.string.ext_empty_skills_title),
                                stringResource(R.string.ext_empty_skills_desc),
                                Icons.Outlined.Psychology,
                            )
                            ExtensionCategory.MCP_SERVERS -> Triple(
                                stringResource(R.string.ext_empty_mcp_title),
                                stringResource(R.string.ext_empty_mcp_desc),
                                Icons.Outlined.Hub,
                            )
                            ExtensionCategory.PLUGINS -> Triple(
                                stringResource(R.string.ext_empty_plugins_title),
                                if (state.allowProotSandboxExtensions) {
                                    stringResource(R.string.ext_empty_plugins_enabled_desc)
                                } else {
                                    stringResource(R.string.ext_empty_plugins_desc)
                                },
                                Icons.Outlined.Extension,
                            )
                        }
                        AgtEmptyState(title = title, description = desc, icon = icon)
                    }
                } else {
                    items(state.visibleItems, key = { it.id }) { item ->
                        ExtensionItemRow(
                            record = item,
                            onClick = { viewModel.inspectExtension(item) },
                        )
                    }
                }
            }
        }
    }

    state.inspectedExtension?.let { ext ->
        ExtensionInspectDialog(
            ext = ext,
            isBusy = state.isBusy,
            onDismiss = viewModel::dismissInspection,
            onInstall = { viewModel.requestInstall(ext) },
            onEnable = { viewModel.setExtensionEnabled(ext, true) },
            onDisable = { viewModel.setExtensionEnabled(ext, false) },
            onUpdate = { viewModel.updateExtension(ext) },
            onTestMcp = { viewModel.testMcpConnection(ext) },
            onUninstall = { viewModel.uninstallExtension(ext) },
        )
    }

    if (state.showAddSkillDialog) {
        AddSkillDialog(
            sourceDraft = state.skillSourceDraft,
            expectedShaDraft = state.skillExpectedShaDraft,
            isBusy = state.isBusy,
            error = state.actionError,
            onSourceChange = viewModel::updateSkillSourceDraft,
            onShaChange = viewModel::updateSkillExpectedShaDraft,
            onDismiss = viewModel::dismissAddSkillDialog,
            onConfirm = viewModel::confirmImportSkill,
        )
    }

    if (state.showAddPluginDialog) {
        AddPluginDialog(
            sourceDraft = state.pluginSourceDraft,
            expectedShaDraft = state.pluginExpectedShaDraft,
            isBusy = state.isBusy,
            error = state.actionError,
            onSourceChange = viewModel::updatePluginSourceDraft,
            onShaChange = viewModel::updatePluginExpectedShaDraft,
            onDismiss = viewModel::dismissAddPluginDialog,
            onConfirm = viewModel::confirmImportPlugin,
        )
    }

    if (state.showAddMcpDialog) {
        AddMcpServerDialog(
            transportDraft = state.mcpTransportDraft,
            nameDraft = state.mcpNameDraft,
            endpointDraft = state.mcpEndpointDraft,
            commandDraft = state.mcpCommandDraft,
            descriptionDraft = state.mcpDescriptionDraft,
            bearerTokenDraft = state.mcpBearerTokenDraft,
            requiresBearerHint = state.mcpRequiresBearerHint,
            isBusy = state.isBusy,
            error = state.actionError,
            onTransportChange = viewModel::updateMcpTransportDraft,
            onNameChange = viewModel::updateMcpNameDraft,
            onEndpointChange = viewModel::updateMcpEndpointDraft,
            onCommandChange = viewModel::updateMcpCommandDraft,
            onDescriptionChange = viewModel::updateMcpDescriptionDraft,
            onTokenChange = viewModel::updateMcpBearerTokenDraft,
            onDismiss = viewModel::dismissAddMcpDialog,
            onConfirm = viewModel::confirmRegisterMcpServer,
        )
    }

    if (state.showSyncCatalogDialog) {
        SyncCatalogDialog(
            catalogUrlDraft = state.catalogUrlDraft,
            isBusy = state.isBusy,
            error = state.actionError,
            onUrlChange = viewModel::updateCatalogUrlDraft,
            onDismiss = viewModel::dismissSyncCatalogDialog,
            onConfirm = viewModel::confirmSyncCatalog,
        )
    }
}

@Composable
private fun InstallProgressCard(progress: ExtensionInstallProgress) {
    val stageLabel = when (progress.stage) {
        ExtensionInstallStage.DOWNLOADING -> stringResource(R.string.ext_stage_downloading)
        ExtensionInstallStage.VERIFYING -> stringResource(R.string.ext_stage_verifying)
        ExtensionInstallStage.EXTRACTING -> stringResource(R.string.ext_stage_extracting)
        ExtensionInstallStage.INSTALLING -> stringResource(R.string.ext_stage_installing)
        ExtensionInstallStage.FAILED -> stringResource(R.string.ext_error_title)
        ExtensionInstallStage.IDLE -> ""
    }
    AgtCard(modifier = Modifier.testTag("ext_install_progress_card")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "${progress.extensionName} · $stageLabel",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            progress.progressPercent?.let { pct ->
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        val pct = progress.progressPercent
        if (pct != null && progress.stage == ExtensionInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (progress.detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = progress.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtensionInspectDialog(
    ext: ExtensionRecord,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUpdate: () -> Unit,
    onTestMcp: () -> Unit,
    onUninstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ext.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgtStatusBadge(
                        label = ext.agentTarget.label,
                        tone = when (ext.agentTarget) {
                            ExtensionAgentTarget.OPENCODE -> StatusTone.ACCENT
                            ExtensionAgentTarget.PI -> StatusTone.SUCCESS
                            ExtensionAgentTarget.UNIVERSAL -> StatusTone.NEUTRAL
                        },
                    )
                    if (ext.tags.isNotEmpty()) {
                        AgtStatusBadge(
                            label = ext.tags.take(2).joinToString(" · "),
                            tone = StatusTone.NEUTRAL,
                        )
                    }
                }
                Text(ext.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "ID: ${ext.id}\nPublisher: ${ext.publisher} · v${ext.version}\nLicense: ${ext.license} · ${ext.compatibility}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Permissions: ${ext.permissions.joinToString(", ").ifBlank { "None" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (ext.sourceUri.isNotBlank()) {
                    Text(
                        text = "Source: ${ext.sourceUri}",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ext.sha256.isNotBlank()) {
                    Text(
                        text = "SHA-256: ${ext.sha256.take(24)}…",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ext.localMcpCommand.isNotEmpty()) {
                    Text(
                        text = "PRoot Command: ${ext.localMcpCommand.joinToString(" ")}",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (ext.endpointUrl != null) {
                    Text(
                        text = "Endpoint: ${ext.endpointUrl}" +
                            if (ext.hasStoredToken) "\nBearer Token: Stored in Android Keystore" else "",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!ext.pluginEntrypoint.isNullOrBlank()) {
                    Text(
                        text = "Plugin Entrypoint: ${ext.pluginEntrypoint}",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!ext.healthMessage.isNullOrBlank()) {
                    AgtInfoBanner(
                        title = "Verification Status",
                        message = ext.healthMessage.orEmpty(),
                    )
                }
                if (ext.discoveredTools.isNotEmpty()) {
                    Text(
                        text = "Discovered MCP Tools (${ext.discoveredTools.size}):",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ext.discoveredTools.forEach { tool ->
                        val riskSuffix = if (tool.isDestructive) " [Approval Required]" else ""
                        Text(
                            text = "• ${tool.name}$riskSuffix" +
                                if (tool.description.isNotBlank()) " — ${tool.description}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tool.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (!ext.skillMarkdownPreview.isNullOrBlank()) {
                    Text(
                        text = if (ext.category == ExtensionCategory.PLUGINS) {
                            "Sandboxed JS/TS plugin preview:"
                        } else {
                            "Non-executable SKILL.md preview:"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = ext.skillMarkdownPreview.orEmpty().take(500),
                            style = CodeTextStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (ext.installState) {
                    ExtensionInstallState.NOT_INSTALLED -> {
                        Button(
                            onClick = onInstall,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ext_install_button"),
                        ) {
                            Text(
                                if (ext.category == ExtensionCategory.MCP_SERVERS) {
                                    stringResource(R.string.ext_connect_action)
                                } else {
                                    stringResource(R.string.ext_install_action)
                                }
                            )
                        }
                    }
                    ExtensionInstallState.INSTALLED_ENABLED -> {
                        if (ext.category == ExtensionCategory.MCP_SERVERS) {
                            OutlinedButton(
                                onClick = onTestMcp,
                                enabled = !isBusy,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("ext_test_mcp_button"),
                            ) {
                                Text(stringResource(R.string.ext_verify_action))
                            }
                        }
                        OutlinedButton(
                            onClick = onDisable,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ext_disable_button"),
                        ) {
                            Text(stringResource(R.string.ext_disable_action))
                        }
                    }
                    ExtensionInstallState.INSTALLED_DISABLED -> {
                        Button(
                            onClick = onEnable,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ext_enable_button"),
                        ) {
                            Text(stringResource(R.string.ext_enable_action))
                        }
                    }
                    ExtensionInstallState.UPDATE_AVAILABLE -> {
                        Button(
                            onClick = onUpdate,
                            enabled = !isBusy,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ext_update_button"),
                        ) {
                            Text(stringResource(R.string.ext_update_action))
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ext.installState != ExtensionInstallState.NOT_INSTALLED) {
                    TextButton(
                        onClick = onUninstall,
                        enabled = !isBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ext_uninstall_button"),
                    ) {
                        Text(stringResource(R.string.ext_uninstall_action))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun AddSkillDialog(
    sourceDraft: String,
    expectedShaDraft: String,
    isBusy: Boolean,
    error: String?,
    onSourceChange: (String) -> Unit,
    onShaChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ext_import_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = sourceDraft,
                    onValueChange = onSourceChange,
                    label = { Text(stringResource(R.string.ext_import_source_label)) },
                    placeholder = { Text("owner/repo or https://github.com/… or SKILL.md") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_skill_source_input"),
                )
                OutlinedTextField(
                    value = expectedShaDraft,
                    onValueChange = onShaChange,
                    label = { Text(stringResource(R.string.ext_import_sha_label)) },
                    singleLine = true,
                    textStyle = CodeTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_skill_sha_input"),
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy && sourceDraft.isNotBlank(),
                modifier = Modifier.testTag("ext_skill_import_confirm"),
            ) {
                Text(stringResource(R.string.ext_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddPluginDialog(
    sourceDraft: String,
    expectedShaDraft: String,
    isBusy: Boolean,
    error: String?,
    onSourceChange: (String) -> Unit,
    onShaChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_plugin_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ext_plugin_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = sourceDraft,
                    onValueChange = onSourceChange,
                    label = { Text(stringResource(R.string.ext_plugin_source_label)) },
                    placeholder = { Text("https://...index.ts or owner/repo or .zip/.tar.gz") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_plugin_source_input"),
                )
                OutlinedTextField(
                    value = expectedShaDraft,
                    onValueChange = onShaChange,
                    label = { Text(stringResource(R.string.ext_import_sha_label)) },
                    singleLine = true,
                    textStyle = CodeTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_plugin_sha_input"),
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy && sourceDraft.isNotBlank(),
                modifier = Modifier.testTag("ext_plugin_import_confirm"),
            ) {
                Text(stringResource(R.string.ext_plugin_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddMcpServerDialog(
    transportDraft: McpTransportType,
    nameDraft: String,
    endpointDraft: String,
    commandDraft: String,
    descriptionDraft: String,
    bearerTokenDraft: String,
    requiresBearerHint: Boolean,
    isBusy: Boolean,
    error: String?,
    onTransportChange: (McpTransportType) -> Unit,
    onNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val canConfirm = !isBusy && nameDraft.isNotBlank() && when (transportDraft) {
        McpTransportType.HTTP_SSE -> endpointDraft.isNotBlank()
        McpTransportType.LOCAL_STDIO -> commandDraft.isNotBlank()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_mcp_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ext_mcp_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = transportDraft == McpTransportType.HTTP_SSE,
                        onClick = { onTransportChange(McpTransportType.HTTP_SSE) },
                        label = { Text(stringResource(R.string.ext_mcp_transport_http)) },
                        modifier = Modifier.testTag("ext_mcp_transport_http"),
                    )
                    FilterChip(
                        selected = transportDraft == McpTransportType.LOCAL_STDIO,
                        onClick = { onTransportChange(McpTransportType.LOCAL_STDIO) },
                        label = { Text(stringResource(R.string.ext_mcp_transport_stdio)) },
                        modifier = Modifier.testTag("ext_mcp_transport_stdio"),
                    )
                }
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = onNameChange,
                    label = { Text("Server name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_mcp_name_input"),
                )
                if (transportDraft == McpTransportType.HTTP_SSE) {
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = onEndpointChange,
                        label = { Text("Endpoint URL (https://... or http://127.0.0.1:...)") },
                        singleLine = true,
                        textStyle = CodeTextStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ext_mcp_url_input"),
                    )
                } else {
                    OutlinedTextField(
                        value = commandDraft,
                        onValueChange = onCommandChange,
                        label = { Text(stringResource(R.string.ext_mcp_command_label)) },
                        placeholder = { Text(stringResource(R.string.ext_mcp_command_hint)) },
                        singleLine = true,
                        textStyle = CodeTextStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ext_mcp_command_input"),
                    )
                }
                OutlinedTextField(
                    value = descriptionDraft,
                    onValueChange = onDescriptionChange,
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bearerTokenDraft,
                    onValueChange = onTokenChange,
                    label = {
                        Text(
                            if (requiresBearerHint) {
                                "Bearer token / PAT (Required by server, Keystore)"
                            } else {
                                "Bearer token (optional, Keystore)"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_mcp_token_input"),
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.testTag("ext_mcp_register_confirm"),
            ) {
                Text(stringResource(R.string.ext_mcp_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SyncCatalogDialog(
    catalogUrlDraft: String,
    isBusy: Boolean,
    error: String?,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_sync_catalog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ext_sync_catalog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = catalogUrlDraft,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.ext_sync_catalog_url_label)) },
                    placeholder = { Text("https://example.com/extensions-catalog.json") },
                    singleLine = true,
                    textStyle = CodeTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ext_catalog_url_input"),
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy && catalogUrlDraft.isNotBlank(),
                modifier = Modifier.testTag("ext_catalog_sync_confirm"),
            ) {
                Text(stringResource(R.string.ext_sync_catalog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ExtensionItemRow(
    record: ExtensionRecord,
    onClick: () -> Unit,
) {
    val (statusLabel, tone) = when (record.installState) {
        ExtensionInstallState.NOT_INSTALLED -> stringResource(R.string.ext_status_not_installed) to StatusTone.NEUTRAL
        ExtensionInstallState.INSTALLED_ENABLED -> stringResource(R.string.ext_status_enabled) to StatusTone.SUCCESS
        ExtensionInstallState.INSTALLED_DISABLED -> stringResource(R.string.ext_status_disabled) to StatusTone.WARNING
        ExtensionInstallState.UPDATE_AVAILABLE -> stringResource(R.string.ext_status_update) to StatusTone.ACCENT
    }
    AgtCard(
        onClick = onClick,
        modifier = Modifier.testTag("ext_item_${record.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AgtStatusBadge(label = statusLabel, tone = tone)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val toolsNote = if (record.discoveredTools.isNotEmpty()) {
                    " · ${record.discoveredTools.size} tools"
                } else {
                    ""
                }
                Text(
                    text = "${record.agentTarget.label} · ${record.publisher} · v${record.version}$toolsNote",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
