package com.labteto.dshmobile.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserAddressTest {
    private fun web(input: String, toAscii: ((String) -> String?)? = null): WebAddress {
        val result = parseBrowserAddress(input, toAscii)
        assertIs<AddressInput.Web>(result, "expected $input to load, got $result")
        return result.address
    }

    private fun refused(input: String): AddressInput.Refused {
        val result = parseBrowserAddress(input)
        assertIs<AddressInput.Refused>(result, "expected $input to be refused, got $result")
        return result
    }

    @Test
    fun localhostWithPortAndNoSchemeBecomesHttp() {
        val a = web("localhost:5173")
        assertEquals("http://localhost:5173/", a.url)
        assertEquals(AddressScope.Loopback, a.scope)
        assertEquals(5173, a.port)
        assertFalse(a.secure)
    }

    @Test
    fun publicNameWithNoSchemeBecomesHttps() {
        val a = web("example.com/docs?q=1")
        assertEquals("https://example.com/docs?q=1", a.url)
        assertEquals(AddressScope.Public, a.scope)
        assertTrue(a.secure)
    }

    @Test
    fun uppercaseSchemeAndHostAreLowered() {
        val a = web("HTTP://LocalHost:3000/Path")
        assertEquals("http://localhost:3000/Path", a.url)
        assertEquals(AddressScope.Loopback, a.scope)
    }

    @Test
    fun defaultPortsAreDropped() {
        assertEquals("http://localhost/", web("http://localhost:80").url)
        assertEquals("https://example.com/", web("https://example.com:443/").url)
        assertEquals(8443, web("https://example.com:8443").port)
    }

    @Test
    fun emptyPortIsNoPort() {
        assertEquals("http://localhost/", web("http://localhost:/").url)
    }

    @Test
    fun badPortsAreRefused() {
        assertEquals(AddressRefusal.Invalid, refused("http://localhost:0/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://localhost:65536/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://localhost:12a/").reason)
    }

    @Test
    fun loopbackLiterals() {
        assertEquals(AddressScope.Loopback, web("http://127.0.0.1:8000").scope)
        assertEquals(AddressScope.Loopback, web("http://127.8.9.10").scope)
        assertEquals(AddressScope.Loopback, web("http://[::1]:5173/").scope)
        assertEquals("http://[::1]:5173/", web("http://[0:0:0:0:0:0:0:1]:5173/").url)
        assertEquals(AddressScope.Loopback, web("http://app.localhost:3000").scope)
        assertEquals(AddressScope.Loopback, web("http://localhost.:3000").scope)
    }

    @Test
    fun everyInterfaceIsWrittenAsLoopback() {
        val v4 = web("http://0.0.0.0:8000/x")
        assertEquals("http://127.0.0.1:8000/x", v4.url)
        assertEquals(AddressScope.Loopback, v4.scope)
        val v6 = web("http://[::]:8000")
        assertEquals("http://[::1]:8000/", v6.url)
        assertEquals(AddressScope.Loopback, v6.scope)
    }

    @Test
    fun numericHostsAreReadAsABrowserReadsThem() {
        assertEquals("http://127.0.0.1/", web("http://127.1/").url)
        assertEquals("http://127.0.0.1/", web("http://0x7f.0.0.1/").url)
        assertEquals("http://127.0.0.1/", web("http://0177.0.0.1/").url)
        assertEquals("http://127.0.0.1/", web("http://2130706433/").url)
        assertEquals(AddressScope.Loopback, web("http://2130706433/").scope)
        assertEquals(AddressRefusal.Invalid, refused("http://08.0.0.1/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://256.0.0.1/").reason)
    }

    @Test
    fun privateNetworks() {
        assertEquals(AddressScope.PrivateNetwork, web("http://10.0.0.5:3000").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://192.168.1.20").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://172.16.0.1").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://172.31.255.255").scope)
        assertEquals(AddressScope.Public, web("http://172.15.0.1").scope)
        assertEquals(AddressScope.Public, web("http://172.32.0.1").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://100.101.102.103").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://[fd12:3456::1]").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://[::ffff:192.168.0.1]").scope)
    }

    @Test
    fun linkLocal() {
        assertEquals(AddressScope.LinkLocal, web("http://169.254.10.1").scope)
        assertEquals(AddressScope.LinkLocal, web("http://[fe80::1]").scope)
        assertEquals(AddressRefusal.Invalid, refused("http://[fe80::1%25wlan0]/").reason)
    }

    @Test
    fun localNames() {
        assertEquals(AddressScope.PrivateNetwork, web("http://devbox.local:8080").scope)
        assertEquals(AddressScope.PrivateNetwork, web("nas.lan").scope)
        assertEquals("http://nas.lan/", web("nas.lan").url)
        assertEquals(AddressScope.PrivateNetwork, web("http://printer.home.arpa").scope)
        assertEquals(AddressScope.PrivateNetwork, web("http://devbox").scope)
        assertEquals(AddressScope.Public, web("http://local.example.com").scope)
    }

    @Test
    fun credentialsAreRefused() {
        assertEquals(AddressRefusal.Credentials, refused("http://a@b/").reason)
        assertEquals(AddressRefusal.Credentials, refused("https://user:pass@example.com/").reason)
        assertEquals(AddressRefusal.Credentials, refused("example.com@localhost").reason)
    }

    @Test
    fun otherSchemesAreRefused() {
        listOf(
            "javascript:alert(1)" to "javascript",
            "JavaScript:alert(1)" to "javascript",
            "intent://scan/#Intent;scheme=zxing;end" to "intent",
            "file:///etc/hosts" to "file",
            "data:text/html,<b>x</b>" to "data",
            "content://com.example/x" to "content",
            "dshmobile://pair" to "dshmobile",
            "about:blank" to "about",
            "ftp://example.com" to "ftp",
        ).forEach { (input, scheme) ->
            val r = refused(input)
            assertEquals(AddressRefusal.UnsupportedScheme, r.reason, input)
            assertEquals(scheme, r.scheme, input)
        }
    }

    @Test
    fun hostsABrowserWouldReadDifferentlyAreRefused() {
        assertEquals(AddressRefusal.Invalid, refused("http://evil.test\\@good.test/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://%6c%6fcalhost/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http:/example.com").reason)
        assertEquals(AddressRefusal.Invalid, refused("//example.com").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://a..b/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://[::1/").reason)
        assertEquals(AddressRefusal.Invalid, refused("http:///path").reason)
    }

    @Test
    fun emptyAndWhitespace() {
        assertEquals(AddressRefusal.Empty, refused("   ").reason)
        assertEquals(AddressRefusal.Invalid, refused("http://exa mple.com").reason)
        assertEquals(AddressRefusal.TooLong, refused("http://a.com/" + "x".repeat(BrowserAddress.MAX_LENGTH)).reason)
    }

    @Test
    fun fullwidthLocalhostIsLoopback() {
        val a = web("http://ｌｏｃａｌｈｏｓｔ:5173/")
        assertEquals("http://localhost:5173/", a.url)
        assertEquals(AddressScope.Loopback, a.scope)
    }

    @Test
    fun punycodeHostIsPublic() {
        val a = web("https://xn--bcher-kva.example/")
        assertEquals("xn--bcher-kva.example", a.host)
        assertEquals(AddressScope.Public, a.scope)
    }

    @Test
    fun unicodeHostIsConvertedWhenAConverterIsGiven() {
        val a = web("https://bücher.example/", toAscii = { if (it == "bücher.example") "xn--bcher-kva.example" else null })
        assertEquals("https://xn--bcher-kva.example/", a.url)
        // A converter that maps a look-alike to a local name is believed: the web view would do the same.
        assertEquals(AddressScope.Loopback, web("http://ⓛocalhost/", toAscii = { "localhost" }).scope)
        assertEquals(AddressRefusal.Invalid, (parseBrowserAddress("http://bücher.example/", toAscii = { null }) as AddressInput.Refused).reason)
    }

    @Test
    fun unicodeHostWithoutAConverterIsKeptAndPublic() {
        val a = web("https://bücher.example/")
        assertEquals("bücher.example", a.host)
        assertEquals(AddressScope.Public, a.scope)
    }

    @Test
    fun ipv6Compression() {
        assertEquals("http://[2001:db8::1]/", web("http://[2001:0DB8:0:0:0:0:0:1]/").url)
        assertEquals(AddressScope.Public, web("http://[2001:db8::1]/").scope)
        assertEquals(AddressRefusal.Invalid, refused("http://[1::2::3]/").reason)
    }

    @Test
    fun fragmentAndQueryOnlyGetARootPath() {
        assertEquals("http://localhost:3000/?a=1", web("localhost:3000?a=1").url)
        assertEquals("http://localhost:3000/#top", web("localhost:3000#top").url)
    }

    @Test
    fun isLocalWebAddressMatchesScope() {
        assertTrue(isLocalWebAddress("http://localhost:5173"))
        assertTrue(isLocalWebAddress("http://192.168.1.5:5173/"))
        assertFalse(isLocalWebAddress("https://example.com"))
        assertFalse(isLocalWebAddress("javascript:alert(1)"))
        assertNull((parseBrowserAddress("") as? AddressInput.Web))
    }
}
