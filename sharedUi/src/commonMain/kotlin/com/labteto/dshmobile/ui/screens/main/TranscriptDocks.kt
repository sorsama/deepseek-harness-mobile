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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_todo_progress
import com.labteto.dshmobile.shared.resources.chat_todo_title
import com.labteto.dshmobile.shared.resources.goal_blocked_reason
import com.labteto.dshmobile.shared.resources.ink_chat_goal_dock

/*
 * The docks' shared shape, and the two of them the transcript draws inline: a task list written
 * mid-turn (`TodoDock`) and a goal event (`GoalSummary`). Moved out of `Docks.kt` (Android) for the
 * shared transcript (iOS M5c, #141); the live docks above the composer (the goal bar, the queue,
 * the run statistics) stay in `Docks.kt` until the composer moves, and draw the same `DockBar`.
 */

// ---------------------------------------------------------------------------
// Shared compact bar
// ---------------------------------------------------------------------------

/**
 * The one dock shape: a rounded 40dp bar with a leading glyph, a title, a right-hand count/meter,
 * and a disclosure chevron. [fraction] draws the thin meter the mock puts on the goal bar.
 */
@Composable
fun DockBar(
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
fun TodoDock(todos: List<TodoEntry>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    // Not keyed on the list: every todo update used to fold an open dock shut under the reader.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completed = todos.count { it.status == "completed" }
    DockBar(
        icon = FeatherIcons.CheckSquare,
        title = stringResource(Res.string.chat_todo_title),
        trailing = stringResource(Res.string.chat_todo_progress, localizedCount(completed), localizedCount(todos.size)),
        fraction = if (todos.isEmpty()) null else completed.toFloat() / todos.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        DockLabel(stringResource(Res.string.chat_todo_title))
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
fun GoalSummary(goal: GoalSnapshot) {
    val colors = DsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(FeatherIcons.Target, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(
            stringResource(Res.string.ink_chat_goal_dock, goal.objective),
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
            stringResource(Res.string.goal_blocked_reason, it.message),
            style = DsType.caption11,
            color = colors.warnText,
        )
    }
}

/** A status is descriptive, not a disabled action; suppress the shared pill's click semantics. */
@Composable
private fun GoalPhaseBadge(phase: GoalPhase) {
    val label = goalPhaseLabel(phase)
    Box(Modifier.clearAndSetSemantics { contentDescription = label }) { DsPill(text = label) }
}
