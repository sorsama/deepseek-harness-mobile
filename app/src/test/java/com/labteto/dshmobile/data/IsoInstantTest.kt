package com.labteto.dshmobile.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [parseIsoInstantMillis] replaced `Instant.parse(text).toEpochMilli()` in the store. These tables hold the two equal:
 * every input either parses to the same millisecond in both or is refused by both.
 */
class IsoInstantTest {

    /** What `java.time` makes of [text]: its epoch milliseconds, or null where it refuses it. */
    private fun javaTime(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    /** Shapes a harness sends: Node's `toISOString()`, other fraction lengths, offsets. All valid. */
    private val valid = listOf(
        "2026-11-26T09:41:07.123Z",
        "2026-11-26T09:41:07Z",
        "2026-11-26T09:41:07.1Z",
        "2026-11-26T09:41:07.12Z",
        "2026-11-26T09:41:07.1234Z",
        "2026-11-26T09:41:07.123456Z",
        "2026-11-26T09:41:07.123456789Z",
        "2026-11-26T09:41:07.999999999Z",
        "2026-11-26T09:41:07.000Z",
        "2026-11-26T09:41:07.123+00:00",
        "2026-11-26T09:41:07.123-00:00",
        "2026-11-26T10:41:07+01:00",
        "2026-11-26T15:11:07.5+05:30",
        "2026-11-26T01:41:07-08:00",
        "2026-11-26T09:41:07+01:02:03",
        "2026-11-26T09:41:07-18:00",
        "2026-11-26T09:41:07+18:00",
        "2026-11-26t09:41:07.123z",
        "1970-01-01T00:00:00Z",
        "1970-01-01T00:00:00.001Z",
        "1969-12-31T23:59:59.999Z",
        "1969-12-31T23:59:59.5Z",
        "1900-03-01T00:00:00Z",
        "1600-02-29T12:00:00Z",
        "0001-01-01T00:00:00Z",
        "2000-02-29T23:59:59Z",
        "2024-02-29T12:00:00Z",
        "2026-12-31T23:59:59.999Z",
        "2026-01-01T00:00:00+14:00",
        "9999-12-31T23:59:59.999999999Z",
        "2026-11-26T24:00:00Z",
        "2026-12-31T24:00:00Z",
        "2016-12-31T23:59:60Z",
        "2016-12-31T23:59:60.5Z",
    )

    /** Not instants: both must refuse them. */
    private val invalid = listOf(
        "",
        "2026-11-26",
        "2026-11-26T09:41Z",
        "2026-11-26T09:41:07",
        "2026-11-26T09:41:07.123",
        "2026-11-26 09:41:07Z",
        "2026-11-26T09:41:07.Z1",
        "2026-11-26T09:41:07.1234567890Z",
        "2026-11-26T09:41:07ZZ",
        "2026-11-26T09:41:07Z ",
        " 2026-11-26T09:41:07Z",
        "2026-11-26T9:41:07Z",
        "26-11-26T09:41:07Z",
        "2026-13-01T00:00:00Z",
        "2026-00-01T00:00:00Z",
        "2026-02-29T00:00:00Z",
        "1900-02-29T00:00:00Z",
        "2026-04-31T00:00:00Z",
        "2026-11-32T00:00:00Z",
        "2026-11-26T25:00:00Z",
        "2026-11-26T24:00:01Z",
        "2026-11-26T23:60:00Z",
        "2026-11-26T12:00:60Z",
        "2026-11-26T09:41:07+0100",
        "2026-11-26T09:41:07+01",
        "2026-11-26T09:41:07+1:00",
        "2026-11-26T09:41:07+01:60",
        "2026-11-26T09:41:07+19:00",
        "2026-11-26T09:41:07UTC",
        "not a date",
    )

    @Test
    fun `every valid shape parses to the millisecond java time gives`() {
        for (text in valid) {
            val expected = javaTime(text)
            assertNotNull("java.time refused $text, so it does not belong in the valid table", expected)
            assertEquals(text, expected, parseIsoInstantMillis(text))
        }
    }

    @Test
    fun `whatever java time refuses is refused`() {
        for (text in invalid) {
            assertNull("java.time accepted $text, so it does not belong in the invalid table", javaTime(text))
            assertNull(text, parseIsoInstantMillis(text))
        }
    }

    @Test
    fun `fixed points hold without java time`() {
        assertEquals(0L, parseIsoInstantMillis("1970-01-01T00:00:00Z"))
        assertEquals(-1L, parseIsoInstantMillis("1969-12-31T23:59:59.999Z"))
        assertEquals(1_795_686_067_123L, parseIsoInstantMillis("2026-11-26T09:41:07.123Z"))
        assertEquals(1_795_686_067_123L, parseIsoInstantMillis("2026-11-26T15:11:07.123+05:30"))
        assertEquals(1_795_686_067_999L, parseIsoInstantMillis("2026-11-26T09:41:07.999999999Z"))
    }
}
