package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/**
 * An image's size in the platform's own short file-size wording: Android's
 * `Formatter.formatShortFileSize` (what the image card always showed), `NSByteCountFormatter` in its
 * file style on iOS.
 */
@Composable
internal expect fun shortFileSize(bytes: Long): String

/**
 * A recorded instant (`2026-11-27T09:00:00Z`, a reminder's time) in the reader's own clock and format: a
 * medium date and a short time in the device's time zone and language. [iso] itself when it is not an instant.
 * Android formats with `java.time` as before; iOS with `NSDateFormatter`.
 */
internal expect fun localDateTime(iso: String): String

/**
 * The short name of an ISO weekday (1 is Monday, 7 is Sunday) in the device's language, for a weekly
 * reminder's days: `java.time.DayOfWeek` on Android as before, `NSDateFormatter.shortWeekdaySymbols` on iOS.
 * Fails on a number outside 1..7, as `DayOfWeek.of` does.
 */
internal expect fun shortWeekdayName(isoDay: Int): String
