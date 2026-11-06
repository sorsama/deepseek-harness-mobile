package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
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
internal enum class ChatTab { Chat, Trajectory, Files }

/** The widest the model chip (or the preset chip) grows, however long the name and however wide the bar. */
private val MODEL_CHIP_MAX_WIDTH = 280.dp

/** How wide the loading shimmer stands in for the model chip. */
private val MODEL_CHIP_SKELETON_WIDTH = 132.dp

/**
 * The session chrome: the Ink three-row bar.
 *
 * Row one is connection and live state — the drawer, the model, how the run is doing, the details
 * button. Row two is the session's identity — its title and how many subagents it spawned. Row
 * three is the view switch plus the workspace, closed by a hairline.
 *
 * The mock put a fourth control on row one and clipped it off the right edge of the phone. The
 * layout here instead lets the model chip wrap its own content (up to what is left, and no wider than
 * [MODEL_CHIP_MAX_WIDTH]) with a plain spacer taking the rest, and the chip ellipsizes before any
 * fixed control gives ground: the right-hand icons always fit, and losing a character of a model
 * name is the one place it costs nothing. The chip is deliberately *not* the weighted item: weighting
 * it stretched its border and fill across the whole bar while its text sat at the start, which read
 * as an empty box.
 */
@Composable
internal fun ChatTopBar(
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
    onOpenDetails: () -> Unit,
    onTabChange: (ChatTab) -> Unit,
    onOpenWorkspace: () -> Unit,
    workspaceEnabled: Boolean,
    /** False while no session is open, where there is no history to pick from yet. */
    historyEnabled: Boolean,
    /**
     * Whether a session is open. With none (a fresh install, an empty harness, everything archived)
     * there is no model to name yet, so the chip is left out rather than shimmering as if one were on
     * its way.
     */
    sessionOpen: Boolean = true,
    /**
     * False when the open session is a subagent child: its model belongs to the parent, and the
     * host refuses a switch from here, so the chip states the model instead of offering a picker.
     */
    modelsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Column(modifier.fillMaxWidth().background(colors.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = DsSpacing.tiny, end = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            DsIconButton(
                icon = FeatherIcons.Menu,
                contentDescription = stringResource(R.string.chatlist_open),
                onClick = onOpenDrawer,
                tint = colors.labelSecondary,
                iconSize = 20.dp,
            )
            // The only elastic item in the row is this empty box: it takes what the icons leave, and
            // the chip inside wraps its text, so the bordered chip is exactly as wide as what it
            // says. With the run-status pill down on the title row, the whole width is available to a
            // long model name plus its reasoning tier.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (sessionOpen) {
                    ModelChip(
                        models = models,
                        onClick = { haptics.tap(); onOpenModels() },
                        switchable = modelsEnabled,
                        modifier = Modifier.widthIn(max = MODEL_CHIP_MAX_WIDTH),
                    )
                }
            }
            if (historyEnabled) {
                DsIconButton(
                    icon = FeatherIcons.List,
                    contentDescription = stringResource(R.string.chat_history_title),
                    onClick = onOpenHistory,
                    tint = colors.labelSecondary,
                    iconSize = 18.dp,
                )
            }
            if (!detailsOpen) {
                DsIconButton(
                    icon = FeatherIcons.Info,
                    contentDescription = stringResource(R.string.chat_details_title),
                    onClick = onOpenDetails,
                    tint = colors.labelTertiary,
                    iconSize = 18.dp,
                )
            }
        }

        // The live state shares the title row: the model chip above needs the width more, and the
        // title is already elastic, so "Working" / "Needs you" reads beside what is being worked on.
        val hasTitleRow = title.isNotBlank() || agentPresetLabel != null || subagentCount > 0 ||
            running || needsYou
        if (hasTitleRow) {
            // The inline triggers are drawn 28dp but carry a 48dp touch target, which makes this row
            // 48dp tall whenever one is present; the padding that spaced a bare title is dropped then
            // so the row does not also pay for the margin on top of the target.
            val hasTrigger = agentPresetLabel != null || subagentCount > 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DsSpacing.large,
                        end = DsSpacing.xsmall,
                        top = if (hasTrigger) 0.dp else 2.dp,
                        bottom = if (hasTrigger) 0.dp else 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    title,
                    style = DsType.base16Strong,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Idle is the absence of news, so it draws nothing here; running and needs-you keep
                // their mascot pill. (An idle dot in the model row used to sit where the model name
                // needed the space.)
                if (running || needsYou) {
                    RunStatusPill(running = running, needsYou = needsYou)
                }
                if (agentPresetLabel != null) {
                    MetaChip(label = agentPresetLabel, onClick = { haptics.tap(); onOpenPresets() })
                }
                if (subagentCount > 0) {
                    SubagentTag(count = subagentCount, onClick = { haptics.tap(); onOpenSubagents() })
                }
            }
        }

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
        needsYou -> stringResource(R.string.chatlist_needs_action)
        running -> stringResource(R.string.chat_status_running)
        else -> stringResource(R.string.chat_status_idle)
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

