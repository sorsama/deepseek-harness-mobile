package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.data.wallClockMillis
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.time_days
import com.labteto.dshmobile.shared.resources.time_hours
import com.labteto.dshmobile.shared.resources.time_minutes
import com.labteto.dshmobile.shared.resources.time_months
import com.labteto.dshmobile.shared.resources.time_now
import com.labteto.dshmobile.shared.resources.time_years
import org.jetbrains.compose.resources.stringResource

/**
 * Coarse "how long ago", matching the harness sidebar's own scale: `now`, `5min`, `3h`, `2d`,
 * `4mo`, `1y`.
 *
 * A clock time is the wrong unit for a session list — two sessions a week apart both read `14:32`,
 * which tells the reader nothing about which one they were just in.
 */
@Composable
fun relativeTime(timestampMs: Long, nowMs: Long = wallClockMillis()): String {
    val elapsed = (nowMs - timestampMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    val years = days / 365
    return when {
        minutes < 1 -> stringResource(Res.string.time_now)
        minutes < 60 -> stringResource(Res.string.time_minutes, localizedCount(minutes.toInt()))
        hours < 24 -> stringResource(Res.string.time_hours, localizedCount(hours.toInt()))
        days < 30 -> stringResource(Res.string.time_days, localizedCount(days.toInt()))
        months < 12 -> stringResource(Res.string.time_months, localizedCount(months.toInt()))
        else -> stringResource(Res.string.time_years, localizedCount(years.coerceAtLeast(1).toInt()))
    }
}
