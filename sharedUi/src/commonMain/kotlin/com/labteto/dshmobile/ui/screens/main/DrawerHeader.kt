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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chatlist_approve_long
import com.labteto.dshmobile.shared.resources.chatlist_archived
import com.labteto.dshmobile.shared.resources.chatlist_needs_action
import com.labteto.dshmobile.shared.resources.chatlist_new_session
import com.labteto.dshmobile.shared.resources.chatlist_new_workspace
import com.labteto.dshmobile.shared.resources.chatlist_refresh
import com.labteto.dshmobile.shared.resources.chatlist_reorder_done
import com.labteto.dshmobile.shared.resources.chatlist_search_content_off
import com.labteto.dshmobile.shared.resources.chatlist_search_hint
import com.labteto.dshmobile.shared.resources.chatlist_sort_manual
import com.labteto.dshmobile.shared.resources.chatlist_sort_project
import com.labteto.dshmobile.shared.resources.chatlist_sort_title
import com.labteto.dshmobile.shared.resources.chatlist_sort_updated
import com.labteto.dshmobile.shared.resources.chatlist_subagents
import com.labteto.dshmobile.shared.resources.common_offline
import com.labteto.dshmobile.shared.resources.harness_automations
import com.labteto.dshmobile.shared.resources.ink_chat_approve
import com.labteto.dshmobile.shared.resources.ink_chat_filter_all
import com.labteto.dshmobile.shared.resources.ink_chat_filter_needs_you
import com.labteto.dshmobile.shared.resources.ink_chat_filter_running
import com.labteto.dshmobile.shared.resources.ink_chat_more
import com.labteto.dshmobile.shared.resources.ink_chat_review
import com.labteto.dshmobile.shared.resources.ink_chat_workspaces
import com.labteto.dshmobile.shared.resources.projects_title
import com.labteto.dshmobile.shared.resources.settings_connection_disconnect
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsFilterChip
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

// ---------------------------------------------------------------------------
// Header controls
// ---------------------------------------------------------------------------

/**
 * The connected harness, as the mock draws it: a status dot, the host's name, a caret.
 *
 * It opens Settings rather than a connection picker because the drawer is hosted inside the chat
 * route and has no route of its own to hand — tapping the harness should at least land where its
 * address and reconnect controls live. The caret says it goes somewhere; with no Settings to open
 * ([onClick] null) the pill only reports, and has no caret.
 */
