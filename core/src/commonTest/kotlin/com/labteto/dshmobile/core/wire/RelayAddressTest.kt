package com.labteto.dshmobile.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RelayPairing.address`: the parts of an address that the pairing flow decides on, read by the platform's
 * own URL parser (OkHttp's on the JVM, `PortableUrls` on iOS). The same file runs on both, so the Pair
 * screen reads an address the same way on Android and on iOS.
 */
class RelayAddressTest {

    @Test
    fun anHttpsAddressIsSecureAndGetsItsDefaultPort() {
        val address = assertNotNull(RelayPairing.address("https://relay.example"))
        assertEquals("https", address.scheme)
        assertEquals("relay.example", address.host)
        assertEquals(443, address.port)
        assertTrue(address.secure)
    }

    @Test
    fun aPlainAddressKeepsItsPortAndIsNotSecure() {
        val address = assertNotNull(RelayPairing.address("http://192.168.1.5:3444"))
        assertEquals(3444, address.port)
        assertFalse(address.secure)
    }

    @Test
    fun theProxyPrefixIsTheEncodedPath() {
        val address = assertNotNull(RelayPairing.address("https://host.example:8443/dsh/relay/"))
        assertEquals("/dsh/relay/", address.encodedPath)
    }

    @Test
    fun anIpv6LiteralIsBareInTheHost() {
        val address = assertNotNull(RelayPairing.address("http://[::1]:3444"))
        assertEquals("::1", address.host)
    }

    @Test
    fun whatIsNotAnAbsoluteHttpAddressIsNull() {
        assertNull(RelayPairing.address(""))
        assertNull(RelayPairing.address("relay.example:3443"))
        assertNull(RelayPairing.address("ftp://relay.example"))
        assertNull(RelayPairing.address("not a url"))
    }
}
