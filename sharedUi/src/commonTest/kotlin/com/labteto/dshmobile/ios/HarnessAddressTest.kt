package com.labteto.dshmobile.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a person can type into the address field, and what it turns into. */
class HarnessAddressTest {

    private fun parsed(raw: String): HarnessAddress = assertNotNull(parseHarnessAddress(raw), "expected \"$raw\" to parse")

    @Test
    fun aBareHostGetsTheDefaultPort() {
        val address = parsed("192.168.1.20")
        assertEquals("192.168.1.20", address.host)
        assertEquals(3080, address.port)
        assertEquals("http://192.168.1.20:3080", address.baseUrl)
        assertEquals("192.168.1.20:3080", address.authority)
        assertEquals("http://192.168.1.20:3080/api/remote.mux", address.muxUrl)
    }

    @Test
    fun aHostAndPortAreKept() {
        assertEquals("http://my-pc.local:4100", parsed("my-pc.local:4100").baseUrl)
        assertEquals("http://127.0.0.1:3181", parsed("  127.0.0.1:3181  ").baseUrl)
    }

    @Test
    fun anHttpUrlIsAccepted() {
        assertEquals("http://192.168.1.20:3080", parsed("http://192.168.1.20").baseUrl)
        assertEquals("http://192.168.1.20:9000", parsed("HTTP://192.168.1.20:9000/").baseUrl)
    }

    @Test
    fun anHttpsUrlDefaultsToThePortOfItsScheme() {
        val address = parsed("https://harness.example.org")
        assertEquals(true, address.tls)
        assertEquals(443, address.port)
        assertEquals("https://harness.example.org:443", address.baseUrl)
    }

    @Test
    fun aReverseProxyPathIsKeptWithoutItsTrailingSlash() {
        val address = parsed("https://example.org:8443/team/dsh/")
        assertEquals("/team/dsh", address.basePath)
        assertEquals("https://example.org:8443/team/dsh/api/remote.mux", address.muxUrl)
    }

    @Test
    fun anIpv6LiteralIsBracketedInTheUrlAndBareInTheHost() {
        assertEquals("::1", parsed("::1").host)
        assertEquals("http://[::1]:3080", parsed("::1").baseUrl)
        assertEquals("http://[::1]:4000", parsed("[::1]:4000").baseUrl)
        assertEquals("[fe80::1]:3080", parsed("http://[fe80::1]").authority)
    }

    @Test
    fun whatIsNotAnAddressIsRefused() {
        val refused = listOf(
            "",
            "   ",
            "ftp://192.168.1.20",
            "ws://192.168.1.20",
            "user:secret@192.168.1.20",
            "http://user@192.168.1.20",
            "192.168.1.20:0",
            "192.168.1.20:65536",
            "192.168.1.20:abc",
            "192.168.1. 20",
            "host\\name",
            "[::1",
            "[]:3080",
            "http://",
            ":3080",
        )
        for (raw in refused) assertNull(parseHarnessAddress(raw), "\"$raw\" should be refused")
    }
}
