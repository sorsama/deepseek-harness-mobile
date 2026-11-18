package com.labteto.dshmobile.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BrowserReachTest {
    private fun address(raw: String): WebAddress = (parseBrowserAddress(raw) as AddressInput.Web).address

    @Test
    fun publicPagesOpenOnEveryConnection() {
        BrowserConnection.entries.forEach { connection ->
            assertEquals(BrowserReach.Open, browserReach(address("https://example.com"), connection, "192.168.1.5", 3080), connection.name)
        }
    }

    @Test
    fun loopbackOpensWhenTheHarnessIsOnThisPhone() {
        assertEquals(BrowserReach.Open, browserReach(address("http://localhost:5173"), BrowserConnection.ThisDevice, "127.0.0.1", 3080))
    }

    @Test
    fun loopbackOpensWhenOffline() {
        assertEquals(BrowserReach.Open, browserReach(address("http://localhost:5173"), BrowserConnection.Offline, null, null))
    }

    @Test
    fun loopbackOnALanHarnessIsNotLoadedAndOffersTheHarnessAddress() {
        val reach = browserReach(address("http://localhost:5173/app?x=1#y"), BrowserConnection.LocalNetwork, "192.168.1.5", 3080)
        assertIs<BrowserReach.HarnessLoopback>(reach)
        assertEquals("http://192.168.1.5:5173/app?x=1#y", reach.onHarnessAddress)
    }

    @Test
    fun loopbackBehindARelayOnTheLanOffersTheRelayHostAddress() {
        val reach = browserReach(address("http://127.0.0.1:8000/"), BrowserConnection.Relay, "10.0.0.7", 3443, harnessSecure = true)
        assertEquals(BrowserReach.HarnessLoopback("http://10.0.0.7:8000/"), reach)
    }

    @Test
    fun noAlternativeWhenTheHarnessIsReachedByAPublicName() {
        val reach = browserReach(address("http://localhost:5173"), BrowserConnection.Relay, "agent.example.com", 443, harnessSecure = true)
        assertEquals(BrowserReach.HarnessLoopback(null), reach)
    }

    @Test
    fun ipv6HarnessHostIsBracketedInTheAlternative() {
        val reach = browserReach(address("http://localhost:5173"), BrowserConnection.LocalNetwork, "fd00::5", 3080)
        assertEquals(BrowserReach.HarnessLoopback("http://[fd00::5]:5173/"), reach)
    }

    @Test
    fun loopbackOnTheHarnessPortWouldBeTheHarnessItselfSoNoAlternative() {
        val reach = browserReach(address("http://localhost:3080"), BrowserConnection.LocalNetwork, "192.168.1.5", 3080)
        assertEquals(BrowserReach.HarnessLoopback(null), reach)
    }

    @Test
    fun theHarnessItselfIsRecognised() {
        assertEquals(BrowserReach.HarnessItself, browserReach(address("http://localhost:3080/"), BrowserConnection.ThisDevice, "127.0.0.1", 3080))
        assertEquals(BrowserReach.HarnessItself, browserReach(address("http://192.168.1.5:3080/x"), BrowserConnection.LocalNetwork, "192.168.1.5", 3080))
        assertEquals(BrowserReach.HarnessItself, browserReach(address("https://192.168.1.5:3443/"), BrowserConnection.Relay, "192.168.1.5", 3443, harnessSecure = true))
    }

    @Test
    fun privateNetworkAddressesOpenOnEveryConnection() {
        BrowserConnection.entries.forEach { connection ->
            assertEquals(BrowserReach.Open, browserReach(address("http://192.168.1.5:5173"), connection, "192.168.1.5", 3080), connection.name)
        }
    }

    @Test
    fun connectionKindFromTheEndpoint() {
        assertEquals(BrowserConnection.Offline, browserConnectionOf(connected = false, isRelay = true, isLoopback = false))
        assertEquals(BrowserConnection.Relay, browserConnectionOf(connected = true, isRelay = true, isLoopback = false))
        assertEquals(BrowserConnection.ThisDevice, browserConnectionOf(connected = true, isRelay = false, isLoopback = true))
        assertEquals(BrowserConnection.LocalNetwork, browserConnectionOf(connected = true, isRelay = false, isLoopback = false))
    }
}
