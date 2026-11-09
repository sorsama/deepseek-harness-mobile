package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * `HarnessEndpoint` is `resolveHarnessUrl` for carriers without OkHttp's `HttpUrl`, so it must give
 * the same answer to every question the app can ask: the same URL for a route that is allowed, and a
 * refusal for one that is not. Running both over one table is what keeps the two from drifting; a
 * hand-written expectation per row would only prove each implementation agrees with its author.
 */
class HarnessEndpointTest {

    private val bases = listOf(
        "http://127.0.0.1:8080",
        "http://127.0.0.1:8080/",
        "http://127.0.0.1:80",
        "https://relay.example",
        "https://relay.example:443/",
        "https://relay.example:8443/team/dsh",
        "https://relay.example/team%20one/dsh/",
        "http://[::1]:3080",
        "http://[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443/x",
        "http://Agent.Home:3080/Proxy",
        "http://host:8080/a/./b/../c",
        "http://host:8080/p?ignored=1#alsoIgnored",
    )

    private val routes = listOf(
        "/api/session.list",
        "/api/\$events/result",
        "/api/pluginManager/installBundle",
        "/api/session.export?sessionId=s%201&format=zip",
        "/api/session.export?sessionId=a+b&x",
        "/api/x?y=a b",
        "/api/x?",
        "/api/x?a=1&b",
        "/api/a%20b",
        "/api/a b",
        "/api/a%2Fb",
        "/api/ü",
        "/api/remote.mux",
        "/",
        "/api/./x",
        "/api/x/.",
        "/api/x/..",
        "/api/%2e/x",
        "/api/../x",
        "/api/%2E%2e/x",
        "/api/../../x",
        "/api/../../../x",
        "//evil.example/x",
        "api/x",
        "",
    )

    @Test
    fun `every base and route resolves exactly as resolveHarnessUrl does or is refused by both`() {
        var compared = 0
        for (base in bases) {
            for (route in routes) {
                val expected = runCatching { resolveHarnessUrl(base, route).toString() }
                val actual = runCatching { HarnessEndpoint.of(base).resolve(route) }
                val row = "base=$base route=$route"
                if (expected.isSuccess) {
                    assertEquals(row, expected.getOrThrow(), actual.getOrNull())
                } else {
                    assertFalse("$row should be refused, resolved to ${actual.getOrNull()}", actual.isSuccess)
                    assertEquals(row, IllegalArgumentException::class, actual.exceptionOrNull()!!::class)
                }
                compared++
            }
        }
        assertEquals(bases.size * routes.size, compared)
    }

    @Test
    fun `a route that climbs out of the prefix is refused, one that stays inside is not`() {
        val endpoint = HarnessEndpoint.of("https://relay.example/team/dsh")
        assertEquals("https://relay.example/team/dsh/api/x", endpoint.resolve("/api/./x"))
        assertEquals("https://relay.example/team/dsh/x", endpoint.resolve("/api/../x"))
        try {
            endpoint.resolve("/../../x")
            fail("a route climbing out of /team/dsh must be refused")
        } catch (expected: IllegalArgumentException) {
            assertEquals("route escapes the harness root", expected.message)
        }
    }

    @Test
    fun `the route's fragment is never sent`() {
        assertEquals("http://127.0.0.1:8080/api/x", HarnessEndpoint.of("http://127.0.0.1:8080").resolve("/api/x#frag"))
    }

    @Test
    fun `credentials and non-http bases are refused`() {
        for (base in listOf("http://user:pass@host:8080", "http://user@host", "ftp://host", "ws://host", "not a url", "")) {
            try {
                HarnessEndpoint.of(base)
                fail("$base must be refused")
            } catch (expected: IllegalArgumentException) {
                // Same exception type resolveHarnessUrl gives, which the unary client reports as
                // "not a harness" rather than crashing on.
            }
        }
    }

    @Test
    fun `the host header follows the shared spelling rule`() {
        assertEquals("[::1]:3080", HarnessEndpoint.of("http://[::1]:3080").hostHeader)
        assertEquals("[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443", HarnessEndpoint.of("http://[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443/x").hostHeader)
        assertEquals("[::1]", HarnessEndpoint.of("http://[::1]:80").hostHeader)
        assertEquals("[::1]", HarnessEndpoint.of("https://[::1]:443").hostHeader)
        assertEquals("relay.example", HarnessEndpoint.of("https://relay.example").hostHeader)
        assertEquals("agent.home:3080", HarnessEndpoint.of("http://Agent.Home:3080").hostHeader)
        assertEquals("192.168.1.20:3080", HarnessEndpoint.of("http://192.168.1.20:3080").hostHeader)
        assertEquals("127.0.0.1:8080", HarnessEndpoint.of("http://127.0.0.1:8080/").hostHeader)
    }
}
