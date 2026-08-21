package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/** One choice in a [DsSegmented] track. */
data class DsSegment(val key: String, val label: String)

/**
 * A compact segmented control: a track of mutually exclusive choices, one of them live.
 *
 * The track matters as much as the thumb. A row of bare labels with the live one merely darker is
 * not a control — on a touchscreen there is no hover to reveal that any of it can be pressed, so it
 * reads as a caption. The enclosing track says "these are the options", and the accent-tinted thumb
 * says which one you have. The tab strip learned this the hard way: its first cut put a white chip
 * on a `#F1F3F5` track, a 3% difference, and leaned entirely on label darkness to carry the state.
 *
 * @param role how assistive tech should announce a segment — [Role.Tab] for a view switch,
 *   [Role.RadioButton] for a setting. The tint is the only visual carrier of the selection, so it
 *   has to reach the accessibility tree some other way.
 * @param stretch divide the track equally between the segments instead of letting each hug its
 *   label. Off by default, because most of these sit inline — in a top bar, beside a caption in a
 *   sheet — where a control the width of its content is the right shape. Turn it on when the track
 *   is given a width of its own: a full-width pill whose segments hug the left of it reads as a
 *   half-drawn control, not a compact one.
 */
@Composable
fun DsSegmented(
    segments: List<DsSegment>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .clip(DsShapes.pillFull)
            .background(colors.hoverSolid)
            // Outlined as well as filled, because the fill alone cannot be trusted to show. The
            // track's grey is a step off `bgLayer1`, but in dark mode it is the *same* colour as
            // `bgLayer2` — so on a sheet the fill vanishes and the control collapses back into the
            // row of bare words this component exists to stop being.
            .border(1.dp, colors.borderL2, DsShapes.pillFull)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            DsSegment(
                label = segment.label,
                selected = segment.key == selectedKey,
                enabled = enabled,
                role = role,
                onClick = { onSelect(segment.key) },
                // A stretched track is a primary control rather than an inline one, so its segments
                // get a button's height. 24dp is a comfortable inline chip and an uncomfortably
                // small thing to hit when it is the first decision on a screen.
                minHeight = if (stretch) 36.dp else 24.dp,
                modifier = Modifier.weight(1f, fill = stretch),
            )
        }
    }
}

@Composable
private fun DsSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = DsAnimations.tabSwap,
        label = "segment",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(DsShapes.pillFull)
            .background(colors.accentTertiary.copy(alpha = emphasis))
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
    ) {
        Text(
            label,
            style = DsType.tabText,
            color = when {
                !enabled -> colors.labelDimmed
                selected -> colors.accent
                else -> colors.labelTertiary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsSegmentedPreview() {
    DshTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DsSegmented(
                segments = listOf(
                    DsSegment("off", "Off"),
                    DsSegment("low", "Low"),
                    DsSegment("high", "High"),
                    DsSegment("max", "Max"),
                ),
                selectedKey = "high",
                onSelect = {},
            )
            // The two shapes side by side: inline hugs its labels, stretched divides the track.
            DsSegmented(
                segments = listOf(
                    DsSegment("lan", "Local network"),
                    DsSegment("relay", "Relay"),
                ),
                selectedKey = "relay",
                onSelect = {},
                stretch = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
