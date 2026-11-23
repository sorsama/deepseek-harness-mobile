package com.labteto.dshmobile.ui.addons

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.uisync.UiSyncHostMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which host gets the add-on page and with what credential, what the view does, and the insets it sends. */
class UiAddonsPolicyTest {
    private val UiAddonsTarget.session get() = auth as UiAddonsAuth.Session

    private val cookie = "dsh-auth-6f1d2c=s3cr3t.value"
    private val loopback = HostConfig(id = "b", name = "this phone", host = "127.0.0.1", port = 3080, isLoopback = true)
    private val lan = HostConfig(id = "a", name = "desk", host = "10.0.2.2", port = 3095)
    private val relay = HostConfig(
        id = "r", name = "relay", host = "relay.home", port = 8443, useTls = true,
        relayFingerprint = "pin=", relayDeviceId = "dev-1",
    )

    @Test fun `a loopback host loads its own index with its session cookie`() {
        val target = uiAddonsTarget(loopback, cookie)!!
        assertEquals("http://127.0.0.1:3080/", target.pageUrl)
        assertEquals("http://127.0.0.1:3080", target.origin)
        assertEquals("$cookie; Path=/; HttpOnly; SameSite=Strict", target.session.cookieLine)
        assertNull(target.pin)
        assertFalse(target.relay)
    }

    @Test fun `a LAN host is a direct target too`() {
        val target = uiAddonsTarget(lan, cookie)!!
        assertEquals("http://10.0.2.2:3095", target.origin)
        assertEquals("http://10.0.2.2:3095/", target.session.cookieUrl)
    }

    @Test fun `a relay signs the page in with its own handoff and never with a cookie`() {
        val target = uiAddonsTarget(relay, null)!!
        assertTrue(target.relay)
        assertEquals(UiAddonsAuth.RelayHandoff, target.auth)
        assertEquals("https://relay.home:8443/", target.pageUrl)
        assertEquals("https://relay.home:8443", target.origin)
        assertEquals("pin=", target.pin)
        assertEquals(RELAY_DEVICE_COOKIE, target.auth.cookieName)
        // A harness cookie the app holds is never put on a relay page.
        assertEquals(target, uiAddonsTarget(relay, cookie))
    }

    @Test fun `only the relay page gets the badge mark at document start`() {
        val direct = uiAddonsStartScript(uiAddonsTarget(lan, cookie)!!)
        assertEquals("window.__DSH_UI_SYNC_HOST__ = 'phone';", direct)
        val relayed = uiAddonsStartScript(uiAddonsTarget(relay, null)!!)
        assertTrue(relayed.startsWith("window.__DSH_UI_SYNC_HOST__ = 'phone';"))
        assertTrue(relayed.contains("data-dsh-relay-badge','off'"))
        // Waits for <html> when the hook runs before the parser made it.
        assertTrue(relayed.contains("MutationObserver"))
    }

    @Test fun `the relay sign in page is told apart from the harness`() {
        val target = uiAddonsTarget(relay, null)!!
        assertTrue(isRelaySignIn(target, "https://relay.home:8443/relay/login?next=%2F"))
        assertTrue(isRelaySignIn(target, "https://relay.home:8443/relay/login/"))
        assertFalse(isRelaySignIn(target, "https://relay.home:8443/"))
        assertFalse(isRelaySignIn(target, "https://relay.home:8443/relay/session/enter?code=x"))
        assertFalse(isRelaySignIn(target, "https://elsewhere.home:8443/relay/login"))
        assertFalse(isRelaySignIn(target, null))
        // A direct harness has no relay sign in, whatever its routes are called.
        assertFalse(isRelaySignIn(uiAddonsTarget(lan, cookie)!!, "http://10.0.2.2:3095/relay/login"))
    }

    @Test fun `no session or a malformed one means no page`() {
        assertNull(uiAddonsTarget(lan, null))
        for (bad in listOf("", "   ", "novalue", "=v", "a=b; Path=/", "a=b\nSet-Cookie: x=y", "a b=c", "a=b,c=d")) {
            assertNull(bad, uiAddonsTarget(lan, bad))
        }
    }

    @Test fun `a TLS reverse proxy under a path keeps the path and a secure cookie`() {
        val proxied = HostConfig(id = "p", name = "agent", host = "agent.home", port = 443, useTls = true, basePath = "/dsh")
        val target = uiAddonsTarget(proxied, cookie)!!
        assertEquals("https://agent.home/dsh/", target.pageUrl)
        // A default port is left out of the origin, exactly as the page reports its own.
        assertEquals("https://agent.home", target.origin)
        assertTrue(target.session.cookieLine.endsWith("; Secure"))
    }

    @Test fun `an IPv6 host gets a bracketed origin`() {
        val v6 = HostConfig(id = "6", name = "v6", host = "::1", port = 3080, isLoopback = true)
        assertEquals("http://[::1]:3080", uiAddonsTarget(v6, cookie)!!.origin)
    }

    @Test fun `two hosts never share a key`() {
        assertTrue(uiAddonsTarget(lan, cookie)!!.key != uiAddonsTarget(loopback, cookie)!!.key)
        val moved = lan.copy(port = 3096)
        assertTrue(uiAddonsTarget(lan, cookie)!!.key != uiAddonsTarget(moved, cookie)!!.key)
    }

    private val target = uiAddonsTarget(lan, cookie)
    private val shown = UiAddonsInputs(
        available = true, target = target, supported = true, enabled = true, phoneItems = true,
        chatShown = true, sessionOpen = true, covered = false, started = true, hasView = true,
    )

