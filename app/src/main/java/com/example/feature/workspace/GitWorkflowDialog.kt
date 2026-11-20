package com.example.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.design.AgtCard
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.GitHubAuthStatus
import com.example.ui.theme.CodeTextStyle

@Composable
fun GitWorkflowDialog(
    gitState: GitWorkflowUiState,
    onRefresh: () -> Unit,
    onInitRepo: () -> Unit,
    onCommitMessageChange: (String) -> Unit,
    onToggleExcludeSensitive: (Boolean) -> Unit,
    onToggleDiffPreview: (Boolean) -> Unit,
    onCommitChanges: () -> Unit,
    onRequestPush: () -> Unit,
    onConfirmPush: () -> Unit,
    onCancelPush: () -> Unit,
    onOpenPrForm: () -> Unit,
    onClosePrForm: () -> Unit,
    onPrTitleChange: (String) -> Unit,
    onPrBodyChange: (String) -> Unit,
    onPrHeadBranchChange: (String) -> Unit,
    onPrBaseBranchChange: (String) -> Unit,
    onRequestCreatePr: () -> Unit,
    onConfirmCreatePr: () -> Unit,
    onCancelCreatePr: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status = gitState.status
    val hasRemote = !status.remoteUrl.isNullOrBlank()
    val hasGitHubAccount = gitState.gitHubAccount.status == GitHubAuthStatus.CONNECTED

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("git_workflow_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Git & GitHub", style = MaterialTheme.typography.titleLarge)
                IconButton(
                    onClick = onRefresh,
                    enabled = !gitState.isLoadingStatus && !gitState.isActionBusy,
                    modifier = Modifier.testTag("git_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh Git status",
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (gitState.isLoadingStatus) {
                    AgtLoadingRow(label = "Inspecting Git repository…")
                } else if (!status.gitAvailable) {
                    AgtInfoBanner(
                        title = "Git Unavailable",
                        message = status.statusError ?: "Install Linux runtime in Settings → Runtime to use Git.",
                        isError = true,
                    )
                } else if (!status.isGitRepo) {
                    AgtInfoBanner(
                        title = "Not a Git Repository",
                        message = "Initialize Git in this workspace to track status, inspect diffs, and create commits.",
                    )
                    Button(
                        onClick = onInitRepo,
                        enabled = !gitState.isActionBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("git_init_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Initialize Git Repository")
                    }
                } else {
                    AgtCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Branch: ${status.branchName ?: "HEAD"}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            AgtStatusBadge(
                                label = if (status.changedFiles.isEmpty()) {
                                    "Clean"
                                } else {
                                    "${status.changedFiles.size} changed"
                                },
                                tone = if (status.changedFiles.isEmpty()) StatusTone.SUCCESS else StatusTone.ACCENT,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status.remoteUrl ?: "Remote: None configured (local repo)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (status.aheadCount > 0 || status.behindCount > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Ahead ${status.aheadCount} · Behind ${status.behindCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (status.changedFiles.isNotEmpty()) {
                        AgtCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Working Tree Changes", style = MaterialTheme.typography.titleSmall)
                                if (status.diffText.isNotBlank()) {
                                    TextButton(
                                        onClick = { onToggleDiffPreview(!gitState.showDiffPreview) },
                                        modifier = Modifier.testTag("git_toggle_diff_button"),
                                    ) {
                                        Text(if (gitState.showDiffPreview) "Hide Diff" else "View Diff")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            status.changedFiles.take(25).forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AgtStatusBadge(
                                        label = file.statusCode,
                                        tone = if (file.isSensitive) StatusTone.WARNING else StatusTone.NEUTRAL,
                                    )
                                    Text(
                                        text = file.relativePath + if (file.isSensitive) " (excluded secret)" else "",
                                        style = CodeTextStyle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (status.changedFiles.size > 25) {
                                Text(
                                    text = "+${status.changedFiles.size - 25} more files…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (gitState.showDiffPreview && status.diffText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.background,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .testTag("git_diff_preview"),
                                ) {
                                    Text(
                                        text = status.diffText,
                                        style = CodeTextStyle,
                                        modifier = Modifier
                                            .horizontalScroll(rememberScrollState())
                                            .verticalScroll(rememberScrollState())
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }
                    }

                    AgtCard {
                        Text("Commit Changes", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = gitState.commitMessageDraft,
                            onValueChange = onCommitMessageChange,
                            label = { Text("Commit message") },
                            placeholder = { Text("feat: implement changes") },
                            enabled = !gitState.isActionBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("git_commit_message_input"),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Exclude .env / keyfiles automatically",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            Switch(
                                checked = gitState.excludeSensitiveFiles,
                                onCheckedChange = onToggleExcludeSensitive,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onCommitChanges,
                            enabled = !gitState.isActionBusy && status.changedFiles.isNotEmpty() && gitState.commitMessageDraft.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("git_commit_button"),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Stage & Commit")
                        }
                    }

                    if (hasRemote) {
                        AgtCard {
                            Text("Remote Sync & Pull Request", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Push and PR creation require explicit approval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (gitState.pendingPushApproval) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("git_push_approval_card"),
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Approve Git Push", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Push branch '${status.branchName ?: "main"}' to ${status.remoteUrl}?",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = onCancelPush,
                                                enabled = !gitState.isActionBusy,
                                                modifier = Modifier.weight(1f).testTag("git_cancel_push_button"),
                                            ) {
                                                Text("Cancel")
                                            }
                                            Button(
                                                onClick = onConfirmPush,
                                                enabled = !gitState.isActionBusy,
                                                modifier = Modifier.weight(1f).testTag("git_confirm_push_button"),
                                            ) {
                                                Text("Approve Push")
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = onRequestPush,
                                        enabled = !gitState.isActionBusy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("git_push_button"),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text("Push")
                                    }
                                    OutlinedButton(
                                        onClick = if (gitState.showPrForm) onClosePrForm else onOpenPrForm,
                                        enabled = !gitState.isActionBusy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("git_open_pr_button"),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text(if (gitState.showPrForm) "Hide PR" else "Create PR")
                                    }
                                }
                            }

                            if (gitState.showPrForm) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.height(8.dp))
                                if (!hasGitHubAccount) {
                                    Text(
                                        text = "Connect GitHub in Settings → GitHub before creating a pull request.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                OutlinedTextField(
                                    value = gitState.prTitleDraft,
                                    onValueChange = onPrTitleChange,
                                    label = { Text("Pull Request Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("git_pr_title_input"),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = gitState.prHeadBranchDraft,
                                        onValueChange = onPrHeadBranchChange,
                                        label = { Text("Head") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("git_pr_head_input"),
                                    )
                                    OutlinedTextField(
                                        value = gitState.prBaseBranchDraft,
                                        onValueChange = onPrBaseBranchChange,
                                        label = { Text("Base") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("git_pr_base_input"),
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = gitState.prBodyDraft,
                                    onValueChange = onPrBodyChange,
                                    label = { Text("Description (optional)") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth().testTag("git_pr_body_input"),
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (gitState.pendingPrApproval) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("git_pr_approval_card"),
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Approve Pull Request", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "Open PR \"${gitState.prTitleDraft}\" (${gitState.prHeadBranchDraft} -> ${gitState.prBaseBranchDraft}) on GitHub?",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = onCancelCreatePr,
                                                    enabled = !gitState.isActionBusy,
                                                    modifier = Modifier.weight(1f).testTag("git_cancel_pr_button"),
                                                ) {
                                                    Text("Cancel")
                                                }
                                                Button(
                                                    onClick = onConfirmCreatePr,
                                                    enabled = !gitState.isActionBusy,
                                                    modifier = Modifier.weight(1f).testTag("git_confirm_pr_button"),
                                                ) {
                                                    Text("Approve PR")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onRequestCreatePr,
                                        enabled = !gitState.isActionBusy && hasGitHubAccount && gitState.prTitleDraft.isNotBlank(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("git_request_pr_button"),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text("Review & Submit PR")
                                    }
                                }
                            }
                        }
                    }
                }

                if (gitState.isActionBusy) {
                    AgtLoadingRow(label = "Running Git operation…")
                }

                if (gitState.actionError != null) {
                    AgtInfoBanner(
                        title = "Git Error",
                        message = gitState.actionError,
                        isError = true,
                    )
                }

                if (gitState.actionMessage != null) {
                    AgtInfoBanner(
                        title = "Git Status",
                        message = gitState.actionMessage,
                    )
                }

                gitState.lastPullRequest?.let { pr ->
                    AgtInfoBanner(
                        title = "Pull Request #${pr.number} (${pr.state})",
                        message = "${pr.title}\n${pr.htmlUrl}",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !gitState.isActionBusy) {
                Text("Close")
            }
        },
    )
}
