package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.data.ActionOutcome
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.holdable
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The strip of persistent context between the transcript and the composer: to-dos, the ongoing
 * goal, the queue, and the run statistics.
 *
 * The Ink redesign turns these into one compact bar each — a leading glyph, a one-line fact, a
 * count or meter on the right — that expands in place when tapped. The old disclosure rows spent a
 * full transcript row on a chevron and a title; these cost 40dp and keep the numbers visible
 * without opening anything, which is what a reader watching a long turn actually wants.
 */

// ---------------------------------------------------------------------------
// Shared compact bar
// ---------------------------------------------------------------------------

/**
 * The one dock shape: a rounded 40dp bar with a leading glyph, a title, a right-hand count/meter,
 * and a disclosure chevron. [fraction] draws the thin meter the mock puts on the goal bar.
 */
@Composable
private fun DockBar(
    icon: ImageVector,
    title: String,
    trailing: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "dockChevron",
    )
    val tint = if (accent) colors.accent else colors.labelSecondary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.control,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSizes.button)
                    .clickable(role = Role.Button) {
                        haptics.tap()
                        onToggle()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(
                    title,
                    style = DsType.dockTitle,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Text(
                        trailing,
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                }
                if (fraction != null) {
                    Box(
                        Modifier
                            .width(48.dp)
                            .height(4.dp)
                            .clip(DsShapes.pillFull)
                            .background(colors.borderL2),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .height(4.dp)
                                .clip(DsShapes.pillFull)
                                .background(colors.accent),
                        )
                    }
                }
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { content() }
            }
        }
    }
}

