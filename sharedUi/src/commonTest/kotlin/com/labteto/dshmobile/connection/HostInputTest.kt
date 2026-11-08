package com.labteto.dshmobile.connection

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNotEquals
import com.labteto.dshmobile.testing.assertNull
import kotlin.test.Test

/**
 * The host field takes what people actually have, which is usually a pasted URL: the line the
 * harness printed, or a reverse proxy's address bar. Before this parser existed, the field's text
 * went to DNS verbatim — so "https://agent.home" was reported as a computer that does not exist,
 * with advice about ipconfig (#6).
 */
class HostInputTest {

    @Test
    fun `a bare host says nothing about port or tls`() {
        assertEquals(HostInput("192.168.1.20", null, null), parseHostInput("192.168.1.20"))
        assertEquals(HostInput("agent.home", null, null), parseHostInput("agent.home"))
        assertEquals(HostInput("localhost", null, null), parseHostInput(" localhost "))
    }

    @Test
    fun `a scheme decides tls in either direction`() {
        assertEquals(HostInput("agent.home", null, true), parseHostInput("https://agent.home"))
        assertEquals(HostInput("agent.home", null, false), parseHostInput("http://agent.home"))
        assertEquals(HostInput("agent.home", null, true), parseHostInput("HTTPS://agent.home"))
    }

    @Test
    fun `a port typed into the host field is kept`() {
        assertEquals(HostInput("192.168.1.20", 3080, null), parseHostInput("192.168.1.20:3080"))
        assertEquals(HostInput("agent.home", 8443, true), parseHostInput("https://agent.home:8443"))
        assertEquals(HostInput("127.0.0.1", 3080, false), parseHostInput("http://127.0.0.1:3080"))
    }

    @Test
    fun `connection failure addresses keep the proxy path out of the port`() {
        assertEquals(HostInput("10.0.2.2", 3192, null, "/dsh"), parseHostInput("10.0.2.2:3192/dsh"))
        assertEquals(HostInput("10.0.2.2", 3192, false, "/dsh"), parseHostInput("http://10.0.2.2:3192/dsh/"))
        assertEquals(HostInput("::1", 3192, true, "/dsh"), parseHostInput("https://[::1]:3192/dsh"))
    }

    @Test
    fun `prefixed relay identity is distinct from its root and other prefixes`() {
        val root = HostConfig("root-id", "relay", "relay.test", 443, useTls = true, relayDeviceId = "root-device")
        val prefixed = root.copy(id = "prefix-id", basePath = "/team", relayDeviceId = "team-device")
        assertNotEquals(root.baseUrl, prefixed.baseUrl)
        assertNotEquals(root.authority, prefixed.authority)
        assertNotEquals(prefixed.baseUrl, prefixed.copy(basePath = "/other").baseUrl)
        assertEquals("https://relay.test:443/team", prefixed.baseUrl)
    }

    /** The harness prints its URL with a trailing slash, and the GUI's address bar carries paths. */
    @Test
    fun `paths queries and fragments are not part of the host`() {
        assertEquals(HostInput("192.168.1.20", 3080, false), parseHostInput("http://192.168.1.20:3080/"))
        assertEquals(HostInput("agent.home", null, true, "/chat"), parseHostInput("https://agent.home/chat?x=1#top"))
        assertEquals(HostInput("agent.home", null, null, "/chat"), parseHostInput("agent.home/chat"))
    }

    @Test
    fun `ipv6 literals survive with and without brackets`() {
        assertEquals(HostInput("::1", null, null), parseHostInput("::1"))
        assertEquals(HostInput("::1", null, null), parseHostInput("[::1]"))
        assertEquals(HostInput("::1", 3080, null), parseHostInput("[::1]:3080"))
        assertEquals(HostInput("fe80::1", 3080, false), parseHostInput("http://[fe80::1]:3080"))
    }

    @Test
    fun `unusable input is refused rather than guessed at`() {
        assertNull(parseHostInput(""))
        assertNull(parseHostInput("   "))
        assertNull(parseHostInput("ftp://agent.home"))
        assertNull(parseHostInput("https://"))
        assertNull(parseHostInput("agent.home:notaport"))
        assertNull(parseHostInput("agent.home:70000"))
        assertNull(parseHostInput("agent.home:0"))
        assertNull(parseHostInput("[::1"))
        assertNull(parseHostInput("[]:3080"))
        assertNull(parseHostInput("[::1]3080"))
        assertNull(parseHostInput("http:///chat"))
        assertNull(parseHostInput("a b"))
    }

    @Test
    fun `url authorities bracket ipv6 literals and leave everything else alone`() {
        assertEquals("192.168.1.20:3080", urlAuthority("192.168.1.20", 3080))
        assertEquals("agent.home:443", urlAuthority("agent.home", 443))
        assertEquals("[::1]:3080", urlAuthority("::1", 3080))
    }

    @Test
    fun `base urls carry the scheme the endpoint needs`() {
        assertEquals("http://192.168.1.20:3080", harnessBaseUrl("192.168.1.20", 3080, useTls = false))
        assertEquals("https://agent.home:443", harnessBaseUrl("agent.home", 443, useTls = true))
        assertEquals("http://[::1]:3080", harnessBaseUrl("::1", 3080, useTls = false))
    }
}
