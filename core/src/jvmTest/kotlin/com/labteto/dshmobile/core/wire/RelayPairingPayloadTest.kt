package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place this client is deliberately strict.
 *
 * Everywhere else the wire layer degrades on shape — unknown events, frames and tool cards fall
 * through to passthroughs rather than failing. A pairing payload is the exception, because acting on
 * one hands a credential to whatever the QR named: a `kind` this build does not recognise is not
 * "some future field to ignore", and a `v` above the one it understands may carry a constraint it
 * would silently skip.
 */
class RelayPairingPayloadTest {

    private val full = """
        {"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.5:3443",
         "plainUrl":"http://192.168.1.5:3444",
         "fingerprint":"3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
         "code":"48213977","expiresAt":1755500000000}
    """.trimIndent()

    @Test
    fun `a complete payload parses`() {
        val result = RelayPairing.parsePayload(full)
        val payload = (result as PairingPayloadResult.Valid).payload
        assertEquals(1, payload.v)
        assertEquals("https://192.168.1.5:3443", payload.url)
        assertEquals("http://192.168.1.5:3444", payload.plainUrl)
        assertEquals("48213977", payload.code)
        assertEquals(1755500000000L, payload.expiresAt)
        assertFalse(payload.isPlaintext)
    }

    /** Both are absent on a relay serving plaintext, and neither is required to pair. */
    @Test
    fun `the optional halves may be missing`() {
        val result = RelayPairing.parsePayload(
            """{"v":1,"kind":"dsh-relay-pair","url":"http://10.0.0.4:3444","code":"11112222","expiresAt":1}""",
        )
        val payload = (result as PairingPayloadResult.Valid).payload
        assertNull(payload.plainUrl)
        assertNull(payload.fingerprint)
        assertTrue(payload.isPlaintext)
    }

    @Test
    fun `a newer payload version is refused rather than guessed at`() {
        val result = RelayPairing.parsePayload(
            """{"v":2,"kind":"dsh-relay-pair","url":"https://h:3443","code":"1","expiresAt":1}""",
        )
        assertEquals(PairingPayloadResult.TooNew(2), result)
    }

    @Test
    fun `some other QR is not a pairing code`() {
        assertEquals(
            PairingPayloadResult.NotAPairingCode,
            RelayPairing.parsePayload("""{"v":1,"kind":"wifi","url":"https://h:3443","code":"1","expiresAt":1}"""),
        )
        assertEquals(PairingPayloadResult.NotAPairingCode, RelayPairing.parsePayload("https://example.com"))
        assertEquals(PairingPayloadResult.NotAPairingCode, RelayPairing.parsePayload(""))
    }

    /** A payload naming something that is not an address cannot be claimed against. */
    @Test
    fun `an unusable url is refused`() {
        assertEquals(
            PairingPayloadResult.NotAPairingCode,
            RelayPairing.parsePayload(
                """{"v":1,"kind":"dsh-relay-pair","url":"not a url","code":"1","expiresAt":1}""",
            ),
        )
    }

    /** Unknown keys still pass: a newer relay adding a field is not the same as bumping `v`. */
    @Test
    fun `unknown keys are ignored at the same version`() {
        val result = RelayPairing.parsePayload(
            """{"v":1,"kind":"dsh-relay-pair","url":"https://h:3443","code":"1","expiresAt":1,"note":"hi"}""",
        )
        assertTrue(result is PairingPayloadResult.Valid)
    }

    @Test
    fun `expiry is judged against the clock the caller supplies`() {
        val payload = (RelayPairing.parsePayload(full) as PairingPayloadResult.Valid).payload
        assertTrue(payload.isLive(payload.expiresAt - 1))
        assertFalse(payload.isLive(payload.expiresAt))
    }
}
