package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_details_title
import com.labteto.dshmobile.shared.resources.chat_history_title
import com.labteto.dshmobile.shared.resources.chat_model_change
import com.labteto.dshmobile.shared.resources.chat_status_idle
import com.labteto.dshmobile.shared.resources.chat_status_running
import com.labteto.dshmobile.shared.resources.chat_tab
import com.labteto.dshmobile.shared.resources.chatlist_hide
import com.labteto.dshmobile.shared.resources.chatlist_needs_action
import com.labteto.dshmobile.shared.resources.chatlist_open
import com.labteto.dshmobile.shared.resources.chatlist_show
import com.labteto.dshmobile.shared.resources.chatlist_subagents
import com.labteto.dshmobile.shared.resources.ink_chat_files_tab
import com.labteto.dshmobile.shared.resources.panel_workspace
import com.labteto.dshmobile.shared.resources.team_chip
import com.labteto.dshmobile.shared.resources.team_title
import com.labteto.dshmobile.shared.resources.trajectory_title
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.pressScale
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The views of a session the app offers: the conversation, its ledger, and the files it touched. */
enum class ChatTab { Chat, Trajectory, Files }

/** The widest the preset chip grows, however long its name and however wide the bar. */
private val CHIP_MAX_WIDTH = 280.dp

/** From this font scale the title and the model line may each take a second line rather than being cut to a few letters. */
private const val LARGE_TEXT_SCALE = 1.3f

/** How wide the loading shimmer stands in for the model line. */
private val MODEL_SKELETON_WIDTH = 132.dp

/** The title block's inset from its own edges: room for its press ripple around the text. */
private val TITLE_INSET = 8.dp

/**
 * Whether the row under the bar has anything to draw: the run status, the agent preset, the team tag
 * or the subagent count. With none of them the row is left out, not drawn empty.
 */
internal fun hasStatusRow(
    running: Boolean,
    needsYou: Boolean,
    agentPresetLabel: String?,
    teamAvailable: Boolean,
    subagentCount: Int,
): Boolean = running || needsYou || agentPresetLabel != null || teamAvailable || subagentCount > 0