@Composable
internal fun HarnessPill(name: String, phase: ConnectionPhase, onClick: (() -> Unit)?) {
    val colors = DsTheme.colors
    val dot = when (phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> StateDotState.Warning
        ConnectionPhase.DISCONNECTED -> StateDotState.Idle
    }
    val label = if (phase == ConnectionPhase.DISCONNECTED) stringResource(Res.string.common_offline) else name
    // Drawn 36dp (the same as a filter chip, so the two read as one family), inside a 48dp target.
    Row(
        modifier = Modifier
            .widthIn(max = 168.dp)
            .minimumInteractiveComponentSize()
            .height(DsSizes.chip)
            .clip(DsShapes.pillFull)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.pillFull)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
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
        if (onClick != null) {
            Icon(
                DsIcons.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
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
fun FadingChipRow(
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

/** Where the drawer's title sits, how big it is and whether Sort has moved into the More menu: see [chooseTitleLayout]. */
internal enum class TitleLayout(val compact: Boolean, val stacked: Boolean, val wraps: Boolean, val sortFolded: Boolean) {
    /** The display size, every action beside it. What a phone's drawer has. */
    Beside(compact = false, stacked = false, wraps = false, sortFolded = false),

    /** The section-title size, still beside every action. A slightly narrower pane, or larger text. */
    BesideCompact(compact = true, stacked = false, wraps = false, sortFolded = false),

    /** The section-title size, beside Search and More, with the three orders in the More menu. A narrow docked pane. */
    BesideFolded(compact = true, stacked = false, wraps = false, sortFolded = true),

    /** The display size alone on its line, every action on the line below. */
    Stacked(compact = false, stacked = true, wraps = false, sortFolded = false),

    /** The section-title size alone on its line, every action below. */
    StackedCompact(compact = true, stacked = true, wraps = false, sortFolded = false),

    /** Too much text for any line at this size: the title wraps rather than lose a letter. */
    StackedWrapped(compact = true, stacked = true, wraps = true, sortFolded = false),
}

/**
 * The first layout, from the roomiest to the tightest, in which the title is not cut.
 *
 * [fitsBeside] asks whether the title, in the display size or (`compact`) the section-title size, fits
 * on a line with the actions, with Sort folded into the More menu (`folded`) or not; [fitsAlone] asks
 * the same for a line of its own. The order is by how much each step costs. Shrinking the title costs
 * nothing, and folding Sort costs one tap on a control that is seldom used; both keep the header one
 * line tall, which a landscape phone has no height to spare for. A second line for the actions is
 * the last resort that still shows everything.
 */
internal fun chooseTitleLayout(
    fitsBeside: (compact: Boolean, folded: Boolean) -> Boolean,
    fitsAlone: (compact: Boolean) -> Boolean,
): TitleLayout = when {
    fitsBeside(false, false) -> TitleLayout.Beside
    fitsBeside(true, false) -> TitleLayout.BesideCompact
    fitsBeside(true, true) -> TitleLayout.BesideFolded
    fitsAlone(false) -> TitleLayout.Stacked
    fitsAlone(true) -> TitleLayout.StackedCompact
    else -> TitleLayout.StackedWrapped
}

/** Slack kept free on the title's line, so a title that fits by a hair is not clipped by rounding. */
private val TitleSlack = 4.dp

/**
 * The drawer's hero title, the secondary line under it, and the actions at its end.
 *
 * The title is never cut with an ellipsis (a drawer that reads "Ses…" has lost its name). In a narrow
 * pane it steps down a size, then Sort moves into the More menu (the [actions] lambda is told so with
 * `sortFolded`, and [foldedActionCount] is how many it then draws), then the title takes a line of its
 * own and the actions go under it, then it wraps; see [chooseTitleLayout]. The actions are 48dp
 * targets ([actionCount] of them), and none is ever pushed out of the pane.
 */
@Composable
fun DrawerTitleRow(
    title: String,
    subtitle: String,
    actionCount: Int,
    foldedActionCount: Int,
    actions: @Composable RowScope.(sortFolded: Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = remember(title, maxWidth, actionCount, foldedActionCount, density.density, density.fontScale, measurer) {
            fun fits(compact: Boolean, room: Dp): Boolean {
                val style = if (compact) DsType.large20 else DsType.display24
                val needed = measurer.measure(title, style, softWrap = false, maxLines = 1).size.width
                return needed <= with(density) { (room - TitleSlack).roundToPx() }
            }
            chooseTitleLayout(
                fitsBeside = { compact, folded ->
                    fits(compact, maxWidth - DsSizes.touchTarget * (if (folded) foldedActionCount else actionCount))
                },
                fitsAlone = { compact -> fits(compact, maxWidth) },
            )
        }
        val titleStyle = if (layout.compact) DsType.large20 else DsType.display24
        val titleText: @Composable () -> Unit = {
            Text(
                title,
                style = titleStyle,
                color = colors.labelPrimary,
                maxLines = if (layout.wraps) Int.MAX_VALUE else 1,
                softWrap = layout.wraps,
                overflow = TextOverflow.Clip,
            )
        }
        val subtitleText: @Composable (Modifier) -> Unit = { modifier ->
            Text(
                subtitle,
                style = DsType.mono12,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.padding(top = 2.dp),
            )
        }
        if (layout.stacked) {
            Column(Modifier.fillMaxWidth()) {
                titleText()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    subtitleText(Modifier.weight(1f))
                    actions(layout.sortFolded)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    titleText()
                    subtitleText(Modifier)
                }
                actions(layout.sortFolded)
            }
        }
    }
}

/** The three orders of the list as menu entries, the current one checked: the Sort menu, or part of More's. */
@Composable
private fun sortMenuItems(sort: String, onPick: (sort: String) -> Unit): List<MenuItem> = listOf(
    MenuItem(text = stringResource(Res.string.chatlist_sort_manual), selected = sort == SORT_MANUAL) { onPick(SORT_MANUAL) },
    MenuItem(text = stringResource(Res.string.chatlist_sort_updated), selected = sort == SORT_UPDATED) { onPick(SORT_UPDATED) },
    MenuItem(text = stringResource(Res.string.chatlist_sort_project), selected = sort == SORT_PROJECT) { onPick(SORT_PROJECT) },
)

/**
 * The session-order control: an icon that opens the three orders, the current one checked.
 *
 * A [DsIconMenu], so it looks and presses exactly like the search button beside it.
 */
@Composable
internal fun SortButton(sort: String, onPick: (sort: String) -> Unit) {
    DsIconMenu(
        icon = DsIcons.SwapVert,
        contentDescription = stringResource(Res.string.chatlist_sort_title),
        items = sortMenuItems(sort, onPick),
    )
}

/**
 * New session, new workspace, workspace management, automations, a manual refresh and, where asked for, Disconnect.
 * With [sort] given (the pane has no room for the Sort button) it also carries the three orders, the current one checked.
 */
@Composable
internal fun MoreMenu(
    automationAvailable: Boolean,
    onNewSession: () -> Unit,
    onNewWorkspace: () -> Unit,
    onWorkspaces: () -> Unit,
    onProjects: (() -> Unit)?,
    onAutomations: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: (() -> Unit)? = null,
    sort: String? = null,
    onSort: (sort: String) -> Unit = {},
) {
    val sortItems = if (sort != null) sortMenuItems(sort, onSort) else emptyList()
    DsIconMenu(
        icon = DsIcons.MoreVert,
        contentDescription = stringResource(Res.string.ink_chat_more),
        items = buildList {
            add(MenuItem(stringResource(Res.string.chatlist_new_session), icon = DsIcons.Add, onClick = onNewSession))
            add(MenuItem(stringResource(Res.string.chatlist_new_workspace), icon = DsIcons.AddOutlined, onClick = onNewWorkspace))
            add(MenuItem(stringResource(Res.string.ink_chat_workspaces), icon = FeatherIcons.Folder, onClick = onWorkspaces))
            if (onProjects != null) add(MenuItem(stringResource(Res.string.projects_title), icon = FeatherIcons.Folder, onClick = onProjects))
            if (automationAvailable) {
                add(MenuItem(stringResource(Res.string.harness_automations), icon = FeatherIcons.Zap, onClick = onAutomations))
            }
            // The pull is the fast path; this is the same read for TalkBack and for anyone who
            // would rather not drag. It runs the same cycle, so it animates the same pill.
            add(MenuItem(stringResource(Res.string.chatlist_refresh), icon = DsIcons.Refresh, onClick = onRefresh))
            addAll(sortItems)
            if (onDisconnect != null) {
                add(MenuItem(stringResource(Res.string.settings_connection_disconnect), icon = FeatherIcons.Server, onClick = onDisconnect))
            }
        },
    )
}

/**
 * The quick filters with their live counts, and the reorder hint that takes their place while a row
 * is held.
 *
 * The chips cannot be used mid-drag, so they fade out for the pill; it sits in the chips' own row,
 * so it can never cover a session row. [hint] is what the held unit may do, empty when nothing is
 * held; [pressing] is the finger pushing past the edge of where it may go; [onDone] lets the held
 * unit go where it stands (a row lifted from its menu has no finger to lift).
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
    onDone: () -> Unit,
) {
    val colors = DsTheme.colors
    // Kept past the drop so the hint fades out with its words instead of emptying first.
    var hintShown by remember { mutableStateOf("") }
    if (hint.isNotEmpty()) hintShown = hint
    val chipsAlpha by animateFloatAsState(if (holding) 0f else 1f, tween(140), label = "chipsAlpha")
    Box(contentAlignment = Alignment.Center) {
        FadingChipRow(fadeColor = colors.sidebar, modifier = Modifier.alpha(chipsAlpha)) {
            DsFilterChip(
                label = stringResource(Res.string.ink_chat_filter_all, localizedCount(allCount)),
                selected = filter == DrawerFilter.All,
                onClick = { onFilter(DrawerFilter.All) },
            )
            DsFilterChip(
                label = stringResource(Res.string.ink_chat_filter_running, localizedCount(runningCount)),
                selected = filter == DrawerFilter.Running,
                onClick = { onFilter(DrawerFilter.Running) },
            )
            DsFilterChip(
                label = stringResource(Res.string.ink_chat_filter_needs_you, localizedCount(needsYouCount)),
                selected = filter == DrawerFilter.NeedsYou,
                warn = needsYouCount > 0,
                onClick = { onFilter(DrawerFilter.NeedsYou) },
            )
            DsFilterChip(
                label = stringResource(Res.string.chatlist_archived),
                selected = filter == DrawerFilter.Archived,
                onClick = { onFilter(DrawerFilter.Archived) },
            )
        }
        ReorderHint(visible = holding, text = hintShown, pressing = pressing, onDone = onDone)
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
                placeholder = stringResource(Res.string.chatlist_search_hint),
                autoFocus = true,
                focusTick = focusTick,
            )
            // A capability note, not a failure: most harnesses ship with content search off.
            if (!contentSearchAvailable && query.isNotBlank()) {
                Text(
                    stringResource(Res.string.chatlist_search_content_off),
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
 * the limit is being met. [onDone], when given, adds a "Done" that lets the held unit go: a row lifted
 * from its menu waits for a drag, and this is how someone who has changed their mind puts it down.
 */
@Composable
internal fun ReorderHint(
    visible: Boolean,
    text: String,
    pressing: Boolean,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = DsType.small13Strong,
                    color = if (pressing) colors.warnLabel else colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = DsSpacing.medium, end = if (onDone == null) DsSpacing.medium else DsSpacing.small)
                        .padding(vertical = DsSpacing.small)
                        .widthIn(max = 240.dp),
                )
                if (onDone != null) {
                    Text(
                        stringResource(Res.string.chatlist_reorder_done),
                        style = DsType.small13Strong,
                        color = colors.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(DsShapes.pillFull)
                            .clickable(role = Role.Button, onClick = onDone)
                            .padding(horizontal = DsSpacing.medium)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
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
                    stringResource(Res.string.chatlist_needs_action).uppercase(),
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
                        stringResource(Res.string.chatlist_approve_long),
                        style = DsType.caption11,
                        color = colors.warnLabel,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(Res.string.ink_chat_review),
                    onClick = onReview,
                    variant = if (cutOff) DsButtonVariant.Primary else DsButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                if (onApprove != null && !cutOff) {
                    DsButton(
                        text = stringResource(Res.string.ink_chat_approve),
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
        // The same rule as the list's rows: the mascot only where something is running or waiting.
        SessionRowLead(hit.session, 28.dp)
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
                    DsPill(text = stringResource(Res.string.chatlist_subagents))
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
