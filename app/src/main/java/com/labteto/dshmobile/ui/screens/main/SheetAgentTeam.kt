package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.team.AGENT_TEAM_PROJECTION
import com.labteto.dshmobile.core.team.AgentTeam
import com.labteto.dshmobile.core.team.TeamMemberRow
import com.labteto.dshmobile.core.team.TeamMemberStatus
import com.labteto.dshmobile.core.team.TeamRole
import com.labteto.dshmobile.core.team.TeamTask
import com.labteto.dshmobile.core.team.TeamTaskGroup
import com.labteto.dshmobile.core.team.groupTeamTasks
import com.labteto.dshmobile.core.team.parseAgentTeam
import com.labteto.dshmobile.core.team.teamMemberRows
import com.labteto.dshmobile.core.team.teamTaskBlockers
import com.labteto.dshmobile.core.team.teamTaskGroup
import com.labteto.dshmobile.core.team.teamTaskSectionStartsCollapsed
import com.labteto.dshmobile.core.team.teamTaskWaitingOn
import com.labteto.dshmobile.core.team.teamWriteOverlapTaskId
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/*
 * Agent Teams (experimental), the phone version of the official web panel
 * (`packages/experimental/client-ui-agent-team`, harness 0.2.1-rc.2).
 *
 * The harness's whole client protocol for teams is the Lead session's `agentTeam` projection; there
 * is no Team RPC, and the web panel is read-only too. So this is an observer: the roster, the shared
 * task board, and a way into each teammate's conversation. A teammate is a continuable child of the
 * Lead, listed in its `subagentCatalog`, so its conversation opens in the Subagents sheet exactly as
 * any other child does, follow-up composer and Stop included.
 */

/**
 * The open session's team, or null when there is none to show: the host has no Agent Teams (no
 * `agentTeam` key), or the open session is itself a child. A teammate's own projection describes a
 * team it leads, which is empty and would be misleading; the web panel reads the Lead's instead,
 * which this app does not hold while a child is open.
 */
@Composable
internal fun rememberAgentTeam(conversation: ConversationSnapshot?, isChild: Boolean): AgentTeam? {
    val raw = conversation?.projections?.get(AGENT_TEAM_PROJECTION)
    return remember(raw, isChild) { if (isChild) null else parseAgentTeam(raw) }
}

/** Roster rows for the Lead [leadId], with activity and job descriptions from its subagent catalog. */
@Composable
internal fun rememberTeamRows(
    team: AgentTeam,
    leadId: String?,
    leadRunning: Boolean,
    subagents: List<SubagentListEntry>,
): List<TeamMemberRow> = remember(team, leadId, leadRunning, subagents) {
    val children = subagents.filterIsInstance<SubagentListEntry.ChildContinuable>()
    val running = children.filter { subagentRunning(it) }.map { it.id }.toSet()
    teamMemberRows(
        team = team,
        currentSessionId = leadId,
        running = { id -> if (id == leadId) leadRunning else id in running },
        continuableLabels = children.associate { it.id to it.label },
    )
}