/**
 * The session chrome: a bar, a status row when there is something to say, and the view tabs.
 *
 * The bar is one row. It holds the list control, then a two-line title block, then the history and
 * details buttons. The block shows the session title over the model and its reasoning tier, and one
 * tap on it opens the model picker (a subagent child states its model without offering one). The block
 * takes the width the icons leave and ellipsizes inside it, so the icons always fit; at large text size
 * its lines wrap rather than being cut.
 *
 * Under the bar, the live run state, the agent preset, the team tag and the subagent count share one
 * row that starts under the title's start edge, wraps instead of clipping, and is not drawn at all
 * while none of them applies.
 *
 * Last come the view tabs (Chat, Trajectory, Files) and the workspace button, closed by a hairline.
 * [tabsVisible] folds the tabs away while the transcript is being read back; the hairline stays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatTopBar(
    title: String,
    running: Boolean,
    models: SessionModelsValue?,
    agentPresetLabel: String?,
    subagentCount: Int,
    detailsOpen: Boolean,
    tab: ChatTab,
    fileCount: Int,
    /** True while an approval, question or plan review is waiting on this session. */
    needsYou: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSubagents: () -> Unit,
    /** Opens the details panel. Null leaves the details button out (iOS has no panel yet). */
    onOpenDetails: (() -> Unit)?,
    onTabChange: (ChatTab) -> Unit,
    onOpenWorkspace: () -> Unit,
    workspaceEnabled: Boolean,
    /** False while no session is open, where there is no history to pick from yet. */
    historyEnabled: Boolean,
    /**
     * Whether a session is open. With none (a fresh install, an empty harness, everything archived)
     * there is no model to name yet, so only the title is drawn, if there is one, rather than a model
     * line shimmering as if one were on its way.
     */
    sessionOpen: Boolean = true,
    /**
     * False when the open session is a subagent child: its model belongs to the parent, and the
     * host refuses a switch from here, so the model line states the model instead of offering a picker.
     */
    modelsEnabled: Boolean = true,
    /**
     * What the leading button is. On a phone it opens the chat list drawer; beside a docked list it
     * shows or hides that pane; where a hinge pins the list in place it is not drawn at all.
     * [onOpenDrawer] is the click handler for all three.
     */
    listControl: ListControl = ListControl.OpenDrawer,
    /** True when the open session leads an experimental Agent Team; the Team chip opens its sheet. */
    teamAvailable: Boolean = false,
    onOpenTeam: () -> Unit = {},
    /**
     * Whether the tab row offers the views (Chat, Trajectory, Files) and the workspace button. False draws only
     * the hairline that closes the bar: iOS has the conversation alone for now.
     */
    showTabs: Boolean = true,
    /**
     * False folds the tab row away, with a collapse animation, and true brings it back. The caller
     * decides (it is the one that sees the transcript scroll); the bar only draws what it is told.
     */
    tabsVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // Where the title's text starts: the bar's start padding, the list control (or the spacer that
    // stands in for it), the gap after it, and the block's own inset. The status row begins at the
    // same edge, so the two read as one column of text.
    val titleStart = DsSpacing.tiny +
        (if (listControl == ListControl.None) DsSpacing.small else DsSizes.touchTarget) +
        DsSpacing.tiny + TITLE_INSET
    Column(modifier.fillMaxWidth().background(colors.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = DsSpacing.tiny, end = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            when (listControl) {
                ListControl.OpenDrawer -> DsIconButton(
                    icon = FeatherIcons.Menu,
                    contentDescription = stringResource(Res.string.chatlist_open),
                    onClick = onOpenDrawer,
                    tint = colors.labelSecondary,
                    iconSize = 20.dp,
                )
                // The list is a pane: the same slot becomes its switch. The pane's own edge shows
                // which way it goes, so the glyph is one drawing for both states and the words say
                // what the tap will do.
                ListControl.Collapse, ListControl.Expand -> DsIconButton(
                    icon = FeatherIcons.Sidebar,
                    contentDescription = stringResource(
                        if (listControl == ListControl.Collapse) Res.string.chatlist_hide else Res.string.chatlist_show,
                    ),
                    onClick = onOpenDrawer,
                    tint = colors.labelSecondary,
                    iconSize = 20.dp,
                )
                // Nothing to open or hide: keep the bar's rhythm with a little start padding instead.
                ListControl.None -> Spacer(Modifier.width(DsSpacing.small))
            }
            // The only elastic item in the row: it takes what the icons leave and wraps or ellipsizes
            // its own text, so the right-hand icons are never pushed off the edge.
            TitleBlock(
                title = title,
                models = models,
                sessionOpen = sessionOpen,
                switchable = modelsEnabled,
                onClick = { haptics.tap(); onOpenModels() },
                modifier = Modifier.weight(1f),
            )
            if (historyEnabled) {
                DsIconButton(
                    icon = FeatherIcons.List,
                    contentDescription = stringResource(Res.string.chat_history_title),
                    onClick = onOpenHistory,
                    tint = colors.labelSecondary,
                    iconSize = 18.dp,
                )
            }
            if (!detailsOpen && onOpenDetails != null) {
                DsIconButton(
                    icon = FeatherIcons.Info,
                    contentDescription = stringResource(Res.string.chat_details_title),
                    onClick = onOpenDetails,
                    tint = colors.labelTertiary,
                    iconSize = 18.dp,
                )
            }
        }

        if (hasStatusRow(running, needsYou, agentPresetLabel, teamAvailable, subagentCount)) {
            // The inline triggers are drawn 28dp but carry a 48dp touch target, which makes this row
            // 48dp tall whenever one is present; the padding that spaced a bare status pill is dropped
            // then so the row does not also pay for the margin on top of the target.
            val hasTrigger = agentPresetLabel != null || subagentCount > 0 || teamAvailable
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = titleStart,
                        end = DsSpacing.xsmall,
                        top = if (hasTrigger) 0.dp else 2.dp,
                        bottom = if (hasTrigger) 0.dp else 6.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // Idle is the absence of news, so it draws nothing here; running and needs-you keep
                // their mascot pill.
                if (running || needsYou) {
                    RunStatusPill(running = running, needsYou = needsYou)
                }
                if (agentPresetLabel != null) {
                    MetaChip(label = agentPresetLabel, onClick = { haptics.tap(); onOpenPresets() })
                }
                if (teamAvailable) {
                    AgentTeamTag(onClick = { haptics.tap(); onOpenTeam() })
                }
                if (subagentCount > 0) {
                    SubagentTag(count = subagentCount, onClick = { haptics.tap(); onOpenSubagents() })
                }
            }
        }

        if (showTabs) {
            AnimatedVisibility(
                visible = tabsVisible,
                enter = DsAnimations.chromeEnter,
                exit = DsAnimations.chromeExit,
            ) {
                ChatTabRow(
                    tab = tab,
                    fileCount = fileCount,
                    onTabChange = { next ->
                        if (next != tab) {
                            haptics.selection()
                            onTabChange(next)
                        }
                    },
                    onOpenWorkspace = onOpenWorkspace,
                    workspaceEnabled = workspaceEnabled,
                )
            }
        }
        BarHairline()
    }
}

