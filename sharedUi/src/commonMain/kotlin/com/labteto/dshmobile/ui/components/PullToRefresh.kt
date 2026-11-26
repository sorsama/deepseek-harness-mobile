package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Where one pull-to-refresh is in its life.
 *
 * The pull itself is a gesture, not a phase: the distance fraction tells that story. These are the
 * states the drawer owns, so the indicator knows which micro-animation to play.
 */
enum class PullRefreshPhase {
    /** Nothing happening: the list is just a list. */
    Idle,

    /** A read is in flight (and stays visible for a moment even when it was fast). */
    Refreshing,

    /** The read landed: the check is drawn, then the indicator slides away. */
    Done,

    /** The read failed: the cross is drawn, then the indicator slides away. */
    Failed,
}

/**
 * The two halves of a pull-to-refresh, made in one place so a screen only wires them up.
 *
 * [modifier] goes on the scrollable that should reload when pulled; [PullToRefreshIndicator] is
 * drawn as a sibling above that scrollable. They share the M3 state that carries the pull
 * distance, which is why they cannot be created independently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class PullToRefresh internal constructor(
    internal val state: PullToRefreshState,
    /** Apply to the scrollable that refreshes when pulled down at its top. */
    val modifier: Modifier,
    /**
     * Apply to the scrollable's content so it makes room for the indicator as it arrives.
     *
     * The list slides down with the pull and the pill fills the gap above it, which is what keeps
     * the indicator off the first row. Nothing recomposes: the offset is read in the placement
     * phase, so a drag costs a relayout of one node, not a redraw of the drawer.
     */
    val followModifier: Modifier,
)

/**
 * Pull-to-refresh for a scrolling surface, in the Ink kit's own language rather than Material's.
 *
 * A downward drag at the top of the scrollable fills a small ring; past the latch the ring closes,
 * haptics tick, and the release starts a read. While the read runs the ring becomes a spinner. When
 * it lands the spinner is replaced by a check (or a cross) that pops in with a touch of overshoot,
 * then the whole pill springs back out of the top of the list. That last beat is the part that says
 * "done" without a string or a banner.
 *
 * The drag itself is Material 3's [pullToRefresh] modifier, which owns the nested-scroll plumbing,
 * the latch arithmetic and the settle springs; everything drawn is this file's. The caller owns
 * [phase] because it also owns the read and the haptic vocabulary.
 *
 * The indicator is deliberately not a Material spinner: the drawer's type, colour and corners are
 * the kit's, and a stock spinner in the middle of the Sessions screen would be the one borrowed
 * shape on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberPullToRefresh(
    phase: PullRefreshPhase,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
): PullToRefresh {
    val state = rememberPullToRefreshState()
    val haptics = rememberDsHaptics()
    val density = LocalDensity.current
    val phaseNow by rememberUpdatedState(phase)
    val enabledNow by rememberUpdatedState(enabled)
    val refreshNow by rememberUpdatedState(onRefresh)

    // The latch is felt before it is read: one tick as the ring closes under the finger. Pulling
    // while a read is already running (or while a row is held) must not tick.
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }
            .distinctUntilChanged()
            .collect { latched ->
                if (latched && enabledNow && phaseNow == PullRefreshPhase.Idle) haptics.tick()
            }
    }

    return PullToRefresh(
        state = state,
        modifier = Modifier.pullToRefresh(
            isRefreshing = phase != PullRefreshPhase.Idle,
            state = state,
            enabled = enabled,
            threshold = PullToRefreshDefaults.PositionalThreshold,
            onRefresh = { refreshNow() },
        ),
        followModifier = Modifier.offset {
            val travel = with(density) { FollowDistance.toPx() } * state.distanceFraction.coerceIn(0f, 1f)
            IntOffset(0, travel.roundToInt())
        },
    )
}

/**
 * The floating pill that follows the finger, drawn above the scrollable's top edge.
 *
 * Place it in the same box as the scrollable, aligned to the top centre and after the scrollable
 * so it paints on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshIndicator(
    pull: PullToRefresh,
    phase: PullRefreshPhase,
    modifier: Modifier = Modifier,
) {
    RefreshPill(
        state = pull.state,
        phase = phase,
        modifier = modifier,
    )
}

/** The pill is drawn at 40dp (between the kit's 36dp chip and its 44dp button). */
private val IndicatorSize = 40.dp

/** The gap the pill leaves under the chips when it is fully seated. */
private val IndicatorSeat = 10.dp

/** How far the list slides down to make room: the pill plus the gap above it. */
private val FollowDistance = IndicatorSize + IndicatorSeat

/** The ring, spinner and glyphs are drawn in a 20dp square inside the pill. */
private val GlyphSize = 20.dp

/** Ring stroke: a hairline at this size, the same weight as the kit's `borderL3`. */
private val GlyphStroke = 2.2.dp

