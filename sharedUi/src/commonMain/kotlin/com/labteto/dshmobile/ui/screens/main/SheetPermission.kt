package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_cancel
import com.labteto.dshmobile.shared.resources.common_ok
import com.labteto.dshmobile.shared.resources.ink_panel_permission_full_desc
import com.labteto.dshmobile.shared.resources.ink_panel_permission_readonly_desc
import com.labteto.dshmobile.shared.resources.ink_panel_permission_workspace_desc
import com.labteto.dshmobile.shared.resources.permission_confirm_ack
import com.labteto.dshmobile.shared.resources.permission_confirm_body
import com.labteto.dshmobile.shared.resources.permission_confirm_title
import com.labteto.dshmobile.shared.resources.permission_custom_hint
import com.labteto.dshmobile.shared.resources.permission_preset
import com.labteto.dshmobile.shared.resources.permission_preset_auto_desc
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PresetOption
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.LocalDsSheetHide
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The permission-preset picker (design 14's permission block).
 *
 * Each option is a card with the glyph that names its reach, its own blurb, and — when it is the
 * one in force — the accent border and filled check. `custom` is a derived state, so it is never
 * offered as a target; the hint at the foot says why the current value matches no card.
 */
@Composable
fun PermissionMenu(
    select: PermissionSelect,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    DsBottomSheet(
        title = stringResource(Res.string.permission_preset),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        // A pick slides the sheet away first and acts when it is gone, so the confirmation dialog
        // or the switch never opens over a window that is still being torn down.
        val hide = LocalDsSheetHide.current
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            // The cards used to sit at zero offset from one another: three 24dp-radius plates butted
            // together read as one cramped slab, and the selected card's accent border ran straight
            // into its neighbours. A gap of its own gives each preset an edge to be seen against.
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            select.selectable.forEach { option ->
                PermissionCard(
                    option = option,
                    selected = option.value == current,
                    onSelect = {
                        haptics.selection()
                        // The preset in force: there is nothing to switch, so the pick just closes.
                        hide { if (option.value == current) onDismiss() else onPick(option.value) }
                    },
                )
            }
            if (select.currentValue !in select.selectable.map { it.value }) {
                Text(
                    stringResource(Res.string.permission_custom_hint),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                    modifier = Modifier.padding(top = DsSpacing.tiny),
                )
            }
        }
    }
}

/**
 * One preset as a card: reach glyph, name, and a one-line blurb. The app's own text for the two
 * presets it owns the wording of (see [permissionPresetDescriptionRes]), the harness's for the rest.
 */
@Composable
private fun PermissionCard(option: PresetOption, selected: Boolean, onSelect: () -> Unit) {
    val own = permissionPresetDescription(option.value, option.name)
    ChoiceCard(
        icon = permissionIcon(option.value),
        title = permissionPresetLabel(option.value, option.name),
        description = when {
            own != null -> own
            !option.description.isNullOrBlank() -> option.description.orEmpty()
            else -> permissionFallbackDescription(option.value)
        },
        selected = selected,
        warn = option.value == FULL_ACCESS_PRESET,
        onSelect = onSelect,
    )
}

/**
 * A radio card: glyph, title, one-line blurb, and the accent border and filled check when it is the
 * one chosen. The permission sheet's presets, and the built-in harness's command choice.
 *
 * @param warn the amber glyph and blurb of an option that removes a question (Full access).
 */
@Composable
fun ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    warn: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val fullAccess = warn
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentTertiary else colors.bgModulePlatform,
        animationSpec = DsAnimations.tabSwapColor,
        label = "permissionCardFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.borderL1,
        animationSpec = DsAnimations.tabSwapColor,
        label = "permissionCardStroke",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = fill,
        border = BorderStroke(1.dp, stroke),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.selected = selected }
                .clickable(role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 64.dp)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when {
                    fullAccess -> colors.warn
                    selected -> colors.accent
                    else -> colors.labelSecondary
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    style = DsType.small13,
                    color = if (fullAccess) colors.warnText else colors.labelTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DsSpacing.xsmall))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else colors.bgLayer2)
                    .border(1.dp, if (selected) colors.accent else colors.borderL3, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        DsIcons.Check,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** The glyph that names a preset's reach. Ids are the harness's; unknown ones fall back to a tool. */
internal fun permissionIcon(value: String): ImageVector = when (value) {
    FULL_ACCESS_PRESET -> FeatherIcons.AlertTriangle
    ASK_COMMANDS_PRESET -> FeatherIcons.CheckSquare
    "read-only", "read", "readonly" -> FeatherIcons.FileText
    "workspace-write", "workspace", "write" -> FeatherIcons.Edit3
    else -> FeatherIcons.Tool
}

/** A short blurb for a preset the harness did not describe itself. */
@Composable
internal fun permissionFallbackDescription(value: String): String = when (value) {
    "read-only", "read", "readonly" -> stringResource(Res.string.ink_panel_permission_readonly_desc)
    FULL_ACCESS_PRESET -> stringResource(Res.string.ink_panel_permission_full_desc)
    "auto" -> stringResource(Res.string.permission_preset_auto_desc)
    else -> stringResource(Res.string.ink_panel_permission_workspace_desc)
}

/**
 * Full access removes the approval prompt for every subsequent tool call, so it takes a deliberate
 * acknowledgement rather than a single tap — the same gate the desktop client puts on it.
 */
@Composable
fun FullAccessConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var acknowledged by remember { mutableStateOf(false) }
    DsDialog(
        title = stringResource(Res.string.permission_confirm_title),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        Text(
            stringResource(Res.string.permission_confirm_body),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable { acknowledged = !acknowledged }
                .padding(vertical = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = {
                    haptics.toggle(it)
                    acknowledged = it
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    uncheckedColor = colors.borderL3,
                    checkmarkColor = colors.onAccent,
                ),
            )
            Text(
                stringResource(Res.string.permission_confirm_ack),
                style = DsType.small13,
                color = colors.labelPrimary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(Res.string.common_ok),
                onClick = {
                    haptics.confirm()
                    onConfirm()
                },
                variant = DsButtonVariant.Danger,
                enabled = acknowledged,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(Res.string.common_cancel),
                onClick = {
                    haptics.reject()
                    onDismiss()
                },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
        }
    }
}


