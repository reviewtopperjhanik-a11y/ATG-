package com.example.feature.github

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubDeviceCodePrompt
import com.example.ui.theme.JetBrainsMonoFontFamily

internal const val GITHUB_PAT_CREATE_URL =
    "https://github.com/settings/tokens/new?description=AGT%20Code&scopes=repo,read:user"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubConnectScreen(
    viewModel: GitHubConnectViewModel,
    onBack: () -> Unit,
    onSkipToHome: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var patDraft by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    var clientIdDraft by remember(state.savedOAuthClientId) {
        mutableStateOf(state.savedOAuthClientId)
    }

    LaunchedEffect(state.accountState.status) {
        if (state.accountState.status == GitHubAuthStatus.CONNECTED) {
            patDraft = ""
        }
    }

    LaunchedEffect(state.devicePrompt?.deviceCode) {
        val prompt = state.devicePrompt ?: return@LaunchedEffect
        copyTextToClipboard(context, "GitHub Device Code", prompt.userCode)
    }

    LaunchedEffect(state.pendingLaunchUrl) {
        val url = state.pendingLaunchUrl ?: return@LaunchedEffect
        viewModel.consumePendingLaunchUrl()
        val launched = openExternalBrowser(context, url)
        if (!launched) {
            viewModel.reportBrowserLaunchFailure(
                "No browser app available to open GitHub. Open github.com/login/device on any browser or check your browser settings."
            )
        }
    }

    val (badgeLabel, badgeTone) = when (state.accountState.status) {
        GitHubAuthStatus.CONNECTED -> "Connected: @${state.accountState.username.orEmpty()}" to StatusTone.SUCCESS
        GitHubAuthStatus.AUTHORIZING -> "Authorizing…" to StatusTone.ACCENT
        GitHubAuthStatus.ERROR -> "Error" to StatusTone.ERROR
        GitHubAuthStatus.NOT_CONNECTED -> "Not connected" to StatusTone.NEUTRAL
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.github_connect_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("github_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                    .weight(1f)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("github_connect_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgtCard(modifier = Modifier.testTag("github_status_card")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Account Status",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AgtStatusBadge(
                            label = badgeLabel,
                            tone = badgeTone,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.github_connect_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.accountState.scopes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Granted scopes: ${state.accountState.scopes.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (state.accountState.status == GitHubAuthStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = viewModel::disconnect,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("github_disconnect_button"),
                        ) {
                            Text("Disconnect GitHub", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (state.errorMessage != null) {
                    AgtInfoBanner(
                        title = "GitHub Authorization",
                        message = state.errorMessage.orEmpty(),
                        isError = true,
                        modifier = Modifier.testTag("github_error_banner"),
                    )
                }

                if (state.infoMessage != null) {
                    AgtInfoBanner(
                        title = "GitHub Status",
                        message = state.infoMessage.orEmpty(),
                    )
                }

                if (state.isBusy && state.devicePrompt == null) {
                    AgtLoadingRow(label = "Validating GitHub credentials…")
                }

                // Main System: Personal Access Token (PAT)
                AgtCard(modifier = Modifier.testTag("github_pat_card")) {
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
                                modifier = Modifier.size(18.dp),
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
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = patDraft,
                        onValueChange = { patDraft = it },
                        label = { Text(stringResource(R.string.github_pat_label)) },
                        placeholder = { Text(stringResource(R.string.github_pat_placeholder)) },
                        singleLine = true,
                        enabled = !state.isBusy,
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
                            .testTag("github_pat_input"),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.connectWithToken(patDraft) },
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("github_connect_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = if (state.accountState.status == GitHubAuthStatus.CONNECTED) {
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
                                viewModel.reportBrowserLaunchFailure(
                                    "No browser app available to open GitHub token generator."
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("github_generate_pat_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        )
                        Text(stringResource(R.string.github_pat_generate_action))
                    }
                }

                // Backup System: OAuth Client ID (Device Flow)
                AgtCard(modifier = Modifier.testTag("github_oauth_backup_card")) {
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
                            label = if (state.isOAuthConfigured) "Ready" else "Backup",
                            tone = if (state.isOAuthConfigured) StatusTone.SUCCESS else StatusTone.NEUTRAL,
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
                            enabled = !state.isBusy && state.devicePrompt == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_oauth_client_id_input"),
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = state.includePrivateRepos,
                                role = Role.Switch,
                                enabled = state.devicePrompt == null,
                                onValueChange = viewModel::setIncludePrivateRepos,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (state.includePrivateRepos) {
                                "Scopes: read:user, repo (private + public)"
                            } else {
                                "Scopes: read:user, public_repo (public only)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Switch(
                            checked = state.includePrivateRepos,
                            onCheckedChange = null,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val activePrompt = state.devicePrompt
                    if (activePrompt != null) {
                        GitHubDeviceCodeCard(
                            prompt = activePrompt,
                            cardTestTag = "github_device_code_card",
                            codeTestTag = "github_device_user_code",
                            copyButtonTestTag = "github_copy_code_button",
                            openBrowserButtonTestTag = "github_open_device_browser_button",
                            cancelButtonTestTag = "github_cancel_device_button",
                            onCopyCode = {
                                copyTextToClipboard(context, "GitHub Device Code", activePrompt.userCode)
                                viewModel.notifyCodeCopied(activePrompt.userCode)
                            },
                            onOpenBrowser = {
                                val target = activePrompt.verificationUriComplete ?: activePrompt.verificationUri
                                if (!openExternalBrowser(context, target)) {
                                    viewModel.reportBrowserLaunchFailure(
                                        "No browser app available. Open ${activePrompt.verificationUri} manually and enter ${activePrompt.userCode}."
                                    )
                                }
                            },
                            onCancel = viewModel::cancelDeviceOAuth,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.startDeviceOAuth(clientIdDraft) },
                            enabled = !state.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("github_oauth_device_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = if (state.accountState.status == GitHubAuthStatus.CONNECTED) {
                                    stringResource(R.string.github_reauthorize_action)
                                } else {
                                    stringResource(R.string.github_connect_action)
                                }
                            )
                        }
                    }
                }

                AgtCard {
                    Text(
                        text = "Requested Capabilities",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CapabilityLine(
                        icon = Icons.Outlined.Code,
                        text = "List and clone repositories into confined local workspaces",
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CapabilityLine(
                        icon = Icons.Outlined.Sync,
                        text = "Review diffs, branch status, and stage local commits",
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CapabilityLine(
                        icon = Icons.Outlined.Lock,
                        text = "Encrypted in Android Keystore; push & PR require approval",
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                OutlinedButton(
                    onClick = onSkipToHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("github_skip_button"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (state.accountState.status == GitHubAuthStatus.CONNECTED) {
                            stringResource(R.string.github_continue_projects)
                        } else {
                            stringResource(R.string.github_skip_action)
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun GitHubDeviceCodeCard(
    prompt: GitHubDeviceCodePrompt,
    cardTestTag: String,
    codeTestTag: String,
    copyButtonTestTag: String,
    openBrowserButtonTestTag: String,
    cancelButtonTestTag: String,
    onCopyCode: () -> Unit,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cardTestTag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.github_device_code_copied),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = prompt.userCode,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(codeTestTag),
            )
            Text(
                text = prompt.verificationUri,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.github_device_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopyCode,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(copyButtonTestTag),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.github_copy_code))
                }
                Button(
                    onClick = onOpenBrowser,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag(openBrowserButtonTestTag),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(stringResource(R.string.github_open_browser))
                }
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.End)
                    .minimumInteractiveComponentSize()
                    .testTag(cancelButtonTestTag),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

internal fun copyTextToClipboard(context: Context, label: String, value: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
    }
}

internal fun openExternalBrowser(context: Context, url: String): Boolean {
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

@Composable
private fun CapabilityLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
