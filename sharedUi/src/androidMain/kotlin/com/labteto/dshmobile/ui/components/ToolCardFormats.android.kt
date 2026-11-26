package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun shortFileSize(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

internal actual fun localDateTime(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(iso))
}.getOrDefault(iso)

internal actual fun shortWeekdayName(isoDay: Int): String =
    java.time.DayOfWeek.of(isoDay).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