/** A compact section label inside a dock's expanded body. */
@Composable
private fun DockLabel(text: String) {
    Text(
        text.uppercase(),
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// To-dos
// ---------------------------------------------------------------------------

@Composable
internal fun TodoDock(todos: List<TodoEntry>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    // Not keyed on the list: every todo update used to fold an open dock shut under the reader.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completed = todos.count { it.status == "completed" }
    DockBar(
        icon = FeatherIcons.CheckSquare,
        title = stringResource(R.string.chat_todo_title),
        trailing = stringResource(R.string.chat_todo_progress, completed, todos.size),
        fraction = if (todos.isEmpty()) null else completed.toFloat() / todos.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        DockLabel(stringResource(R.string.chat_todo_title))
        todos.forEach { todo ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(todoStatusDot(todo.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    todo.content,
                    style = DsType.small13,
                    color = if (todo.status == "completed") DsTheme.colors.labelTertiary else DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Goals
// ---------------------------------------------------------------------------

/** The read-only goal summary shown inline in the transcript when a goal event lands. */
@Composable
internal fun GoalSummary(goal: GoalSnapshot) {
    val colors = DsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(FeatherIcons.Target, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.ink_chat_goal_dock, goal.objective),
            style = DsType.dockTitle,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GoalPhaseBadge(goal.phase)
    }
    goal.blockedReason?.let {
        Text(
            stringResource(R.string.goal_blocked_reason, it.message),
            style = DsType.caption11,
            color = colors.warnText,
        )
    }
}

/**
 * The live goal bar above the composer: the mock's compact card, expanding into its verbs.
 *
 * The mock's round meter (`3/10`) needs a completed-round count the goal projection does not
 * publish — it carries the cap only — so the bar states the phase on the right and the expanded
 * body states the cap. Inventing a fraction would be worse than leaving it out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalBar(goal: GoalSnapshot, store: SessionStore, modifier: Modifier = Modifier, roundsStarted: Int? = null) {
    val colors = DsTheme.colors
    var expanded by rememberSaveable(goal.id) { mutableStateOf(false) }
    val cap = goal.maxGoalRounds.takeIf { it > 0 }
    val rounds = roundsStarted?.takeIf { cap != null }
    val fraction = if (rounds != null && cap != null) rounds.toFloat() / cap else null

    DockBar(
        icon = FeatherIcons.Target,
        title = stringResource(R.string.ink_chat_goal_dock, goal.objective),
        // The meter's count is the mock's "3/10"; with no round event this build cannot read, the
        // bar falls back to the phase, which is always known.
        trailing = if (rounds != null && cap != null) {
            stringResource(R.string.chat_todo_progress, rounds, cap)
        } else {
            stringResource(goalPhaseLabelRes(goal.phase))
        },
        fraction = fraction,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        accent = goal.phase == GoalPhase.ACTIVE,
    ) {
        Text(
            goal.objective,
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        goal.blockedReason?.let {
            Text(
                stringResource(R.string.goal_blocked_reason, it.message),
                style = DsType.caption11,
                color = colors.warnText,
            )
        }
        if (rounds != null && cap != null) {
            Text(
                stringResource(R.string.goal_rounds, rounds, cap.toString()),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        Text(
            stringResource(R.string.goal_max_rounds, goal.maxGoalRounds.toString()),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        GoalActions(goal, store)
    }
}

/**
 * The goal's verbs: pause or resume, edit, clear, and the two dialogs the last two open.
 *
 * Shared by the dock above the composer and the details panel's Goal row. The panel is drawn over
 * the chat, so the chat's error banner is hidden behind it; it passes [onFailed] and says a refusal
 * itself, and the store stays off the banner for that call. With no [onFailed] a refusal goes to
 * the banner, as it always did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalActions(
    goal: GoalSnapshot,
    store: SessionStore,
    modifier: Modifier = Modifier,
    onFailed: ((String?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberDsHaptics()
    var editing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var editText by remember(goal.revision) { mutableStateOf(goal.objective) }

    fun run(action: String, objective: String? = null) {
        scope.launch {
            val outcome = store.goalAction(action, objective, report = onFailed == null)
            if (outcome is ActionOutcome.Failed) onFailed?.invoke(outcome.message)
        }
    }

    FlowRow(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (goal.phase) {
            GoalPhase.ACTIVE -> DsButton(
                text = stringResource(R.string.goal_pause),
                onClick = {
                    haptics.tap()
                    run("pause")
                },
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
            GoalPhase.PAUSED, GoalPhase.BLOCKED -> DsButton(
                text = stringResource(R.string.goal_resume),
                onClick = {
                    haptics.tap()
                    run("resume")
                },
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
            // A finished goal has no next state, and a phase from a newer harness has none
            // this build can name. Edit and clear stay.
            GoalPhase.COMPLETE, GoalPhase.UNKNOWN -> Unit
        }
        DsButton(
            text = stringResource(R.string.goal_edit),
            onClick = { haptics.tap(); editing = true },
            variant = DsButtonVariant.Outline,
            size = DsButtonSize.Small,
        )
        DsButton(
            text = stringResource(R.string.goal_clear),
            onClick = { haptics.reject(); clearing = true },
            variant = DsButtonVariant.Danger,
            size = DsButtonSize.Small,
        )
    }

    if (clearing) {
        ConfirmDialog(
            title = stringResource(R.string.goal_clear),
            body = goal.objective,
            confirmLabel = stringResource(R.string.goal_clear),
            onDismiss = { clearing = false },
            onConfirm = {
                clearing = false
                run("clear")
            },
        )
    }
    if (editing) {
        DsDialog(title = stringResource(R.string.goal_edit), onDismiss = { editing = false }) {
            TextField(
                minLines = 3,
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                placeholder = { Text(stringResource(R.string.goal_title), style = DsType.std14) },
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = {
                        run("edit", editText)
                        editing = false
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editing = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** A status is descriptive, not a disabled action; suppress the shared pill's click semantics. */
@Composable
private fun GoalPhaseBadge(phase: GoalPhase) {
    val label = stringResource(goalPhaseLabelRes(phase))
    Box(Modifier.clearAndSetSemantics { contentDescription = label }) { DsPill(text = label) }
}

// ---------------------------------------------------------------------------
// Queue
// ---------------------------------------------------------------------------

/** Pending turns, with the edit / remove / steer verbs the harness exposes. */
@Composable
internal fun QueueDock(
    queue: List<QueueItem>,
    store: SessionStore,
    modifier: Modifier = Modifier,
    sessionId: String? = store.currentSessionId.value,
    running: Boolean,
    blocked: Boolean = false,
    operationState: androidx.compose.runtime.MutableState<Boolean>? = null,
) {
    if (queue.isEmpty()) return
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val busyState: androidx.compose.runtime.MutableState<Boolean> = operationState ?: remember(sessionId) { mutableStateOf(false) }
    var busy by busyState
    fun change(id: String, action: String, text: String? = null) {
        if (busy || blocked) return
        busy = true
        scope.launch { try { store.updateQueue(id, action, text, sessionId) } finally { busy = false } }
    }
    // Kept open while turns join or leave the queue; it closes only for another session.
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    // A queued turn is something the reader already sent, so it holds like their bubble does.
    var heldId by remember { mutableStateOf<String?>(null) }
    val copy = rememberCopier()
    val holdLabel = stringResource(R.string.hold_message_actions)
    fun fullText(item: QueueItem): String =
        (item.content as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("text").asString() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: item.previewText

    DockBar(
        icon = FeatherIcons.List,
        title = stringResource(R.string.chat_queue_title),
        trailing = queue.size.toString(),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        // Steering is a verb on a live turn. With nothing running the host refuses it, so the
        // button is not offered; the note below says why instead of leaving a dead control.
        if (running) {
            DsButton(
                text = stringResource(R.string.chat_queue_steer),
                enabled = !busy && !blocked,
                onClick = {
                    busy = true
                    val ids = queue.map { it.id }
                    scope.launch { try { ids.forEach { store.updateQueue(it, "steer", sessionId = sessionId) } } finally { busy = false } }
                },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        } else {
            Text(
                stringResource(R.string.chat_composer_steer_idle),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        queue.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .holdable(holdLabel) { heldId = item.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.previewText,
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DsPill(text = queuePlacementLabel(item.placement))
                if (!busy && !blocked && item.placement != "steering") DsMenu(
                    anchor = {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_queue_edit),
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    items = buildList {
                        add(
                            MenuItem(stringResource(R.string.chat_queue_edit)) {
                                editingId = item.id
                                editText = fullText(item)
                            },
                        )
                        add(
                            MenuItem(stringResource(R.string.chat_queue_remove), danger = true) {
                                change(item.id, "remove")
                            },
                        )
                        if (running) {
                            add(
                                MenuItem(stringResource(R.string.chat_queue_steer)) {
                                    change(item.id, "steer")
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    // Looked up by id on every pass, so a turn that leaves the queue while its menu is open takes
    // the menu with it instead of leaving actions aimed at something that is gone.
    queue.firstOrNull { it.id == heldId }?.let { item ->
        val text = fullText(item)
        val changeable = !busy && !blocked && item.placement != "steering"
        HoldMenu(
            onDismiss = { heldId = null },
            preview = text,
            actions = buildList {
                add(HoldAction(stringResource(R.string.common_copy), Icons.Outlined.ContentCopy) { copy(text) })
                if (changeable) {
                    add(HoldAction(stringResource(R.string.chat_queue_edit), Icons.Outlined.Edit) {
                        editingId = item.id
                        editText = text
                    })
                    // Steering needs a live turn; edit and remove do not, so only the steer verb
                    // follows the turn's state.
                    if (running) {
                        add(HoldAction(stringResource(R.string.chat_queue_steer), Icons.Outlined.Bolt) { change(item.id, "steer") })
                    }
                    add(HoldAction(stringResource(R.string.chat_queue_remove), Icons.Outlined.Delete, danger = true) {
                        change(item.id, "remove")
                    })
                }
            },
        )
    }

    editingId?.let { id ->
        DsDialog(title = stringResource(R.string.chat_queue_edit), onDismiss = { editingId = null }) {
            TextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                placeholder = { Text(stringResource(R.string.chat_composer_hint), style = DsType.std14) },
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    enabled = editText.isNotBlank() && !busy && !blocked,
                    onClick = {
                        busy = true
                        scope.launch {
                            try { if (store.updateQueue(id, "edit", editText, sessionId)) editingId = null }
                            finally { busy = false }
                        }
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingId = null },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Statistics footer
// ---------------------------------------------------------------------------

/**
 * The run statistics line under the transcript, as the mock's mono caption strip.
 *
 * Two of these numbers are aggregates on the wire, not averages: `ttftMs` is a *sum* across
 * `ttftSteps`, and there is no throughput field at all — it comes from decoded tokens over decode
 * milliseconds. Printing them raw would have shown a time-to-first-token of several minutes.
 */
@Composable
internal fun StatsFooter(
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    modifier: Modifier = Modifier,
    quotaText: String = "",
) {
    if (stats == null && usage == null) return
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val parts = buildList {
        stats?.let {
            add(pluralStringResource(R.plurals.chat_stats_turn_count, it.turns, it.turns) + " · " + pluralStringResource(R.plurals.trajectory_step_count, it.steps, it.steps))
            val ttftMs = it.meanTtftMs
            val rate = it.tokensPerSecond
            // Both numbers come from the first steps of a run; until they exist the clause is left out.
            if (ttftMs != null && rate != null) {
                add(
                    stringResource(
                        R.string.chat_stats_speed,
                        formatDurationMs(ttftMs),
                        String.format(java.util.Locale.US, "%.0f", rate),
                    ),
                )
            }
        }
        usage?.cacheHitRatio?.let { add(stringResource(R.string.chat_stats_cache, (it * 100).toInt())) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 12.dp),
    ) {
        Text(
            parts.joinToString(" · "),
            style = DsType.statsText,
            color = colors.labelCaption,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
        )
        if (expanded) {
            stats?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_timing,
                        formatDurationMs(it.llmMs),
                        formatDurationMs(it.toolMs),
                    ),
                )
            }
            usage?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_tokens,
                        formatTokens(it.inputTokens),
                        formatTokens(it.outputTokens),
                    ) + quotaText.takeIf { text -> text.isNotBlank() }?.let { text -> " · $text" }.orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = DsType.statsText,
        color = DsTheme.colors.labelCaption,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The wire's placement words (`queued`, `steering`, `context`) in the reader's language. */
@Composable
private fun queuePlacementLabel(placement: String): String = when (placement) {
    // The mock's tag for a waiting turn is "Next turn", which says when it runs rather than
    // repeating the dock's own title.
    com.labteto.dshmobile.core.wire.dto.InboxProjection.QUEUED -> stringResource(R.string.ink_chat_queue_next_turn)
    com.labteto.dshmobile.core.wire.dto.InboxProjection.STEERING -> stringResource(R.string.chat_queue_placement_steering)
    com.labteto.dshmobile.core.wire.dto.InboxProjection.CONTEXT -> stringResource(R.string.chat_queue_placement_context)
    else -> placement
}
