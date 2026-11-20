package com.example.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtLoadingRow
import com.example.core.design.AgtSectionHeader
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.GitHubRepoSummary
import com.example.domain.model.ProjectSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val suggestedName = uri?.let {
            runCatching {
                DocumentsContract.getTreeDocumentId(it)
                    .substringAfterLast(':')
                    .substringAfterLast('/')
                    .take(40)
            }.getOrNull()
        }.orEmpty().ifBlank { "imported-project" }
        viewModel.onLocalDirectoryPicked(uri, suggestedName)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.openCreateDialog() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_create_project_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.home_create_project))
                    }
                    OutlinedButton(
                        onClick = { viewModel.openImportChooser() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("home_import_repo_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.home_import_repo))
                    }
                }
            }

            state.lastCrashReport?.let { crash ->
                item {
                    AgtCard(modifier = Modifier.testTag("home_crash_recovery_banner")) {
                        AgtInfoBanner(
                            title = stringResource(R.string.home_crash_banner_title),
                            message = stringResource(
                                R.string.home_crash_banner_body,
                                crash.exceptionClass.substringAfterLast('.'),
                                crash.message,
                            ),
                            isError = true,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = viewModel::dismissLastCrashReport,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_crash_dismiss_button"),
                            ) {
                                Text(stringResource(R.string.home_crash_banner_dismiss))
                            }
                        }
                    }
                }
            }

            if (state.isBusy) {
                item { AgtLoadingRow(label = "Preparing workspace…") }
            }

            if (state.errorBanner != null) {
                item {
                    AgtInfoBanner(
                        title = "Workspace Error",
                        message = state.errorBanner.orEmpty(),
                        isError = true,
                    )
                }
            }

            if (state.infoBanner != null) {
                item {
                    AgtInfoBanner(
                        title = "Repository Import",
                        message = state.infoBanner.orEmpty(),
                    )
                }
            }

            item {
                AgtSectionHeader(
                    title = stringResource(R.string.home_recent_projects),
                    trailing = {
                        Text(
                            text = "${state.projects.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            if (state.projects.isEmpty()) {
                item {
                    AgtEmptyState(
                        title = stringResource(R.string.home_empty_title),
                        description = stringResource(R.string.home_empty_subtitle),
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
            } else {
                items(state.projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        onClick = { onOpenProject(project.id) },
                        onDeleteClick = { viewModel.requestDeleteProject(project) },
                    )
                }
            }
        }
    }

    if (state.showCreateDialog) {
        val isImport = state.pendingImportUri != null
        AlertDialog(
            onDismissRequest = { viewModel.dismissCreateDialog() },
            title = {
                Text(
                    text = if (isImport) stringResource(R.string.import_project_dialog_title) else stringResource(R.string.create_project_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.projectNameDraft,
                        onValueChange = { viewModel.updateProjectNameDraft(it) },
                        label = { Text(stringResource(R.string.create_project_name_label)) },
                        placeholder = { Text(stringResource(R.string.create_project_name_hint)) },
                        singleLine = true,
                        isError = state.projectNameError != null,
                        supportingText = state.projectNameError?.let { err ->
                            { Text(text = err) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("project_name_input"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmCreateOrImportProject(onOpenProject) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("create_project_confirm_button"),
                ) {
                    Text(if (isImport) stringResource(R.string.import_confirm) else stringResource(R.string.create_project_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissCreateDialog() },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.showImportChooserDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportChooser() },
            title = { Text("Open Existing Project", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Import a local folder via Android Storage Access Framework or clone a GitHub repository into a confined workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("home_import_local_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Import Local Folder")
                    }
                    OutlinedButton(
                        onClick = { viewModel.onGitHubCloneRequested() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("home_clone_github_button"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Clone from GitHub")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissImportChooser() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.showGitHubCloneDialog) {
        GitHubCloneDialog(
            state = state,
            onRefreshRepos = viewModel::refreshGitHubRepositories,
            onSearchQueryChange = viewModel::updateCloneSearchQuery,
            onSelectRepo = viewModel::selectGitHubRepoToClone,
            onCloneUrlChange = viewModel::updateCloneUrlDraft,
            onBranchChange = viewModel::updateCloneBranchDraft,
            onProjectNameChange = viewModel::updateCloneProjectNameDraft,
            onConfirmClone = { viewModel.confirmCloneRepository(onOpenProject) },
            onDismiss = viewModel::dismissGitHubCloneDialog,
        )
    }

    state.projectPendingDeletion?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteProject() },
            title = { Text("Delete Project?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    text = "Remove \"${target.name}\" and all local files in ${target.runtimeMountPath}? This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmDeleteProject() }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteProject() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GitHubCloneDialog(
    state: HomeUiState,
    onRefreshRepos: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectRepo: (GitHubRepoSummary) -> Unit,
    onCloneUrlChange: (String) -> Unit,
    onBranchChange: (String) -> Unit,
    onProjectNameChange: (String) -> Unit,
    onConfirmClone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isConnected = state.gitHubAccount.status == GitHubAuthStatus.CONNECTED
    val filteredRepos = remember(state.gitHubRepositories, state.cloneSearchQuery) {
        val q = state.cloneSearchQuery.trim()
        if (q.isEmpty()) {
            state.gitHubRepositories
        } else {
            state.gitHubRepositories.filter {
                it.fullName.contains(q, ignoreCase = true) ||
                    (it.description?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("github_clone_dialog"),
        title = { Text("Clone GitHub Repository", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgtStatusBadge(
                        label = if (isConnected) {
                            "@${state.gitHubAccount.username.orEmpty()}"
                        } else {
                            "Public HTTPS / Unauthenticated"
                        },
                        tone = if (isConnected) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                    )
                    if (isConnected) {
                        IconButton(
                            onClick = onRefreshRepos,
                            enabled = !state.isLoadingGitHubRepos && !state.isCloning,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.home_refresh_repos_desc),
                            )
                        }
                    }
                }

                if (isConnected) {
                    OutlinedTextField(
                        value = state.cloneSearchQuery,
                        onValueChange = onSearchQueryChange,
                        label = { Text("Filter your repositories") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.isLoadingGitHubRepos) {
                        AgtLoadingRow(label = "Loading GitHub repositories…")
                    } else if (state.gitHubRepoError != null) {
                        AgtInfoBanner(
                            title = "GitHub Repositories",
                            message = state.gitHubRepoError,
                            isError = true,
                        )
                    } else if (filteredRepos.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            filteredRepos.take(6).forEach { repo ->
                                val selected = state.cloneUrlDraft == repo.cloneUrl
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
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(enabled = !state.isCloning) { onSelectRepo(repo) },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = repo.fullName + if (repo.isPrivate) " (Private)" else "",
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "Branch: ${repo.defaultBranch}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = state.cloneUrlDraft,
                    onValueChange = onCloneUrlChange,
                    label = { Text("Repository HTTPS URL") },
                    placeholder = { Text("https://github.com/owner/repo.git") },
                    singleLine = true,
                    enabled = !state.isCloning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clone_url_input"),
                )

                OutlinedTextField(
                    value = state.cloneProjectNameDraft,
                    onValueChange = onProjectNameChange,
                    label = { Text("Workspace project name") },
                    placeholder = { Text("my-repo") },
                    singleLine = true,
                    enabled = !state.isCloning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clone_project_name_input"),
                )

                OutlinedTextField(
                    value = state.cloneBranchDraft,
                    onValueChange = onBranchChange,
                    label = { Text("Branch (optional)") },
                    placeholder = { Text("main") },
                    singleLine = true,
                    enabled = !state.isCloning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clone_branch_input"),
                )

                if (state.isCloning) {
                    AgtLoadingRow(label = "Cloning repository via Git…")
                }

                if (state.cloneError != null) {
                    AgtInfoBanner(
                        title = "Clone Error",
                        message = state.cloneError,
                        isError = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmClone,
                enabled = !state.isCloning,
                modifier = Modifier.testTag("clone_confirm_button"),
            ) {
                Text("Clone")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isCloning) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val formattedDate = remember(project.lastOpenedEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(project.lastOpenedEpochMs))
    }
    AgtCard(
        onClick = onClick,
        modifier = Modifier.testTag("project_row_${project.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AgtStatusBadge(label = project.stackLabel, tone = StatusTone.NEUTRAL)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${project.runtimeMountPath} · $formattedDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_project_${project.id}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.home_delete_project_desc, project.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
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
