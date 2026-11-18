package com.labteto.dshmobile.ui.screens.main.browser

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.browser.AddressInput
import com.labteto.dshmobile.core.browser.AddressRefusal
import com.labteto.dshmobile.core.browser.AddressScope
import com.labteto.dshmobile.core.browser.BrowserConnection
import com.labteto.dshmobile.core.browser.parseBrowserAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app half of the Browser tab's address rules: which harness the panel is on, and IDN. */
class BrowserProblemTest {
    private val lan = HostConfig(id = "a", name = "desk", host = "192.168.1.20", port = 3080)
    private val builtIn = HostConfig(id = "b", name = "phone", host = "127.0.0.1", port = 3080, isLoopback = true)
    private val relay = HostConfig(id = "c", name = "relay", host = "192.168.1.20", port = 3443, useTls = true, relayDeviceId = "dev")

    private fun keyOf(host: HostConfig) = "${host.baseUrl}|${host.id}"

    @Test
    fun theConnectionComesFromThePanelsOwnHost() {
        assertEquals(BrowserConnection.LocalNetwork, browserEndpointOf(lan, keyOf(lan)).connection)
        assertEquals(BrowserConnection.ThisDevice, browserEndpointOf(builtIn, keyOf(builtIn)).connection)
        assertEquals(BrowserConnection.Relay, browserEndpointOf(relay, keyOf(relay)).connection)
    }

    @Test
    fun aPanelOfAnotherHostIsOffline() {
        val endpoint = browserEndpointOf(lan, keyOf(builtIn))
        assertEquals(BrowserConnection.Offline, endpoint.connection)
        assertNull(endpoint.host)
        assertNull(browserProblemFor("http://localhost:5173", endpoint))
    }

    @Test
    fun loopbackOnTheBuiltInHarnessLoads() {
        assertNull(browserProblemFor("localhost:5173", browserEndpointOf(builtIn, keyOf(builtIn))))
    }

    @Test
    fun loopbackOnALanHarnessIsExplainedWithTheHostAddress() {
        val problem = browserProblemFor("localhost:5173/app", browserEndpointOf(lan, keyOf(lan)))
        assertEquals(BrowserProblem.HarnessLoopback("http://localhost:5173/app", "http://192.168.1.20:5173/app", relay = false), problem)
    }

    @Test
    fun loopbackBehindARelayIsExplainedAsARelay() {
        val problem = browserProblemFor("http://127.0.0.1:8000/", browserEndpointOf(relay, keyOf(relay)))
        assertEquals(BrowserProblem.HarnessLoopback("http://127.0.0.1:8000/", "http://192.168.1.20:8000/", relay = true), problem)
    }

    @Test
    fun theHarnessItselfIsNotOpened() {
        assertEquals(
            BrowserProblem.HarnessItself("http://192.168.1.20:3080/"),
            browserProblemFor("http://192.168.1.20:3080", browserEndpointOf(lan, keyOf(lan))),
        )
        assertEquals(
            BrowserProblem.HarnessItself("http://localhost:3080/"),
            browserProblemFor("localhost:3080", browserEndpointOf(builtIn, keyOf(builtIn))),
        )
    }

    @Test
    fun otherSchemesAreRefusedWithTheirName() {
        // What was typed is kept, so the address field can go on showing it while the explanation is up.
        assertEquals(
            BrowserProblem.Refused(AddressRefusal.UnsupportedScheme, "intent", typed = "intent://x#Intent;end"),
            browserProblemFor("intent://x#Intent;end", browserEndpointOf(lan, keyOf(lan))),
        )
        assertEquals(
            BrowserProblem.Refused(AddressRefusal.Credentials, null, typed = "http://a@b/"),
            browserProblemFor("http://a@b/", browserEndpointOf(lan, keyOf(lan))),
        )
    }

    @Test
    fun publicPagesLoadOnEveryConnection() {
        listOf(lan, builtIn, relay).forEach { host ->
            assertNull(browserProblemFor("https://example.com", browserEndpointOf(host, keyOf(host))))
        }
    }

    @Test
    fun unicodeHostsAreConvertedWithJavaIdn() {
        val web = parseBrowserAddress("https://bücher.example/", ::idnToAscii) as AddressInput.Web
        assertEquals("https://xn--bcher-kva.example/", web.address.url)
        // A look-alike that IDNA maps to a local name is read as that name, as the web view reads it.
        val circled = parseBrowserAddress("http://ⓛocalhost:5173/", ::idnToAscii) as AddressInput.Web
        assertEquals(AddressScope.Loopback, circled.address.scope)
    }

    @Test
    fun everyConnectionHasANoteExceptOffline() {
        BrowserConnection.entries.forEach { connection ->
            assertEquals(connection != BrowserConnection.Offline, connectionNote(connection) != null)
        }
    }
}
