package com.labteto.dshmobile.ui.components

/**
 * Compact duration for job and turn timings: `4.2s`, `1:03`, `12:45`.
 *
 * Written out in integer arithmetic because `String.format` is not common code. The sub-minute form
 * rounds to a tenth of a second half up, which is what `%.1f` does with a value that has at most
 * three decimals (`DurationFormatParityTest` in `:app` holds it equal to `String.format` for every
 * millisecond up to ten minutes and then for a spread of larger values).
 */
fun formatDurationMs(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> {
        val tenths = (ms + 50) / 100
        "${tenths / 10}.${tenths % 10}s"
    }
    ms >= 3_600_000 -> "${ms / 3_600_000}:${twoDigits(ms / 60_000 % 60)}:${twoDigits(ms / 1000 % 60)}"
    else -> {
        val totalSeconds = ms / 1000
        "${totalSeconds / 60}:${twoDigits(totalSeconds % 60)}"
    }
}

private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()
