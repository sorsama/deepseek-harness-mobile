package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsFilterChip
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

// ---------------------------------------------------------------------------
// Header controls
// ---------------------------------------------------------------------------

/**
 * The connected harness, as the mock draws it: a status dot, the host's name, a caret.
 *
 * It opens Settings rather than a connection picker because the drawer is hosted inside the chat
 * route and has no route of its own to hand — tapping the harness should at least land where its
 * address and reconnect controls live. The caret says it goes somewhere.
 */
@Composable
internal fun HarnessPill(name: String, phase: ConnectionPhase, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val dot = when (phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> StateDotState.Warning
        ConnectionPhase.DISCONNECTED -> StateDotState.Idle
    }
    val label = if (phase == ConnectionPhase.DISCONNECTED) stringResource(R.string.common_offline) else name
    // Drawn 36dp (the same as a filter chip, so the two read as one family), inside a 48dp target.
    Row(
        modifier = Modifier
            .widthIn(max = 168.dp)
            .minimumInteractiveComponentSize()
            .height(DsSizes.chip)
            .clip(DsShapes.pillFull)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.pillFull)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StateDot(dot, size = 7.dp)
        Text(
            label,
            style = DsType.small13Strong,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A row of chips that scrolls sideways and fades into [fadeColor] at its trailing edge, so the last
 * chip never looks chopped with no way to reach it. The fade runs toward the reading end, so it works
 * on both LTR and RTL.
 *
 * The fade is painted over the row, not laid out beside it. It used to be a second child of the [Box]
 * with `fillMaxHeight()`, and inside a [Box] a `fillMaxHeight()` child counts toward the box's own
 * size; a [Column] offers its unweighted children all the height that is left, so the "scroll
 * affordance" grew to the whole drawer. The session list and the start bar below it were then
 * measured at zero height and the drawer looked empty (issue #70). A draw-time overlay has no size
 * of its own to add.
 */
@Composable
internal fun FadingChipRow(
    fadeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl
    val fadeWidth = with(LocalDensity.current) { 32.dp.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val start = if (rtl) 0f else size.width - fadeWidth
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = if (rtl) listOf(fadeColor, Color.Transparent) else listOf(Color.Transparent, fadeColor),
                        startX = start,
                        endX = start + fadeWidth,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(fadeWidth, size.height),
                )
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The session-order control: an icon that opens the three orders, the current one checked.
 *
 * A [DsIconMenu], so it looks and presses exactly like the search button beside it.
 */
@Composable
internal fun SortButton(sort: String, onPick: (sort: String) -> Unit) {
    DsIconMenu(
        icon = Icons.Outlined.SwapVert,
        contentDescription = stringResource(R.string.chatlist_sort_title),
        items = listOf(
            MenuItem(text = stringResource(R.string.chatlist_sort_manual), selected = sort == SORT_MANUAL) { onPick(SORT_MANUAL) },
            MenuItem(text = stringResource(R.string.chatlist_sort_updated), selected = sort == SORT_UPDATED) { onPick(SORT_UPDATED) },
            MenuItem(text = stringResource(R.string.chatlist_sort_project), selected = sort == SORT_PROJECT) { onPick(SORT_PROJECT) },
        ),
    )
}

/** New session, new workspace, workspace management, automations and a manual refresh. */
@Composable
internal fun MoreMenu(
    automationAvailable: Boolean,
    onNewSession: () -> Unit,
    onNewWorkspace: () -> Unit,
    onWorkspaces: () -> Unit,
    onProjects: (() -> Unit)?,
    onAutomations: () -> Unit,
    onRefresh: () -> Unit,
) {
    DsIconMenu(
        icon = Icons.Outlined.MoreVert,
        contentDescription = stringResource(R.string.ink_chat_more),
        items = buildList {
            add(MenuItem(stringResource(R.string.chatlist_new_session), icon = Icons.Filled.Add, onClick = onNewSession))
            add(MenuItem(stringResource(R.string.chatlist_new_workspace), icon = Icons.Outlined.Add, onClick = onNewWorkspace))
            add(MenuItem(stringResource(R.string.ink_chat_workspaces), icon = FeatherIcons.Folder, onClick = onWorkspaces))
            if (onProjects != null) add(MenuItem(stringResource(R.string.projects_title), icon = FeatherIcons.Folder, onClick = onProjects))
            if (automationAvailable) {
                add(MenuItem(stringResource(R.string.harness_automations), icon = FeatherIcons.Zap, onClick = onAutomations))
            }
            // The pull is the fast path; this is the same read for TalkBack and for anyone who
            // would rather not drag. It runs the same cycle, so it animates the same pill.
            add(MenuItem(stringResource(R.string.chatlist_refresh), icon = Icons.Outlined.Refresh, onClick = onRefresh))
        },
    )
}

/**
 * The quick filters with their live counts, and the reorder hint that takes their place while a row
 * is held.
 *
 * The chips cannot be used mid-drag, so they fade out for the pill; it sits in the chips' own row,
 * so it can never cover a session row. [hint] is what the held unit may do, empty when nothing is
 * held; [pressing] is the finger pushing past the edge of where it may go.
 */
@Composable
internal fun DrawerFilterRow(
    filter: DrawerFilter,
    onFilter: (DrawerFilter) -> Unit,
    allCount: Int,
    runningCount: Int,
    needsYouCount: Int,
    holding: Boolean,
    hint: String,
    pressing: Boolean,
) {
    val colors = DsTheme.colors
    // Kept past the drop so the hint fades out with its words instead of emptying first.
    var hintShown by remember { mutableStateOf("") }
    if (hint.isNotEmpty()) hintShown = hint
    val chipsAlpha by animateFloatAsState(if (holding) 0f else 1f, tween(140), label = "chipsAlpha")
    Box(contentAlignment = Alignment.Center) {
        FadingChipRow(fadeColor = colors.sidebar, modifier = Modifier.alpha(chipsAlpha)) {
            DsFilterChip(
                label = stringResource(R.string.ink_chat_filter_all, allCount),
                selected = filter == DrawerFilter.All,
                onClick = { onFilter(DrawerFilter.All) },
            )
            DsFilterChip(
                label = stringResource(R.string.ink_chat_filter_running, runningCount),
                selected = filter == DrawerFilter.Running,
                onClick = { onFilter(DrawerFilter.Running) },
            )
            DsFilterChip(
                label = stringResource(R.string.ink_chat_filter_needs_you, needsYouCount),
                selected = filter == DrawerFilter.NeedsYou,
                warn = needsYouCount > 0,
                onClick = { onFilter(DrawerFilter.NeedsYou) },
            )
            DsFilterChip(
                label = stringResource(R.string.chatlist_archived),
                selected = filter == DrawerFilter.Archived,
                onClick = { onFilter(DrawerFilter.Archived) },
            )
        }
        ReorderHint(visible = holding, text = hintShown, pressing = pressing)
    }
}

/**
 * The search field, folded away while search is closed rather than holding a row of a phone-height
 * drawer, and the note that content search is off on this harness.
 */
@Composable
internal fun ColumnScope.DrawerSearch(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    focusTick: Int,
    contentSearchAvailable: Boolean,
) {
    val colors = DsTheme.colors
    AnimatedVisibility(visible = open) {
        Column(modifier = Modifier.padding(top = DsSpacing.small)) {
            // Present only while search is open, so asking for focus here is "the toggle that
            // opened it was just tapped" — the keyboard comes up with the field.
            DsSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.chatlist_search_hint),
                autoFocus = true,
                focusTick = focusTick,
            )
            // A capability note, not a failure: most harnesses ship with content search off.
            if (!contentSearchAvailable && query.isNotBlank()) {
                Text(
                    stringResource(R.string.chatlist_search_content_off),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    modifier = Modifier.padding(top = DsSpacing.tiny),
                )
            }
        }
    }
}

/**
 * The one line of explanation shown while something is held: where it is allowed to go.
 *
 * It turns amber-bordered while the finger presses past the edge of that place, which is the moment
 * the limit is being met.
 */
@Composable
internal fun ReorderHint(visible: Boolean, text: String, pressing: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(160)),
    ) {
        Surface(
            shape = DsShapes.pillFull,
            color = colors.bgLayer3,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (pressing) colors.warnBorder else colors.borderL3),
            shadowElevation = 4.dp,
        ) {
            Text(
                text,
                style = DsType.small13Strong,
                color = if (pressing) colors.warnLabel else colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small).widthIn(max = 240.dp),
            )
        }
    }
}

