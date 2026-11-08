package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.mascot_mark
import com.labteto.dshmobile.ui.theme.DsTheme
import org.jetbrains.compose.resources.painterResource

/**
 * What the mascot is doing, mirroring the design kit's `.s-idle .s-connecting .s-thinking
 * .s-streaming .s-needs .s-done .s-offline` states.
 *
 * The mascot is the app's only expressive surface: it floats while idle, pulses while working,
 * nudges when the agent needs a person, and goes flat when the harness is gone. Each state is a
 * different tint of the same mark, so it stays recognisable in every condition.
 */
enum class MascotState {
    /** Nothing running. A slow float, accent tinted. */
    Idle,

    /** A connection attempt is in flight. Dashed ring spinning, neutral tint. */
    Connecting,

    /** The agent is working (streaming, thinking). Accent tint, slow sway. */
    Working,

    /** Approval, question or plan review waiting: amber tint with an attention nudge. */
    NeedsYou,

    /** A turn finished: green tint with a short badge pop. */
    Done,

    /** Harness unreachable: desaturated, still. */
    Offline,
}

/**
 * The DSH mascot mark, tinted and animated for [state].
 *
 * Kept as one composable so the splash, connect, sessions, chat and empty states all draw the same
 * character at different sizes; the asset is a mask-like silhouette, so tinting it with the accent
 * (rather than shipping five PNGs) keeps it correct under every theme preset.
 */
@Composable
fun MascotMark(
    modifier: Modifier = Modifier,
    state: MascotState = MascotState.Idle,
    size: Dp = 48.dp,
) {
    val colors = DsTheme.colors
    val tint = when (state) {
        MascotState.Idle, MascotState.Working -> colors.accent
        MascotState.Connecting -> colors.labelTertiary
        MascotState.NeedsYou -> colors.warn
        MascotState.Done -> colors.success
        MascotState.Offline -> colors.labelDimmed
    }
    val transition = rememberInfiniteTransition(label = "mascot")
    // 3.6s float, 1.4s breathe, 1.6s nudge — the kit's own periods.
    val float by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Reverse),
        label = "float",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val sway by transition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val idleAlpha = when (state) {
        MascotState.Connecting -> breathe
        MascotState.Working -> 1f
        MascotState.Done -> 1f
        MascotState.Offline -> 0.55f
        MascotState.NeedsYou -> 1f
        MascotState.Idle -> 1f
    }
    val rotation = when (state) {
        MascotState.Working -> sway
        MascotState.Connecting -> 0f
        else -> 0f
    }
    val lift = when (state) {
        MascotState.Idle -> (float - 0.5f) * 3f
        else -> 0f
    }
    val ringRotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (state == MascotState.Connecting) {
            // Dashed ring around the mark: the kit's "still reaching the harness" state.
            Canvas(Modifier.matchParentSize()) {
                val stroke = Stroke(width = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
                drawArc(
                    color = tint.copy(alpha = 0.55f),
                    startAngle = ringRotation,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(stroke.width / 2f, stroke.width / 2f),
                    size = Size(this.size.width - stroke.width, this.size.height - stroke.width),
                    style = stroke,
                )
            }
        }
        Image(
            painter = mascotPainter(),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier
                .size(size * 0.82f)
                .alpha(idleAlpha)
                .rotate(rotation)
                .graphicsOffsetY(lift),
        )
    }
}

/** A translation-only offset for the mascot float, without pulling in layout. */
private fun Modifier.graphicsOffsetY(dy: Float): Modifier = graphicsLayer {
    translationY = dy.dp.toPx()
}

/**
 * The mascot's silhouette, untinted. For the places that draw it at their own size instead of through
 * [MascotMark]; the file is a shared resource (`composeResources/drawable/mascot_mark.png`), so both apps
 * draw the same pixels.
 */
@Composable
fun mascotPainter(): Painter = painterResource(Res.drawable.mascot_mark)
