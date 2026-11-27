package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.data.parseIsoInstantMillis
import platform.Foundation.NSByteCountFormatter
import platform.Foundation.NSByteCountFormatterCountStyleFile
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

@Composable
internal actual fun shortFileSize(bytes: Long): String =
    NSByteCountFormatter.stringFromByteCount(bytes, NSByteCountFormatterCountStyleFile)

internal actual fun localDateTime(iso: String): String {
    val millis = parseIsoInstantMillis(iso) ?: return iso
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

internal actual fun shortWeekdayName(isoDay: Int): String {
    require(isoDay in 1..7) { "Invalid value for DayOfWeek: $isoDay" }
    // shortWeekdaySymbols starts on Sunday; ISO starts on Monday.
    return NSDateFormatter().shortWeekdaySymbols[isoDay % 7] as String
}