/**
 * The session title with the model underneath, as one control.
 *
 * `session.models` returns `deepseek-official / deepseek-v4-pro / max`, which is not what anyone
 * calls it, so the subtitle uses the catalog's display names. The whole block is the tap target for
 * the model picker, drawn with no border or fill: the chevron on the subtitle says it opens something.
 * [switchable] is false on a subagent child, whose model the parent owns: the subtitle stays and the
 * chevron and the tap go, because a picker there could only be refused. With no session open there is
 * no model line, and the block carries the title alone.
 *
 * A screen reader reads the block as one node: the title, then the model, then "Change model" as
 * what activating it does.
 */
@Composable
private fun TitleBlock(
    title: String,
    models: SessionModelsValue?,
    sessionOpen: Boolean,
    switchable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    // At a large text size each line wraps to a second line instead of being cut to a few letters.
    val maxLines = if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) 2 else 1
    val tappable = sessionOpen && switchable
    val changeModel = stringResource(Res.string.chat_model_change)
    val interaction = remember { MutableInteractionSource() }
    val tap = if (tappable) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onClickLabel = changeModel,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .heightIn(min = DsSizes.touchTarget)
            .clip(DsShapes.control)
            .then(tap)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = TITLE_INSET, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (title.isNotBlank()) {
            Text(
                title,
                style = DsType.title17,
                color = colors.labelPrimary,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (sessionOpen) {
            ModelLine(models = models, switchable = switchable, maxLines = maxLines)
        }
    }
}

