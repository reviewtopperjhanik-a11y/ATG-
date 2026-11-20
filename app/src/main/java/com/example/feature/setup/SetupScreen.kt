package com.example.feature.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtSectionHeader
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.ComponentInstallStatus
import com.example.domain.model.RuntimeInstallStage
import com.example.domain.model.SetupComponentItem

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onContinueToGitHub: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            viewModel.startRuntimeInstall()
        },
    )

    val triggerInstall = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startRuntimeInstall()
        }
    }

    val installedCount = state.runtimeState.components.count {
        it.status == ComponentInstallStatus.COMPLETED
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("setup_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.setup_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = stringResource(R.string.setup_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    AgtSectionHeader(title = stringResource(R.string.setup_device_check))
                    AgtCard {
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
                                    imageVector = Icons.Outlined.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "ABI: ${state.deviceInfo.primaryAbi}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            AgtStatusBadge(
                                label = when {
                                    state.deviceInfo.isArm64 -> "ARM64 Ready"
                                    state.compatibilityReport.abiSupported -> "${state.deviceInfo.primaryAbi} Ready"
                                    else -> "Unsupported ABI"
                                },
                                tone = if (state.compatibilityReport.abiSupported) {
                                    StatusTone.SUCCESS
                                } else {
                                    StatusTone.ERROR
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Android ${state.deviceInfo.androidRelease} (API ${state.deviceInfo.androidSdk}) · Free storage: ${state.deviceInfo.availableStorageGbFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!state.compatibilityReport.isCompatible) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.compatibilityReport.blockingReasons.joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    AgtCard(modifier = Modifier.testTag("setup_runtime_status_card")) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setup_runtime_card_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            AgtStatusBadge(
                                label = state.runtimeState.stage.label,
                                tone = when (state.runtimeState.stage) {
                                    RuntimeInstallStage.INSTALLED -> StatusTone.SUCCESS
                                    RuntimeInstallStage.FAILED -> StatusTone.ERROR
                                    RuntimeInstallStage.IDLE,
                                    RuntimeInstallStage.CANCELLED -> StatusTone.NEUTRAL
                                    else -> StatusTone.ACCENT
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.runtimeState.statusDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.runtimeState.isInstalling) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val pct = state.runtimeState.progressPercent
                            if (pct != null) {
                                LinearProgressIndicator(
                                    progress = { pct.coerceIn(0, 100) / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("setup_install_progress"),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("setup_install_progress"),
                                )
                            }
                        }
                    }
                }

                val activeError = state.errorNotice
                    ?: if (!state.isRuntimeConfigured && !state.runtimeState.isInstalled) state.configError else null

                if (activeError != null) {
                    item {
                        AgtInfoBanner(
                            title = if (state.errorNotice == null && !state.isRuntimeConfigured) {
                                stringResource(R.string.setup_config_missing_title)
                            } else {
                                "Runtime Setup"
                            },
                            message = activeError,
                            isError = true,
                            modifier = Modifier.testTag("setup_error_banner"),
                        )
                    }
                }

                if (state.isUpstreamBootstrapMode && !state.runtimeState.isInstalled && activeError == null) {
                    item {
                        AgtInfoBanner(
                            title = stringResource(R.string.runtime_upstream_mode_title),
                            message = stringResource(R.string.runtime_upstream_mode_body),
                            isError = false,
                            modifier = Modifier.testTag("setup_upstream_banner"),
                        )
                    }
                }

                if (state.runtimeState.isInstalled) {
                    item {
                        AgtInfoBanner(
                            title = "Linux Runtime Ready",
                            message = state.runtimeState.healthMessage,
                            isError = false,
                        )
                    }
                }

                val showComponentsBreakdown = state.runtimeState.hasCheckedEnvironment ||
                    state.runtimeState.isInstalled ||
                    state.runtimeState.isInstalling ||
                    state.runtimeState.stage == RuntimeInstallStage.FAILED ||
                    state.runtimeState.canResumeDownload ||
                    activeError != null

                if (showComponentsBreakdown) {
                    item {
                        AgtSectionHeader(
                            title = stringResource(R.string.setup_components_header),
                            trailing = {
                                Text(
                                    text = "$installedCount / ${state.runtimeState.components.size} installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }

                    items(state.runtimeState.components, key = { it.id }) { component ->
                        SetupComponentRow(component = component)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.runtimeState.isInstalling -> {
                        OutlinedButton(
                            onClick = { viewModel.cancelRuntimeInstall() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_cancel_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.setup_cancel_action))
                        }
                    }
                    state.runtimeState.isInstalled -> {
                        Button(
                            onClick = { viewModel.markSetupCompleted(onContinueToGitHub) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_continue_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.setup_continue_github))
                        }
                    }
                    else -> {
                        Button(
                            onClick = triggerInstall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_install_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.runtimeState.canResumeDownload) {
                                    stringResource(R.string.setup_resume_action)
                                } else {
                                    stringResource(R.string.setup_install_action)
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.markSetupDeferred(onContinueToGitHub) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_skip_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(text = stringResource(R.string.setup_skip_action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupComponentRow(component: SetupComponentItem) {
    val (badgeLabel, badgeTone) = when (component.status) {
        ComponentInstallStatus.WAITING -> "Waiting" to StatusTone.NEUTRAL
        ComponentInstallStatus.NOT_INSTALLED -> "Not installed" to StatusTone.NEUTRAL
        ComponentInstallStatus.DOWNLOADING -> "Downloading" to StatusTone.ACCENT
        ComponentInstallStatus.VERIFYING -> "Verifying" to StatusTone.ACCENT
        ComponentInstallStatus.EXTRACTING -> "Extracting" to StatusTone.ACCENT
        ComponentInstallStatus.INSTALLING -> "Installing" to StatusTone.ACCENT
        ComponentInstallStatus.COMPLETED -> "Installed" to StatusTone.SUCCESS
        ComponentInstallStatus.FAILED -> "Failed" to StatusTone.ERROR
    }

    AgtCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = component.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            AgtStatusBadge(label = badgeLabel, tone = badgeTone)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = component.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (component.progressPercent != null && component.status != ComponentInstallStatus.COMPLETED) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (component.progressPercent.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
