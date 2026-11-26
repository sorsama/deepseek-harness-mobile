package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

/**
 * Coarse "how long ago", matching the harness sidebar's own scale: `now`, `5min`, `3h`, `2d`,
 * `4mo`, `1y`.
 *
 * A clock time is the wrong unit for a session list — two sessions a week apart both read `14:32`,
 * which tells the reader nothing about which one they were just in.
 */
@Composable
fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val elapsed = (nowMs - timestampMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    val years = days / 365
    return when {
        minutes < 1 -> stringResource(R.string.time_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes.toInt())
        hours < 24 -> stringResource(R.string.time_hours, hours.toInt())
        days < 30 -> stringResource(R.string.time_days, days.toInt())
        months < 12 -> stringResource(R.string.time_months, months.toInt())
        else -> stringResource(R.string.time_years, years.coerceAtLeast(1).toInt())
    }
}

// formatDurationMs moved to :sharedUi (Durations.kt) with the connect screen.

/** Token counts read better abbreviated once they pass a thousand. */
fun formatTokens(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
}
