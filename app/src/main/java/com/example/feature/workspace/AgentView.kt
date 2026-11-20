package com.example.feature.workspace

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AgentTimelineItem
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.PermissionClass
import com.example.domain.model.TaskState
import com.example.ui.theme.CodeTextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentView(
    state: WorkspaceUiState,
    onComposerTextChanged: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onAttachDocument: (Uri, ComposerAttachmentKind) -> Unit,
    onAttachCameraBitmap: (Bitmap) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onExtensionAttachmentSelected: (ComposerAttachmentKind) -> Unit,
    onSendPrompt: () -> Unit,
    onStopStream: () -> Unit,
    onPauseStream: () -> Unit = {},
    onResumeTask: (taskId: String, modelId: String?, backendId: String?, note: String?) -> Unit = { _, _, _, _ -> },
    onResolveApproval: (String, Boolean) -> Unit,
    onResolvePlanApproval: (String, Boolean) -> Unit,
    onOpenCheckpoints: () -> Unit,
    onOpenFlightRecorder: () -> Unit = {},
    onPreviewCheckpointDiff: (String) -> Unit,
    onOpenGatewaySettings: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onSelectExecutionMode: (CouncilExecutionMode) -> Unit = {},
    onEditCouncilSection: (sessionId: String, roundNumber: Int, sectionId: CouncilSectionId, newContent: String) -> Unit = { _, _, _, _ -> },
    onRevertCouncilSection: (sessionId: String, roundNumber: Int, sectionId: CouncilSectionId) -> Unit = { _, _, _ -> },
    onSubmitCouncilDecision: (taskId: String, decision: CouncilUserDecision) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAttachDocument(uri, ComposerAttachmentKind.IMAGE)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAttachDocument(uri, ComposerAttachmentKind.FILE)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            onAttachCameraBitmap(bitmap)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.timeline.size) {
        if (state.timeline.isNotEmpty()) {
            listState.animateScrollToItem(state.timeline.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Runtime + Gateway status bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val activeAgent = state.activeAgentId
                val agentReady = state.activeAgentInstalled
                val agentVer = state.activeAgentVersion
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgtStatusBadge(
                        label = if (agentReady) {
                            "${activeAgent.shortLabel} v${agentVer.orEmpty()}"
                        } else {
                            "${activeAgent.shortLabel} Not Installed"
                        },
                        tone = if (agentReady) StatusTone.SUCCESS else StatusTone.WARNING,
                    )
                    state.activeTaskState?.let { tState ->
                        AgtStatusBadge(
                            label = tState.name,
                            tone = when (tState) {
                                TaskState.COMPLETED -> StatusTone.SUCCESS
                                TaskState.FAILED -> StatusTone.ERROR
                                TaskState.AWAITING_TOOL_APPROVAL,
                                TaskState.AWAITING_PLAN_APPROVAL,
                                TaskState.AWAITING_COUNCIL_APPROVAL -> StatusTone.WARNING
                                else -> StatusTone.ACCENT
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.flightRecorderEvents.isNotEmpty() || state.taskSnapshots.isNotEmpty()) {
                        TextButton(
                            onClick = onOpenFlightRecorder,
                            modifier = Modifier.testTag("agent_flight_recorder_button"),
                        ) {
                            val label = if (state.flightRecorderFailureCount > 0) {
                                "Flight (${state.flightRecorderEvents.size} · ${state.flightRecorderFailureCount}!)"
                            } else {
                                "Flight (${state.flightRecorderEvents.size})"
                            }
                            Text(
                                text = label,
                                color = if (state.flightRecorderFailureCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    if (state.checkpoints.isNotEmpty()) {
                        TextButton(
                            onClick = onOpenCheckpoints,
                            modifier = Modifier.testTag("agent_checkpoints_button"),
                        ) {
                            Text("Checkpoints (${state.checkpoints.size})")
                        }
                    }
                    if (!agentReady) {
                        TextButton(
                            onClick = onOpenAgentSettings,
                            modifier = Modifier.testTag("agent_configure_opencode_button"),
                        ) {
                            Text("Setup ${activeAgent.shortLabel}")
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (state.timeline.isEmpty()) {
            val activeAgent = state.activeAgentId
            val agentReady = state.activeAgentInstalled
            val isCouncilOnly = state.selectedExecutionMode == CouncilExecutionMode.COUNCIL_ONLY
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AgtEmptyState(
                    title = when {
                        state.availableModels.isEmpty() -> "Connect JH GetWay Pro"
                        isCouncilOnly -> "Multi-Agent Council Ready (${state.availableModels.size} models)"
                        !agentReady -> "Install ${activeAgent.displayName}"
                        else -> "${activeAgent.shortLabel} & Gateway Ready (${state.availableModels.size} models)"
                    },
                    description = when {
                        state.availableModels.isEmpty() ->
                            "Configure JH GetWay Pro in Settings → Gateway to discover models from /v1/models."
                        isCouncilOnly ->
                            "Council Only mode is active: run Web Search, GitHub Repo Search, and 5-specialist debate without modifying files or installing a CLI runtime."
                        !agentReady ->
                            "${activeAgent.displayName} is required for autonomous workspace tools. Configure and install it in Settings → Agent."
                        else ->
                            "Submit an engineering task below to run ${activeAgent.shortLabel} inside ${state.project?.name ?: "this workspace"} via JH GetWay Pro."
                    },
                    icon = Icons.Outlined.SmartToy,
                    action = when {
                        state.availableModels.isEmpty() -> {
                            {
                                TextButton(onClick = onOpenGatewaySettings) {
                                    Text("Configure Gateway")
                                }
                            }
                        }
                        !agentReady && !isCouncilOnly -> {
                            {
                                TextButton(onClick = onOpenAgentSettings) {
                                    Text("Open Agent Settings")
                                }
                            }
                        }
                        else -> null
                    },
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("agent_timeline_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.timeline, key = { it.id }) { item ->
                    when (item) {
                        is AgentTimelineItem.UserPrompt -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = item.modelId?.let { "Task Request · $it" } ?: "Task Request",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        is AgentTimelineItem.AssistantResponse -> {
                            AgtCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = item.modelId ?: "${state.activeAgentId.shortLabel} Response",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (item.isStreaming) {
                                        AgtStatusBadge(label = "Streaming", tone = StatusTone.ACCENT)
                                    } else if (item.promptTokens != null || item.completionTokens != null) {
                                        Text(
                                            text = "${item.promptTokens ?: 0}→${item.completionTokens ?: 0} tok",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.text.ifEmpty { "…" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        is AgentTimelineItem.SystemNotice -> {
                            AgtInfoBanner(
                                title = item.title,
                                message = item.detail,
                                isError = item.isWarning,
                            )
                        }
                        is AgentTimelineItem.ToolCallCard -> {
                            ToolCallCardItem(item)
                        }
                        is AgentTimelineItem.ApprovalPrompt -> {
                            ApprovalPromptCardItem(
                                item = item,
                                onApprove = { onResolveApproval(item.id, true) },
                                onDeny = { onResolveApproval(item.id, false) },
                            )
                        }
                        is AgentTimelineItem.PlanCard -> {
                            PlanCardItem(
                                item = item,
                                onApprovePlan = { onResolvePlanApproval(item.plan.taskId, true) },
                                onDenyPlan = { onResolvePlanApproval(item.plan.taskId, false) },
                            )
                        }
                        is AgentTimelineItem.CheckpointCard -> {
                            CheckpointCardItem(
                                item = item,
                                onPreviewDiff = { onPreviewCheckpointDiff(item.checkpointId) },
                            )
                        }
                        is AgentTimelineItem.ValidationCard -> {
                            ValidationCardItem(item = item)
                        }
                        is AgentTimelineItem.TaskSummaryCard -> {
                            TaskSummaryCardItem(
                                item = item,
                                onInspectCheckpoint = onPreviewCheckpointDiff,
                            )
                        }
                        is AgentTimelineItem.CouncilSessionCard -> {
                            CouncilSessionCardItem(
                                item = item,
                                onEditSection = onEditCouncilSection,
                                onRevertSection = onRevertCouncilSection,
                                onSubmitDecision = onSubmitCouncilDecision,
                            )
                        }
                    }
                }
            }
        }

        val resumableSnapshot = if (!state.isStreaming) {
            state.taskSnapshots.firstOrNull()?.takeIf { snap ->
                snap.currentState == com.example.domain.model.TaskState.PAUSED ||
                    snap.currentState == com.example.domain.model.TaskState.FAILED ||
                    snap.currentState == com.example.domain.model.TaskState.CANCELLED
            }
        } else null

        if (resumableSnapshot != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_resume_banner"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${resumableSnapshot.currentState.name} · Step ${resumableSnapshot.activeStepIndex + 1}/${resumableSnapshot.planSteps.size.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = resumableSnapshot.objective,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = onOpenFlightRecorder,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("agent_resume_handoff_options_button"),
                    ) {
                        Text(stringResource(R.string.agent_resume_switch))
                    }
                    Button(
                        onClick = {
                            onResumeTask(
                                resumableSnapshot.taskId,
                                state.selectedModelId,
                                state.activeAgentId.id,
                                null,
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("agent_resume_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.agent_resume))
                    }
                }
            }
        }

        AgentComposer(
            text = state.composerText,
            attachments = state.composerAttachments,
            selectedModelId = state.selectedModelId,
            availableModels = state.availableModels,
            selectedExecutionMode = state.selectedExecutionMode,
            isStreaming = state.isStreaming,
            onTextChanged = onComposerTextChanged,
            onSelectModel = onSelectModel,
            onSelectExecutionMode = onSelectExecutionMode,
            onRemoveAttachment = onRemoveAttachment,
            onAttachmentSelected = { kind ->
                when (kind) {
                    ComposerAttachmentKind.IMAGE ->
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    ComposerAttachmentKind.FILE ->
                        filePicker.launch(arrayOf("*/*"))
                    ComposerAttachmentKind.CAMERA ->
                        cameraLauncher.launch(null)
                    ComposerAttachmentKind.SKILLS,
                    ComposerAttachmentKind.MCP_SERVERS,
                    ComposerAttachmentKind.PLUGINS ->
                        onExtensionAttachmentSelected(kind)
                }
            },
            onSend = onSendPrompt,
            onPause = onPauseStream,
            onStop = onStopStream,
            onOpenGatewaySettings = onOpenGatewaySettings,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCallCardItem(item: AgentTimelineItem.ToolCallCard) {
    val statusTone = when (item.status.lowercase()) {
        "completed" -> StatusTone.SUCCESS
        "failed", "denied", "error" -> StatusTone.ERROR
        "running" -> StatusTone.ACCENT
        else -> StatusTone.NEUTRAL
    }
    AgtCard(modifier = Modifier.testTag("tool_card_${item.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgtStatusBadge(
                    label = item.permissionClass.name,
                    tone = if (item.permissionClass == PermissionClass.DESTRUCTIVE ||
                        item.permissionClass == PermissionClass.PRODUCTION_DEPLOYMENT
                    ) StatusTone.WARNING else StatusTone.NEUTRAL,
                )
            }
            AgtStatusBadge(label = item.status, tone = statusTone)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.summary,
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.outputSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.outputSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApprovalPromptCardItem(
    item: AgentTimelineItem.ApprovalPrompt,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("approval_card_${item.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                }
                AgtStatusBadge(
                    label = when {
                        !item.resolved -> "Approval Required"
                        item.approved == true -> "Approved"
                        else -> "Denied"
                    },
                    tone = when {
                        !item.resolved -> StatusTone.WARNING
                        item.approved == true -> StatusTone.SUCCESS
                        else -> StatusTone.ERROR
                    },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!item.resolved) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("approval_deny_${item.id}"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Deny")
                    }
                    Button(
                        onClick = onApprove,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("approval_approve_${item.id}"),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentComposer(
    text: String,
    attachments: List<AgentAttachment>,
    selectedModelId: String?,
    availableModels: List<GatewayModelInfo>,
    selectedExecutionMode: CouncilExecutionMode,
    isStreaming: Boolean,
    onTextChanged: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectExecutionMode: (CouncilExecutionMode) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onAttachmentSelected: (ComposerAttachmentKind) -> Unit,
    onSend: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onOpenGatewaySettings: () -> Unit,
) {
    var showPlusMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CouncilExecutionMode.entries.forEach { mode ->
                    val selected = mode == selectedExecutionMode
                    val label = when (mode) {
                        CouncilExecutionMode.DIRECT_CODE -> stringResource(R.string.council_mode_direct)
                        CouncilExecutionMode.COUNCIL_AND_CODE -> stringResource(R.string.council_mode_council_code)
                        CouncilExecutionMode.COUNCIL_ONLY -> stringResource(R.string.council_mode_council_only)
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
                            .heightIn(min = 48.dp)
                            .minimumInteractiveComponentSize()
                            .clickable(enabled = !isStreaming) { onSelectExecutionMode(mode) }
                            .testTag("composer_mode_${mode.id}"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
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
            if (attachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    attachments.forEach { att ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveAttachment(att.id) },
                            label = {
                                Text(
                                    text = "${att.kind.label}: ${att.displayName}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove ${att.displayName}",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.agent_composer_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChanged,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("agent_composer_input"),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box {
                                Surface(
                                    onClick = { showPlusMenu = true },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("agent_plus_button"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = stringResource(R.string.agent_plus_menu),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showPlusMenu,
                                    onDismissRequest = { showPlusMenu = false },
                                ) {
                                    ComposerAttachmentKind.entries.forEach { kind ->
                                        val icon = when (kind) {
                                            ComposerAttachmentKind.CAMERA -> Icons.Outlined.CameraAlt
                                            ComposerAttachmentKind.IMAGE -> Icons.Outlined.Image
                                            ComposerAttachmentKind.FILE -> Icons.Outlined.Description
                                            ComposerAttachmentKind.SKILLS -> Icons.Outlined.Psychology
                                            ComposerAttachmentKind.MCP_SERVERS -> Icons.Outlined.Hub
                                            ComposerAttachmentKind.PLUGINS -> Icons.Outlined.Extension
                                        }
                                        DropdownMenuItem(
                                            text = { Text(kind.label) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            },
                                            onClick = {
                                                showPlusMenu = false
                                                onAttachmentSelected(kind)
                                            },
                                            modifier = Modifier.testTag("plus_item_${kind.name.lowercase()}"),
                                        )
                                    }
                                }
                            }

                            Box {
                                Surface(
                                    onClick = { showModelMenu = true },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("agent_model_selector"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = selectedModelId ?: stringResource(R.string.agent_model_unconfigured),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = "Select model",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showModelMenu,
                                    onDismissRequest = { showModelMenu = false },
                                ) {
                                    if (availableModels.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("No models discovered (Gateway unconfigured)") },
                                            enabled = false,
                                            onClick = {},
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Open Gateway Settings") },
                                            onClick = {
                                                showModelMenu = false
                                                onOpenGatewaySettings()
                                            },
                                        )
                                    } else {
                                        availableModels.forEach { model ->
                                            val suffix = when (model.supportsToolCalling) {
                                                true -> " · tools"
                                                false -> " · no-tools"
                                                null -> ""
                                            }
                                            DropdownMenuItem(
                                                text = { Text("${model.id}$suffix") },
                                                onClick = {
                                                    showModelMenu = false
                                                    onSelectModel(model.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (isStreaming) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = onPause,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("agent_pause_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Pause,
                                        contentDescription = stringResource(R.string.agent_pause),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = onStop,
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("agent_stop_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Stop,
                                        contentDescription = stringResource(R.string.agent_stop),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = onSend,
                                enabled = text.isNotBlank(),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("agent_send_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = stringResource(R.string.agent_send),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
