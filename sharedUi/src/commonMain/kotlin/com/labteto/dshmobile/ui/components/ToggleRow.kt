package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The Ink switch: a 50×30 track with a 24dp knob that springs across 20dp when it flips.
 *
 * Material's switch is 52×32 with a ringed 20dp thumb and animates differently; the kit's `.sw` is
 * flatter and rounder, and in a settings list full of switches the difference is the whole look of
 * the screen. Semantics stay [Role.Switch] with the whole row as the target, as before.
 */
@Composable
fun DsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> colors.bgLayer2
            checked -> colors.accent
            else -> colors.bgLayer3
        },
        animationSpec = DsAnimationsToggle.color,
        label = "switchTrack",
    )
    val knob by animateColorAsState(
        targetValue = when {
            !enabled -> colors.labelDimmed
            checked -> colors.onAccent
            else -> colors.labelTertiary
        },
        animationSpec = DsAnimationsToggle.color,
        label = "switchKnob",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = DsAnimationsToggle.thumb,
        label = "switchOffset",
    )
    Box(
        modifier = modifier
            .width(50.dp)
            .height(30.dp)
            .clip(DsShapes.pillFull)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = interaction,
                        indication = null,
                        onValueChange = {
                            haptics.toggle(it)
                            onCheckedChange(it)
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = offset)
                .size(24.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

private object DsAnimationsToggle {
    /** Knob travel — the kit's `cubic-bezier(.3,1.4,.5,1)` overshoot, expressed as a spring. */
    val thumb = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = 0.72f,
        stiffness = 900f,
    )
    val color = androidx.compose.animation.core.tween<androidx.compose.ui.graphics.Color>(200)
}

/**
 * A labelled on/off row: name (with optional [hint]) on the left, [DsSwitch] on the right.
 *
 * The whole row is the tap target, not just the switch — a thumb at the far edge of a phone is a
 * poor thing to have to hit, and the label is what people aim at anyway.
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onChange: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.toggle(!checked)
                    onChange()
                },
            )
            .padding(vertical = DsSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = DsType.rowTitle,
                color = if (enabled) colors.labelPrimary else colors.labelTertiary,
            )
            if (hint != null) {
                Text(hint, style = DsType.small13, color = colors.labelTertiary)
            }
        }
        DsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
