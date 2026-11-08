package com.labteto.dshmobile.core.wire

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PortableUrls` is the iOS answer to every URL question the connect flow asks; on the JVM the same
 * questions go to OkHttp's `HttpUrl`. Both are run over the same tables here, so what the iOS code
 * says about an address, a redirect or a route is what the Android code says, apart from the
 * differences this file lists by name.
 */
class PortableUrlsTest {

    /** What OkHttp makes of [text], in [WireUrl]'s terms, without its fragment (which iOS drops). */
    private fun jvm(text: String): WireUrl? = parseWireUrl(text)?.let(::withoutFragment)

    /** OkHttp's text without the two things iOS leaves out of it: the fragment and the user info. */
    private fun withoutFragment(url: WireUrl): WireUrl = WireUrl(
        url.scheme,
        url.host,
        url.port,
        url.encodedPath,
        url.hasCredentials,
        url.text.substringBefore('#').replaceFirst(Regex("^(https?://)[^/?]*@"), "$1"),
    )

    /** Every mismatch of the running test, so one failure shows the whole table's disagreements. */
    private val problems = mutableListOf<String>()

    private fun same(label: String, expected: WireUrl?, actual: WireUrl?) {
        fun check(what: String, want: Any?, got: Any?) {
            if (want != got) problems += "$label: $what\n    OkHttp: $want\n    here:   $got"
        }
        check("accepted", expected != null, actual != null)
        if (expected == null || actual == null) return
        check("text", expected.text, actual.text)
        check("scheme", expected.scheme, actual.scheme)
        check("host", expected.host, actual.host)
        check("port", expected.port, actual.port)
        check("path", expected.encodedPath, actual.encodedPath)
        check("credentials", expected.hasCredentials, actual.hasCredentials)
    }

    private fun assertNoProblems() {
        assertTrue("${problems.size} disagreement(s):\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    // ---------------------------------------------------------------- parse

    private val addresses = listOf(
        "http://127.0.0.1:8080", "http://127.0.0.1:8080/", "http://127.0.0.1:80", "https://relay.example",
        "https://relay.example:443/", "HTTPS://Relay.EXAMPLE:443/", "HtTp://Relay.Example", "https://relay.example:8443/team/dsh",
        "https://relay.example/team%20one/dsh/", "https://relay.example/team one/dsh", "http://[::1]:3080", "http://[::1]",
        "https://[::1]:443/x", "http://[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443/x", "http://[FDEF::1]:3080",
        "http://Agent.Home:3080/Proxy", "http://host:8080/a/./b/../c", "http://host/a/%2e/b/%2E%2e/c",
        "http://host/p?q=1#frag", "http://host/p?q=a b&r='x'", "http://host?x=1", "http://host#f", "http://host/?", "http://host/a?b?c",
        "http://host/p#f?q", "http://user@host", "http://user:pass@host:81/p", "http://@host", "http://:@host", "http://:pass@host",
        "http://a@b@host/", "http://us%40er:p@host/", "http://host:/x", "  https://host  ", "\thttp://host\n", "http:\\\\host\\x\\y",
        "http:/\\host/x", "http:////host/x", "http:/host", "http:host", "http://ho<st/", "http://192.168.1.5:3443", "http://192.168.1.140:3443/", "https://192.168.1.140:3443/",
        "http://1.2.3", "http://999.1.1.1", "http://a_b.c-d.e", "http://host:65535", "http://host:1", "http://host:08080/",
        "https://host/ü", "https://host/a%zz", "https://host/a%", "https://host/a|b^c`d{e}f", "https://host/a\"b<c>d",
        "http://host/a\\b", "http://10.0.2.2:3080", "http://localhost", "http://LOCALHOST:3000/dsh/",
    )

    private val refusedAddresses = listOf(
        "", "   ", "host", "host:8080", "localhost:3000/path", "192.168.1.5:3443", "192.168.1.5", "ftp://host", "ws://host/x",
        "mailto:a@b.co", "javascript:alert(1)", "file:///etc/passwd", "http://", "http:///", "http://:80", "http://@", "https://:443/x",
        "http://host:99999", "http://host:0", "http://host:65536", "http://host:-1", "http://host:abc", "http://host:80:90",
        "http://[::1", "http://[::1]x", "http://[]", "http://[g::1]", "http://ho st/", "http://host name",
        "not a url", "http//host", "://host", "http:", "https:/",
    )

    @Test
    fun `an address is read as OkHttp reads it`() {
        for (text in addresses) same("<$text>", jvm(text), PortableUrls.parse(text)?.let(::withoutFragment))
        assertNoProblems()
    }

    @Test
    fun `text that is not an http address is refused by both`() {
        for (text in refusedAddresses) {
            if (jvm(text) != null) problems += "<$text>: OkHttp accepts it (${jvm(text)})"
            if (PortableUrls.parse(text) != null) problems += "<$text>: PortableUrls accepts it (${PortableUrls.parse(text)})"
        }
        assertNoProblems()
    }

    @Test
    fun `the canonical text OkHttp writes parses back to itself`() {
        // The JVM actuals reuse the HttpUrl they were given; this is what lets any other WireUrl be
        // re-parsed from its text without changing it.
        for (text in addresses) {
            val canonical = text.toHttpUrlOrNull()?.toString() ?: continue
            assertEquals("<$text>", canonical, canonical.toHttpUrl().toString())
        }
    }

    @Test
    fun `the differences from OkHttp are the ones documented`() {
        // Internationalised and percent-encoded host names: OkHttp converts, PortableUrls refuses.
        for (text in listOf("http://münchen.de/", "http://host%2eexample/", "http://höst:8080")) {
            assertNotNull("OkHttp accepts <$text>", text.toHttpUrlOrNull())
            assertNull("PortableUrls refuses <$text>", PortableUrls.parse(text))
        }
        // Unusual literal spellings: OkHttp rewrites them, PortableUrls keeps what was typed.
        assertEquals("[::1]", "http://[0:0:0:0:0:0:0:1]/".toHttpUrl().host.let { "[$it]" })
        assertEquals("0:0:0:0:0:0:0:1", PortableUrls.parse("http://[0:0:0:0:0:0:0:1]/")!!.host)
        assertEquals("192.168.1.5", "http://[::ffff:192.168.1.5]:3080/".toHttpUrl().host)
        assertEquals("::ffff:192.168.1.5", PortableUrls.parse("http://[::ffff:192.168.1.5]:3080/")!!.host)
        // The fragment is dropped.
        assertEquals("http://host/p", PortableUrls.parse("http://host/p#frag")!!.text)
        assertEquals("http://host/p#frag", "http://host/p#frag".toHttpUrl().toString())
    }

    // ---------------------------------------------------------------- resolve

    private val bases = listOf(
        "http://h:8080/a/b/c?x=1", "https://h/", "https://h", "https://h/dsh/relay/health", "https://h/a/b/", "http://[::1]:3080/p/q?z",
        "http://user:pw@h/a/b", "http://h/a%20b/c d",
    )

    private val references = listOf(
        "", "#f", "?y=2", "?", "d", "./d", "../d", "../../d", "../../../d", "/abs", "/abs?q=1", "/abs/../x", "/abs#f", "//other/x",
        "//other:81", "\\\\other\\x", "http://abs.example/p", "https://abs.example", "HTTPS://Abs.Example:444/Q?r=1", "ftp://x",
        "mailto:a@b", "javascript:alert(1)", "http:rel", "https:rel", "http:/rel", "https:/rel", " /padded ", "d e", "d%20e",
        "d/../../e", ".", "..", "./", "../", "/..", "%2e%2e/x", "/relay/health", "/other/dsh/relay/health", "/login", "d?x=1#f",
        "d#f", "http://", "//", "a\\b", "/a\\b", "x:y", "1:2",
    )

    @Test
    fun `a reference resolves against a base as OkHttp resolves it`() {
        for (baseText in bases) {
            val jvmBase = parseWireUrl(baseText)!!
            val portableBase = PortableUrls.parse(baseText)!!
            for (reference in references) {
                val expected = jvmBase.resolveReference(reference)?.let(::withoutFragment)
                val actual = PortableUrls.resolve(portableBase, reference)?.let(::withoutFragment)
                same("base=<$baseText> ref=<$reference>", expected, actual)
            }
        }
        assertNoProblems()
    }

    // ---------------------------------------------------------------- query

    @Test
    fun `a query parameter is appended as OkHttp appends it`() {
        val values = listOf(
            "abc", "a b", "a+b", "a&b=c", "100%", "a/b?c#d", "café", "中文", "😀", "~tilde!*'()", "token_9-X.y",
            "", "x".repeat(200), "a\u0000b", "tab\there",
        )
        for (baseText in listOf("http://h/", "https://h:8443/dsh/", "http://h/p?x=1", "http://[::1]:3080/?a=b&c")) {
            val jvmBase = parseWireUrl(baseText)!!
            val portableBase = PortableUrls.parse(baseText)!!
            for (value in values) {
                same(
                    "base=<$baseText> value=<$value>",
                    jvmBase.withQueryParameter("token", value),
                    PortableUrls.withQueryParameter(portableBase, "token", value),
                )
            }
            same("name needing escapes", jvmBase.withQueryParameter("a b&c", "v"), PortableUrls.withQueryParameter(portableBase, "a b&c", "v"))
        }
        assertNoProblems()
    }

    // ---------------------------------------------------------------- harness routes

    private val harnessBases = listOf(
        "http://127.0.0.1:8080", "http://127.0.0.1:8080/", "https://relay.example", "https://relay.example:443/",
        "https://relay.example:8443/team/dsh", "https://relay.example/team%20one/dsh/", "http://[::1]:3080",
        "http://[fdef:c0:c0:b216:f9bf:82c:1d97:d12a]:3443/x", "http://Agent.Home:3080/Proxy", "http://host:8080/a/./b/../c",
        "http://host:8080/p?ignored=1#alsoIgnored", "http://user:pass@host:8080", "ftp://host", "not a url", "",
    )

    private val harnessRoutes = listOf(
        "/", "/api/session.list", "/api/\$events/result", "/api/session.export?sessionId=s%201&format=zip", "/api/x?y=a b",
        "/api/a%2Fb", "/api/ü", "/api/../x", "/api/../../x", "/api/../../../x", "//evil.example/x", "api/x", "", "/relay/health",
        "/relay/pair",
    )

    @Test
    fun `a harness route resolves as resolveHarnessUrl resolves it or is refused by both`() {
        for (base in harnessBases) {
            for (route in harnessRoutes) {
                val expected = runCatching { resolveHarnessWireUrl(base, route) }
                val actual = runCatching { PortableUrls.harness(base, route) }
                val row = "base=<$base> route=<$route>"
                if (expected.isSuccess != actual.isSuccess) problems += "$row: OkHttp ${expected.getOrNull()} here ${actual.getOrNull()}"
                else if (expected.isSuccess) same(row, expected.getOrThrow(), actual.getOrThrow())
                else if (actual.exceptionOrNull() !is IllegalArgumentException) problems += "$row: refused with ${actual.exceptionOrNull()}"
            }
        }
        assertNoProblems()
    }

    @Test
    fun `the session exchange URL carries the token as OkHttp writes it`() {
        for (token in listOf("abc-DEF_123", "a=b", "a+b", "x/y", "100%", "é")) {
            val expected = resolveHarnessWireUrl("http://127.0.0.1:3080/", "/").withQueryParameter("token", token)
            val actual = PortableUrls.harness("http://127.0.0.1:3080/", "/").let { PortableUrls.withQueryParameter(it, "token", token) }
            same("token <$token>", expected, actual)
            assertFalse(actual.text.contains(' '))
        }
        assertNoProblems()
    }
}
