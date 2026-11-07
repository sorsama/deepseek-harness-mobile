package com.labteto.dshmobile.core.wire

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How `/api` requests spell their `Host`.
 *
 * Issue #24: on an IPv6-only overlay the app paired and then had every `/api` call refused with
 * `unparsable-host`, because this header carried an unbracketed IPv6 literal. Pairing, health and
 * the WebSocket upgrade were all fine — they let OkHttp write the header — so nothing but this
 * function was wrong, and nothing but this function guards it.
 */
class HostHeaderTest {

    @Test
    fun `an ipv6 literal is bracketed so the port stays distinguishable`() {
        // Unbracketed this reads as host `fdef:c0:c0:b216:f9bf:82c:1d97` on port… nothing sane.
        assertEquals(
            "[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443",
            hostHeaderFor("http://[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443".toHttpUrl()),
        )
        assertEquals("[::1]:3080", hostHeaderFor("http://[::1]:3080".toHttpUrl()))
    }

    @Test
    fun `a default port is omitted, brackets and all`() {
        assertEquals("[::1]", hostHeaderFor("http://[::1]:80".toHttpUrl()))
        assertEquals("[::1]", hostHeaderFor("https://[::1]:443".toHttpUrl()))
        // A relay behind a name on :443 is the case this omission exists for.
        assertEquals("relay.example", hostHeaderFor("https://relay.example".toHttpUrl()))
    }

    @Test
    fun `ipv4 and hostnames are spelled exactly as before`() {
        assertEquals("192.168.1.20:3080", hostHeaderFor("http://192.168.1.20:3080".toHttpUrl()))
        assertEquals("agent.home:3080", hostHeaderFor("http://agent.home:3080".toHttpUrl()))
        assertEquals("127.0.0.1:8080", hostHeaderFor("http://127.0.0.1:8080".toHttpUrl()))
    }

    @Test
    fun `authorities are bracketed by the one helper the whole client shares`() {
        assertEquals("[::1]:3080", authorityOf("::1", 3080))
        assertEquals("192.168.1.20:3080", authorityOf("192.168.1.20", 3080))
        assertEquals("agent.home:443", authorityOf("agent.home", 443))
    }

    @Test
    fun `a refusal naming its own reason does not blame the harness`() {
        // dsh-relay refuses in front of the harness, which never sees the request at all.
        assertEquals(
            "request refused before the harness: unparsable-host (HTTP 403)",
            carrierMessage(403, """{"ok":false,"error":"unparsable-host"}"""),
        )
    }

    @Test
    fun `a 403 with nothing to go on keeps the harness wording`() {
        val fence = "harness trust fence rejected the request (HTTP 403)"
        assertEquals(fence, carrierMessage(403))
        assertEquals(fence, carrierMessage(403, ""))
        assertEquals(fence, carrierMessage(403, "Forbidden"))
        // Malformed JSON on a failure path must not become a second failure.
        assertEquals(fence, carrierMessage(403, "{not json"))
        assertEquals(fence, carrierMessage(403, """{"ok":false}"""))
        assertEquals(fence, carrierMessage(403, """{"error":"   "}"""))
    }

    @Test
    fun `other statuses are untouched`() {
        assertEquals("harness has no browser session for this client (HTTP 401)", carrierMessage(401))
        assertEquals("carrier returned HTTP 500", carrierMessage(500, """{"error":"boom"}"""))
    }

    /**
     * Three statuses used to read as "that is not a harness", which sent people to check an
     * address that was never wrong: 413 means the body was too big, 429 means wait, and 502 means
     * the thing in front of the harness is up while the harness is not. Each needs a different
     * response from the person, so each says a different thing.
     */
    @Test
    fun `an over-large body, a throttle and a dead upstream each say what they are`() {
        assertEquals("the harness refused the request as too large (HTTP 413)", carrierMessage(413))
        assertEquals("rate limited before the harness (HTTP 429)", carrierMessage(429))
        assertEquals("nothing answered behind the relay (HTTP 502)", carrierMessage(502))
    }
}
