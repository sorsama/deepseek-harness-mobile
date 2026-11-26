package com.labteto.dshmobile.ui.components

// formatDurationMs is in :sharedUi (Durations.kt), and relativeTime (RelativeTime.kt) moved there with the session list.

/** Token counts read better abbreviated once they pass a thousand. */
fun formatTokens(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
}
