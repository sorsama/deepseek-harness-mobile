package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** One choice in a [DsSegmented] track. */
data class DsSegment(val key: String, val label: String)

/** Time between the ticks of a thumb sliding over several segments. Slower than the throttle's fast zone. */
private const val SEGMENT_STEP_MS = 60L

/** How far a scrolling track fades into [DsSegmented]'s `scrollFadeColor` at an end that has more. */
private val ScrollFadeWidth = 28.dp

/**
 * A compact segmented control, restyled to the Ink kit's `.seg`.
 *
 * The track is a 12dp-radius plate (`bgLayer1` + hairline) and the selected segment is the
 * kit's inverted pill: white on ink, ink on paper. One thumb slides between segments rather than
 * each segment cross-fading, which is what tells the eye they are the same control.
 *
 * @param role how assistive tech should announce a segment — [Role.Tab] for a view switch,
 *   [Role.RadioButton] for a setting.
 * @param stretch divide the track equally between the segments instead of letting each hug its
 *   label. Turn it on when the track is given a width of its own.
 * @param feedback play the selection haptics (a tick, or a run of ticks when the thumb passes
 *   several segments). Turn it off when the caller plays its own.
 * @param scrollFadeColor for a track that must never break a label or shrink it to a letter (a
 *   view switch with four words at a large text size). Every label keeps its natural width on one
 *   line: when the segments all fit the width the track is given it looks exactly like a
 *   [stretch]ed one, and when they do not the track scrolls sideways, fading into this colour (the
 *   colour behind the track) at the end that has more, and the selected segment is kept in view.
 *   Null keeps the usual behaviour. A scrolling track always divides its width equally.
 */