/** The model's display name and reasoning tier, quietly, with a chevron when the model can be switched. */
@Composable
private fun ModelLine(models: SessionModelsValue?, switchable: Boolean, maxLines: Int) {
    val colors = DsTheme.colors
    if (models == null) {
        // Loading, with a session on its way: a shimmer the size of the line it stands in for.
        Box(
            Modifier
                .padding(vertical = 3.dp)
                .width(MODEL_SKELETON_WIDTH)
                .height(14.dp)
                .skeleton(colors.bgLayer2, colors.hover, DsShapes.pillFull),
        )
        return
    }
    val current = models.current
    val group = models.groups.firstOrNull { it.id == current.provider }
    val model = group?.models?.firstOrNull { it.id == current.model }
    // A selection without an effort runs on the adapter's default; the line names that default
    // rather than leaving a reasoning model looking tierless (the same rule the host's picker uses).
    val effectiveEffort = current.reasoningEffort ?: model?.reasoning?.defaultEffort
    val effort = model?.reasoning?.efforts?.firstOrNull { it.id == effectiveEffort }
    val modelLabel = model?.name ?: technicalDisplay(current.model)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        if (!models.routable) {
            StateDot(StateDotState.Warning, size = 6.dp)
        }
        Text(
            modelLabel,
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            // The model name is the one elastic label in the line: the tier ("High") and the
            // chevron keep their natural width, so the name gets every pixel left rather than
            // splitting the row with the tier and truncating early.
            modifier = Modifier.weight(1f, fill = false),
        )
        effort?.let {
            Text(
                it.name,
                style = DsType.small13,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 72.dp),
            )
        }
        if (switchable) {
            Icon(
                DsIcons.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The live run state, said in words and carried by the mascot.
 *
 * The design puts the mascot in the pill, and it is the one place in the chrome where an animated
 * glyph earns its keep: it is the only thing that changes while a turn runs. Idle collapses to a
 * dot, so the chrome only spends width on a state worth noticing.
 */
@Composable
private fun RunStatusPill(running: Boolean, needsYou: Boolean) {
    val colors = DsTheme.colors
    val state = when {
        needsYou -> MascotState.NeedsYou
        running -> MascotState.Working
        else -> null
    }
    val label = when {
        needsYou -> stringResource(Res.string.chatlist_needs_action)
        running -> stringResource(Res.string.chat_status_running)
        else -> stringResource(Res.string.chat_status_idle)
    }
    if (state == null) {
        Box(
            Modifier
                .padding(horizontal = DsSpacing.small)
                .clearAndSetSemantics { contentDescription = label },
        ) {
            StateDot(StateDotState.Idle)
        }
        return
    }
    val fill = if (needsYou) colors.warnTertiary else colors.accentTertiary
    val content = if (needsYou) colors.warnLabel else colors.accent
    Row(
        modifier = Modifier
            .clip(DsShapes.pillFull)
            .background(fill)
            .animateContentSize(DsAnimations.expand)
            .padding(start = 6.dp, end = 12.dp, top = 3.dp, bottom = 3.dp)
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MascotMark(state = state, size = 22.dp)
        Text(label, style = DsType.small13Strong, color = content, maxLines = 1)
    }
}

/** A small inline trigger — the agent preset, while the session is still blank enough to pick one. */
@Composable
private fun MetaChip(label: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    // Drawn 28dp, a 48dp target around it: the chip stays slim, the thumb still has somewhere to land.
    Row(
        modifier = Modifier
            .widthIn(max = CHIP_MAX_WIDTH)
            .minimumInteractiveComponentSize()
            .height(DsSizes.chipCompact)
            .clip(DsShapes.pillFull)
            .background(colors.hoverSolid)
            .border(1.dp, colors.borderL2, DsShapes.pillFull)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Icon(FeatherIcons.Server, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(13.dp))
        Text(label, style = DsType.small13, color = colors.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How many subagents the session spawned; opens the subagents sheet. */
@Composable
private fun SubagentTag(count: Int, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val label = stringResource(Res.string.chatlist_subagents)
    // The same 28dp-in-48dp shape as the preset chip beside it. It is tappable, so it is a pill, not a
    // 6dp badge: the shape is what says "this opens something".
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(DsSizes.chipCompact)
            .clip(DsShapes.pillFull)
            .background(colors.accentTertiary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = DsSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(DsIcons.Groups, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
        Text(count.toString(), style = DsType.small13Strong, color = colors.accent, maxLines = 1)
    }
}

/**
 * The tab strip: three boxed tabs where the live one takes the brand fill, as in the mock.
 *
 * The workspace is a screen of its own rather than a fourth view of the conversation, so it stays a
 * separate control at the end of the row — an icon button, because three labels plus a labelled
 * button is exactly what clipped in the mock.
 */
@Composable
private fun ChatTabRow(
    tab: ChatTab,
    fileCount: Int,
    onTabChange: (ChatTab) -> Unit,
    onOpenWorkspace: () -> Unit,
    workspaceEnabled: Boolean,
) {
    val colors = DsTheme.colors
    // The row is 48dp because its folder button is; the tabs, drawn 36dp, sit centred in it with their
    // own 48dp targets, so there is no vertical padding to add on top.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.large, end = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tabs live in a box that takes whatever the folder button leaves, and scroll inside it.
        // The button is measured first (it has no weight), so at the largest text sizes, or with a long
        // translation, it is the tabs that scroll: the only way into the workspace never gets squeezed
        // out of the row.
        val tabScroll = rememberScrollState()
        Box(Modifier.weight(1f).fadeWhereMoreToScroll(tabScroll, colors.bgBase), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = Modifier.horizontalScroll(tabScroll),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatTabButton(
                    label = stringResource(Res.string.chat_tab),
                    selected = tab == ChatTab.Chat,
                    onClick = { onTabChange(ChatTab.Chat) },
                )
                ChatTabButton(
                    label = stringResource(Res.string.trajectory_title),
                    selected = tab == ChatTab.Trajectory,
                    onClick = { onTabChange(ChatTab.Trajectory) },
                )
                ChatTabButton(
                    label = stringResource(Res.string.ink_chat_files_tab, localizedCount(fileCount)),
                    selected = tab == ChatTab.Files,
                    onClick = { onTabChange(ChatTab.Files) },
                )
            }
        }
        DsIconButton(
            icon = FeatherIcons.Folder,
            contentDescription = stringResource(Res.string.panel_workspace),
            onClick = onOpenWorkspace,
            tint = if (workspaceEnabled) colors.labelSecondary else colors.labelDimmed,
            iconSize = 20.dp,
            enabled = workspaceEnabled,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

/** The hairline that closes the bar, under the tab row (or alone where there are no tabs or they are folded away). */
@Composable
private fun BarHairline() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DsTheme.colors.borderL1),
    )
}

/**
 * A fade into [color] over the end of a sideways-scrolling row that still has more to scroll to, so a
 * tab cut by the edge reads as "this scrolls" and not as clipped. It is painted over the row and adds
 * no size of its own, and it is gone when everything fits or the row is scrolled to its end. The end
 * is the reading end: the right in left-to-right text, the left in right-to-left.
 */
private fun Modifier.fadeWhereMoreToScroll(scroll: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    if (!scroll.canScrollForward) return@drawWithContent
    val fade = 32.dp.toPx().coerceAtMost(size.width / 2f)
    val rtl = layoutDirection == LayoutDirection.Rtl
    val start = if (rtl) 0f else size.width - fade
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (rtl) listOf(color, Color.Transparent) else listOf(Color.Transparent, color),
            startX = start,
            endX = start + fade,
        ),
        topLeft = Offset(start, 0f),
        size = Size(fade, size.height),
    )
}

/** One boxed tab, its label and fill cross-fading with the selection. */
@Composable
private fun ChatTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val fill by animateColorAsState(
        if (selected) colors.brandPrimary else Color.Transparent,
        DsAnimations.tabSwapColor,
        label = "tabFill",
    )
    val content by animateColorAsState(
        if (selected) colors.onBrandPrimary else colors.labelSecondary,
        DsAnimations.tabSwapColor,
        label = "tabContent",
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(DsSizes.chip)
            .clip(DsShapes.control)
            .background(fill)
            .pressScale(interaction)
            // selectable, not clickable: the selected state has to reach the accessibility tree, and
            // the fill and label tint are the only visual carriers of it.
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = DsType.tabText,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The status-row chip that opens the team sheet; the same 28dp-in-48dp pill as the subagent count. */
@Composable
internal fun AgentTeamTag(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val description = stringResource(Res.string.team_title)
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
        Icon(DsIcons.Diversity3, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
        Text(
            stringResource(Res.string.team_chip),
            style = DsType.small13Strong,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
