package com.labteto.dshmobile.ui.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a page that lived through a dropped connection is loaded again (see [UiAddonsRecovery]). */
class UiAddonsRecoveryTest {
    private val host = "a|http://10.0.2.2:3095"

    private fun rule() = UiAddonsRecovery(settleMs = 1_000, minGapMs = 10_000)

    @Test fun `the first connect is never a recovery`() {
        val rule = rule()
        rule.onConnection(host, connected = false, now = 0)
        rule.onConnection(host, connected = true, now = 500)
        assertNull(rule.waitMs(now = 500))
        assertFalse(rule.take(now = 60_000, hasPage = true))
    }

    @Test fun `a connection that never dropped owes nothing, however long it stays up`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        assertNull(rule.waitMs(now = 100_000))
        assertFalse(rule.take(now = 100_000, hasPage = true))
    }

    @Test fun `a recovery reloads once, after the connection has held for a second`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 5_000)
        rule.onConnection(host, connected = true, now = 8_000)
        assertEquals(1_000L, rule.waitMs(now = 8_000))
        assertFalse(rule.take(now = 8_999, hasPage = true))
        assertEquals(1L, rule.waitMs(now = 8_999))
        assertTrue(rule.take(now = 9_000, hasPage = true))
        // Used up: the same recovery does not reload again.
        assertNull(rule.waitMs(now = 9_000))
        assertFalse(rule.take(now = 20_000, hasPage = true))
    }

    @Test fun `while the connection is down nothing is due, and the reload is asked for again once it is back`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        assertNull(rule.waitMs(now = 30_000))
        assertFalse(rule.take(now = 30_000, hasPage = true))
        rule.onConnection(host, connected = true, now = 31_000)
        assertEquals(1_000L, rule.waitMs(now = 31_000))
    }

    @Test fun `a link that flaps reloads once, a second after the last bounce`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 10_000)
        rule.onConnection(host, connected = true, now = 10_400)
        assertFalse(rule.take(now = 10_900, hasPage = true))
        rule.onConnection(host, connected = false, now = 10_950)
        assertFalse(rule.take(now = 11_100, hasPage = true))
        rule.onConnection(host, connected = true, now = 11_300)
        assertFalse(rule.take(now = 11_900, hasPage = true))
        // Broken again before the second was up: the hold starts over.
        rule.onConnection(host, connected = false, now = 12_000)
        rule.onConnection(host, connected = true, now = 12_500)
        assertFalse(rule.take(now = 13_000, hasPage = true))
        assertEquals(500L, rule.waitMs(now = 13_000))
        assertTrue(rule.take(now = 13_500, hasPage = true))
        assertFalse(rule.take(now = 14_000, hasPage = true))
    }

    @Test fun `a repeated down report is one drop`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onConnection(host, connected = false, now = 2_000)
        rule.onConnection(host, connected = true, now = 3_000)
        rule.onConnection(host, connected = true, now = 3_100)
        assertEquals(900L, rule.waitMs(now = 3_100))
        assertTrue(rule.take(now = 4_000, hasPage = true))
    }

    @Test fun `with no page the recovery is used up and a later page is not reloaded for it`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onConnection(host, connected = true, now = 2_000)
        assertFalse(rule.take(now = 3_000, hasPage = false))
        assertNull(rule.waitMs(now = 3_000))
        assertFalse(rule.take(now = 60_000, hasPage = true))
    }

    @Test fun `a page made once the connection is back loads fresh and is not reloaded for the drop`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onConnection(host, connected = true, now = 2_000)
        rule.onPageMade()
        assertNull(rule.waitMs(now = 2_500))
        assertFalse(rule.take(now = 3_000, hasPage = true))
    }

    @Test fun `a page made while the connection is down may be broken, so it is still reloaded after the recovery`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onPageMade()
        rule.onConnection(host, connected = true, now = 2_000)
        assertTrue(rule.take(now = 3_000, hasPage = true))
    }

    @Test fun `another host starts over, and its first connect is not a recovery`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        val other = "b|https://relay.home:8443"
        rule.onConnection(other, connected = false, now = 1_500)
        rule.onConnection(other, connected = true, now = 2_000)
        assertNull(rule.waitMs(now = 2_000))
        assertFalse(rule.take(now = 10_000, hasPage = true))
        // And a drop of the new host is a recovery of its own.
        rule.onConnection(other, connected = false, now = 11_000)
        rule.onConnection(other, connected = true, now = 12_000)
        assertTrue(rule.take(now = 13_000, hasPage = true))
    }

    @Test fun `no host at all owes nothing`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onConnection(null, connected = false, now = 2_000)
        rule.onConnection(null, connected = true, now = 3_000)
        assertNull(rule.waitMs(now = 5_000))
        assertFalse(rule.take(now = 5_000, hasPage = true))
    }

    @Test fun `two reloads are never closer than the minimum gap`() {
        val rule = rule()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1_000)
        rule.onConnection(host, connected = true, now = 2_000)
        assertTrue(rule.take(now = 3_000, hasPage = true))
        // Dropped again straight away: held for a second, but the last reload was 3 s ago.
        rule.onConnection(host, connected = false, now = 3_500)
        rule.onConnection(host, connected = true, now = 4_000)
        assertEquals(9_000L, rule.waitMs(now = 4_000))
        assertFalse(rule.take(now = 5_000, hasPage = true))
        assertFalse(rule.take(now = 12_999, hasPage = true))
        assertTrue(rule.take(now = 13_000, hasPage = true))
    }

    @Test fun `the defaults wait a second and keep reloads ten seconds apart`() {
        assertEquals(1_000L, UI_ADDONS_RECOVERY_SETTLE_MS)
        assertEquals(10_000L, UI_ADDONS_RECOVERY_MIN_GAP_MS)
        val rule = UiAddonsRecovery()
        rule.onConnection(host, connected = true, now = 0)
        rule.onConnection(host, connected = false, now = 1)
        rule.onConnection(host, connected = true, now = 2)
        assertEquals(UI_ADDONS_RECOVERY_SETTLE_MS, rule.waitMs(now = 2))
    }

    @Test fun `only a host that is not the phone itself can be hit by a network change`() {
        assertTrue(uiAddonsNetworkMatters(loopback = false))
        assertFalse(uiAddonsNetworkMatters(loopback = true))
    }
}
