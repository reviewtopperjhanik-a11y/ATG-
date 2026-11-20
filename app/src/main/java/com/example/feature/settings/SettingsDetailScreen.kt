package com.example.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtSectionHeader
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.AgentBackendId
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilRole
import com.example.domain.model.GatewayHealthStatus
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.RuntimeInstallStage
import com.example.domain.model.SettingsCategory
import com.example.domain.model.ThemeMode
import com.example.feature.github.GITHUB_PAT_CREATE_URL
import com.example.feature.github.GitHubDeviceCodeCard
import com.example.feature.github.copyTextToClipboard
import com.example.feature.github.openExternalBrowser
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(
    category: SettingsCategory,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToGateway: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(category) {
        viewModel.clearNotices()
        if (category == SettingsCategory.STORAGE) {
            viewModel.refreshStorageStats()
        } else if (category == SettingsCategory.ABOUT) {
            viewModel.refreshDiagnostics()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(category.titleRes),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("settings_detail_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.gatewayErrorBanner != null) {
                    AgtInfoBanner(
                        title = state.errorBannerTitle,
                        message = state.gatewayErrorBanner.orEmpty(),
                        isError = true,
                    )
                }

                if (state.savedBanner != null) {
                    AgtInfoBanner(
                        title = stringResource(R.string.common_status),
                        message = state.savedBanner.orEmpty(),
                    )
                }

                when (category) {
                    SettingsCategory.GATEWAY -> GatewayDetailContent(state, viewModel)
                    SettingsCategory.GITHUB -> GitHubDetailContent(state, viewModel)
                    SettingsCategory.AGENT -> AgentDetailContent(state, viewModel)
                    SettingsCategory.RUNTIME -> RuntimeDetailContent(state, viewModel)
                    SettingsCategory.MODELS -> ModelsDetailContent(state, viewModel, onNavigateToGateway)
                    SettingsCategory.SECURITY -> SecurityDetailContent(state, viewModel)
                    SettingsCategory.APPEARANCE -> AppearanceDetailContent(state, viewModel)
                    SettingsCategory.NOTIFICATIONS -> NotificationsDetailContent(state, viewModel)
                    SettingsCategory.STORAGE -> StorageDetailContent(state)
                    SettingsCategory.ABOUT -> AboutDetailContent(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun GatewayDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    var baseUrl by remember(state.preferences.gatewayBaseUrl) {
        mutableStateOf(state.preferences.gatewayBaseUrl)
    }
    var selectedProtocol by remember(state.preferences.gatewayProtocol) {
        mutableStateOf(state.preferences.gatewayProtocol)
    }
    var deviceTokenDraft by remember { mutableStateOf("") }

    var webSearchUrl by remember(state.preferences.webSearchBaseUrl) {
        mutableStateOf(state.preferences.webSearchBaseUrl)
    }
    var webSearchTokenDraft by remember { mutableStateOf("") }

    var githubSearchUrl by remember(state.preferences.githubSearchBaseUrl) {
        mutableStateOf(state.preferences.githubSearchBaseUrl)
    }
    var githubSearchTokenDraft by remember { mutableStateOf("") }

    val (badgeLabel, badgeTone) = when (state.gatewaySummary.status) {
        GatewayHealthStatus.UNCONFIGURED -> "Not tested" to StatusTone.NEUTRAL
        GatewayHealthStatus.CHECKING -> "Checking…" to StatusTone.ACCENT
        GatewayHealthStatus.CONNECTED -> "Connected" to StatusTone.SUCCESS
        GatewayHealthStatus.ERROR -> "Error" to StatusTone.ERROR
    }

    AgtSectionHeader(title = "API 1 · LLM Models & Chat")

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("JH GetWay Pro Status", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(label = badgeLabel, tone = badgeTone)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.gatewaySummary.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.gatewaySummary.lastSuccessfulEpochMs?.let { ts ->
            val formatted = remember(ts) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
            }
            Text(
                text = "Last successful check: $formatted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Remote gateways require HTTPS. Local loopback (127.0.0.1 / localhost) permits HTTP.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AgtCard {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Gateway Base URL") },
            placeholder = { Text("http://127.0.0.1:8787") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("gateway_url_input"),
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Device Token (Keystore)", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(
                label = if (state.hasGatewayToken) "Stored in Keystore" else "No token saved",
                tone = if (state.hasGatewayToken) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = deviceTokenDraft,
            onValueChange = { deviceTokenDraft = it },
            label = {
                Text(if (state.hasGatewayToken) "Replace Device Token" else "Device Token (Bearer)")
            },
            placeholder = { Text("Enter scoped CHAT / MODELS_READ token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("gateway_token_input"),
        )
        if (state.hasGatewayToken) {
            TextButton(
                onClick = {
                    deviceTokenDraft = ""
                    viewModel.clearGatewayToken()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("gateway_clear_token_button"),
            ) {
                Text("Clear Stored Token")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Protocol Mode", style = MaterialTheme.typography.titleSmall)
        GatewayProtocolMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selectedProtocol == mode,
                        role = Role.RadioButton,
                        onClick = { selectedProtocol = mode },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedProtocol == mode,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.label, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    viewModel.saveGatewayConfiguration(baseUrl, selectedProtocol, deviceTokenDraft)
                    deviceTokenDraft = ""
                },
                modifier = Modifier.weight(1f).height(48.dp).testTag("gateway_save_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Save")
            }
            Button(
                onClick = {
                    viewModel.saveAndTestGateway(baseUrl, selectedProtocol, deviceTokenDraft)
                    deviceTokenDraft = ""
                },
                enabled = state.gatewaySummary.status != GatewayHealthStatus.CHECKING,
                modifier = Modifier.weight(1f).height(48.dp).testTag("gateway_test_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Test & Sync")
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    AgtSectionHeader(title = "API 2 · Web Search Gateway (Rotated)")

    val (webBadgeLabel, webBadgeTone) = when (state.webSearchHealth.status) {
        GatewayHealthStatus.UNCONFIGURED -> "Not configured" to StatusTone.NEUTRAL
        GatewayHealthStatus.CHECKING -> "Checking…" to StatusTone.ACCENT
        GatewayHealthStatus.CONNECTED -> "Connected" to StatusTone.SUCCESS
        GatewayHealthStatus.ERROR -> "Error" to StatusTone.ERROR
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Web Search API Status", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(label = webBadgeLabel, tone = webBadgeTone)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.webSearchHealth.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = webSearchUrl,
            onValueChange = { webSearchUrl = it },
            label = { Text("Web Search Gateway URL") },
            placeholder = { Text("https://gateway.example.com (uses /v1/search/web)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("web_search_url_input"),
        )
        if (baseUrl.isNotBlank() && webSearchUrl.trim() != baseUrl.trim()) {
            TextButton(
                onClick = { webSearchUrl = baseUrl.trim() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("web_search_use_llm_url_button"),
            ) {
                Text("Use LLM Gateway URL")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scoped Search Token", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(
                label = if (state.hasWebSearchToken) "Stored in Keystore" else "No token saved",
                tone = if (state.hasWebSearchToken) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = webSearchTokenDraft,
            onValueChange = { webSearchTokenDraft = it },
            label = {
                Text(if (state.hasWebSearchToken) "Replace Web Search Token" else "Web Search Token (WEB_SEARCH)")
            },
            placeholder = { Text("Bearer token with WEB_SEARCH scope") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("web_search_token_input"),
        )
        if (state.hasWebSearchToken) {
            TextButton(
                onClick = {
                    webSearchTokenDraft = ""
                    viewModel.clearWebSearchToken()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("web_search_clear_token_button"),
            ) {
                Text("Clear Web Search Token")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    viewModel.saveWebSearchConfiguration(webSearchUrl, webSearchTokenDraft)
                    webSearchTokenDraft = ""
                },
                modifier = Modifier.weight(1f).height(48.dp).testTag("web_search_save_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Save")
            }
            Button(
                onClick = {
                    viewModel.saveAndTestWebSearch(webSearchUrl, webSearchTokenDraft)
                    webSearchTokenDraft = ""
                },
                enabled = state.webSearchHealth.status != GatewayHealthStatus.CHECKING,
                modifier = Modifier.weight(1f).height(48.dp).testTag("web_search_test_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Test Web API")
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    AgtSectionHeader(title = "API 3 · GitHub Search Gateway (PAT Pool)")

    val (ghBadgeLabel, ghBadgeTone) = when (state.githubSearchHealth.status) {
        GatewayHealthStatus.UNCONFIGURED -> "Fallback Ready" to StatusTone.NEUTRAL
        GatewayHealthStatus.CHECKING -> "Checking…" to StatusTone.ACCENT
        GatewayHealthStatus.CONNECTED -> "Connected" to StatusTone.SUCCESS
        GatewayHealthStatus.ERROR -> "Error" to StatusTone.ERROR
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GitHub Search API Status", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(label = ghBadgeLabel, tone = ghBadgeTone)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.githubSearchHealth.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = githubSearchUrl,
            onValueChange = { githubSearchUrl = it },
            label = { Text("GitHub Search Gateway URL") },
            placeholder = { Text("https://gateway.example.com (uses /v1/search/github)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("github_search_url_input"),
        )
        if (baseUrl.isNotBlank() && githubSearchUrl.trim() != baseUrl.trim()) {
            TextButton(
                onClick = { githubSearchUrl = baseUrl.trim() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("github_search_use_llm_url_button"),
            ) {
                Text("Use LLM Gateway URL")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scoped GitHub Search Token", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(
                label = if (state.hasGitHubSearchToken) "Stored in Keystore" else "No token saved",
                tone = if (state.hasGitHubSearchToken) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = githubSearchTokenDraft,
            onValueChange = { githubSearchTokenDraft = it },
            label = {
                Text(if (state.hasGitHubSearchToken) "Replace GitHub Search Token" else "GitHub Search Token (GITHUB_SEARCH)")
            },
            placeholder = { Text("Bearer token with GITHUB_SEARCH scope") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("github_search_token_input"),
        )
        if (state.hasGitHubSearchToken) {
            TextButton(
                onClick = {
                    githubSearchTokenDraft = ""
                    viewModel.clearGitHubSearchToken()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("github_search_clear_token_button"),
            ) {
                Text("Clear GitHub Search Token")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    viewModel.saveGitHubSearchConfiguration(githubSearchUrl, githubSearchTokenDraft)
                    githubSearchTokenDraft = ""
                },
                modifier = Modifier.weight(1f).height(48.dp).testTag("github_search_save_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Save")
            }
            Button(
                onClick = {
                    viewModel.saveAndTestGitHubSearch(githubSearchUrl, githubSearchTokenDraft)
                    githubSearchTokenDraft = ""
                },
                enabled = state.githubSearchHealth.status != GatewayHealthStatus.CHECKING,
                modifier = Modifier.weight(1f).height(48.dp).testTag("github_search_test_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Test GitHub API")
            }
        }
    }
}

@Composable
private fun GitHubDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var patDraft by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    var clientIdDraft by remember(state.preferences.githubOAuthClientId) {
        mutableStateOf(state.preferences.githubOAuthClientId)
    }
    var includePrivateRepos by remember { mutableStateOf(true) }
    var branch by remember(state.preferences.gitDefaultBranch) {
        mutableStateOf(state.preferences.gitDefaultBranch)
    }
    var authorName by remember(state.preferences.gitAuthorName) {
        mutableStateOf(state.preferences.gitAuthorName)
    }
    var authorEmail by remember(state.preferences.gitAuthorEmail) {
        mutableStateOf(state.preferences.gitAuthorEmail)
    }

    LaunchedEffect(state.gitHubAccount.status) {
        if (state.gitHubAccount.status == GitHubAuthStatus.CONNECTED) {
            patDraft = ""
        }
    }

    LaunchedEffect(state.gitHubDevicePrompt?.deviceCode) {
        val prompt = state.gitHubDevicePrompt ?: return@LaunchedEffect
        copyTextToClipboard(context, "GitHub Device Code", prompt.userCode)
    }

    LaunchedEffect(state.pendingGitHubOAuthUrl) {
        val url = state.pendingGitHubOAuthUrl ?: return@LaunchedEffect
        viewModel.consumePendingGitHubOAuthUrl()
        val launched = openExternalBrowser(context, url)
        if (!launched) {
            viewModel.reportGitHubBrowserFailure(
                "No browser available to open GitHub. Open github.com/login/device on any browser and enter the shown code."
            )
        }
    }

    val (badgeLabel, badgeTone) = when (state.gitHubAccount.status) {
        GitHubAuthStatus.CONNECTED -> "Connected: @${state.gitHubAccount.username.orEmpty()}" to StatusTone.SUCCESS
        GitHubAuthStatus.AUTHORIZING -> "Authorizing…" to StatusTone.ACCENT
        GitHubAuthStatus.ERROR -> "Error" to StatusTone.ERROR
        GitHubAuthStatus.NOT_CONNECTED -> "Not connected" to StatusTone.NEUTRAL
    }

    AgtCard(modifier = Modifier.testTag("settings_github_status_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GitHub Account", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(label = badgeLabel, tone = badgeTone)
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (state.gitHubAccount.status == GitHubAuthStatus.CONNECTED) {
            val scopeText = if (state.gitHubAccount.scopes.isNotEmpty()) {
                state.gitHubAccount.scopes.joinToString(", ")
            } else {
                "Fine-grained / token verified"
            }
            Text(
                text = "Signed in as @${state.gitHubAccount.username.orEmpty()} · Scopes: $scopeText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = viewModel::disconnectGitHub,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("settings_github_disconnect_button"),
            ) {
                Text("Disconnect GitHub", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                text = stringResource(R.string.github_connect_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.isGitHubBusy && state.gitHubDevicePrompt == null) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }

    // Main System: Personal Access Token (PAT)
    AgtCard(modifier = Modifier.testTag("settings_github_pat_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.github_pat_card_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            AgtStatusBadge(
                label = stringResource(R.string.github_pat_primary_badge),
                tone = StatusTone.ACCENT,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.github_pat_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = patDraft,
            onValueChange = { patDraft = it },
            label = { Text(stringResource(R.string.github_pat_label)) },
            placeholder = { Text(stringResource(R.string.github_pat_placeholder)) },
            singleLine = true,
            enabled = !state.isGitHubBusy,
            visualTransformation = if (patVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(
                    onClick = { patVisible = !patVisible },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = if (patVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (patVisible) "Hide token" else "Show token",
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_github_pat_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.connectGitHubWithToken(patDraft) },
            enabled = !state.isGitHubBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("settings_github_pat_connect_button"),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = if (state.gitHubAccount.status == GitHubAuthStatus.CONNECTED) {
                    stringResource(R.string.github_pat_update_action)
                } else {
                    stringResource(R.string.github_pat_connect_action)
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (!openExternalBrowser(context, GITHUB_PAT_CREATE_URL)) {
                    viewModel.reportGitHubBrowserFailure("No browser available to open GitHub token generator.")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("settings_github_generate_pat_button"),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(stringResource(R.string.github_pat_generate_action))
        }
    }

    // Backup System: OAuth Client ID (Device Flow)
    AgtCard(modifier = Modifier.testTag("settings_github_oauth_backup_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.github_oauth_card_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            AgtStatusBadge(
                label = if (state.isGitHubOAuthConfigured) "Ready" else "Backup",
                tone = if (state.isGitHubOAuthConfigured) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.github_oauth_pkce_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.hasBuildConfigOAuthClientId) {
            Text(
                text = "Using build-configured GitHub OAuth Client ID.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = clientIdDraft,
                onValueChange = { clientIdDraft = it },
                label = { Text(stringResource(R.string.github_client_id_label)) },
                placeholder = { Text(stringResource(R.string.github_client_id_placeholder)) },
                singleLine = true,
                enabled = !state.isGitHubBusy && state.gitHubDevicePrompt == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_github_client_id_input"),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        ToggleSettingRow(
            title = if (includePrivateRepos) {
                "Request repo scope (public + private)"
            } else {
                "Request public_repo scope (public only)"
            },
            checked = includePrivateRepos,
            onCheckedChange = {
                if (state.gitHubDevicePrompt == null) {
                    includePrivateRepos = it
                }
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        val activePrompt = state.gitHubDevicePrompt
        if (activePrompt != null) {
            GitHubDeviceCodeCard(
                prompt = activePrompt,
                cardTestTag = "settings_github_device_code_card",
                codeTestTag = "settings_github_device_user_code",
                copyButtonTestTag = "settings_github_copy_code_button",
                openBrowserButtonTestTag = "settings_github_open_device_browser_button",
                cancelButtonTestTag = "settings_github_cancel_device_button",
                onCopyCode = {
                    copyTextToClipboard(context, "GitHub Device Code", activePrompt.userCode)
                    viewModel.notifyGitHubCodeCopied(activePrompt.userCode)
                },
                onOpenBrowser = {
                    val target = activePrompt.verificationUriComplete ?: activePrompt.verificationUri
                    if (!openExternalBrowser(context, target)) {
                        viewModel.reportGitHubBrowserFailure(
                            "No browser available. Open ${activePrompt.verificationUri} manually and enter ${activePrompt.userCode}."
                        )
                    }
                },
                onCancel = viewModel::cancelGitHubDeviceOAuth,
            )
        } else {
            OutlinedButton(
                onClick = { viewModel.launchGitHubDeviceOAuth(clientIdDraft, includePrivateRepos) },
                enabled = !state.isGitHubBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_github_oauth_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (state.gitHubAccount.status == GitHubAuthStatus.CONNECTED) {
                        stringResource(R.string.github_reauthorize_action)
                    } else {
                        stringResource(R.string.github_connect_action)
                    }
                )
            }
        }
    }

    AgtCard {
        Text("Git Identity", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Default Branch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings_git_branch_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = authorName,
            onValueChange = { authorName = it },
            label = { Text("Commit Author Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings_git_author_name_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = authorEmail,
            onValueChange = { authorEmail = it },
            label = { Text("Commit Author Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings_git_author_email_input"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveGitIdentity(branch, authorName, authorEmail) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("settings_git_save_button"),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Save Git Identity")
        }
    }
}

@Composable
private fun AgentDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val prefs = state.preferences
    val ocState = state.openCodeState
    val piState = state.piState
    val selectedBackend = AgentBackendId.fromId(prefs.activeAgentId)
    var stepLimitText by remember(prefs.agentStepLimit) {
        mutableStateOf(prefs.agentStepLimit.toString())
    }
    var timeoutText by remember(prefs.commandTimeoutSeconds) {
        mutableStateOf(prefs.commandTimeoutSeconds.toString())
    }
    var autoDebug by remember(prefs.autoDebugEnabled) { mutableStateOf(prefs.autoDebugEnabled) }
    var checkpoints by remember(prefs.checkpointsEnabled) { mutableStateOf(prefs.checkpointsEnabled) }
    var planApproval by remember(prefs.requirePlanApproval) {
        mutableStateOf(prefs.requirePlanApproval)
    }

    AgtCard {
        Text(
            text = stringResource(R.string.settings_agent_active_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_agent_active_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AgentBackendId.entries.forEach { backend ->
            val selected = backend == selectedBackend
            val isReady = when (backend) {
                AgentBackendId.OPENCODE -> ocState.isInstalled
                AgentBackendId.PI -> piState.isInstalled
            }
            val desc = when (backend) {
                AgentBackendId.OPENCODE -> "Primary CLI coding agent via OpenCode --format json"
                AgentBackendId.PI -> "Lightweight coding agent CLI (@mariozechner/pi-coding-agent) via --mode json"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .selectable(
                        selected = selected,
                        onClick = { viewModel.selectActiveAgent(backend.id) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp)
                    .testTag("settings_agent_select_${backend.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(backend.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                AgtStatusBadge(
                    label = if (isReady) "Installed" else "Setup Needed",
                    tone = if (isReady) StatusTone.SUCCESS else StatusTone.WARNING,
                )
            }
        }
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OpenCode Agent Runtime", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(
                label = when {
                    ocState.isInstalling -> "Installing…"
                    ocState.isInstalled -> "Ready v${ocState.version.orEmpty()}"
                    else -> "Not Installed"
                },
                tone = when {
                    ocState.isInstalling -> StatusTone.ACCENT
                    ocState.isInstalled -> StatusTone.SUCCESS
                    else -> StatusTone.WARNING
                },
            )
        }
        if (ocState.statusDetail.isNotBlank() && ocState.statusDetail != ocState.errorMessage) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = ocState.statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (ocState.isInstalling) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (ocState.errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = ocState.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.installOrUpdateOpenCode() },
                enabled = !ocState.isInstalling,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_opencode_install_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(if (ocState.isInstalled) "Update OpenCode" else "Install OpenCode")
            }
            OutlinedButton(
                onClick = viewModel::verifyOpenCode,
                enabled = !ocState.isInstalling,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_opencode_verify_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Verify")
            }
        }
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_pi_title),
                style = MaterialTheme.typography.titleSmall,
            )
            AgtStatusBadge(
                label = when {
                    piState.isInstalling -> "Installing…"
                    piState.isInstalled -> "Ready v${piState.version.orEmpty()}"
                    else -> "Not Installed"
                },
                tone = when {
                    piState.isInstalling -> StatusTone.ACCENT
                    piState.isInstalled -> StatusTone.SUCCESS
                    else -> StatusTone.WARNING
                },
            )
        }
        if (piState.statusDetail.isNotBlank() && piState.statusDetail != piState.errorMessage) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = piState.statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (piState.isInstalling) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (piState.errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = piState.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::installPiViaNpm,
                enabled = !piState.isInstalling,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_pi_npm_install_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (piState.isInstalled) {
                        stringResource(R.string.settings_pi_update_action)
                    } else {
                        stringResource(R.string.settings_pi_install_npm)
                    }
                )
            }
            OutlinedButton(
                onClick = viewModel::verifyPi,
                enabled = !piState.isInstalling,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_pi_verify_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Verify")
            }
        }
    }

    AgtCard {
        Text("Execution Policy", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = stepLimitText,
            onValueChange = { stepLimitText = it.filter(Char::isDigit) },
            label = { Text("Max Agent Steps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = timeoutText,
            onValueChange = { timeoutText = it.filter(Char::isDigit) },
            label = { Text("Command Timeout (seconds)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToggleSettingRow("Auto-debug test/build failures", autoDebug) { autoDebug = it }
        ToggleSettingRow("Create workspace checkpoints", checkpoints) { checkpoints = it }
        ToggleSettingRow("Require plan approval before coding", planApproval) { planApproval = it }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                viewModel.saveAgentPolicy(
                    stepLimit = stepLimitText.toIntOrNull() ?: 20,
                    timeoutSeconds = timeoutText.toIntOrNull() ?: 120,
                    autoDebug = autoDebug,
                    checkpoints = checkpoints,
                    requirePlanApproval = planApproval,
                )
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Save Agent Policy")
        }
    }

    MultiAgentCouncilPolicyCard(state = state, viewModel = viewModel)
}

@Composable
private fun MultiAgentCouncilPolicyCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val prefs = state.preferences
    var selectedMode by remember(prefs.councilDefaultMode) {
        mutableStateOf(prefs.councilDefaultMode)
    }
    var tokenBudgetText by remember(prefs.councilTokenBudget) {
        mutableStateOf(prefs.councilTokenBudget.toString())
    }

    AgtCard(modifier = Modifier.testTag("settings_council_policy_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.council_settings_policy_title),
                style = MaterialTheme.typography.titleSmall,
            )
            AgtStatusBadge(
                label = "${prefs.councilEnabledSpecialists.size}/5 Active",
                tone = StatusTone.ACCENT,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.council_settings_default_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CouncilExecutionMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selectedMode == mode,
                        role = Role.RadioButton,
                        onClick = { selectedMode = mode },
                    )
                    .testTag("settings_council_mode_${mode.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedMode == mode, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(mode.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = tokenBudgetText,
            onValueChange = { tokenBudgetText = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.council_settings_token_budget)) },
            supportingText = { Text("4,000 – 128,000 tokens per Council round (default 24,000)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_council_budget_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.council_settings_specialists_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CouncilRole.entries.filter { it.isSpecialistDebater }.forEach { role ->
            val enabled = role in prefs.councilEnabledSpecialists
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = { viewModel.setCouncilSpecialistEnabled(role, it) },
                    )
                    .padding(vertical = 4.dp)
                    .testTag("settings_council_role_toggle_${role.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = role.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Switch(checked = enabled, onCheckedChange = null)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                viewModel.saveCouncilBudgetAndMode(
                    defaultMode = selectedMode,
                    tokenBudget = tokenBudgetText.toLongOrNull()?.coerceIn(4_000L, 128_000L)?.toInt() ?: 24_000,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("settings_council_save_policy_button"),
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Save Council Policy")
        }
    }
}

@Composable
private fun RuntimeDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val runtime = state.runtimeState
    val (badgeLabel, badgeTone) = when {
        runtime.isInstalled -> "Installed v${runtime.installedVersion.orEmpty()}" to StatusTone.SUCCESS
        runtime.isInstalling -> runtime.stage.label to StatusTone.ACCENT
        runtime.stage == RuntimeInstallStage.FAILED -> "Error" to StatusTone.ERROR
        else -> "Not installed" to StatusTone.WARNING
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Linux Environment (PRoot)", style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(label = badgeLabel, tone = badgeTone)
        }
        Spacer(modifier = Modifier.height(6.dp))
        state.deviceInfo?.let { info ->
            Text(
                text = "Device ABI: ${info.primaryAbi} · Android ${info.androidRelease} · Free: ${info.availableStorageGbFormatted}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = runtime.healthMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        runtime.lastHealthCheckEpochMs?.let { ts ->
            val formatted = remember(ts) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
            }
            Text(
                text = "Last health check: $formatted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (runtime.isInstalling) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = runtime.statusDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val pct = runtime.progressPercent
            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (runtime.errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = runtime.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "PRoot is a userspace compatibility layer, not a hardened security VM. Only run trusted repositories.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AgtCard {
        Text("Runtime Installer & Maintenance", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = runtime.statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.isRuntimeConfigured && state.runtimeConfigError != null && !runtime.isInstalled) {
            Spacer(modifier = Modifier.height(8.dp))
            AgtInfoBanner(
                title = stringResource(R.string.setup_config_missing_title),
                message = state.runtimeConfigError,
                isError = true,
                modifier = Modifier.testTag("settings_runtime_config_error"),
            )
        } else if (state.isUpstreamBootstrapMode && !runtime.isInstalled) {
            Spacer(modifier = Modifier.height(8.dp))
            AgtInfoBanner(
                title = stringResource(R.string.runtime_upstream_mode_title),
                message = stringResource(R.string.runtime_upstream_mode_body),
                isError = false,
                modifier = Modifier.testTag("settings_runtime_upstream_banner"),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runtime.isInstalling) {
                OutlinedButton(
                    onClick = viewModel::cancelRuntimeInstall,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("settings_runtime_cancel_button"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = { viewModel.installOrUpdateRuntime() },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("settings_runtime_install_button"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        if (runtime.isInstalled) {
                            "Update"
                        } else {
                            stringResource(R.string.setup_install_action)
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = viewModel::verifyRuntimeHealth,
                enabled = !runtime.isInstalling,
                modifier = Modifier.weight(1f).height(48.dp).testTag("settings_runtime_health_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Verify Health")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::repairRuntime,
                enabled = !runtime.isInstalling,
                modifier = Modifier.weight(1f).height(48.dp).testTag("settings_runtime_repair_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text("Repair Runtime")
            }
            if (runtime.isInstalled) {
                TextButton(
                    onClick = viewModel::removeRuntime,
                    enabled = !runtime.isInstalling,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("settings_runtime_remove_button"),
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    val showRuntimeComponents = runtime.hasCheckedEnvironment ||
        runtime.isInstalled ||
        runtime.isInstalling ||
        runtime.stage == RuntimeInstallStage.FAILED ||
        runtime.canResumeDownload

    if (showRuntimeComponents) {
        AgtCard(modifier = Modifier.testTag("settings_runtime_components_card")) {
            val doneCount = runtime.components.count {
                it.status == com.example.domain.model.ComponentInstallStatus.COMPLETED
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setup_components_header), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$doneCount / ${runtime.components.size} installed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            runtime.components.forEach { item ->
                val (label, tone) = when (item.status) {
                    com.example.domain.model.ComponentInstallStatus.COMPLETED -> "Installed" to StatusTone.SUCCESS
                    com.example.domain.model.ComponentInstallStatus.FAILED -> "Failed" to StatusTone.ERROR
                    com.example.domain.model.ComponentInstallStatus.NOT_INSTALLED,
                    com.example.domain.model.ComponentInstallStatus.WAITING -> "Missing" to StatusTone.NEUTRAL
                    else -> item.status.name.lowercase().replaceFirstChar { it.uppercase() } to StatusTone.ACCENT
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AgtStatusBadge(label = label, tone = tone)
                }
            }
        }
    }

    AgtCard {
        Text("Runtime Component Licenses", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(6.dp))
        runtime.licenseNotices.forEach { notice ->
            Text(
                text = "${notice.component}: ${notice.license} (${notice.spdxId})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (notice.summary.isNotBlank()) {
                Text(
                    text = notice.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ModelsDetailContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onNavigateToGateway: () -> Unit,
) {
    if (state.isRefreshingModels) {
        AgtLoadingRow(label = "Querying JH GetWay Pro /v1/models…")
    }

    if (state.discoveredModels.isEmpty()) {
        AgtEmptyState(
            title = stringResource(R.string.settings_models_none_title),
            description = stringResource(R.string.settings_models_none_desc),
            icon = Icons.Outlined.Psychology,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onNavigateToGateway,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.settings_models_configure_gateway))
                    }
                    OutlinedButton(
                        onClick = viewModel::refreshModels,
                        enabled = !state.isRefreshingModels,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.common_refresh))
                    }
                }
            },
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.discoveredModels.size} Gateway Model(s)",
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedButton(
            onClick = viewModel::refreshModels,
            enabled = !state.isRefreshingModels,
            modifier = Modifier.minimumInteractiveComponentSize(),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(stringResource(R.string.common_refresh))
        }
    }

    state.discoveredModels.forEach { model ->
        ModelRowCard(
            model = model,
            isDefault = state.preferences.defaultModelId == model.id,
            onSelectDefault = { viewModel.setDefaultModel(model.id) },
        )
    }

    Spacer(modifier = Modifier.height(6.dp))
    CouncilRoleModelRoutingCard(
        discoveredModels = state.discoveredModels,
        overrides = state.preferences.councilRoleModelOverrides,
        defaultModelId = state.preferences.defaultModelId,
        onAssignRoleModel = viewModel::setCouncilRoleModel,
    )
}

@Composable
private fun CouncilRoleModelRoutingCard(
    discoveredModels: List<GatewayModelInfo>,
    overrides: Map<CouncilRole, String>,
    defaultModelId: String?,
    onAssignRoleModel: (CouncilRole, String?) -> Unit,
) {
    val llmRoles = remember {
        listOf(
            CouncilRole.DECOMPOSER,
            CouncilRole.PLANNER,
            CouncilRole.COST_ANALYST,
            CouncilRole.REALIST,
            CouncilRole.CRITIC,
            CouncilRole.ALTERNATIVE,
            CouncilRole.SYNTHESIZER,
        )
    }
    AgtCard(modifier = Modifier.testTag("settings_council_routing_card")) {
        Text(
            text = stringResource(R.string.council_settings_routing_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.council_settings_routing_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        llmRoles.forEach { role ->
            var menuExpanded by remember { mutableStateOf(false) }
            val assigned = overrides[role]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(role.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (assigned != null) {
                            "Custom: $assigned"
                        } else {
                            "Follow Default (${defaultModelId ?: "Composer"})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("settings_council_role_model_${role.id}"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = assigned ?: "Default",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Assign model for ${role.displayName}",
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Default / Composer Model") },
                            onClick = {
                                menuExpanded = false
                                onAssignRoleModel(role, null)
                            },
                            modifier = Modifier.testTag("settings_council_role_${role.id}_default"),
                        )
                        discoveredModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text("${model.id} (${model.provider})") },
                                onClick = {
                                    menuExpanded = false
                                    onAssignRoleModel(role, model.id)
                                },
                                modifier = Modifier.testTag("settings_council_role_${role.id}_${model.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRowCard(
    model: GatewayModelInfo,
    isDefault: Boolean,
    onSelectDefault: () -> Unit,
) {
    AgtCard(onClick = onSelectDefault) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isDefault, onClick = onSelectDefault)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(model.id, style = MaterialTheme.typography.titleSmall)
                val ctxText = model.contextWindow?.let { " · ${it / 1000}k ctx" }.orEmpty()
                Text(
                    text = "Provider: ${model.provider}$ctxText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CapabilityChip("Stream", model.supportsStreaming)
                    CapabilityChip("Tools", model.supportsToolCalling)
                    CapabilityChip("Vision", model.supportsVision)
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(name: String, supported: Boolean?) {
    val (label, tone) = when (supported) {
        true -> "$name: Yes" to StatusTone.SUCCESS
        false -> "$name: No" to StatusTone.ERROR
        null -> "$name: Unverified" to StatusTone.NEUTRAL
    }
    AgtStatusBadge(label = label, tone = tone)
}

@Composable
private fun SecurityDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val prefs = state.preferences
    AgtCard {
        ToggleSettingRow(
            title = stringResource(R.string.settings_security_destructive),
            checked = prefs.requireDestructiveApproval,
            onCheckedChange = {
                viewModel.saveSecurityPolicy(
                    requireDestructive = it,
                    requirePush = prefs.requirePushApproval,
                    requireDeploy = prefs.requireDeployApproval,
                    secretMasking = prefs.secretMaskingEnabled,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_security_push),
            checked = prefs.requirePushApproval,
            onCheckedChange = {
                viewModel.saveSecurityPolicy(
                    requireDestructive = prefs.requireDestructiveApproval,
                    requirePush = it,
                    requireDeploy = prefs.requireDeployApproval,
                    secretMasking = prefs.secretMaskingEnabled,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_security_deploy),
            checked = prefs.requireDeployApproval,
            onCheckedChange = {
                viewModel.saveSecurityPolicy(
                    requireDestructive = prefs.requireDestructiveApproval,
                    requirePush = prefs.requirePushApproval,
                    requireDeploy = it,
                    secretMasking = prefs.secretMaskingEnabled,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_security_masking),
            checked = prefs.secretMaskingEnabled,
            onCheckedChange = {
                viewModel.saveSecurityPolicy(
                    requireDestructive = prefs.requireDestructiveApproval,
                    requirePush = prefs.requirePushApproval,
                    requireDeploy = prefs.requireDeployApproval,
                    secretMasking = it,
                )
            },
        )
    }
}

@Composable
private fun AppearanceDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    AgtSectionHeader(title = stringResource(R.string.appearance_theme_mode))
    ThemeMode.entries.forEach { mode ->
        val selected = state.preferences.themeMode == mode
        val description = when (mode) {
            ThemeMode.LIGHT -> stringResource(R.string.appearance_desc_light)
            ThemeMode.DARK -> stringResource(R.string.appearance_desc_dark)
            ThemeMode.SYSTEM -> stringResource(R.string.appearance_desc_system)
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { viewModel.setThemeMode(mode) },
                )
                .testTag("appearance_option_${mode.name.lowercase()}"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(stringResource(mode.labelRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsDetailContent(state: SettingsUiState, viewModel: SettingsViewModel) {
    val prefs = state.preferences
    val context = LocalContext.current
    var permissionGranted by remember(context) {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            permissionGranted = granted
        },
    )

    AgtCard(modifier = Modifier.testTag("notifications_permission_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_notif_perm_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            AgtStatusBadge(
                label = stringResource(
                    if (permissionGranted) R.string.settings_notif_perm_granted
                    else R.string.settings_notif_perm_needed
                ),
                tone = if (permissionGranted) StatusTone.SUCCESS else StatusTone.WARNING,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (permissionGranted) R.string.settings_notif_perm_granted_desc
                else R.string.settings_notif_perm_needed_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("grant_notifications_permission_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.settings_notif_grant_action))
            }
        }
    }

    AgtCard {
        ToggleSettingRow(
            title = stringResource(R.string.settings_notif_complete),
            checked = prefs.notifyTaskComplete,
            onCheckedChange = {
                viewModel.saveNotifications(
                    taskComplete = it,
                    taskFailure = prefs.notifyTaskFailure,
                    approvals = prefs.notifyApprovals,
                    runtimeEvents = prefs.notifyRuntimeEvents,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_notif_failure),
            checked = prefs.notifyTaskFailure,
            onCheckedChange = {
                viewModel.saveNotifications(
                    taskComplete = prefs.notifyTaskComplete,
                    taskFailure = it,
                    approvals = prefs.notifyApprovals,
                    runtimeEvents = prefs.notifyRuntimeEvents,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_notif_approvals),
            checked = prefs.notifyApprovals,
            onCheckedChange = {
                viewModel.saveNotifications(
                    taskComplete = prefs.notifyTaskComplete,
                    taskFailure = prefs.notifyTaskFailure,
                    approvals = it,
                    runtimeEvents = prefs.notifyRuntimeEvents,
                )
            },
        )
        ToggleSettingRow(
            title = stringResource(R.string.settings_notif_runtime),
            checked = prefs.notifyRuntimeEvents,
            onCheckedChange = {
                viewModel.saveNotifications(
                    taskComplete = prefs.notifyTaskComplete,
                    taskFailure = prefs.notifyTaskFailure,
                    approvals = prefs.notifyApprovals,
                    runtimeEvents = it,
                )
            },
        )
    }
}

@Composable
private fun StorageDetailContent(state: SettingsUiState) {
    val kb = state.workspaceStorageBytes / 1024.0
    val runtimeKb = state.runtimeStorageBytes / 1024.0
    val runtimeStatus = if (state.runtimeState.isInstalled) {
        String.format(java.util.Locale.US, "v%s (%.1f KB)", state.runtimeState.installedVersion ?: "?", runtimeKb)
    } else if (state.runtimeStorageBytes > 0L) {
        String.format(java.util.Locale.US, "Staging / partial (%.1f KB)", runtimeKb)
    } else {
        "Not installed"
    }
    AgtCard {
        Text(stringResource(R.string.settings_storage_title), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Persisted projects: ${state.projectCount} · Saved tasks: ${state.taskCount} · Checkpoints: ${state.checkpointCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = String.format(java.util.Locale.US, "Workspace files: %.1f KB", kb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Runtime rootfs: $runtimeStatus",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutDetailContent(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    AgtCard {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.about_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.deviceInfo?.let { info ->
            Text(
                text = stringResource(
                    R.string.about_device_abi_format,
                    info.primaryAbi,
                    info.androidRelease,
                    info.androidSdk,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    AgtCard(modifier = Modifier.testTag("about_crash_card")) {
        Text(
            text = stringResource(R.string.about_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        val crash = state.lastCrashReport
        if (crash == null) {
            Text(
                text = stringResource(R.string.about_diagnostics_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val formattedTime = remember(crash.timestampMs) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(crash.timestampMs))
            }
            Text(
                text = stringResource(
                    R.string.about_diagnostics_crash_format,
                    formattedTime,
                    crash.threadName,
                    crash.exceptionClass.substringAfterLast('.'),
                    crash.message,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (crash.stackExcerpt.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = crash.stackExcerpt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::clearLastCrashReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("about_clear_crash_button"),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.about_diagnostics_clear))
            }
        }
    }

    AgtCard(modifier = Modifier.testTag("about_release_plan_card")) {
        Text(
            text = stringResource(R.string.about_release_plan_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_release_plan_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AgtCard(modifier = Modifier.testTag("about_licenses_card")) {
        Text(
            text = stringResource(R.string.about_licenses_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_licenses_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        listOf(
            R.string.about_license_agt,
            R.string.about_license_androidx,
            R.string.about_license_kotlin,
            R.string.about_license_networking,
            R.string.about_license_proot,
            R.string.about_license_talloc,
            R.string.about_license_opencode,
            R.string.about_license_fonts,
            R.string.about_license_antigem,
        ).forEach { noticeRes ->
            Text(
                text = stringResource(noticeRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
