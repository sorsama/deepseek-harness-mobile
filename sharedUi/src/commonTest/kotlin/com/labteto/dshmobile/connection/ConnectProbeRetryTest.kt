package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * When a connect probes an address again ([retriesConnectProbe]): after a timeout, once. Everything else is an answer
 * from something that replied, and asking again would only give it later.
 */
class ConnectProbeRetryTest {

    private val answers = listOf(
        ProbeOutcome.Reachable(HostDescription("/home/demo")),
        ProbeOutcome.Reachable(null),
        ProbeOutcome.TrustFence,
        ProbeOutcome.Unauthenticated,
        ProbeOutcome.RelayUnauthenticated,
        ProbeOutcome.PairingRequired,
        ProbeOutcome.CertificateChanged,
        ProbeOutcome.Refused,
        ProbeOutcome.DnsFailure,
        ProbeOutcome.Unreachable,
        ProbeOutcome.NotAHarness,
        ProbeOutcome.TlsFailure,
        ProbeOutcome.Other("rate limited"),
    )

    @Test
    fun `a timeout on the first probe is asked once more`() {
        assertTrue(retriesConnectProbe(ProbeOutcome.Timeout, attempt = 1))
    }

    @Test
    fun `a timeout on the last probe is the verdict`() {
        assertEquals(2, CONNECT_PROBE_ATTEMPTS)
        assertFalse(retriesConnectProbe(ProbeOutcome.Timeout, attempt = CONNECT_PROBE_ATTEMPTS))
        assertFalse(retriesConnectProbe(ProbeOutcome.Timeout, attempt = CONNECT_PROBE_ATTEMPTS + 1))
    }

    @Test
    fun `an answer is never asked again`() {
        for (answer in answers) {
            assertFalse("$answer", retriesConnectProbe(answer, attempt = 1))
        }
    }
}
