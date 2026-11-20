package com.example.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.design.AgtCard
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.AgentTimelineItem
import com.example.domain.model.PlanStepStatus
import com.example.domain.model.TaskState
import com.example.domain.model.ValidationOutcome
import com.example.ui.theme.CodeTextStyle

@Composable
fun PlanCardItem(
    item: AgentTimelineItem.PlanCard,
    onApprovePlan: () -> Unit,
    onDenyPlan: () -> Unit,
) {
    AgtCard(modifier = Modifier.testTag("plan_card_${item.plan.taskId}")) {
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
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text("Execution Plan", style = MaterialTheme.typography.titleSmall)
            }
            if (item.requiresApproval) {
                AgtStatusBadge(
                    label = when {
                        !item.approvalResolved -> "Plan Approval"
                        item.approved == true -> "Approved"
                        else -> "Declined"
                    },
                    tone = when {
                        !item.approvalResolved -> StatusTone.WARNING
                        item.approved == true -> StatusTone.SUCCESS
                        else -> StatusTone.ERROR
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        item.plan.steps.forEach { step ->
            val (icon, tint) = when (step.status) {
                PlanStepStatus.COMPLETED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                PlanStepStatus.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                PlanStepStatus.IN_PROGRESS -> Icons.Outlined.HourglassEmpty to MaterialTheme.colorScheme.primary
                else -> Icons.Outlined.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = step.status.name,
                    tint = tint,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${step.index + 1}. ${step.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (item.plan.acceptanceCriteria.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Acceptance: ${item.plan.acceptanceCriteria.joinToString(" · ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.requiresApproval && !item.approvalResolved) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDenyPlan,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("plan_deny_${item.plan.taskId}"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Decline")
                }
                Button(
                    onClick = onApprovePlan,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("plan_approve_${item.plan.taskId}"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("Approve Plan")
                }
            }
        }
    }
}

@Composable
fun CheckpointCardItem(
    item: AgentTimelineItem.CheckpointCard,
    onPreviewDiff: () -> Unit,
) {
    AgtCard(modifier = Modifier.testTag("checkpoint_card_${item.checkpointId}")) {
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
                    imageVector = Icons.Outlined.BookmarkAdded,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.reason,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.fileCount} file(s) snapshotted · ${item.checkpointId.take(16)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.restored) {
                AgtStatusBadge(label = "Restored", tone = StatusTone.SUCCESS)
            } else {
                TextButton(
                    onClick = onPreviewDiff,
                    modifier = Modifier.testTag("checkpoint_diff_btn_${item.checkpointId}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = "Inspect diff or restore checkpoint",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Diff / Restore")
                }
            }
        }
    }
}

@Composable
fun ValidationCardItem(item: AgentTimelineItem.ValidationCard) {
    val res = item.result
    val tone = when (res.status) {
        ValidationOutcome.PASSED -> StatusTone.SUCCESS
        ValidationOutcome.FAILED, ValidationOutcome.TIMED_OUT -> StatusTone.ERROR
        ValidationOutcome.CANCELLED, ValidationOutcome.SKIPPED -> StatusTone.NEUTRAL
    }
    AgtCard(modifier = Modifier.testTag("validation_card_${res.commandId}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(res.label, style = MaterialTheme.typography.titleSmall)
            AgtStatusBadge(
                label = if (res.exitCode != null) "${res.status.label} (${res.exitCode})" else res.status.label,
                tone = tone,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$ ${res.command} · ${res.durationMs}ms",
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (res.outputExcerpt.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = res.outputExcerpt,
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun TaskSummaryCardItem(
    item: AgentTimelineItem.TaskSummaryCard,
    onInspectCheckpoint: (String) -> Unit,
) {
    val s = item.summary
    val tone = when (s.finalState) {
        TaskState.COMPLETED -> StatusTone.SUCCESS
        TaskState.FAILED -> StatusTone.ERROR
        TaskState.CANCELLED -> StatusTone.NEUTRAL
        else -> StatusTone.ACCENT
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_summary_card_${s.taskId}"),
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
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Task Summary", style = MaterialTheme.typography.titleSmall)
                }
                AgtStatusBadge(label = s.finalState.name, tone = tone)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Files changed (${s.filesChanged.size}): ${
                    if (s.filesChanged.isEmpty()) "None" else s.filesChanged.take(6).joinToString(", ")
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val checksText = if (s.checksExecuted.isEmpty()) {
                "No automated build/test checks executed"
            } else {
                s.checksExecuted.joinToString(" · ") { "${it.label}: ${it.status.label}" }
            }
            Text(
                text = "Checks: $checksText (auto-debug passes: ${s.fixAttempts})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = s.gitStatusNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.unresolvedIssues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Unresolved: ${s.unresolvedIssues.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (s.checkpointId != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { onInspectCheckpoint(s.checkpointId) },
                    modifier = Modifier.testTag("summary_diff_btn_${s.taskId}"),
                ) {
                    Text("Inspect Checkpoint Diff")
                }
            }
        }
    }
}
