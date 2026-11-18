package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.core.session.TimeKind
import com.labteto.dshmobile.ui.theme.DsColors

/**
 * Whole percent of [total] that [ms] is, rounded half up, and never 0 for a stretch that happened.
 *
 * A slice of a third of a percent is still on the legend, and "0%" beside a real duration reads as
 * though the number were wrong, so the floor is 1.
 */
internal fun timingPercent(ms: Long, total: Long): Int {
    if (total <= 0L || ms <= 0L) return 0
    return ((ms * 100 + total / 2) / total).toInt().coerceIn(1, 100)
}

/**
 * A shell tool's title by its product name: `pwsh` is "PowerShell" and `bash` is "Bash", never the
 * wire name. Null for any other tool, which keeps whatever title it had.
 *
 * The chat reaches the same words through its own tool-row table (the transcript branch); this is the
 * minimal mapping for the Trajectory, the inspector's title above all, which read "pwsh". The
 * names are product names and stay the same in every language.
 */
internal fun shellToolTitle(toolName: String): String? = when (toolName.lowercase()) {
    "pwsh" -> "PowerShell"
    "bash" -> "Bash"
    else -> null
}

/**
 * The fill of one kind of time in the stacked bar, from the Ink role tokens.
 *
 * The bar is a visual aid and the legend's words and numbers are the content, but where colour is
 * used it must not be hue alone: neighbours in [TimeKind] order alternate between a light and a dark
 * fill, so two slices side by side differ in lightness whatever the accent. `TrajectoryColorsTest`
 * holds that for the light and dark schemes.
 */
internal fun timingFill(kind: TimeKind, colors: DsColors): Color = when (kind) {
    TimeKind.FIRST_TOKEN_WAIT -> colors.accentTertiary
    TimeKind.GENERATING -> colors.accent
    TimeKind.MODEL -> colors.borderL3
    TimeKind.APPROVAL_WAIT -> colors.warn
    TimeKind.TOOLS -> colors.labelPrimary
    TimeKind.OTHER -> colors.borderL2
}
