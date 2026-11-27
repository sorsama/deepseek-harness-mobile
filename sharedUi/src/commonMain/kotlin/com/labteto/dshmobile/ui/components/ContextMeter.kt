package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The composer's bar, in dp. Shared so the action row can tell whether one fits before composing it. */
internal const val ContextMeterBarWidth = 44
internal const val ContextMeterBarHeight = 6

/**
 * How full the model's context window is, split by what is filling it.
 *
 * The harness keeps this next to the composer rather than in a panel, and that placement is the
 * point: context pressure is something you act on while writing the next message, not something
 * you go looking for after a turn fails.
 */
@Composable
fun ContextMeter(
    breakdown: ContextBreakdownView?,
    pressure: ContextPressureView?,
    modifier: Modifier = Modifier,
    /** Fixed bar width in dp; null lets the caller's modifier size it (the detail reading). */
    barWidth: Int? = ContextMeterBarWidth,
) {
    val colors = DsTheme.colors
    val ratio = pressure?.usedRatio ?: return
    val animated by animateFloatAsState(
        targetValue = ratio,
        animationSpec = DsAnimations.fade,
        label = "contextPressure",
    )
    val total = breakdown?.total?.takeIf { it > 0 }
    // Without a breakdown the bar still tells you how full the window is; with one it also says
    // what is filling it, which is what turns "compact soon" into "compact the messages".
    val segments = if (total == null) {
        listOf(1f to colors.accent)
    } else {
        listOf(
            breakdown.systemTokens.toFloat() / total to Ds.MeterSystem,
            breakdown.toolsTokens.toFloat() / total to Ds.MeterTools,
            breakdown.messageTokens.toFloat() / total to Ds.MeterMessages,
        )
    }

    Canvas(
        modifier = modifier
            .then(if (barWidth != null) Modifier.width(barWidth.dp) else Modifier)
            .height(ContextMeterBarHeight.dp)
            .clip(CircleShape)
            .background(colors.bgModulePlatform),
    ) {
        var x = 0f
        val filled = size.width * animated
        segments.forEach { (share, color) ->
            val segmentWidth = filled * share
            if (segmentWidth > 0f) {
                drawRect(color = color, topLeft = Offset(x, 0f), size = Size(segmentWidth, size.height))
                x += segmentWidth
            }
        }
    }
}

/** The expanded reading used in the details panel: the same bar plus a labelled legend. */
@Composable
fun ContextMeterDetail(
    breakdown: ContextBreakdownView?,
    pressure: ContextPressureView?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val ratio = pressure?.usedRatio
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ratio != null) {
            Text(
                stringResource(R.string.chat_context_used, (ratio * 100).toInt()),
                style = DsType.caption11,
                color = colors.labelSecondary,
            )
        }
        ContextMeter(breakdown, pressure, Modifier.fillMaxWidth(), barWidth = null)
        if (breakdown != null && breakdown.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(stringResource(R.string.chat_context_system), Ds.MeterSystem)
                LegendDot(stringResource(R.string.chat_context_tools), Ds.MeterTools)
                LegendDot(stringResource(R.string.chat_context_messages), Ds.MeterMessages)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
    }
}
