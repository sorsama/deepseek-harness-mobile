package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.settings.formatNumber
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_thinking
import com.labteto.dshmobile.shared.resources.common_reconnecting
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Right-aligned user message bubble: r22, `userBubble` fill, hairline edge, 16/24 text.
 *
 * Three things carry the shape, and it needs all three. The assistant's turn is deliberately
 * container-less — as in the harness web UI — so the bubble is the *only* thing distinguishing who
 * said what, and it kept reading as plain text. `userBubble` now sits a step darker than the web
 * token (see `DsLight.userBubble`), `borderL3` draws an edge that survives a bright screen, and the
 * width cap keeps a short message a narrow pill hugging the right margin rather than a full-width
 * band that looks like more prose.
 *
 * The cap mirrors the harness's `max-width: min(525px, 82%)`, which is why this measures its parent
 * rather than hardcoding a dp: a flat 320dp was most of a phone's width and none of a tablet's.
 *
 * Holding the bubble opens what can be done with the message (see [holdable]). It used to offer
 * nothing at all, so a prompt sent by mistake and then stopped could not even be copied back to fix.
 */

@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    /** Opens the message's hold menu; null leaves the bubble inert, as it always was. */
    onHold: (() -> Unit)? = null,
    holdLabel: String = "",
) {
    val colors = DsTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            // The message reads in its own language, not the app's (#163).
            style = DsType.bubbleText.copy(textDirection = TextDirection.Content),
            color = colors.labelPrimary,
            modifier = Modifier
                .widthIn(max = minOf(525.dp, maxWidth * 0.82f))
                // Only the bubble holds, not the empty width beside it: a press in the gutter of a
                // scrolling transcript is a scroll that has not started yet.
                .then(if (onHold != null) Modifier.holdable(holdLabel, onHold = onHold) else Modifier)
                .background(colors.userBubble, DsShapes.bubbleUser)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 24dp disclosure row for collapsible thinking blocks; muted, never italic. */
@Composable
fun ThinkingRow(
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    streaming: Boolean = false,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clip(DsShapes.row)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (streaming) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            summary ?: "Thinking…",
            style = DsType.mdSmall.copy(color = colors.labelTertiary),
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = DsAnimations.chevron,
            label = "thinkingChevron",
        )
        Icon(
            DsIcons.KeyboardArrowDown,
            contentDescription = stringResource(Res.string.chat_thinking),
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * Full-width top strip announcing a connection problem.
 *
 * Two tones, because the two situations ask different things of the reader. A dropped stream the
 * app is already re-establishing is [warning]: amber, with a spinner, nothing to do. A failure that
 * will not fix itself is red and carries its own [actionLabel]; the retry used to be the whole strip
 * wrapped in a borderless text button, which nobody could tell was pressable.
 */
@Composable
fun ConnectionBanner(
    message: String,
    warning: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val background = if (warning) colors.warnTertiary else colors.errorTertiary
    val content = if (warning) colors.warnLabel else colors.error
    val border = if (warning) colors.warnBorder else colors.errorBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .border(1.dp, border)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = if (actionLabel != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (warning) {
            CircularProgressIndicator(Modifier.size(14.dp), color = content, strokeWidth = 2.dp)
        } else {
            Icon(
                DsIcons.Warning,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = DsType.small13,
            color = content,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = DsType.small13Strong, color = content)
            }
        }
    }
}

/** Centered run statistics with tabular numerals. */
@Composable
fun StatsLine(
    turns: Int,
    steps: Int,
    llmMs: Long? = null,
    ttftMs: Long? = null,
    tokPerSec: Double? = null,
) {
    val colors = DsTheme.colors
    val text = buildString {
        append("$turns turns")
        append(" · $steps steps")
        if (ttftMs != null) append(" · TTFT ${formatDuration(ttftMs)}")
        if (tokPerSec != null) append(" · ${formatRate(tokPerSec)} tok/s")
        if (llmMs != null) append(" · LLM ${formatDuration(llmMs)}")
    }
    Text(
        text,
        style = DsType.statsText.copy(color = colors.labelCaption, fontFeatureSettings = "tnum"),
        color = colors.labelCaption,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatDuration(ms: Long): String = when {
    ms >= 1000 -> oneDecimal(ms / 1000.0) + "s"
    else -> "${ms}ms"
}

private fun formatRate(perSec: Double): String = oneDecimal(perSec)

/**
 * [value] with one decimal and a `.` whatever the language, as JVM `"%.1f".format(Locale.US, value)` writes it: the
 * shortest decimal that reads back as [value], rounded half up. Common code has no `String.format`;
 * `OneDecimalTest` holds the two equal on Android.
 */
internal fun oneDecimal(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val digits = formatNumber(kotlin.math.abs(value))
    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', "")
    var tenths = (fraction.getOrNull(0) ?: '0') - '0'
    var integer = whole
    if ((fraction.getOrNull(1) ?: '0') >= '5') {
        tenths++
        if (tenths == 10) {
            tenths = 0
            integer = incrementDecimal(whole)
        }
    }
    return (if (negative) "-" else "") + integer + "." + tenths
}

/**
 * [value] rounded to a whole number and written with no point, as JVM `"%.0f".format(Locale.US, value)` writes it: the
 * shortest decimal that reads back as [value], rounded half up at its first decimal. `OneDecimalTest` holds the two
 * equal on Android.
 */
internal fun wholeNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val digits = formatNumber(kotlin.math.abs(value))
    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', "")
    val integer = if ((fraction.getOrNull(0) ?: '0') >= '5') incrementDecimal(whole) else whole
    return (if (negative) "-" else "") + integer
}

/** A non-negative whole number written in digits, plus one. */
private fun incrementDecimal(digits: String): String {
    val chars = digits.toCharArray()
    var at = chars.size - 1
    while (at >= 0) {
        if (chars[at] == '9') {
            chars[at] = '0'
            at--
        } else {
            chars[at] = chars[at] + 1
            return chars.concatToString()
        }
    }
    return "1" + chars.concatToString()
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ChatComponentsPreview() {
    DshTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            UserBubble("Build the shared component library.")
            ThinkingRow("Working through the diff…", expanded = false, onToggle = {}, streaming = true)
            ConnectionBanner(stringResource(Res.string.common_reconnecting))
            StatsLine(turns = 3, steps = 12, llmMs = 2100, ttftMs = 420, tokPerSec = 18.4)
        }
    }
}
