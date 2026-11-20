package com.example.feature.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timeline
import com.example.agent.orchestrator.RepoMapEngine
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.AgentBackendId
import com.example.domain.model.FlightRecorderEvent
import com.example.domain.model.FlightRecorderEventType
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.FlightRecorderOutcome
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.KnowledgeRetrievalHit
import com.example.domain.model.ProjectKnowledgeEntry
import com.example.domain.model.ProjectMemory
import com.example.domain.model.TaskState
import com.example.domain.model.TaskStateSnapshot
import com.example.ui.theme.CodeTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FlightRecorderDialog(
    events: List<FlightRecorderEvent>,
    totalEventCount: Int,
    failureCount: Int,
    selectedFilter: FlightRecorderFilter,
    selectedTaskId: String?,
    taskSnapshots: List<TaskStateSnapshot>,
    latestSnapshot: TaskStateSnapshot?,
    projectMemory: ProjectMemory?,
    onSelectFilter: (FlightRecorderFilter) -> Unit,
    onSelectTask: (String?) -> Unit,
    onInspectCheckpoint: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefreshRepoMap: () -> Unit = {},
    availableModels: List<GatewayModelInfo> = emptyList(),
    activeModelId: String? = null,
    activeBackendId: AgentBackendId = AgentBackendId.OPENCODE,
    onResumeTask: (taskId: String, modelId: String, backendId: String, note: String?) -> Unit = { _, _, _, _ -> },
    knowledgeEntries: List<ProjectKnowledgeEntry> = emptyList(),
    knowledgeSearchQuery: String = "",
    knowledgeSearchResults: List<KnowledgeRetrievalHit> = emptyList(),
    knowledgeIncludeSuperseded: Boolean = false,
    knowledgeUsedFts5: Boolean = false,
    onKnowledgeSearchChanged: (String) -> Unit = {},
    onToggleKnowledgeSuperseded: (Boolean) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Timeline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.flight_recorder_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "$totalEventCount recorded · $failureCount failure(s) or block(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (failureCount > 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("flight_recorder_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_close),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                // Task Selector (when multiple snapshots exist)
                if (taskSnapshots.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = selectedTaskId == null,
                            onClick = { onSelectTask(null) },
                            label = { Text("All Tasks (${taskSnapshots.size})") },
                            modifier = Modifier.testTag("flight_task_all"),
                        )
                        taskSnapshots.take(6).forEach { snap ->
                            FilterChip(
                                selected = selectedTaskId == snap.taskId,
                                onClick = { onSelectTask(snap.taskId) },
                                label = {
                                    Text(
                                        text = snap.objective.take(26) + if (snap.objective.length > 26) "…" else "",
                                        maxLines = 1,
                                    )
                                },
                                modifier = Modifier.testTag("flight_task_${snap.taskId}"),
                            )
                        }
                    }
                }

                // Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlightRecorderFilter.entries.forEach { filter ->
                        val label = when (filter) {
                            FlightRecorderFilter.FAILURES -> "${filter.label} ($failureCount)"
                            else -> filter.label
                        }
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("flight_filter_${filter.name.lowercase()}"),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("flight_recorder_event_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (latestSnapshot != null || projectMemory != null) {
                        item(key = "resume_cursor_header") {
                            ResumeCursorAndMemoryCard(
                                snapshot = latestSnapshot,
                                memory = projectMemory,
                                onInspectCheckpoint = onInspectCheckpoint,
                                onRefreshRepoMap = onRefreshRepoMap,
                                availableModels = availableModels,
                                activeModelId = activeModelId,
                                activeBackendId = activeBackendId,
                                onResumeTask = onResumeTask,
                            )
                        }
                    }

                    if (knowledgeEntries.isNotEmpty() || knowledgeSearchQuery.isNotBlank()) {
                        item(key = "long_term_knowledge_card") {
                            LongTermKnowledgeCard(
                                entries = knowledgeEntries,
                                searchQuery = knowledgeSearchQuery,
                                searchResults = knowledgeSearchResults,
                                includeSuperseded = knowledgeIncludeSuperseded,
                                usedFts5 = knowledgeUsedFts5,
                                onSearchChanged = onKnowledgeSearchChanged,
                                onToggleSuperseded = onToggleKnowledgeSuperseded,
                            )
                        }
                    }

                    if (events.isEmpty()) {
                        item(key = "flight_empty") {
                            AgtEmptyState(
                                title = stringResource(R.string.flight_recorder_empty_title),
                                description = stringResource(R.string.flight_recorder_empty_desc),
                                icon = Icons.Outlined.Timeline,
                            )
                        }
                    } else {
                        items(events, key = { it.id }) { event ->
                            FlightRecorderEventCard(
                                event = event,
                                onInspectCheckpoint = onInspectCheckpoint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumeCursorAndMemoryCard(
    snapshot: TaskStateSnapshot?,
    memory: ProjectMemory?,
    onInspectCheckpoint: (String) -> Unit,
    onRefreshRepoMap: () -> Unit = {},
    availableModels: List<GatewayModelInfo> = emptyList(),
    activeModelId: String? = null,
    activeBackendId: AgentBackendId = AgentBackendId.OPENCODE,
    onResumeTask: (taskId: String, modelId: String, backendId: String, note: String?) -> Unit = { _, _, _, _ -> },
) {
    var showRepoMap by remember(memory?.repoMapUpdatedAtEpochMs) { mutableStateOf(false) }
    AgtCard(modifier = Modifier.testTag("flight_recorder_resume_card")) {
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
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Durable Resume Point & Memory",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (snapshot != null) {
                val tone = when (snapshot.currentState) {
                    TaskState.COMPLETED -> StatusTone.SUCCESS
                    TaskState.FAILED -> StatusTone.ERROR
                    TaskState.PAUSED,
                    TaskState.AWAITING_TOOL_APPROVAL,
                    TaskState.AWAITING_PLAN_APPROVAL,
                    TaskState.AWAITING_COUNCIL_APPROVAL -> StatusTone.WARNING
                    else -> StatusTone.ACCENT
                }
                AgtStatusBadge(label = snapshot.currentState.name, tone = tone)
            }
        }

        if (snapshot != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = snapshot.objective,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val totalSteps = snapshot.planSteps.size.coerceAtLeast(1)
            val backendLabel = snapshot.lastBackendId ?: "opencode"
            val modelLabel = snapshot.lastModelId ?: "unset"
            Text(
                text = "Cursor: Step ${snapshot.activeStepIndex + 1}/$totalSteps · Seq #${snapshot.resumeCursor.lastEventSeq} · $backendLabel · $modelLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.modifiedFiles.isNotEmpty()) {
                Text(
                    text = "Modified (${snapshot.modifiedFiles.size}): ${snapshot.modifiedFiles.take(4).joinToString(", ")}",
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            snapshot.resumeCursor.lastError?.let { err ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last error: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            snapshot.resumeCursor.checkpointId?.let { chkId ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Checkpoint: ${chkId.take(16)}",
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onInspectCheckpoint(chkId) },
                        modifier = Modifier.testTag("flight_recorder_checkpoint_btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = "Inspect checkpoint diff or restore",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Diff / Restore")
                    }
                }
            }
            if (snapshot.currentState != TaskState.COMPLETED) {
                var targetBackend by remember(snapshot.taskId, activeBackendId) {
                    mutableStateOf(
                        snapshot.lastBackendId?.let { AgentBackendId.fromId(it) } ?: activeBackendId
                    )
                }
                var targetModel by remember(snapshot.taskId, activeModelId, availableModels) {
                    mutableStateOf(
                        activeModelId?.takeIf { it.isNotBlank() }
                            ?: snapshot.lastModelId?.takeIf { it.isNotBlank() }
                            ?: availableModels.firstOrNull()?.id.orEmpty()
                    )
                }
                var resumeNote by remember(snapshot.taskId) { mutableStateOf("") }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.testTag("flight_recorder_handoff_section")) {
                    Text(
                        text = stringResource(R.string.flight_handoff_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentBackendId.entries.forEach { backend ->
                            FilterChip(
                                selected = targetBackend == backend,
                                onClick = { targetBackend = backend },
                                label = { Text(backend.shortLabel) },
                                modifier = Modifier.testTag("flight_handoff_backend_${backend.id}"),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (availableModels.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_model_unconfigured),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("flight_handoff_model_selector"),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("flight_handoff_model_selector"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            availableModels.forEach { model ->
                                FilterChip(
                                    selected = targetModel == model.id,
                                    onClick = { targetModel = model.id },
                                    label = {
                                        Text(
                                            text = model.id,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.testTag("flight_handoff_model_option_${model.id}"),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = resumeNote,
                        onValueChange = { resumeNote = it },
                        placeholder = { Text(stringResource(R.string.flight_handoff_note_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("flight_handoff_note_input"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onResumeTask(
                                snapshot.taskId,
                                targetModel,
                                targetBackend.id,
                                resumeNote.trim().takeIf { it.isNotEmpty() },
                            )
                        },
                        enabled = targetModel.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("flight_recorder_resume_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.flight_handoff_resume_action, targetBackend.shortLabel))
                    }
                }
            }
        }

        if (memory != null) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Project Memory · ${memory.stackLabel} · ${memory.validationCommands.size} check(s) · ${memory.knownFixes.size} known fix(es)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val repoTokens = RepoMapEngine.estimateTokens(memory.repoMapCache)
                val staleSuffix = if (memory.repoMapUpdatedAtEpochMs == 0L && memory.repoMapCache.isNotBlank()) " · stale" else ""
                val repoSummary = if (memory.repoMapCache.isNotBlank()) {
                    "Repo Map (~$repoTokens tokens$staleSuffix)"
                } else {
                    "Repo Map (not built yet)"
                }
                TextButton(
                    onClick = { showRepoMap = !showRepoMap },
                    enabled = memory.repoMapCache.isNotBlank(),
                    modifier = Modifier.testTag("flight_recorder_repomap_toggle"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (showRepoMap) "Hide $repoSummary" else "View $repoSummary")
                }
                IconButton(
                    onClick = onRefreshRepoMap,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("flight_recorder_repomap_refresh"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh Repo Map",
                    )
                }
            }
            AnimatedVisibility(visible = showRepoMap && memory.repoMapCache.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = memory.repoMapCache,
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(10.dp)
                            .testTag("flight_recorder_repomap_content"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlightRecorderEventCard(
    event: FlightRecorderEvent,
    onInspectCheckpoint: (String) -> Unit,
) {
    var expanded by remember(event.id) { mutableStateOf(event.isFailureOrBlock) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(event.timestampMs) { timeFormat.format(Date(event.timestampMs)) }

    val outcomeTone = when (event.outcome) {
        FlightRecorderOutcome.PASSED, FlightRecorderOutcome.ALLOWED -> StatusTone.SUCCESS
        FlightRecorderOutcome.FAILED, FlightRecorderOutcome.TIMED_OUT, FlightRecorderOutcome.REJECTED -> StatusTone.ERROR
        FlightRecorderOutcome.REQUIRES_APPROVAL -> StatusTone.WARNING
        FlightRecorderOutcome.INFO -> StatusTone.ACCENT
        FlightRecorderOutcome.CANCELLED, FlightRecorderOutcome.SKIPPED -> StatusTone.NEUTRAL
    }

    AgtCard(
        onClick = { if (event.outputExcerpt.isNotBlank()) expanded = !expanded },
        modifier = Modifier.testTag("flight_event_${event.id}"),
    ) {
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
                Text(
                    text = "#${event.seq}",
                    style = CodeTextStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = event.eventType.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                event.toolName?.let { tName ->
                    Text(
                        text = "· $tName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AgtStatusBadge(
                label = if (event.exitCode != null) "${event.outcome.label} (${event.exitCode})" else event.outcome.label,
                tone = outcomeTone,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val meta = buildList {
                add(formattedTime)
                event.stepIndex?.let { add("step ${it + 1}") }
                event.backendId?.let { add(it) }
                event.modelId?.let { add(it) }
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        event.commandOrPath?.takeIf { it.isNotBlank() }?.let { cmd ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = cmd,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(visible = expanded && event.outputExcerpt.isNotBlank()) {
            Column {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(
                        1.dp,
                        if (event.isFailureOrBlock) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = event.outputExcerpt,
                        style = CodeTextStyle,
                        color = if (event.isFailureOrBlock) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        if (event.eventType == FlightRecorderEventType.CHECKPOINT && event.checkpointId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = { onInspectCheckpoint(event.checkpointId) },
                modifier = Modifier.testTag("flight_event_chk_${event.checkpointId}"),
            ) {
                Text("Inspect Checkpoint Diff")
            }
        }
    }
}

@Composable
private fun LongTermKnowledgeCard(
    entries: List<ProjectKnowledgeEntry>,
    searchQuery: String,
    searchResults: List<KnowledgeRetrievalHit>,
    includeSuperseded: Boolean,
    usedFts5: Boolean,
    onSearchChanged: (String) -> Unit,
    onToggleSuperseded: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeCount = remember(entries) { entries.count { it.isCurrentlyValid } }
    val supersededCount = remember(entries) { entries.count { !it.isCurrentlyValid } }
    val displayedEntries = remember(entries, searchQuery, searchResults, includeSuperseded) {
        if (searchQuery.isNotBlank()) {
            searchResults.map { it.entry }
        } else {
            entries.filter { includeSuperseded || it.isCurrentlyValid }.take(12)
        }
    }

    AgtCard(modifier = Modifier.testTag("flight_recorder_knowledge_card")) {
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
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.flight_knowledge_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "$activeCount active · $supersededCount superseded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("flight_recorder_knowledge_toggle"),
            ) {
                Text(if (expanded) "Hide" else "Browse")
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChanged,
                    placeholder = { Text(stringResource(R.string.flight_knowledge_search_hint)) },
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
                        .testTag("flight_recorder_knowledge_search"),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = includeSuperseded,
                        onClick = { onToggleSuperseded(!includeSuperseded) },
                        label = { Text(stringResource(R.string.flight_knowledge_include_superseded)) },
                        modifier = Modifier.testTag("flight_recorder_knowledge_include_superseded"),
                    )
                    if (searchQuery.isNotBlank()) {
                        AgtStatusBadge(
                            label = if (usedFts5) "FTS5" else "Token Match",
                            tone = if (usedFts5) StatusTone.ACCENT else StatusTone.NEUTRAL,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (displayedEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.flight_knowledge_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        displayedEntries.forEach { entry ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.background,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("flight_recorder_knowledge_entry_${entry.id}"),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = entry.category.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        AgtStatusBadge(
                                            label = if (entry.isCurrentlyValid) "Active" else "Superseded",
                                            tone = if (entry.isCurrentlyValid) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = entry.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "key: ${entry.factKey} · conf ${(entry.confidence * 100).toInt()}%",
                                        style = CodeTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
