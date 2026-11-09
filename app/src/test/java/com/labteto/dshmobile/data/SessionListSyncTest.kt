package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session started, prompted or renamed on the computer has to reach the drawer without a
 * reconnect. These pin the pieces that decide it: when a background list read may run, and how a
 * pushed fact or a fresh list row is allowed to change what the drawer already shows.
 */
class SessionListSyncTest {

    private fun row(id: String = "s", blank: Boolean = true, title: String? = null) = SessionRow(
        sessionId = id,
        title = title,
        running = false,
        blank = blank,
        parentSessionId = null,
        origin = null,
        cwd = null,
        agentPreset = null,
        updatedAt = 0,
        pendingInteraction = null,
    )

    // ------------------------------------------------------------------ RefreshThrottle
    @Test
    fun `the first read is always allowed`() {
        assertTrue(RefreshThrottle(4_000) { 0L }.begin())
    }

    @Test
    fun `a second read inside the interval is refused`() {
        var t = 1_000L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        throttle.end()
        t += 3_999
        assertFalse(throttle.begin())
    }

    @Test
    fun `a read after the interval is allowed again`() {
        var t = 1_000L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        throttle.end()
        t += 4_000
        assertTrue(throttle.begin())
    }

    @Test
    fun `a refused read does not push the next one back`() {
        var t = 0L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        throttle.end()
        t = 2_000
        assertFalse(throttle.begin())
        t = 4_000
        assertTrue("spacing counts from the last read that ran, not the last that asked", throttle.begin())
    }

    @Test
    fun `only one read is in flight even when forced`() {
        var t = 0L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        t = 60_000
        assertFalse(throttle.begin())
        assertFalse(throttle.begin(force = true))
        throttle.end()
        assertTrue(throttle.begin(force = true))
    }

    @Test
    fun `force skips the spacing`() {
        var t = 0L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        throttle.end()
        t = 10
        assertFalse(throttle.begin())
        assertTrue(throttle.begin(force = true))
    }

    @Test
    fun `a clock that steps backwards does not lock reads out`() {
        var t = 10_000L
        val throttle = RefreshThrottle(4_000) { t }
        assertTrue(throttle.begin())
        throttle.end()
        t = 5_000
        assertTrue(throttle.begin())
    }

    // ------------------------------------------------------------------ pushed facts
    @Test
    fun `metadata that says not blank is read`() {
        val value = buildJsonObject { put("blank", false); put("lastPromptAt", 1L) }
        assertEquals(false, listMetadataBlank(value))
    }

    @Test
    fun `metadata that says blank is read as blank`() {
        assertEquals(true, listMetadataBlank(buildJsonObject { put("blank", true) }))
    }

    @Test
    fun `metadata this client cannot read changes nothing`() {
        assertNull(listMetadataBlank(null))
        assertNull(listMetadataBlank(JsonNull))
        assertNull(listMetadataBlank(JsonPrimitive("false")))
        assertNull(listMetadataBlank(buildJsonObject { put("lastPromptAt", 1L) }))
        assertNull(listMetadataBlank(buildJsonObject { put("blank", "no") }))
    }

    @Test
    fun `a title projection is a bare string or an object`() {
        assertEquals("Fix the build", titleOf(JsonPrimitive("Fix the build")))
        assertEquals("Fix the build", titleOf(buildJsonObject { put("title", "Fix the build") }))
        assertNull(titleOf(null))
        assertNull(titleOf(buildJsonObject { put("other", "x") }))
    }

    @Test
    fun `engaging a blank row shows it and leaves everything else alone`() {
        val before = row(blank = true, title = "Hello")
        val after = before.engaged()
        assertFalse(after.blank)
        assertEquals(before.copy(blank = false), after)
    }

    @Test
    fun `engaging a row that is already shown changes nothing`() {
        val shown = row(blank = false)
        assertSame(shown, shown.engaged())
    }

    // ------------------------------------------------------------------ list reads
    @Test
    fun `a session that was prompted stays shown when a stale row says blank`() {
        assertFalse(effectiveBlank(listedBlank = true, engaged = true))
    }

    @Test
    fun `a session nobody has prompted stays hidden`() {
        assertTrue(effectiveBlank(listedBlank = true, engaged = false))
    }

    @Test
    fun `a row that says not blank is never hidden`() {
        assertFalse(effectiveBlank(listedBlank = false, engaged = false))
        assertFalse(effectiveBlank(listedBlank = false, engaged = true))
    }

    @Test
    fun `a current list row carries a rename made elsewhere`() {
        assertEquals("New name", listedTitle(held = "Old name", listed = "New name", listedIsCurrent = true))
    }

    @Test
    fun `a cached list row never overwrites a title we hold`() {
        assertEquals("Old name", listedTitle(held = "Old name", listed = "Stale hint", listedIsCurrent = false))
    }

    @Test
    fun `a cached title is still better than none`() {
        assertEquals("Hint", listedTitle(held = null, listed = "Hint", listedIsCurrent = false))
    }

    @Test
    fun `a row without a title keeps the one we hold`() {
        assertEquals("Old name", listedTitle(held = "Old name", listed = null, listedIsCurrent = true))
        assertNull(listedTitle(held = null, listed = null, listedIsCurrent = true))
    }
}
