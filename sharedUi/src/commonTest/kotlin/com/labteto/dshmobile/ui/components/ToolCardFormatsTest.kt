package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * The reminder card's two platform formatters, on each platform: the JVM run is Android's `java.time`,
 * the simulator run is `NSDateFormatter`. The wording is the platform's, so this pins the contract the
 * card relies on rather than the words.
 */
class ToolCardFormatsTest {

    @Test
    fun `text that is not an instant is shown as it came`() {
        assertEquals("tomorrow at nine", localDateTime("tomorrow at nine"))
        assertEquals("2026-13-01T09:00:00Z", localDateTime("2026-13-01T09:00:00Z"))
    }

    @Test
    fun `an instant is written in the reader's own format`() {
        val shown = localDateTime("2026-11-27T09:30:00Z")
        assertTrue(shown, shown.isNotBlank() && shown != "2026-11-27T09:30:00Z")
        assertTrue(shown, shown.contains("2026"))
        val withFraction = localDateTime("2026-11-27T09:30:00.250Z")
        assertEquals(shown, withFraction)
    }

    @Test
    fun `every weekday has its own short name`() {
        val names = (1..7).map { shortWeekdayName(it) }
        assertTrue(names.toString(), names.all { it.isNotBlank() })
        assertEquals(7, names.toSet().size)
    }

    @Test
    fun `a weekday outside one to seven fails as DayOfWeek does`() {
        assertFails { shortWeekdayName(0) }
        assertFails { shortWeekdayName(8) }
    }
}
