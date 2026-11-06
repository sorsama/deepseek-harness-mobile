package com.labteto.dshmobile.ui.screens.main.preview

import java.util.Locale

/** U+FEFF, which some editors put at the start of a text file. Built from its number so no invisible character sits in the source. */
internal val BYTE_ORDER_MARK: String = 0xFEFF.toChar().toString()

/** `812 B`, `1.2 KB`, `3.4 MB`: a size a person can read beside a file name. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** `0:07`, `3:45`, `1:02:09`: a player clock. */
internal fun formatClock(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
    else String.format(Locale.US, "%d:%02d", minutes, rest)
}

/** A path split into the folder it is in (with a trailing `/`, empty at the root) and its name. */
internal fun splitPath(path: String): Pair<String, String> {
    val normal = path.replace('\\', '/')
    val cut = normal.lastIndexOf('/')
    return if (cut < 0) "" to normal else normal.substring(0, cut + 1) to normal.substring(cut + 1)
}

/**
 * [text] cut from the left, with a leading `…`, until [fits] says it fits; [text] itself if it
 * already does. The end of a path is the part that tells one folder from another, so that is the
 * part kept. [fits] must get easier as the text gets shorter (width does).
 */
internal fun ellipsizeStart(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    var low = 1
    var high = text.length
    // Smallest number of characters to drop so that "…" + the rest fits.
    while (low < high) {
        val mid = (low + high) / 2
        if (fits("…" + text.substring(mid))) high = mid else low = mid + 1
    }
    return "…" + text.substring(low.coerceAtMost(text.length))
}

/** `width × height`. */
internal fun formatDimensions(width: Int, height: Int): String = "$width × $height"

/**
 * The next zoom level after a double tap: in steps up to [max], and back to 1 once the top is
 * reached, so repeated taps cycle instead of getting stuck.
 */
internal fun nextZoomStep(current: Float, max: Float): Float = when {
    current < 1.5f -> 2f.coerceAtMost(max)
    current < 3f -> 4f.coerceAtMost(max)
    else -> 1f
}
