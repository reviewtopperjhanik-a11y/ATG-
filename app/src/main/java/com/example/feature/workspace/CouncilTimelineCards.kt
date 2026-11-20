package com.example.feature.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.design.AgtCard
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.domain.model.AgentTimelineItem
import com.example.domain.model.CouncilAgentOpinion
import com.example.domain.model.CouncilAgentStatus
import com.example.domain.model.CouncilExecutionMode
import com.example.domain.model.CouncilReportSection
import com.example.domain.model.CouncilRole
import com.example.domain.model.CouncilRound
import com.example.domain.model.CouncilRoundDiff
import com.example.domain.model.CouncilSectionId
import com.example.domain.model.CouncilUserDecision
import com.example.domain.model.CouncilVerdict
import com.example.domain.model.ResearchDossier

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CouncilSessionCardItem(
    item: AgentTimelineItem.CouncilSessionCard,
    onEditSection: (sessionId: String, roundNumber: Int, sectionId: CouncilSectionId, newContent: String) -> Unit,
    onRevertSection: (sessionId: String, roundNumber: Int, sectionId: CouncilSectionId) -> Unit,
    onSubmitDecision: (taskId: String, decision: CouncilUserDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = item.session
    val latestRoundNumber = session?.currentRoundNumber ?: item.currentRoundNumber
    var selectedRoundNum by remember(latestRoundNumber) {
        mutableIntStateOf(latestRoundNumber)
    }
    val viewedRound: CouncilRound? = session?.roundByNumber(selectedRoundNum) ?: session?.latestRound
    val activeDossier = viewedRound?.dossier ?: item.liveDossier
    val activeOpinions = viewedRound?.opinions ?: item.liveOpinions

    var expandDossier by rememberSaveable(item.id) { mutableStateOf(false) }
    var expandOpinions by rememberSaveable(item.id) { mutableStateOf(false) }
    var amendmentNote by rememberSaveable(item.id, latestRoundNumber) { mutableStateOf("") }
    var refreshSearch by rememberSaveable(item.id, latestRoundNumber) { mutableStateOf(false) }

    val verdictTone = when {
        item.isRunning -> StatusTone.ACCENT
        viewedRound?.verdict == CouncilVerdict.GO -> StatusTone.SUCCESS
        viewedRound?.verdict == CouncilVerdict.MODIFY -> StatusTone.WARNING
        viewedRound?.verdict == CouncilVerdict.RECONSIDER -> StatusTone.ERROR
        session?.approvedForCoding == true -> StatusTone.SUCCESS
        else -> StatusTone.NEUTRAL
    }

    val verdictLabel = when {
        item.isRunning -> item.activeStage ?: "DELIBERATING"
        session?.approvedForCoding == true -> "Approved · ${viewedRound?.verdict?.name ?: "GO"}"
        viewedRound != null -> "R${viewedRound.roundNumber} · ${viewedRound.verdict.name}"
        else -> "READY"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (item.requiresDecision) 1.5.dp else 1.dp,
            color = if (item.requiresDecision) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("council_session_card_${item.taskId}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 1. Header + Verdict
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
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.council_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AgtStatusBadge(label = item.executionMode.label, tone = StatusTone.NEUTRAL)
                }
                Spacer(modifier = Modifier.width(6.dp))
                AgtStatusBadge(label = verdictLabel, tone = verdictTone)
            }

            // 2. Active Stage + Progress Bar
            if (item.isRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                if (!item.activeStageDetail.isNullOrBlank()) {
                    Text(
                        text = item.activeStageDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 3. Live Parallel Role Progress Chips
            if (item.roleStatuses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CouncilRole.entries.forEach { role ->
                        val st = item.roleStatuses[role] ?: return@forEach
                        val (sym, tone) = when (st) {
                            CouncilAgentStatus.COMPLETED -> "✓" to StatusTone.SUCCESS
                            CouncilAgentStatus.RUNNING -> "⏳" to StatusTone.ACCENT
                            CouncilAgentStatus.FAILED -> "!" to StatusTone.ERROR
                            CouncilAgentStatus.SKIPPED -> "—" to StatusTone.NEUTRAL
                            CouncilAgentStatus.PENDING -> "…" to StatusTone.NEUTRAL
                        }
                        AgtStatusBadge(
                            label = "${role.shortLabel} $sym",
                            tone = tone,
                            modifier = Modifier.testTag("council_role_chip_${role.id}"),
                        )
                    }
                }
            }

            // 4. Token, Latency & Search Quota Telemetry Strip
            if (viewedRound != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val overBudget = viewedRound.exceedsBudget(item.tokenBudget)
                val secText = String.format(java.util.Locale.US, "%.1fs", viewedRound.durationMs / 1000.0)
                val webProv = viewedRound.dossier.webProviderUsed ?: "skipped"
                val ghMode = when {
                    viewedRound.dossier.githubFindings.isEmpty() -> "skipped"
                    viewedRound.dossier.githubFallbackUsed -> "public"
                    else -> "gateway"
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AgtStatusBadge(
                        label = "${viewedRound.totalTokens}/${item.tokenBudget} tok · $secText",
                        tone = if (overBudget) StatusTone.WARNING else StatusTone.NEUTRAL,
                        modifier = Modifier.testTag("council_telemetry_tokens"),
                    )
                    AgtStatusBadge(
                        label = "Web: $webProv (${viewedRound.dossier.webFindings.size})",
                        tone = if (viewedRound.dossier.webFindings.isNotEmpty()) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                    )
                    AgtStatusBadge(
                        label = "GitHub: $ghMode (${viewedRound.dossier.githubFindings.size})",
                        tone = if (viewedRound.dossier.githubFindings.isNotEmpty()) StatusTone.SUCCESS else StatusTone.NEUTRAL,
                    )
                }
            }

            // 5. Round Switcher Pills (Round 1 / Round 2 / ...)
            if (session != null && session.rounds.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    session.rounds.forEach { round ->
                        val selected = round.roundNumber == (viewedRound?.roundNumber ?: latestRoundNumber)
                        Surface(
                            onClick = { selectedRoundNum = round.roundNumber },
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("council_round_tab_${round.roundNumber}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Round ${round.roundNumber} (${round.verdict.name})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }

            // 6. Round Diff Summary Strip
            if (item.diffFromPrevious != null && viewedRound?.roundNumber == latestRoundNumber) {
                Spacer(modifier = Modifier.height(8.dp))
                RoundDiffStrip(diff = item.diffFromPrevious)
            }

            // 7. Expandable Research Dossier
            if (activeDossier != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ExpandableRowHeader(
                    title = "Research Dossier · Web ${activeDossier.webFindings.size} / GitHub ${activeDossier.githubFindings.size}",
                    expanded = expandDossier,
                    onToggle = { expandDossier = !expandDossier },
                    tag = "council_toggle_dossier",
                )
                AnimatedVisibility(visible = expandDossier) {
                    DossierContent(dossier = activeDossier)
                }
            }

            // 8. Expandable Specialist Debate Reports
            if (activeOpinions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val completedCount = activeOpinions.count { it.status == CouncilAgentStatus.COMPLETED }
                ExpandableRowHeader(
                    title = "Specialist Debate Reports ($completedCount/${activeOpinions.size})",
                    expanded = expandOpinions,
                    onToggle = { expandOpinions = !expandOpinions },
                    tag = "council_toggle_opinions",
                )
                AnimatedVisibility(visible = expandOpinions) {
                    Column(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        activeOpinions.forEach { opinion ->
                            SpecialistOpinionRow(opinion = opinion)
                        }
                    }
                }
            }

            // 9. Six Editable Synthesized Report Sections
            if (viewedRound != null && viewedRound.sections.isNotEmpty() && session != null) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(8.dp))

                val canEditThisRound = !item.isRunning && !session.approvedForCoding &&
                    viewedRound.roundNumber == latestRoundNumber

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewedRound.sections.forEach { section ->
                        EditableReportSectionCard(
                            sessionId = session.sessionId,
                            roundNumber = viewedRound.roundNumber,
                            section = section,
                            canEdit = canEditThisRound,
                            onSaveEdit = { newText ->
                                onEditSection(session.sessionId, viewedRound.roundNumber, section.sectionId, newText)
                            },
                            onRevert = {
                                onRevertSection(session.sessionId, viewedRound.roundNumber, section.sectionId)
                            },
                        )
                    }
                }
            }

            // 10. HITL Decision Controls (Re-Council / Approve & Start Coding / Cancel)
            if (item.requiresDecision && session != null && !session.approvedForCoding && !item.isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = amendmentNote,
                    onValueChange = { amendmentNote = it },
                    label = { Text(stringResource(R.string.council_amendment_label)) },
                    placeholder = { Text(stringResource(R.string.council_amendment_placeholder)) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("council_amendment_input"),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = refreshSearch,
                            role = Role.Switch,
                            onValueChange = { refreshSearch = it },
                        )
                        .testTag("council_refresh_search_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.council_refresh_search_toggle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = refreshSearch, onCheckedChange = null)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onSubmitDecision(
                                item.taskId,
                                CouncilUserDecision.ReCouncil(
                                    userAmendmentNote = amendmentNote.trim().ifBlank { null },
                                    refreshSearch = refreshSearch,
                                ),
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("council_recouncil_button"),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.council_action_recouncil),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val isCouncilOnly = item.executionMode == CouncilExecutionMode.COUNCIL_ONLY
                    Button(
                        onClick = {
                            onSubmitDecision(
                                item.taskId,
                                if (isCouncilOnly) CouncilUserDecision.SaveCouncilOnly else CouncilUserDecision.ApproveForCoding,
                            )
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag(if (isCouncilOnly) "council_save_only_button" else "council_approve_coding_button"),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = if (isCouncilOnly) Icons.Outlined.AutoAwesome else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isCouncilOnly) R.string.council_action_save_plan else R.string.council_action_approve_coding
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { onSubmitDecision(item.taskId, CouncilUserDecision.Cancel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("council_cancel_button"),
                ) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RoundDiffStrip(diff: CouncilRoundDiff) {
    val changedNames = diff.changedSections.filter { it.changed }.joinToString(", ") { it.title }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("council_diff_strip"),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = buildString {
                    append("Round ${diff.fromRound} → Round ${diff.toRound}")
                    if (diff.verdictChanged) {
                        append(" · Verdict: ${diff.fromVerdict.name} → ${diff.toVerdict.name}")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (changedNames.isNotBlank()) {
                Text(
                    text = "Updated sections: $changedNames",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpandableRowHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.TravelExplore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
    }
}

@Composable
private fun DossierContent(dossier: ResearchDossier) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dossier.notices.isNotEmpty()) {
            Text(
                text = dossier.notices.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        dossier.githubFindings.take(4).forEach { gh ->
            AgtCard {
                Text(
                    text = "${gh.fullName} (★${gh.stars}${gh.language?.let { " · $it" }.orEmpty()})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (gh.description.isNotBlank()) {
                    Text(
                        text = gh.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        dossier.webFindings.take(4).forEach { web ->
            AgtCard {
                Text(
                    text = web.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = web.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (web.snippet.isNotBlank()) {
                    Text(
                        text = web.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecialistOpinionRow(opinion: CouncilAgentOpinion) {
    var expanded by rememberSaveable(opinion.role.id, opinion.headline) { mutableStateOf(false) }
    AgtCard(
        onClick = { expanded = !expanded },
        modifier = Modifier.testTag("council_opinion_${opinion.role.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                Text(opinion.role.displayName, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "${opinion.modelId} · ${opinion.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AgtStatusBadge(
                label = opinion.verdictTag ?: opinion.status.name,
                tone = when (opinion.status) {
                    CouncilAgentStatus.COMPLETED -> StatusTone.SUCCESS
                    CouncilAgentStatus.FAILED -> StatusTone.ERROR
                    else -> StatusTone.NEUTRAL
                },
            )
        }
        if (opinion.headline.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(opinion.headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                if (opinion.summary.isNotBlank()) {
                    Text(opinion.summary, style = MaterialTheme.typography.bodySmall)
                }
                opinion.bulletPoints.forEach { pt ->
                    Text(
                        text = "• $pt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!opinion.errorMessage.isNullOrBlank()) {
                    Text(
                        text = opinion.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableReportSectionCard(
    sessionId: String,
    roundNumber: Int,
    section: CouncilReportSection,
    canEdit: Boolean,
    onSaveEdit: (String) -> Unit,
    onRevert: () -> Unit,
) {
    var isEditing by rememberSaveable(sessionId, roundNumber, section.sectionId.id) { mutableStateOf(false) }
    var draft by rememberSaveable(sessionId, roundNumber, section.sectionId.id, section.effectiveContent) {
        mutableStateOf(section.effectiveContent)
    }

    AgtCard(modifier = Modifier.testTag("council_section_${section.sectionId.id}")) {
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
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (section.isModifiedByUser) {
                    AgtStatusBadge(label = "Edited", tone = StatusTone.ACCENT)
                }
            }
            if (canEdit && !isEditing) {
                Row {
                    if (section.isModifiedByUser) {
                        TextButton(
                            onClick = onRevert,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("council_section_revert_${section.sectionId.id}"),
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(stringResource(R.string.council_action_revert))
                        }
                    }
                    TextButton(
                        onClick = {
                            draft = section.effectiveContent
                            isEditing = true
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("council_section_edit_${section.sectionId.id}"),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.council_action_edit))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (isEditing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("council_section_input_${section.sectionId.id}"),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        draft = section.effectiveContent
                        isEditing = false
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSaveEdit(draft)
                        isEditing = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("council_section_save_${section.sectionId.id}"),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(stringResource(R.string.council_action_save_edit))
                }
            }
        } else {
            Text(
                text = section.effectiveContent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
