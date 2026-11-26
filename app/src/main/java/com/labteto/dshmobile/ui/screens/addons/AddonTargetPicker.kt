package com.labteto.dshmobile.ui.screens.addons

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.handCursor
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * One choice of the Add-ons picker. [short] is the label in the side-by-side row ("Phone");
 * [long] is its full meaning ("Phone only"), read out by a screen reader and used when the choices
 * are stacked and a label has the whole width.
 */
internal data class AddonPickerOption(val key: String, val short: String, val long: String)

private val SegmentPadding = DsSpacing.small
private val TrackPadding = 3.dp
private val TrackBorder = 1.dp
private val SegmentGap = 2.dp

/**
 * The choice for one add-on, in the look of the app's segmented control.
 *
 * The labels are short, so the row stays one line at the default font size on a phone. Each
 * segment takes the room its label needs, and when the labels with their padding do not all fit on
 * one line (the largest font size, a long translation) the choices stack as a list instead, each
 * as wide as the card: a label can then wrap, and is never cut off or ended with an ellipsis. The
 * row's own text never wraps, so the measured fit is the real one.
 *
 * Every choice is announced with its [AddonPickerOption.long] meaning and as a radio button of the
 * group named [groupLabel].
 */
@Composable
internal fun AddonTargetPicker(
    options: List<AddonPickerOption>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    groupLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Measured with the label's own style at the user's font size, so a bigger font fails the fit.
        val widths = remember(options, density, measurer) {
            options.map { measurer.measure(it.short, DsType.tabText, maxLines = 1, softWrap = false).size.width }
        }
        val padding = with(density) { SegmentPadding.roundToPx() }
        val rowFits = with(density) {
            addonPickerFitsOneLine(
                labelWidths = widths,
                available = constraints.maxWidth,
                segmentPadding = padding,
                gap = SegmentGap.roundToPx(),
                chrome = ((TrackPadding + TrackBorder) * 2).roundToPx(),
            )
        }
        val select: (AddonPickerOption) -> Unit = { option ->
            if (option.key != selectedKey) {
                haptics.selection()
                onSelect(option.key)
            }
        }
        val track = Modifier
            .fillMaxWidth()
            .clip(DsShapes.control)
            .background(colors.bgLayer1)
            .border(TrackBorder, colors.borderL2, DsShapes.control)
            .padding(TrackPadding)
            .selectableGroup()
            .semantics { contentDescription = groupLabel }
        if (rowFits) {
            Row(track, horizontalArrangement = Arrangement.spacedBy(SegmentGap)) {
                options.forEachIndexed { index, option ->
                    AddonChoice(
                        label = option.short,
                        description = option.long,
                        selected = option.key == selectedKey,
                        enabled = enabled,
                        onClick = { select(option) },
                        singleLine = true,
                        // Room in proportion to the label, so "Both" and "Off" do not take as much as "Desktop".
                        modifier = Modifier.weight((widths[index] + 2 * padding).toFloat()),
                    )
                }
            }
        } else {
            Column(track, verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                options.forEach { option ->
                    AddonChoice(
                        label = option.long,
                        description = option.long,
                        selected = option.key == selectedKey,
                        enabled = enabled,
                        onClick = { select(option) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonChoice(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val fill by animateColorAsState(
        targetValue = if (selected) colors.brandPrimary else Color.Transparent,
        animationSpec = DsAnimations.tabSwapColor,
        label = "addonChoiceFill",
    )
    val text by animateColorAsState(
        targetValue = when {
            !enabled -> colors.labelDimmed
            selected -> colors.onBrandPrimary
            else -> colors.labelSecondary
        },
        animationSpec = DsAnimations.tabSwapColor,
        label = "addonChoiceText",
    )
    Box(
        contentAlignment = if (singleLine) Alignment.Center else Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = if (singleLine) 40.dp else DsSpacing.touchTarget)
            .clip(DsShapes.segment)
            .background(fill)
            .handCursor(enabled)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (singleLine) SegmentPadding else DsSpacing.medium, vertical = DsSpacing.tiny),
    ) {
        // The full meaning rides on the label, which the selectable box merges: a separate semantics
        // modifier on the box itself took the selected state and the click action off the node.
        Text(
            label,
            style = DsType.tabText,
            color = text,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}