/** The title-row chip that opens the team sheet; the same 28dp-in-48dp pill as the subagent count. */
@Composable
internal fun AgentTeamTag(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val description = stringResource(R.string.team_title)
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(DsSizes.chipCompact)
            .clip(DsShapes.pillFull)
            .background(colors.accentTertiary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = DsSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Diversity3, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
        Text(
            stringResource(R.string.team_chip),
            style = DsType.small13Strong,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val TAB_MEMBERS = "members"
private const val TAB_TASKS = "tasks"

/**
 * The team sheet: members and the shared task board, one at a time under a segmented control.
 *
 * @param onOpenTeammate opens a teammate's conversation; the caller hands it to the Subagents sheet.
 */
@Composable
internal fun AgentTeamSheet(
    team: AgentTeam,
    rows: List<TeamMemberRow>,
    onOpenTeammate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberDsHaptics()
    // Open on the board when there is one: on a phone it is the part that changes.
    var tab by rememberSaveable { mutableStateOf(if (team.tasks.isNotEmpty()) TAB_TASKS else TAB_MEMBERS) }
    DsBottomSheet(
        title = stringResource(R.string.team_title),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
        trailing = { DsPill(text = stringResource(R.string.team_beta_badge), selected = true) },
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            team.failure?.let { TeamFailureNote(it) }
            DsSegmented(
                segments = listOf(
                    DsSegment(TAB_MEMBERS, stringResource(R.string.team_tab_members, team.members.size)),
                    DsSegment(TAB_TASKS, stringResource(R.string.team_tab_tasks, team.tasks.size)),
                ),
                selectedKey = tab,
                onSelect = { tab = it },
                role = Role.Tab,
                stretch = true,
                maxLabelLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            when (tab) {
                TAB_MEMBERS -> MembersTab(team, rows, onOpenTeammate)
                else -> TasksTab(team, rows, onOpenTeammate)
            }
            Text(
                stringResource(R.string.team_read_only_note),
                style = DsType.caption11,
                color = DsTheme.colors.labelTertiary,
                modifier = Modifier.padding(top = DsSpacing.tiny),
            )
        }
    }
}

/** A rejected persisted Team record: the host keeps showing the last valid roster and tasks. */
@Composable
private fun TeamFailureNote(message: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.errorTertiary)
            .padding(DsSpacing.medium),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.team_failure), style = DsType.small13, color = colors.labelPrimary)
            Text(technicalDisplay(message), style = DsType.caption11, color = colors.labelSecondary)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------------------------

@Composable
private fun MembersTab(team: AgentTeam, rows: List<TeamMemberRow>, onOpenTeammate: (String) -> Unit) {
    val haptics = rememberDsHaptics()
    rows.forEach { row ->
        MemberCard(row) {
            haptics.selection()
            onOpenTeammate(row.member.id)
        }
    }
    if (team.teammates.isEmpty()) {
        EmptyHero(
            headline = stringResource(R.string.team_members_empty_title),
            subtitle = stringResource(R.string.team_members_empty_body),
        )
    }
}

/** The state of a member in words, and an icon that does not depend on colour to read. */
@Composable
private fun MemberStatusIcon(status: TeamMemberStatus) {
    val colors = DsTheme.colors
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            TeamMemberStatus.RUNNING -> StateDot(StateDotState.Running, size = 12.dp)
            TeamMemberStatus.INACTIVE -> Icon(Icons.Outlined.Person, null, tint = colors.labelTertiary, modifier = Modifier.size(20.dp))
            TeamMemberStatus.PROVISIONING -> Icon(Icons.Outlined.HourglassEmpty, null, tint = colors.labelSecondary, modifier = Modifier.size(20.dp))
            TeamMemberStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun memberStatusLabel(status: TeamMemberStatus): String = stringResource(
    when (status) {
        TeamMemberStatus.RUNNING -> R.string.team_member_running
        TeamMemberStatus.INACTIVE -> R.string.team_member_idle
        TeamMemberStatus.PROVISIONING -> R.string.team_member_starting
        TeamMemberStatus.FAILED -> R.string.team_member_failed
    },
)

/** The Lead's roster name is always `lead`; it reads better in the reader's language. */
@Composable
private fun memberName(row: TeamMemberRow): String =
    if (row.member.role == TeamRole.LEAD) stringResource(R.string.team_lead) else technicalDisplay(row.member.name)

@Composable
private fun MemberCard(row: TeamMemberRow, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val openLabel = stringResource(R.string.team_open_chat)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgModulePlatform,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.openable, role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                .heightIn(min = 64.dp)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            MemberStatusIcon(row.status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Text(
                        memberName(row),
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.isCurrent) DsPill(text = stringResource(R.string.team_this_chat))
                }
                val status = memberStatusLabel(row.status)
                Text(
                    if (row.openTasks > 0) stringResource(R.string.team_member_status_tasks, status, row.openTasks) else status,
                    style = DsType.small13,
                    color = if (row.status == TeamMemberStatus.RUNNING) colors.accent else colors.labelTertiary,
                )
                row.description?.let {
                    Text(it, style = DsType.small13, color = colors.labelSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                row.member.error?.let {
                    Text(technicalDisplay(it), style = DsType.caption11, color = colors.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.openable) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------------------------

@Composable
private fun TasksTab(team: AgentTeam, rows: List<TeamMemberRow>, onOpenTeammate: (String) -> Unit) {
    if (team.tasks.isEmpty()) {
        EmptyHero(
            headline = stringResource(R.string.team_tasks_empty_title),
            subtitle = stringResource(R.string.team_tasks_empty_body),
        )
        return
    }
    // Which sections the reader folded, and which task is open; both by key so a live update that
    // moves a task to another section keeps what the reader chose.
    val collapsed = remember { mutableStateMapOf<TeamTaskGroup, Boolean>() }
    var openTask by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = rememberDsHaptics()
    groupTeamTasks(team.tasks).forEach { section ->
        val isCollapsed = collapsed[section.group] ?: teamTaskSectionStartsCollapsed(section.group)
        TaskSectionHeader(section.group, section.tasks.size, expanded = !isCollapsed) {
            haptics.selection()
            collapsed[section.group] = !isCollapsed
        }
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
            exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                section.tasks.forEach { task ->
                    TaskCard(
                        task = task,
                        team = team,
                        rows = rows,
                        expanded = openTask == task.id,
                        onToggle = {
                            haptics.selection()
                            openTask = if (openTask == task.id) null else task.id
                        },
                        onOpenTeammate = onOpenTeammate,
                    )
                }
            }
        }
    }
}

@Composable
private fun taskGroupLabel(group: TeamTaskGroup): String = stringResource(
    when (group) {
        TeamTaskGroup.IN_PROGRESS -> R.string.team_group_in_progress
        TeamTaskGroup.READY -> R.string.team_group_ready
        TeamTaskGroup.BLOCKED -> R.string.team_group_waiting
        TeamTaskGroup.OTHER -> R.string.team_group_other
        TeamTaskGroup.COMPLETED -> R.string.team_group_done
    },
)

/** A task's state as an icon; the section header and the card's state line say it in words. */
@Composable
private fun TaskStateIcon(group: TeamTaskGroup, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val colors = DsTheme.colors
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (group) {
            TeamTaskGroup.IN_PROGRESS -> StateDot(StateDotState.Running, size = 12.dp)
            else -> {
                val (icon, tint) = taskIcon(group, colors.labelTertiary, colors.warnLabel, colors.success)
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
            }
        }
    }
}

private fun taskIcon(group: TeamTaskGroup, idle: Color, warn: Color, done: Color): Pair<ImageVector, Color> = when (group) {
    TeamTaskGroup.READY -> Icons.Outlined.RadioButtonUnchecked to idle
    TeamTaskGroup.BLOCKED -> Icons.Outlined.HourglassEmpty to warn
    TeamTaskGroup.COMPLETED -> Icons.Outlined.CheckCircle to done
    TeamTaskGroup.OTHER, TeamTaskGroup.IN_PROGRESS -> Icons.AutoMirrored.Outlined.HelpOutline to idle
}

@Composable
private fun TaskSectionHeader(group: TeamTaskGroup, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = DsTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, DsAnimations.chevron, label = "teamSectionChevron")
    val stateWord = stringResource(if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(DsShapes.control)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = stateWord
            }
            .padding(horizontal = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        TaskStateIcon(group, size = 18.dp)
        Text(
            taskGroupLabel(group),
            style = DsType.small13Strong,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        DsPill(text = count.toString())
        Box(Modifier.weight(1f))
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun TaskCard(
    task: TeamTask,
    team: AgentTeam,
    rows: List<TeamMemberRow>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenTeammate: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val group = teamTaskGroup(task)
    val owner = task.ownerName?.let { name -> rows.firstOrNull { it.member.name == name } }
    val ownerLabel = owner?.let { memberName(it) } ?: task.ownerName?.let { technicalDisplay(it) }
    val waitingOn = teamTaskWaitingOn(task, team.tasks)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, DsAnimations.chevron, label = "teamTaskChevron")
    val stateWord = stringResource(if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgModulePlatform,
        border = BorderStroke(1.dp, if (expanded) colors.accent else colors.borderL1),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = stateWord }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                TaskStateIcon(group)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        task.subject.ifBlank { technicalDisplay(task.id) },
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.team_task_state_line,
                            taskGroupLabel(group),
                            ownerLabel ?: stringResource(R.string.team_task_unassigned),
                        ),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                    if (waitingOn.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.team_task_waiting_on,
                                waitingOn.joinToString(", ") { it.subject ?: technicalDisplay(it.id) },
                            ),
                            style = DsType.small13,
                            color = colors.warnLabel,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                TaskDetail(task, team, owner, onOpenTeammate)
            }
        }
    }
}