/** How many lines of a command the needs-you card shows before it stops offering a one-tap approval. */
private const val COMMAND_PREVIEW_LINES = 4

/**
 * The one session that needs a person: mascot, amber label, its title, what the harness is asking
 * to run, and the two decisions.
 *
 * The card is the mock's pinned approval, not a second copy of the row: approving from here is the
 * whole reason it is pinned, so it carries the command and the buttons rather than a link to a row
 * further down.
 */
@Composable
internal fun NeedsYouCard(
    session: SessionRow,
    approvalTool: String?,
    approvalReason: String?,
    onReview: () -> Unit,
    onApprove: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.warnSecondary),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotMark(state = MascotState.NeedsYou, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chatlist_needs_action).uppercase(),
                    style = DsType.label12,
                    color = colors.warnLabel,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    relativeTime(session.updatedAt),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            Text(
                displaySessionTitle(session),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val command = approvalReason?.takeIf { it.isNotBlank() } ?: approvalTool
            // A one-tap approval is only honest when the whole command is on screen. A long one (a
            // `rm -rf build && curl ... | sh` that shows only its first words) is shown over several
            // lines, and when even that cuts it off the card refuses to approve in one tap: it sends
            // the person to Review, where the full text is.
            var cutOff by remember(command) {
                // A first guess so an obviously long command never flashes a one-tap button; the
                // measured text corrects it either way a moment later.
                mutableStateOf(command != null && (command.length > 160 || command.count { it == '\n' } >= COMMAND_PREVIEW_LINES))
            }
            if (command != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DsShapes.code,
                    color = colors.bgLayer2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL1),
                ) {
                    Text(
                        "$ " + command,
                        style = DsType.mono13,
                        color = colors.labelSecondary,
                        maxLines = COMMAND_PREVIEW_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { cutOff = it.hasVisualOverflow },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (cutOff && onApprove != null) {
                    Text(
                        stringResource(R.string.chatlist_approve_long),
                        style = DsType.caption11,
                        color = colors.warnLabel,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.ink_chat_review),
                    onClick = onReview,
                    variant = if (cutOff) DsButtonVariant.Primary else DsButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                if (onApprove != null && !cutOff) {
                    DsButton(
                        text = stringResource(R.string.ink_chat_approve),
                        onClick = onApprove,
                        variant = DsButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One search result: the session's own name first, then where it lives, then the matching excerpt.
 * A result should name the thing you are about to open.
 */
@Composable
internal fun SearchResultRow(hit: SearchHit, index: Int, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val avatarTint = when {
        hit.session.pendingInteraction != null -> colors.warn
        hit.session.running -> colors.accent
        else -> colors.labelDimmed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .arrival(index)
            .clip(DsShapes.row)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = DsSpacing.small, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SessionAvatar(avatarTint, 28.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displaySessionTitle(hit.session),
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.session.origin == "subagent") {
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    DsPill(text = stringResource(R.string.chatlist_subagents))
                }
            }
            hit.workspaceLabel.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            hit.snippet?.let {
                Text(
                    text = it,
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            relativeTime(hit.session.updatedAt),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
        )
    }
}
