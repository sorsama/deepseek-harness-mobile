package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.RecordUsage
import com.labteto.dshmobile.core.session.TimeKind
import com.labteto.dshmobile.core.session.TimeSlice
import com.labteto.dshmobile.core.session.TimelineTotals
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The words for a kind of time: the legend's label, which is what a person without colour reads. */
@Composable
internal fun timeKindLabel(kind: TimeKind): String = stringResource(
    when (kind) {
        TimeKind.FIRST_TOKEN_WAIT -> R.string.traj_time_first_token
        TimeKind.GENERATING -> R.string.traj_time_generating
        TimeKind.MODEL -> R.string.traj_time_model
        TimeKind.APPROVAL_WAIT -> R.string.traj_time_approval
        TimeKind.TOOLS -> R.string.traj_time_tools
        TimeKind.OTHER -> R.string.traj_time_other
    },
)

/**
 * Where one stretch of time went, as one horizontal stacked bar.
 *
 * The bar is a visual aid only: it is hidden from screen readers, and the [TimingLegend] under it
 * carries every label and number. It is drawn from boxes in a [Row], so it lays out from the start
 * edge in both reading directions, and a 1dp gap of the card colour separates neighbours so they never
 * rest on a colour change alone.
 */
@Composable
internal fun TimingBar(slices: List<TimeSlice>, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    if (slices.sumOf { it.ms } <= 0L) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(DsShapes.chip)
            .border(1.dp, colors.borderL2, DsShapes.chip)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        slices.forEach { slice ->
            Box(
                Modifier
                    .weight(slice.ms.toFloat())
                    .widthIn(min = 2.dp)
                    .fillMaxHeight()
                    .background(timingFill(slice.kind, colors)),
            )
        }
    }
}

/** Every slice as a swatch, a label, its time and its share, wrapping to as many lines as it needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimingLegend(slices: List<TimeSlice>, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val total = slices.sumOf { it.ms }
    if (total <= 0L) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(DsShapes.chip)
                        .background(timingFill(slice.kind, colors))
                        .border(1.dp, colors.borderL3, DsShapes.chip),
                )
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    stringResource(
                        R.string.traj_legend_item,
                        timeKindLabel(slice.kind),
                        formatDurationMs(slice.ms),
                        timingPercent(slice.ms, total),
                    ),
                    style = DsType.xsmall12,
                    color = colors.labelSecondary,
                )
            }
        }
    }
}

/** One "label … value" line that wraps instead of clipping, and reads as one item to a screen reader. */
@Composable
internal fun FactRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = DsType.small13, color = colors.labelTertiary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = DsType.small13Strong,
            color = colors.labelPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The outcome of a call the user stopped: an icon and the word "Stopped" in the warning colour. A
 * stop is not a failure, so it is neither red nor shown as "Failed", and it is never only a colour:
 * the word and the icon say it.
 */
@Composable
internal fun StoppedLabel(modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = DsType.caption11Strong) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.warnText)
        Text(stringResource(R.string.chat_stopped), style = style, color = colors.warnText, maxLines = 1)
    }
}

/** The status line of an inspected record that was stopped: [FactRow]'s layout with the word and its icon as the value. */
@Composable
internal fun StoppedStatusRow(label: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = DsType.small13, color = colors.labelTertiary, modifier = Modifier.weight(1f))
        StoppedLabel(style = DsType.small13Strong, modifier = Modifier.weight(1f, fill = false))
    }
}

/** A line per token count the harness reported; a count nobody reported is not shown as zero. */
@Composable
internal fun UsageFacts(usage: RecordUsage?) {
    if (usage == null) return
    usage.inputTokens?.let { FactRow(stringResource(R.string.traj_tok_input), formatTokens(it)) }
    usage.outputTokens?.let { FactRow(stringResource(R.string.traj_tok_output), formatTokens(it)) }
    usage.cacheReadTokens?.let { FactRow(stringResource(R.string.traj_tok_cached), formatTokens(it)) }
    usage.cacheWriteTokens?.let { FactRow(stringResource(R.string.traj_tok_cache_written), formatTokens(it)) }
    usage.reasoningTokens?.let { FactRow(stringResource(R.string.traj_tok_reasoning), formatTokens(it)) }
}

/** The counts shared by a turn and a whole session: model replies, tool calls, failures and stops. */
@Composable
internal fun CountFacts(modelSteps: Int, toolCalls: Int, failedToolCalls: Int, stoppedToolCalls: Int = 0) {
    if (modelSteps > 0) FactRow(stringResource(R.string.traj_sum_model_steps), modelSteps.toString())
    if (toolCalls > 0) {
        FactRow(
            stringResource(R.string.traj_sum_tool_calls),
            when {
                failedToolCalls > 0 && stoppedToolCalls > 0 ->
                    stringResource(R.string.traj_sum_tool_calls_failed_stopped, toolCalls, failedToolCalls, stoppedToolCalls)
                failedToolCalls > 0 -> stringResource(R.string.traj_sum_tool_calls_failed, toolCalls, failedToolCalls)
                stoppedToolCalls > 0 -> stringResource(R.string.traj_sum_tool_calls_stopped, toolCalls, stoppedToolCalls)
                else -> toolCalls.toString()
            },
        )
    }
}

/**
 * The whole loaded session at a glance: one bar and legend of where the time went, and the totals of
 * time, replies, tool calls and tokens. When history is paged and earlier turns are not loaded the
 * card says so, so a total is never mistaken for the whole session.
 */
@Composable
internal fun SessionTimingCard(totals: TimelineTotals, partial: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    DsCard(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            stringResource(R.string.traj_overview_title).uppercase(),
            style = DsType.label12,
            color = colors.labelTertiary,
        )
        if (totals.slices.isNotEmpty()) {
            TimingBar(totals.slices)
            TimingLegend(totals.slices)
        }
        totals.wallMs?.let { FactRow(stringResource(R.string.traj_sum_time), formatDurationMs(it)) }
        FactRow(stringResource(R.string.traj_sum_turns), totals.turns.toString())
        CountFacts(totals.modelSteps, totals.toolCalls, totals.failedToolCalls, totals.stoppedToolCalls)
        UsageFacts(totals.usage)
        if (partial) {
            Text(
                stringResource(R.string.traj_overview_partial),
                style = DsType.caption11,
                color = colors.labelCaption,
            )
        }
    }
}
