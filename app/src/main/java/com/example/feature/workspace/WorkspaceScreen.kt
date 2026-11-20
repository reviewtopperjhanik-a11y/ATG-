package com.example.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.WorkspaceTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onBack: () -> Unit,
    onOpenGatewaySettings: () -> Unit,
    onOpenAgentSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.project?.name ?: "Project",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("workspace_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::openFlightRecorder,
                        modifier = Modifier.testTag("workspace_flight_recorder_button"),
                    ) {
                        if (state.flightRecorderFailureCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(state.flightRecorderFailureCount.toString())
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Timeline,
                                    contentDescription = stringResource(R.string.flight_recorder_open_desc),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Timeline,
                                contentDescription = stringResource(R.string.flight_recorder_open_desc),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (state.isLoadingProject) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                com.example.core.design.AgtLoadingRow(label = "Loading project workspace…")
            }
            return@Scaffold
        }

        if (state.project == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AgtEmptyState(
                    title = "Project Not Found",
                    description = "Return to Home to create or open a workspace.",
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            WorkspaceTabStrip(
                selectedTab = state.selectedTab,
                onSelectTab = viewModel::selectTab,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.selectedTab) {
                    WorkspaceTab.AGENT -> AgentView(
                        state = state,
                        onComposerTextChanged = viewModel::updateComposerText,
                        onSelectModel = viewModel::selectModel,
                        onAttachDocument = viewModel::attachDocumentUri,
                        onAttachCameraBitmap = viewModel::attachCameraPreview,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onExtensionAttachmentSelected = viewModel::onExtensionAttachmentSelected,
                        onSendPrompt = viewModel::submitPrompt,
                        onStopStream = viewModel::stopActiveStream,
                        onPauseStream = viewModel::pauseActiveStream,
                        onResumeTask = viewModel::resumeTask,
                        onResolveApproval = viewModel::resolveApproval,
                        onResolvePlanApproval = viewModel::resolvePlanApproval,
                        onOpenCheckpoints = viewModel::openCheckpointsSheet,
                        onOpenFlightRecorder = viewModel::openFlightRecorder,
                        onPreviewCheckpointDiff = viewModel::previewCheckpointDiff,
                        onOpenGatewaySettings = onOpenGatewaySettings,
                        onOpenAgentSettings = onOpenAgentSettings,
                        onSelectExecutionMode = viewModel::selectExecutionMode,
                        onEditCouncilSection = viewModel::editCouncilSection,
                        onRevertCouncilSection = viewModel::revertCouncilSection,
                        onSubmitCouncilDecision = viewModel::submitCouncilDecision,
                    )
                    WorkspaceTab.FILES -> FilesView(
                        state = state,
                        onRefresh = { viewModel.refreshFiles(state.currentRelativePath) },
                        onNavigateUp = viewModel::navigateUpDirectory,
                        onOpenEntry = viewModel::openEntry,
                        onCloseFile = viewModel::closeOpenedFile,
                        onToggleEdit = viewModel::toggleEditMode,
                        onEditDraftChanged = viewModel::updateEditedFileDraft,
                        onSaveFile = viewModel::saveOpenedFile,
                        onOpenCreateFile = viewModel::openCreateFileDialog,
                        onDismissCreateFile = viewModel::dismissCreateFileDialog,
                        onNewFileNameChanged = viewModel::updateNewFileNameDraft,
                        onConfirmCreateFile = viewModel::confirmCreateFile,
                        onRequestDeleteEntry = viewModel::requestDeleteEntry,
                        onDismissDeleteEntry = viewModel::dismissDeleteEntry,
                        onConfirmDeleteEntry = viewModel::confirmDeleteEntry,
                        onOpenCheckpoints = viewModel::openCheckpointsSheet,
                        onOpenGit = viewModel::openGitDialog,
                    )
                    WorkspaceTab.TERMINAL -> TerminalView(
                        state = state,
                        onTerminalInputChanged = viewModel::updateTerminalInput,
                        onSubmitCommand = viewModel::submitTerminalCommand,
                        onStopCommand = viewModel::stopTerminalCommand,
                        onClearOutput = viewModel::clearTerminalOutput,
                    )
                    WorkspaceTab.PREVIEW -> PreviewView(
                        state = state,
                        onPreviewUrlChanged = viewModel::updatePreviewUrl,
                        onPreviewCommandChanged = viewModel::updatePreviewCommandDraft,
                        onStartServer = viewModel::startPreviewServer,
                        onStopServer = viewModel::stopPreviewServer,
                        onReloadOrOpenUrl = viewModel::openOrReloadPreviewUrl,
                        onToggleLogs = viewModel::togglePreviewLogs,
                        onWebViewError = viewModel::reportPreviewWebViewError,
                    )
                }
            }
        }

        if (state.showFlightRecorderSheet) {
            FlightRecorderDialog(
                events = state.filteredFlightRecorderEvents,
                totalEventCount = state.flightRecorderEvents.size,
                failureCount = state.flightRecorderFailureCount,
                selectedFilter = state.flightRecorderFilter,
                selectedTaskId = state.flightRecorderTaskFilterId,
                taskSnapshots = state.taskSnapshots,
                latestSnapshot = state.latestTaskSnapshot,
                projectMemory = state.projectMemory,
                onSelectFilter = viewModel::setFlightRecorderFilter,
                onSelectTask = viewModel::setFlightRecorderTaskFilter,
                onInspectCheckpoint = { chkId ->
                    viewModel.dismissFlightRecorder()
                    viewModel.previewCheckpointDiff(chkId)
                },
                onDismiss = viewModel::dismissFlightRecorder,
                onRefreshRepoMap = viewModel::refreshRepoMap,
                availableModels = state.availableModels,
                activeModelId = state.selectedModelId,
                activeBackendId = state.activeAgentId,
                onResumeTask = viewModel::resumeTask,
                knowledgeEntries = state.knowledgeEntries,
                knowledgeSearchQuery = state.knowledgeSearchQuery,
                knowledgeSearchResults = state.knowledgeSearchResults,
                knowledgeIncludeSuperseded = state.knowledgeIncludeSuperseded,
                knowledgeUsedFts5 = state.knowledgeUsedFts5,
                onKnowledgeSearchChanged = viewModel::searchKnowledge,
                onToggleKnowledgeSuperseded = viewModel::toggleKnowledgeIncludeSuperseded,
            )
        }

        if (state.showCheckpointsSheet) {
            CheckpointsListDialog(
                checkpoints = state.checkpoints,
                actionMessage = state.checkpointActionMessage,
                onCreateCheckpoint = { viewModel.createManualCheckpoint() },
                onPreviewDiff = viewModel::previewCheckpointDiff,
                onRequestRestore = viewModel::requestRestoreCheckpoint,
                onDismiss = viewModel::dismissCheckpointsSheet,
            )
        }

        CheckpointDiffPreviewDialog(
            diff = state.selectedDiff,
            isLoading = state.diffLoading,
            pendingRestore = state.checkpointPendingRestore,
            onDismissDiff = viewModel::dismissDiffPreview,
            onConfirmRestore = viewModel::confirmRestoreCheckpoint,
            onDismissRestore = viewModel::dismissRestoreCheckpoint,
        )

        if (state.gitState.showDialog) {
            GitWorkflowDialog(
                gitState = state.gitState,
                onRefresh = viewModel::refreshGitStatus,
                onInitRepo = viewModel::initGitRepository,
                onCommitMessageChange = viewModel::updateGitCommitMessage,
                onToggleExcludeSensitive = viewModel::toggleExcludeSensitiveFiles,
                onToggleDiffPreview = viewModel::toggleGitDiffPreview,
                onCommitChanges = viewModel::commitGitChanges,
                onRequestPush = viewModel::requestGitPushApproval,
                onConfirmPush = viewModel::confirmGitPush,
                onCancelPush = viewModel::cancelGitPushApproval,
                onOpenPrForm = viewModel::openPullRequestForm,
                onClosePrForm = viewModel::closePullRequestForm,
                onPrTitleChange = viewModel::updatePrTitle,
                onPrBodyChange = viewModel::updatePrBody,
                onPrHeadBranchChange = viewModel::updatePrHeadBranch,
                onPrBaseBranchChange = viewModel::updatePrBaseBranch,
                onRequestCreatePr = viewModel::requestCreatePullRequestApproval,
                onConfirmCreatePr = viewModel::confirmCreatePullRequest,
                onCancelCreatePr = viewModel::cancelCreatePullRequestApproval,
                onDismiss = viewModel::dismissGitDialog,
            )
        }

        state.pendingExtensionPickerKind?.let { pickerKind ->
            val options = when (pickerKind) {
                ComposerAttachmentKind.SKILLS -> state.enabledSkills
                ComposerAttachmentKind.MCP_SERVERS -> state.enabledMcpServers
                ComposerAttachmentKind.PLUGINS -> state.enabledPlugins
                else -> emptyList()
            }
            ExtensionAttachmentPickerDialog(
                kind = pickerKind,
                options = options,
                onSelect = viewModel::attachExtensionRecord,
                onDismiss = viewModel::dismissExtensionPicker,
            )
        }
    }
}

@Composable
private fun ExtensionAttachmentPickerDialog(
    kind: ComposerAttachmentKind,
    options: List<ExtensionRecord>,
    onSelect: (ExtensionRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach Enabled ${kind.label}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.id }) { ext ->
                    AgtCard(
                        onClick = { onSelect(ext) },
                        modifier = Modifier.testTag("ext_picker_item_${ext.id}"),
                    ) {
                        Text(ext.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = ext.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceTabStrip(
    selectedTab: WorkspaceTab,
    onSelectTab: (WorkspaceTab) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkspaceTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val label = when (tab) {
                    WorkspaceTab.AGENT -> stringResource(R.string.tab_agent)
                    WorkspaceTab.FILES -> stringResource(R.string.tab_files)
                    WorkspaceTab.TERMINAL -> stringResource(R.string.tab_terminal)
                    WorkspaceTab.PREVIEW -> stringResource(R.string.tab_preview)
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .minimumInteractiveComponentSize()
                        .clickable { onSelectTab(tab) }
                        .testTag("workspace_tab_${tab.name.lowercase()}"),
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
    }
}