@Composable
fun DsSegmented(
    segments: List<DsSegment>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: androidx.compose.ui.semantics.Role = androidx.compose.ui.semantics.Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
    minSegmentHeight: Dp? = null,
    maxLabelLines: Int = 1,
    feedback: Boolean = true,
    scrollFadeColor: Color? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val ticker = remember(haptics) { haptics.ticker() }
    val scope = rememberCoroutineScope()
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val target = selectedKey?.let { bounds[it] }
    val thumbX = remember { Animatable(0f) }
    val thumbWidth = remember { Animatable(0f) }
    val scrolls = scrollFadeColor != null
    val equalWidths = stretch || scrolls
    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        if (thumbWidth.value == 0f) {
            thumbX.snapTo(to.left)
            thumbWidth.snapTo(to.width)
        } else {
            val spec = spring<Float>(dampingRatio = 0.85f, stiffness = 520f)
            launch { thumbX.animateTo(to.left, spec) }
            launch { thumbWidth.animateTo(to.width, spec) }
        }
    }
    val track: @Composable (Modifier) -> Unit = { outer ->
        Row(
            modifier = outer
                .clip(DsShapes.control)
                .background(colors.bgLayer1)
                .border(1.dp, colors.borderL2, DsShapes.control)
                .padding(3.dp)
                .drawBehind {
                    val placed = target ?: return@drawBehind
                    if (thumbWidth.value <= 0f) return@drawBehind
                    drawRoundRect(
                        color = colors.brandPrimary,
                        topLeft = Offset(thumbX.value, placed.top),
                        size = Size(thumbWidth.value, placed.height),
                        cornerRadius = CornerRadius(DsShapes.SegmentRadius.toPx()),
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEach { segment ->
                DsSegmentItem(
                    label = segment.label,
                    selected = segment.key == selectedKey,
                    enabled = enabled,
                    role = role,
                    onClick = {
                        if (segment.key == selectedKey) return@DsSegmentItem
                        val from = segments.indexOfFirst { it.key == selectedKey }
                        val distance = if (from < 0) 1 else abs(segments.indexOf(segment) - from)
                        if (feedback && distance < 2) {
                            haptics.selection()
                        } else if (feedback) {
                            // The thumb is about to slide over the segments between: one tick for
                            // each, spaced like the slide, and a firmer one where it lands.
                            scope.launch {
                                ticker.reset()
                                repeat(distance) {
                                    ticker.cross()
                                    delay(SEGMENT_STEP_MS)
                                }
                                ticker.settle()
                            }
                        }
                        onSelect(segment.key)
                    },
                    // Item + 2 x 3dp track padding + 2 x 1dp border: a stretched rail comes to the 48dp field
                    // height, a hugging one to the 44dp button height, so a rail beside an input or a
                    // button shares its height.
                    minHeight = minSegmentHeight ?: if (equalWidths) 40.dp else 36.dp,
                    maxLabelLines = maxLabelLines,
                    keepInView = scrolls,
                    modifier = Modifier
                        .weight(1f, fill = equalWidths)
                        .onPlaced { bounds[segment.key] = Rect(it.positionInParent(), it.size.toSize()) },
                )
            }
        }
    }
    if (scrollFadeColor == null) {
        track(modifier)
    } else {
        val scroll = rememberScrollState()
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        BoxWithConstraints(modifier.scrollEdgeFades(scroll, scrollFadeColor, rtl)) {
            val viewport = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            track(
                Modifier
                    .horizontalScroll(scroll)
                    .layout { measurable, c ->
                        // The scroll container offers unlimited width. The track takes what all the
                        // labels need on one line, or the width it is given when that is more, so it
                        // is either exactly as wide as its space or scrolls inside it.
                        val natural = measurable.maxIntrinsicWidth(c.maxHeight)
                        val width = max(natural, viewport)
                        val placeable = measurable.measure(
                            Constraints(minWidth = width, maxWidth = width, minHeight = c.minHeight, maxHeight = c.maxHeight),
                        )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }
    }
}

/** A fade into [color] over the end of the box that has more to scroll to; the reading end is the left in RTL. */
private fun Modifier.scrollEdgeFades(scroll: ScrollState, color: Color, rtl: Boolean): Modifier = drawWithContent {
    drawContent()
    val fade = ScrollFadeWidth.toPx().coerceAtMost(size.width / 2f)
    val moreOnRight = if (rtl) scroll.canScrollBackward else scroll.canScrollForward
    val moreOnLeft = if (rtl) scroll.canScrollForward else scroll.canScrollBackward
    if (moreOnRight) drawFade(size.width - fade, fade, color, opaqueOnRight = true)
    if (moreOnLeft) drawFade(0f, fade, color, opaqueOnRight = false)
}

private fun DrawScope.drawFade(left: Float, width: Float, color: Color, opaqueOnRight: Boolean) {
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (opaqueOnRight) listOf(Color.Transparent, color) else listOf(color, Color.Transparent),
            startX = left,
            endX = left + width,
        ),
        topLeft = Offset(left, 0f),
        size = Size(width, size.height),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DsSegmentItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    role: androidx.compose.ui.semantics.Role,
    onClick: () -> Unit,
    minHeight: Dp,
    maxLabelLines: Int,
    keepInView: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    // The fill itself is the track's sliding thumb; a segment only tints its label.
    val labelColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.labelDimmed
            selected -> colors.onBrandPrimary
            else -> colors.labelSecondary
        },
        animationSpec = DsAnimations.tabSwapColor,
        label = "segmentLabel",
    )
    val requester = remember { BringIntoViewRequester() }
    if (keepInView) {
        // A scrolling track: the chosen segment (also one chosen from outside, such as a link that
        // opens the last tab) is brought on screen once it has been laid out.
        LaunchedEffect(selected) {
            if (selected) {
                withFrameNanos { }
                requester.bringIntoView()
            }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (keepInView) Modifier.bringIntoViewRequester(requester) else Modifier)
            .heightIn(min = minHeight)
            .clip(DsShapes.segment)
            .handCursor(enabled)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
    ) {
        Text(
            label,
            style = DsType.tabText,
            color = labelColor,
            maxLines = maxLabelLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun DsSegmentedPreview() {
    DshTheme(customization = com.labteto.dshmobile.ui.theme.ThemeCustomization.Default.copy(preference = com.labteto.dshmobile.ui.theme.ThemePreference.DARK)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DsSegmented(
                segments = listOf(DsSegment("off", "Off"), DsSegment("high", "High")),
                selectedKey = "high",
                onSelect = {},
            )
            DsSegmented(
                segments = listOf(DsSegment("lan", "Local network"), DsSegment("relay", "Relay")),
                selectedKey = "relay",
                onSelect = {},
                stretch = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
