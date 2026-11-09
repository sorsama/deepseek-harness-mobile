package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme

/** Harness tool run states. */
enum class StateDotState { Idle, Running, Done, Warning, Error }

/** Small status dot; [Running] shows a 3x3 pixel-matrix chase with discrete 1s steps. */
@Composable
fun StateDot(
    state: StateDotState,
    size: Dp = 8.dp,
) {
    val color = when (state) {
        StateDotState.Idle -> DsTheme.colors.labelCaption
        StateDotState.Running -> DsTheme.colors.accent
        StateDotState.Done -> DsTheme.colors.success
        StateDotState.Warning -> DsTheme.colors.warn
        StateDotState.Error -> DsTheme.colors.error
    }
    if (state == StateDotState.Running) {
        val transition = rememberInfiniteTransition(label = "stateDot")
        val chase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 9f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 9000
                    0f at 0
                    1f at 1000
                    2f at 2000
                    3f at 3000
                    4f at 4000
                    5f at 5000
                    6f at 6000
                    7f at 7000
                    8f at 8000
                    9f at 9000
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "chase",
        )
        Canvas(modifier = Modifier.size(size)) {
            drawHalo(color)
            drawPixelChase(color, chase.toInt().coerceIn(0, 8))
        }
    } else {
        Canvas(modifier = Modifier.size(size)) {
            drawHalo(color)
            drawCircle(color, radius = this.size.minDimension / 2f)
        }
    }
}

/** Soft 10%-alpha halo behind the core. */
private fun DrawScope.drawHalo(color: Color) {
    drawCircle(color.copy(alpha = 0.10f), radius = this.size.minDimension * 0.58f)
}

/** 3x3 pixel matrix, one cell lit per step in ring order with dimmed trailing cells. */
private fun DrawScope.drawPixelChase(color: Color, step: Int) {
    val ring = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3, 4)
    val cell = size.minDimension / 3f
    val dot = cell * 0.74f
    val origin = Offset((size.width - cell * 3f) / 2f, (size.height - cell * 3f) / 2f)
    ring.forEachIndexed { index, cellIndex ->
        val row = cellIndex / 3
        val col = cellIndex % 3
        val alpha = when {
            index < step -> 0.45f
            index == step -> 1f
            else -> 0.12f
        }
        val topLeft = Offset(
            origin.x + col * cell + (cell - dot) / 2f,
            origin.y + row * cell + (cell - dot) / 2f,
        )
        drawRoundRect(
            color.copy(alpha = alpha),
            topLeft = topLeft,
            size = Size(dot, dot),
            cornerRadius = CornerRadius(dot * 0.25f, dot * 0.25f),
        )
    }
}

@Preview
@Composable
private fun StateDotPreview() {
    DshTheme {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateDot(StateDotState.Idle)
            StateDot(StateDotState.Running, size = 10.dp)
            StateDot(StateDotState.Done)
            StateDot(StateDotState.Warning)
            StateDot(StateDotState.Error)
        }
    }
}