    @Test fun `with everything in place the layer is shown`() {
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown))
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown.copy(hasView = false)))
    }

    @Test fun `the layer is off without a companion a target or a capable web view`() {
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(available = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(target = null)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(supported = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(givenUp = true)))
    }

    @Test fun `the switch off means no web view at all`() {
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(enabled = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(enabled = false, hasView = false)))
    }

    @Test fun `no item for the phone means no web view and one that exists goes`() {
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(phoneItems = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(phoneItems = false, hasView = false)))
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown.copy(phoneItems = true, hasView = false)))
    }

    @Test fun `while the item store is unknown a view is kept but none is made`() {
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown.copy(phoneItems = null)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(phoneItems = null, hasView = false)))
    }

    @Test fun `a relay target is shown like a direct one`() {
        val relayed = shown.copy(target = uiAddonsTarget(relay, null), hasView = false)
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(relayed))
    }

    @Test fun `the switch defaults on from 6 GB and off below or on a low RAM phone`() {
        val gib = 1024L * 1024 * 1024
        // What phones report: totalMem leaves out the kernel's and firmware's share.
        assertTrue(uiAddonsDefaultOn(5_600_000_000L, lowRam = false)) // a 6 GB phone
        assertTrue(uiAddonsDefaultOn(7 * gib + gib / 2, lowRam = false)) // 8 GB
        assertTrue(uiAddonsDefaultOn(UI_ADDONS_MEMORY_LINE, lowRam = false))
        assertFalse(uiAddonsDefaultOn(UI_ADDONS_MEMORY_LINE - 1, lowRam = false))
        assertFalse(uiAddonsDefaultOn(3_800_000_000L, lowRam = false)) // a 4 GB phone
        assertFalse(uiAddonsDefaultOn(2 * gib, lowRam = false))
        assertFalse(uiAddonsDefaultOn(12 * gib, lowRam = true))
    }

    @Test fun `while the companion is unknown a view is kept but none is made`() {
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown.copy(available = null)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(shown.copy(available = null, hasView = false)))
    }

    @Test fun `a view is made only for an open session on a chat in front and uncovered`() {
        val none = shown.copy(hasView = false)
        assertEquals(UiAddonsMode.Off, uiAddonsMode(none.copy(sessionOpen = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(none.copy(chatShown = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(none.copy(started = false)))
        assertEquals(UiAddonsMode.Off, uiAddonsMode(none.copy(covered = true)))
    }

    @Test fun `an existing view steps aside and pauses instead of being destroyed`() {
        assertEquals(UiAddonsMode.Covered, uiAddonsMode(shown.copy(covered = true)))
        assertEquals(UiAddonsMode.Away, uiAddonsMode(shown.copy(chatShown = false)))
        assertEquals(UiAddonsMode.Away, uiAddonsMode(shown.copy(started = false)))
        assertEquals(UiAddonsMode.Away, uiAddonsMode(shown.copy(started = false, covered = true)))
        // A chat that closed its session keeps the page; it follows the next one.
        assertEquals(UiAddonsMode.Shown, uiAddonsMode(shown.copy(sessionOpen = false)))
    }

    @Test fun `insets are the chat chrome in CSS pixels`() {
        // A 1080 x 2400 phone at density 2.625: pane from y 0 to 2400, top bar ends at 252 px
        // (status bar and bar), composer block from y 2100, gutters of 42 px.
        val bounds = UiAddonsChromeBounds(
            paneLeft = 0f, paneTop = 0f, paneRight = 1080f, paneBottom = 2400f,
            contentTop = 252f, composerLeft = 42f, composerTop = 2100f, composerRight = 1038f,
        )
        assertEquals(UiSyncHostMessage.Insets(top = 96.0, right = 16.0, bottom = 114.3, left = 16.0), uiAddonsInsets(bounds, 2.625f))
    }

    @Test fun `insets are relative to a pane that does not start at the window edge`() {
        val docked = UiAddonsChromeBounds(
            paneLeft = 800f, paneTop = 0f, paneRight = 2000f, paneBottom = 1600f,
            contentTop = 160f, composerLeft = 800f, composerTop = 1400f, composerRight = 2000f,
        )
        assertEquals(UiSyncHostMessage.Insets(top = 80.0, right = 0.0, bottom = 100.0, left = 0.0), uiAddonsInsets(docked, 2f))
    }

    @Test fun `no insets until the chrome is measured or with no scale`() {
        val unmeasured = UiAddonsChromeBounds(0f, 0f, 100f, 100f, contentTop = null, composerLeft = null, composerTop = null, composerRight = null)
        assertNull(uiAddonsInsets(unmeasured, 2f))
        val measured = unmeasured.copy(contentTop = 10f, composerTop = 90f)
        assertNotNull(uiAddonsInsets(measured, 2f))
        assertNull(uiAddonsInsets(measured, 0f))
        assertNull(uiAddonsInsets(measured, Float.NaN))
    }

    @Test fun `a composer pushed past the pane edge never gives a negative inset`() {
        val odd = UiAddonsChromeBounds(0f, 0f, 100f, 100f, contentTop = -5f, composerLeft = -10f, composerTop = 120f, composerRight = 130f)
        assertEquals(UiSyncHostMessage.Insets(0.0, 0.0, 0.0, 0.0), uiAddonsInsets(odd, 1f))
    }
}