/** What the board knows about one task beyond its title: the brief, dependencies, files and owner. */
@Composable
private fun TaskDetail(task: TeamTask, team: AgentTeam, owner: TeamMemberRow?, onOpenTeammate: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.medium, end = DsSpacing.medium, bottom = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            task.description.ifBlank { stringResource(R.string.team_task_no_details) },
            style = DsType.std14,
            color = if (task.description.isBlank()) colors.labelTertiary else colors.labelPrimary,
        )
        val blockers = teamTaskBlockers(task, team.tasks)
        if (blockers.isNotEmpty()) {
            DetailLabel(stringResource(R.string.team_task_depends_on))
            blockers.forEach { blocker ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    val (icon, tint) = taskIcon(
                        if (blocker.done) TeamTaskGroup.COMPLETED else TeamTaskGroup.BLOCKED,
                        colors.labelTertiary, colors.warnLabel, colors.success,
                    )
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(
                            if (blocker.done) R.string.team_task_dependency_done else R.string.team_task_dependency_open,
                            blocker.subject ?: technicalDisplay(blocker.id),
                        ),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                }
            }
        }
        if (task.writeScopes.isNotEmpty()) {
            DetailLabel(stringResource(R.string.team_task_files))
            Text(
                task.writeScopes.joinToString("\n") { technicalDisplay(it) },
                style = DsType.small13,
                color = colors.labelSecondary,
            )
        }
        task.writeScopeWarnings.forEach { warning ->
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.size(16.dp))
                val other = teamWriteOverlapTaskId(warning)
                val otherLabel = other?.let { id -> team.tasks.firstOrNull { it.id == id }?.subject?.takeIf { it.isNotBlank() } ?: technicalDisplay(id) }
                Text(
                    if (otherLabel != null) stringResource(R.string.team_task_overlap, otherLabel) else technicalDisplay(warning),
                    style = DsType.small13,
                    color = colors.warnLabel,
                )
            }
        }
        Text(
            technicalDisplay(task.id),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        when {
            owner == null -> Unit
            owner.isCurrent -> Text(
                stringResource(R.string.team_task_in_this_chat),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            owner.openable -> DsButton(
                text = stringResource(R.string.team_task_open_owner, memberName(owner)),
                onClick = {
                    haptics.tap()
                    onOpenTeammate(owner.member.id)
                },
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
            else -> Unit
        }
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(
        text,
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(top = DsSpacing.tiny),
    )
}
