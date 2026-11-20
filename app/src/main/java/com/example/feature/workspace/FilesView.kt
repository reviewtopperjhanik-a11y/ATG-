package com.example.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.domain.model.FileTreeEntry
import com.example.ui.theme.CodeTextStyle

@Composable
fun FilesView(
    state: WorkspaceUiState,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenEntry: (FileTreeEntry) -> Unit,
    onCloseFile: () -> Unit,
    onToggleEdit: (Boolean) -> Unit,
    onEditDraftChanged: (String) -> Unit,
    onSaveFile: () -> Unit,
    onOpenCreateFile: () -> Unit,
    onDismissCreateFile: () -> Unit,
    onNewFileNameChanged: (String) -> Unit,
    onConfirmCreateFile: () -> Unit,
    onRequestDeleteEntry: (FileTreeEntry) -> Unit,
    onDismissDeleteEntry: () -> Unit,
    onConfirmDeleteEntry: () -> Unit,
    onOpenCheckpoints: () -> Unit,
    onOpenGit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.openedFilePath != null) {
        Column(modifier = modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        IconButton(
                            onClick = onCloseFile,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to file tree",
                            )
                        }
                        Text(
                            text = state.openedFilePath,
                            style = CodeTextStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.isEditingFile) {
                        IconButton(
                            onClick = onSaveFile,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("files_save_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = "Save file",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { onToggleEdit(true) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("files_edit_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit file",
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (state.isEditingFile) {
                OutlinedTextField(
                    value = state.editedFileDraft,
                    onValueChange = onEditDraftChanged,
                    textStyle = CodeTextStyle,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .testTag("files_editor_input"),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        text = state.openedFileContent.orEmpty(),
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.currentRelativePath.isNotEmpty()) {
                        TextButton(
                            onClick = onNavigateUp,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Text("..")
                        }
                    }
                    Text(
                        text = "/${state.currentRelativePath}",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenGit,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("files_git_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccountTree,
                            contentDescription = "Git status, diff, commit, and push",
                        )
                    }
                    IconButton(
                        onClick = onOpenCheckpoints,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("files_checkpoints_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "Checkpoints and diffs",
                        )
                    }
                    IconButton(
                        onClick = onOpenCreateFile,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("files_create_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "New file",
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("files_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh files",
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.filesError != null) {
                item {
                    AgtInfoBanner(
                        title = "File Access Error",
                        message = state.filesError,
                        isError = true,
                    )
                }
            }
            if (state.filesLoading) {
                item {
                    AgtLoadingRow(label = "Scanning project directory…")
                }
            } else if (state.files.isEmpty()) {
                item {
                    AgtEmptyState(
                        title = "Workspace is empty",
                        description = "Create a file with '+' or let the agent create files inside this confined project root.",
                        icon = Icons.Outlined.Folder,
                    )
                }
            } else {
                items(state.files, key = { it.relativePath }) { entry ->
                    AgtCard(onClick = { onOpenEntry(entry) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = null,
                                tint = if (entry.isDirectory) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!entry.isDirectory) {
                                Text(
                                    text = "${entry.sizeBytes} B",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onRequestDeleteEntry(entry) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("delete_entry_${entry.name}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete ${entry.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = onDismissCreateFile,
            title = { Text("New File", style = MaterialTheme.typography.titleLarge) },
            text = {
                OutlinedTextField(
                    value = state.newFileNameDraft,
                    onValueChange = onNewFileNameChanged,
                    label = { Text("Relative file path") },
                    placeholder = { Text("e.g. README.md or src/main.kt") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_name_input"),
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCreateFile,
                    modifier = Modifier.testTag("confirm_create_file_button"),
                ) {
                    Text(stringResource(R.string.create_project_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCreateFile) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.entryPendingDeletion?.let { target ->
        AlertDialog(
            onDismissRequest = onDismissDeleteEntry,
            title = { Text("Delete Entry?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text("Delete \"${target.relativePath}\" from this workspace?")
            },
            confirmButton = {
                Button(onClick = onConfirmDeleteEntry) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteEntry) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
