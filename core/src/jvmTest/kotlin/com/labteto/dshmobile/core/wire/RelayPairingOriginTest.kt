package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failing over between the origins one invitation names.
 *
 * A relay on a machine with more than one network address cannot tell which one the phone is on.
 * It used to name just one — whatever the request arrived on — and a QR opened on the computer's
 * own loopback became unusable from any phone. The relay now names the others too; this is the
 * client half of that agreement, and these are the rules worth pinning down: the order the relay
 * ranked, duplicates collapsed, unusable entries dropped, and no retry once the answer is about
 * the code rather than the address.
 */
class RelayPairingOriginTest {

    private val payloadWithAlternatives = """
        {"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.122:3443",
         "urls":["https://192.168.1.140:3443","https://10.0.0.5:3443"],
         "fingerprint":"3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
         "code":"48213977","expiresAt":1755500000000}
    """.trimIndent()

    @Test
    fun `the payload keeps the alternative origins`() {
        val parsed = RelayPairing.parsePayload(payloadWithAlternatives)

        assertTrue(parsed is PairingPayloadResult.Valid)
        val payload = (parsed as PairingPayloadResult.Valid).payload
        assertEquals(
            listOf("https://192.168.1.140:3443", "https://10.0.0.5:3443"),
            payload.urls,
        )
    }

    @Test
    fun `a payload from an older relay still parses`() {
        val parsed = RelayPairing.parsePayload(
            """{"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.5:3443","code":"12345678","expiresAt":4102444800000}""",
        )

        assertTrue(parsed is PairingPayloadResult.Valid)
        assertNull((parsed as PairingPayloadResult.Valid).payload.urls)
    }

    @Test
    fun `candidates keep the order the relay ranked`() {
        assertEquals(
            listOf("https://192.168.1.122:3443", "https://192.168.1.140:3443", "https://10.0.0.5:3443"),
            RelayPairing.candidates(
                "https://192.168.1.122:3443",
                listOf("https://192.168.1.140:3443", "https://10.0.0.5:3443"),
            ),
        )
    }

    @Test
    fun `candidates collapse duplicates, including the primary`() {
        assertEquals(
            listOf("https://192.168.1.122:3443", "https://192.168.1.140:3443"),
            RelayPairing.candidates(
                "https://192.168.1.122:3443",
                listOf("https://192.168.1.122:3443", "https://192.168.1.140:3443", "https://192.168.1.140:3443/"),
            ),
        )
    }

    @Test
    fun `candidates drop entries that are not usable origins`() {
        assertEquals(
            listOf("https://192.168.1.122:3443"),
            RelayPairing.candidates(
                "https://192.168.1.122:3443",
                listOf("", "   ", "192.168.1.140:3443", "ftp://10.0.0.5:3443", "not a url"),
            ),
        )
    }

    @Test
    fun `a typed address is still one candidate`() {
        assertEquals(
            listOf("https://192.168.1.5:3443"),
            RelayPairing.candidates("https://192.168.1.5:3443"),
        )
    }

    @Test
    fun `only failures that say nothing about the code are retried`() {
        assertFalse(RelayPairOutcome.Rejected.worthAnotherOrigin())
        assertFalse(RelayPairOutcome.RateLimited(30L).worthAnotherOrigin())
        assertTrue(RelayPairOutcome.NotARelay.worthAnotherOrigin())
        assertTrue(RelayPairOutcome.HostRefused.worthAnotherOrigin())
        assertTrue(RelayPairOutcome.Unreachable(TransportFailure.DNS, null).worthAnotherOrigin())
        // Another address answering with a different key is what a misdirected QR looks like, so it
        // stops rather than shopping the fingerprint around.
        assertFalse(RelayPairOutcome.Unreachable(TransportFailure.CERTIFICATE_PIN, null).worthAnotherOrigin())
    }
}
