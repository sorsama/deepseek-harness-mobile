package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/** How a disclosure row's subject is doing; drives the leading slot and the title shimmer. */
enum class DisclosureState { Idle, Running, Error, Stopped }

/**
 * 24dp disclosure row: chevron, leading icon, title, a 2x2-dot separator, an ellipsized summary,
 * and a content slot revealed when expanded.
 *
 * The chevron and the icon are separate slots rather than a hover crossfade. The web client can
 * afford to hide the icon behind a chevron on hover; a phone has no hover, so one of the two would
 * simply never be seen — and a transcript of rows that all begin with the same chevron gives the
 * reader nothing to scan by. [state] substitutes a state dot for the icon when the subject failed
 * or was interrupted, which is the harness's own rule: the terminal semantic outranks the glyph,
 * and a running row keeps its icon because the title shimmer already carries the signal.
 */
@Composable
fun DisclosureRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    state: DisclosureState = DisclosureState.Idle,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Opens the row's [HoldMenu], on a row that toggles or on one with nothing to open. */
    onHold: (() -> Unit)? = null,
    holdLabel: String = "",
    /** How many lines the summary may take; a row that is open can afford more than one. */
    summaryMaxLines: Int = 1,
    /** A short caption at the end of the row, such as how long the step took. */
    trailingText: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val running = state == DisclosureState.Running
    val toggle: () -> Unit = {
        haptics.tap()
        onToggle?.invoke()
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .then(
                    if (onHold != null) {
                        // A row with nothing to open (a header-only tool card) still has its hold
                        // menu; a tap on it does nothing rather than toggling an empty body.
                        Modifier
                            .hoverable(interaction)
                            .clickableWithHold(
                                holdLabel = holdLabel,
                                onHold = onHold,
                                role = Role.Button,
                                interactionSource = interaction,
                                onClick = if (onToggle != null) toggle else ({}),
                            )
                    } else if (onToggle != null) {
                        Modifier
                            .hoverable(interaction)
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Button,
                                onClick = toggle,
                            )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron is always visible when the row can expand. It used to be revealed by
            // hover, which never fires on a touchscreen — on a phone the affordance was invisible.
            if (onToggle != null) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = DsAnimations.chevron,
                    label = "chevron",
                )
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = if (hovered) colors.labelSecondary else colors.labelTertiary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
                Spacer(Modifier.width(4.dp))
            }
            // The leading slot: the terminal state outranks the glyph, a running row keeps it.
            when {
                // The dot is a picture: it is named for a screen reader as what it says.
                state == DisclosureState.Error -> {
                    val named = stringResource(R.string.chat_state_failed)
                    LeadingSlot(Modifier.semantics { contentDescription = named }) { StateDot(StateDotState.Error, size = 8.dp) }
                }
                state == DisclosureState.Stopped -> {
                    val named = stringResource(R.string.chat_state_stopped)
                    LeadingSlot(Modifier.semantics { contentDescription = named }) { StateDot(StateDotState.Warning, size = 8.dp) }
                }
                icon != null -> LeadingSlot {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // Rows with neither still need the indent, or a list of them fails to line up.
                onToggle == null -> Spacer(Modifier.size(16.dp))
                else -> Unit
            }
            Spacer(Modifier.width(8.dp))
            val titleModifier = if (running) Modifier.shimmer(runningBrush(colors)) else Modifier
            // With a summary, the title is capped at half the row and measured first, so the summary
            // gets everything the title does not use. Two weights of 1 gave the summary only half
            // however short the title was, and the figures it opens with ("2 of 5 done · 1 added")
            // were cut at large text sizes and in languages with long words.
            val shareWithSummary = if (summary != null) Modifier.atMostHalfWidth() else Modifier.weight(1f, fill = false)
            Text(
                title,
                style = DsType.std14,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = shareWithSummary.then(titleModifier),
            )
            if (summary != null) {
                Spacer(Modifier.width(6.dp))
                TwoByTwoDots(colors.labelDimmed)
                Spacer(Modifier.width(6.dp))
                Text(
                    summary,
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    maxLines = summaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (trailingText != null) {
                Spacer(Modifier.width(8.dp))
                Text(trailingText, style = DsType.caption11, color = colors.labelCaption, maxLines = 1)
            }
        }
        // Every disclosure in the app routes through here — tool cards, compaction, workflows,
        // archived sessions, the todo dock — so animating this one place animates all of them.
        AnimatedVisibility(
            visible = expanded && content != null,
            enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
            exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
        ) {
            Column { content?.invoke() }
        }
    }
}

/** At most half of the width the parent offers; narrower when the content is. */
private fun Modifier.atMostHalfWidth(): Modifier = layout { measurable, constraints ->
    val limit = if (constraints.hasBoundedWidth) constraints.maxWidth / 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(maxWidth = limit.coerceAtLeast(constraints.minWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Fixed 16dp box so the icon and the state dot occupy the same column. */
@Composable
private fun LeadingSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) { content() }
}

/** Glare band used for the running-state shimmer sweep. */
private fun runningBrush(colors: DsColors): Brush = Brush.linearGradient(
    colors = listOf(Color.Transparent, colors.labelPrimary.copy(alpha = 0.30f), Color.Transparent),
)

/** Tiny 2x2 dot grid used as a title/summary separator. */
@Composable
private fun TwoByTwoDots(color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(2) {
                    Box(Modifier.size(2.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DisclosureRowPreview() {
    DshTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DisclosureRow(
                title = "Bash",
                summary = "build",
                icon = FeatherIcons.Terminal,
                state = DisclosureState.Running,
                expanded = false,
                onToggle = {},
            )
            DisclosureRow(
                title = "Bash",
                summary = "exit 1",
                icon = FeatherIcons.Terminal,
                state = DisclosureState.Error,
                expanded = false,
                onToggle = {},
            )
            DisclosureRow(
                title = "Search",
                summary = "12 results",
                icon = FeatherIcons.Search,
                expanded = true,
                onToggle = {},
            ) {
                Text(
                    "Expanded body",
                    style = DsType.small13,
                    color = DsTheme.colors.labelTertiary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
