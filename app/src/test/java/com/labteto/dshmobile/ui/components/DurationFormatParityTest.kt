package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * `formatDurationMs` moved to common code, where `String.format` does not exist, and was written out
 * in integer arithmetic. This is the old formatter, kept here verbatim as the reference, held equal
 * to the new one for every millisecond up to ten minutes and then across the larger ranges.
 */
class DurationFormatParityTest {
    private fun reference(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        ms >= 3_600_000 -> String.format(Locale.US, "%d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
        else -> {
            val totalSeconds = ms / 1000
            String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }

    @Test
    fun `every millisecond up to ten minutes reads as it did`() {
        for (ms in 0L..600_000L) {
            assertEquals("at $ms ms", reference(ms), formatDurationMs(ms))
        }
    }

    @Test
    fun `hours, days and awkward values read as they did`() {
        val values = mutableListOf(3_599_999L, 3_600_000L, 3_600_001L, 3_661_000L, 86_399_999L, 86_400_000L, 359_999_999L, Long.MAX_VALUE / 1_000_000)
        var x = 600_000L
        while (x < 400_000_000L) {
            values += x
            x += 99_991L
        }
        for (ms in values) assertEquals("at $ms ms", reference(ms), formatDurationMs(ms))
    }
}