/**
 * The model chip: display names, not wire ids.
 *
 * `session.models` returns `deepseek-official / deepseek-v4-pro / max`, which is not what anyone
 * calls it — the catalog's own names resolve that to `DeepSeek-V4-Pro High`. Drawn as a filled
 * rounded pill so a touchscreen can see it is a control without a hover state to learn it from.
 * [switchable] is false on a subagent child, whose model the parent owns: the label stays and the
 * caret goes, because a picker there could only be refused.
 */
@Composable
private fun ModelChip(
    models: SessionModelsValue?,
    onClick: () -> Unit,
    switchable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    if (models == null) {
        // Loading, with a session on its way: a shimmer the width of a typical chip.
        Box(
            modifier
                .padding(horizontal = DsSpacing.small)
                .width(MODEL_CHIP_SKELETON_WIDTH)
                .height(DsSizes.button)
                .skeleton(colors.bgLayer2, colors.hover, DsShapes.control),
        )
        return
    }
    val current = models.current
    val group = models.groups.firstOrNull { it.id == current.provider }
    val model = group?.models?.firstOrNull { it.id == current.model }
    // A selection without an effort runs on the adapter's default; the chip names that default
    // rather than leaving a reasoning model looking tierless (the same rule the host's picker uses).
    val effectiveEffort = current.reasoningEffort ?: model?.reasoning?.defaultEffort
    val effort = model?.reasoning?.efforts?.firstOrNull { it.id == effectiveEffort }
    val modelLabel = model?.name ?: technicalDisplay(current.model)

    val interaction = remember { MutableInteractionSource() }
    val press = if (switchable) Modifier.pressScale(interaction) else Modifier
    val tap = if (switchable) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .then(press)
            .heightIn(min = DsSizes.button)
            .clip(DsShapes.control)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.control)
            .then(tap)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        if (!models.routable) {
            StateDot(StateDotState.Warning, size = 6.dp)
        }
        Text(
            modelLabel,
            style = DsType.std14Strong,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The model name is the one elastic label in the chip: the tier ("High") and the
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
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A small inline trigger — the agent preset, while the session is still blank enough to pick one. */
@Composable
private fun MetaChip(label: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    // Drawn 28dp, a 48dp target around it: the chip stays slim, the thumb still has somewhere to land.
    Row(
        modifier = Modifier
            .widthIn(max = MODEL_CHIP_MAX_WIDTH)
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
    val label = stringResource(R.string.chatlist_subagents)
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
        Icon(Icons.Outlined.Groups, contentDescription = null, tint = colors.accent, modifier = Modifier.size(15.dp))
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatTabButton(
                    label = stringResource(R.string.chat_tab),
                    selected = tab == ChatTab.Chat,
                    onClick = { onTabChange(ChatTab.Chat) },
                )
                ChatTabButton(
                    label = stringResource(R.string.trajectory_title),
                    selected = tab == ChatTab.Trajectory,
                    onClick = { onTabChange(ChatTab.Trajectory) },
                )
                ChatTabButton(
                    label = stringResource(R.string.ink_chat_files_tab, fileCount),
                    selected = tab == ChatTab.Files,
                    onClick = { onTabChange(ChatTab.Files) },
                )
            }
        }
        DsIconButton(
            icon = FeatherIcons.Folder,
            contentDescription = stringResource(R.string.panel_workspace),
            onClick = onOpenWorkspace,
            tint = if (workspaceEnabled) colors.labelSecondary else colors.labelDimmed,
            iconSize = 20.dp,
            enabled = workspaceEnabled,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.borderL1),
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
