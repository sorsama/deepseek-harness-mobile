package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The startup line is the only thing standing between "the app started the harness" and "the app
 * is signed in to it", so the parser has to cope with the line as it is actually printed — behind
 * script markers, coloured, with a LAN suffix — and must never invent a token that is not there.
 */
class HarnessLaunchLineTest {

    @Test
    fun `a line with a token yields the origin and the token`() {
        val line = HarnessLaunchLine.parse("dsh web: http://127.0.0.1:3080/?token=abc-DEF_123")
        assertEquals("http://127.0.0.1:3080", line?.baseUrl)
        assertEquals("abc-DEF_123", line?.token)
        assertEquals("127.0.0.1:3080", line?.authority)
    }

    @Test
    fun `the raw line is kept verbatim for the exchange`() {
        val raw = "dsh web: http://127.0.0.1:3080/?token=t0k3n"
        assertEquals(raw, HarnessLaunchLine.parse("DSHM:started\n$raw\n")?.raw)
    }

    /** Harnesses before 0.1.2 print no token; the line is still a readiness signal. */
    @Test
    fun `a token-less line yields the origin and no token`() {
        val line = HarnessLaunchLine.parse("dsh web: http://127.0.0.1:3080 (LAN: http://192.168.1.20:3080)")
        assertEquals("http://127.0.0.1:3080", line?.baseUrl)
        assertNull(line?.token)
    }

    @Test
    fun `colour codes are stripped before matching`() {
        val line = HarnessLaunchLine.parse("\u001B[32mdsh web:\u001B[0m http://127.0.0.1:3081/?token=x\u001B[0m")
        assertEquals("http://127.0.0.1:3081", line?.baseUrl)
        assertEquals("x", line?.token)
    }

    @Test
    fun `the first startup line wins`() {
        val stdout = "noise\ndsh web: http://127.0.0.1:3080/?token=first\ndsh web: http://127.0.0.1:3080/?token=second"
        assertEquals("first", HarnessLaunchLine.parse(stdout)?.token)
    }

    @Test
    fun `a base64url token keeps its dashes, underscores and padding`() {
        assertEquals("a-b_c=", HarnessLaunchLine.parse("dsh web: http://127.0.0.1:3080/?token=a-b_c=")?.token)
    }

    @Test
    fun `output without the line is not a launch`() {
        assertNull(HarnessLaunchLine.parse(""))
        assertNull(HarnessLaunchLine.parse("DSHM:missing-dsh"))
        assertNull(HarnessLaunchLine.parse("dsh web: (no url)"))
    }
}
