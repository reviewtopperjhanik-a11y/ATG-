package com.example.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.CheckpointSummary
import com.example.domain.model.FileChangeType
import com.example.domain.model.ProjectDiff
import com.example.ui.theme.CodeTextStyle
import java.text.DateFormat
import java.util.Date

@Composable
fun CheckpointsListDialog(
    checkpoints: List<CheckpointSummary>,
    actionMessage: String?,
    onCreateCheckpoint: () -> Unit,
    onPreviewDiff: (String) -> Unit,
    onRequestRestore: (CheckpointSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace Checkpoints", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actionMessage != null) {
                    Text(
                        text = actionMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = onCreateCheckpoint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("create_manual_checkpoint_button"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Create Snapshot Now")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (checkpoints.isEmpty()) {
                    AgtEmptyState(
                        title = "No checkpoints yet",
                        description = "Create a manual snapshot or run an agent task with checkpoints enabled.",
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(checkpoints, key = { it.id }) { chk ->
                            val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(chk.createdAtEpochMs))
                            AgtCard(modifier = Modifier.testTag("checkpoint_row_${chk.id}")) {
                                Text(
                                    text = chk.reason,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$formatted · ${chk.fileCount} file(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onPreviewDiff(chk.id) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("checkpoint_view_diff_${chk.id}"),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text("Diff")
                                    }
                                    Button(
                                        onClick = { onRequestRestore(chk) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("checkpoint_restore_btn_${chk.id}"),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text("Restore")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
fun CheckpointDiffPreviewDialog(
    diff: ProjectDiff?,
    isLoading: Boolean,
    pendingRestore: CheckpointSummary?,
    onDismissDiff: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit,
) {
    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text("Confirm Checkpoint Restore", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Restoring '${pendingRestore.reason}' will revert workspace changes back to this snapshot. A safety backup checkpoint will be created first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isLoading) {
                        AgtLoadingRow(label = "Calculating workspace diff impact…")
                    } else if (diff != null) {
                        DiffEntryList(diff = diff)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmRestore,
                    modifier = Modifier.testTag("confirm_restore_checkpoint_button"),
                ) {
                    Text("Restore Workspace")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestore) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        return
    }

    if (diff != null || isLoading) {
        AlertDialog(
            onDismissRequest = onDismissDiff,
            title = { Text("Diff vs Checkpoint", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLoading) {
                        AgtLoadingRow(label = "Computing file diffs…")
                    } else if (diff != null) {
                        Text(
                            text = "${diff.checkpointReason} · ${diff.totalFilesChanged} file(s) changed since snapshot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DiffEntryList(diff = diff)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissDiff) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun DiffEntryList(diff: ProjectDiff) {
    if (!diff.hasChanges) {
        Text(
            text = "Workspace matches this checkpoint with no uncommitted differences.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(diff.entries, key = { it.relativePath }) { entry ->
            val tone = when (entry.changeType) {
                FileChangeType.ADDED -> StatusTone.SUCCESS
                FileChangeType.MODIFIED -> StatusTone.ACCENT
                FileChangeType.DELETED -> StatusTone.ERROR
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.relativePath,
                            style = CodeTextStyle,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AgtStatusBadge(label = entry.changeType.label, tone = tone)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.unifiedDiff,
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