/**
 * The pill that follows the finger.
 *
 * It lives above the list's top edge and is translated down by the pull, so the first thing a
 * person sees is it arriving under their thumb — not a spinner that was already there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshPill(
    state: PullToRefreshState,
    phase: PullRefreshPhase,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    // M3 lets the fraction run past 1 while the finger keeps going; the drawing never should.
    val fraction = state.distanceFraction.coerceIn(0f, 1f)
    val latched = fraction >= 1f

    val glyphColor by animateColorAsState(
        targetValue = when {
            phase == PullRefreshPhase.Done -> colors.success
            phase == PullRefreshPhase.Failed -> colors.error
            else -> colors.accent
        },
        animationSpec = tween(160, easing = DsAnimations.inkStandard),
        label = "pullRefreshGlyphColor",
    )
    // The ring is in the accent throughout, so a pull reads as something that can act from the
    // first few pixels. Its border brightens at the latch, which is the visual half of the tick.
    val pillBorder by animateColorAsState(
        targetValue = if (latched || phase != PullRefreshPhase.Idle) colors.accentBorder else colors.borderL3,
        animationSpec = tween(160, easing = DsAnimations.inkStandard),
        label = "pullRefreshBorder",
    )

    // A one-shot scale for the glyph when the read lands: shrink, then overshoot back to 1.
    val pop = remember { Animatable(1f) }
    // The check/cross is drawn on, stroke-first, rather than faded in: it reads as a glyph being
    // written, which is the smallest possible "done" animation.
    val write = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            PullRefreshPhase.Done, PullRefreshPhase.Failed -> {
                pop.snapTo(0.55f)
                write.snapTo(0f)
                launch {
                    pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                }
                launch { write.animateTo(1f, tween(260, easing = DsAnimations.inkStandard)) }
            }
            else -> {
                pop.snapTo(1f)
                write.snapTo(0f)
            }
        }
    }

    Box(
        modifier
            .graphicsLayer {
                // Fraction 0 puts the pill wholly above the list's top edge; fraction 1 seats it in
                // the gap under the chips, so the indicator ends where a resting spinner would sit
                // rather than wherever the threshold happens to be.
                val seat = IndicatorSeat.toPx()
                translationY = -size.height + (size.height + seat) * fraction
                alpha = fraction
                val grow = 0.6f + 0.4f * fraction
                scaleX = grow
                scaleY = grow
            }
            // Decorative: the pull is not a control a screen reader can operate. The drawer offers
            // the same read as an accessibility action and in its overflow menu.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(IndicatorSize)
                .shadow(8.dp, CircleShape)
                .background(colors.bgLayer3, CircleShape)
                .border(1.dp, pillBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(GlyphSize).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                when (phase) {
                    PullRefreshPhase.Refreshing -> RefreshSpinner(glyphColor)
                    PullRefreshPhase.Done -> ProgressGlyph(
                        progress = write.value,
                        color = glyphColor,
                        build = ::checkPath,
                    )
                    PullRefreshPhase.Failed -> ProgressGlyph(
                        progress = write.value,
                        color = glyphColor,
                        build = ::crossPath,
                    )
                    PullRefreshPhase.Idle -> PullRing(glyphColor, fraction)
                }
            }
        }
    }
}

/**
 * The ring that fills as the list is pulled: a sliver at rest, a closed circle at the latch.
 *
 * The start angle drifts as well, so the ring reads as winding up rather than merely growing.
 */
@Composable
private fun PullRing(color: Color, fraction: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val ring = ringStroke()
        drawArc(
            color = color,
            startAngle = -90f + 150f * fraction,
            sweepAngle = 36f + 324f * fraction,
            useCenter = false,
            topLeft = ringTopLeft(ring),
            size = ringSize(ring),
            style = ring,
        )
    }
}

/**
 * The spinner: a faint track with one bright arc orbiting it.
 *
 * It is an infinite animation on purpose, and allowed by the house rule — it runs only while a
 * read is genuinely in flight, and stops the moment the check replaces it.
 */
@Composable
private fun RefreshSpinner(color: Color) {
    val spin = rememberInfiniteTransition(label = "pullRefreshSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "pullRefreshSpinAngle",
    )
    Canvas(Modifier.fillMaxSize()) {
        val ring = ringStroke()
        drawArc(
            color = color.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = ringTopLeft(ring),
            size = ringSize(ring),
            style = ring,
        )
        drawArc(
            color = color,
            startAngle = -90f + angle,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = ringTopLeft(ring),
            size = ringSize(ring),
            style = ring,
        )
    }
}

/** Draws [build]'s path up to [progress] of its length, so a check is written rather than popped. */
@Composable
private fun ProgressGlyph(progress: Float, color: Color, build: (Size) -> Path) {
    Canvas(Modifier.fillMaxSize()) {
        if (progress <= 0f) return@Canvas
        val measure = PathMeasure().apply { setPath(build(size), false) }
        if (measure.length <= 0f) return@Canvas
        val drawn = Path()
        measure.getSegment(0f, measure.length * progress, drawn, true)
        drawPath(
            drawn,
            color = color,
            style = Stroke(
                width = GlyphStroke.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** The success tick, authored in the glyph box's own coordinates. */
private fun checkPath(size: Size): Path = Path().apply {
    moveTo(size.width * 0.14f, size.height * 0.52f)
    lineTo(size.width * 0.40f, size.height * 0.78f)
    lineTo(size.width * 0.88f, size.height * 0.24f)
}

/** The failure cross, authored in the glyph box's own coordinates. */
private fun crossPath(size: Size): Path = Path().apply {
    moveTo(size.width * 0.24f, size.height * 0.24f)
    lineTo(size.width * 0.76f, size.height * 0.76f)
    moveTo(size.width * 0.76f, size.height * 0.24f)
    lineTo(size.width * 0.24f, size.height * 0.76f)
}

private fun DrawScope.ringStroke(): Stroke = Stroke(width = GlyphStroke.toPx(), cap = StrokeCap.Round)

/** The draw box of a ring, inset by half its stroke so the stroke lands inside the glyph box. */
private fun DrawScope.ringSize(stroke: Stroke): Size =
    Size(size.width - stroke.width, size.height - stroke.width)

private fun DrawScope.ringTopLeft(stroke: Stroke): Offset = Offset(stroke.width / 2f, stroke.width / 2f)
