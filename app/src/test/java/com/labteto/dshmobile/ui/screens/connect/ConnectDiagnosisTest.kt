package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole point of the probe keeping its reason is that these two lead to opposite advice:
 * a refusal means the harness is bound to loopback, a timeout means something is dropping packets.
 */
class ConnectDiagnosisTest {

    @Test
    fun `probe outcomes map to their own diagnosis`() {
        assertEquals(ConnectFailure.TrustFence, ConnectFailure.from(ProbeOutcome.TrustFence))
        assertEquals(ConnectFailure.Refused, ConnectFailure.from(ProbeOutcome.Refused))
        assertEquals(ConnectFailure.Timeout, ConnectFailure.from(ProbeOutcome.Timeout))
        assertEquals(ConnectFailure.DnsFailure, ConnectFailure.from(ProbeOutcome.DnsFailure))
        assertEquals(ConnectFailure.NotAHarness, ConnectFailure.from(ProbeOutcome.NotAHarness))
        assertEquals(ConnectFailure.TlsFailure, ConnectFailure.from(ProbeOutcome.TlsFailure))
    }

    /** No route is a different-network problem; "nothing answered" is the honest reading. */
    @Test
    fun `unreachable reads as a timeout`() {
        assertEquals(ConnectFailure.Timeout, ConnectFailure.from(ProbeOutcome.Unreachable))
    }

    @Test
    fun `an unclassified probe failure keeps the carrier's words`() {
        assertEquals(
            ConnectFailure.Other("socket closed"),
            ConnectFailure.from(ProbeOutcome.Other("socket closed")),
        )
    }

    @Test
    fun `streams that never open are reported as blocked, not as a dead host`() {
        assertEquals(
            ConnectFailure.StreamsBlocked,
            ConnectFailure.from(GenerationFailure.StreamsTimedOut(3_000)),
        )
    }

    @Test
    fun `a stream failure carries its transport kind through`() {
        assertEquals(
            ConnectFailure.TrustFence,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.TRUST_FENCE, "403")),
        )
        assertEquals(
            ConnectFailure.Refused,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.REFUSED, null)),
        )
        assertEquals(
            ConnectFailure.Timeout,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.TIMEOUT, null)),
        )
        assertEquals(
            ConnectFailure.TlsFailure,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.TLS, "handshake failed")),
        )
    }

    @Test
    fun `an unclassifiable stream failure falls back to blocked streams`() {
        assertEquals(
            ConnectFailure.StreamsBlocked,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.OTHER, null)),
        )
    }

    @Test
    fun `a describe failure reads its kind out of the error details`() {
        val fenced = RpcError(
            code = "forbidden",
            message = "harness trust fence rejected the request (HTTP 403)",
            details = TransportFailures.details(TransportFailure.TRUST_FENCE, 403),
        )
        assertEquals(ConnectFailure.TrustFence, ConnectFailure.from(GenerationFailure.DescribeFailed(fenced)))

        val notHarness = RpcError(
            code = "internal",
            message = "decode failed",
            details = TransportFailures.details(TransportFailure.NOT_A_HARNESS),
        )
        assertEquals(ConnectFailure.NotAHarness, ConnectFailure.from(GenerationFailure.DescribeFailed(notHarness)))
    }

    /** A business error (agent-busy, say) carries no transport marker; keep its message. */
    @Test
    fun `a describe failure with no marker falls back to its message`() {
        val business = RpcError("agent-busy", "the agent is busy", JsonObject(emptyMap()))
        assertEquals(
            ConnectFailure.Other("the agent is busy"),
            ConnectFailure.from(GenerationFailure.DescribeFailed(business)),
        )
    }

    /**
     * A relay answers 403 for a missing, expired or revoked token, and never 401 — so the status is
     * identical to the harness's own `Host` fence. The only thing that separates them is whether
     * this device ever paired with the address, which is knowledge the app has and the wire does
     * not.
     */
    @Test
    fun `a 403 from a paired relay means pair again, not add a trusted host`() {
        assertEquals(
            ConnectFailure.PairingRequired,
            ConnectFailure.from(ProbeOutcome.TrustFence, relay = true),
        )
        assertEquals(
            ConnectFailure.PairingRequired,
            ConnectFailure.from(
                GenerationFailure.StreamFailed(TransportFailure.TRUST_FENCE, "403"),
                relay = true,
            ),
        )
        val fenced = RpcError(
            code = "forbidden",
            message = "forbidden",
            details = TransportFailures.details(TransportFailure.TRUST_FENCE, 403),
        )
        assertEquals(
            ConnectFailure.PairingRequired,
            ConnectFailure.from(GenerationFailure.DescribeFailed(fenced), relay = true),
        )
    }

    /** Same status, no relay: the diagnosis that has always been right stays right. */
    @Test
    fun `a 403 from a plain harness is still the trust fence`() {
        assertEquals(ConnectFailure.TrustFence, ConnectFailure.from(ProbeOutcome.TrustFence))
        assertEquals(
            ConnectFailure.TrustFence,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.TRUST_FENCE, "403")),
        )
    }

    /** The probe can decide this itself when it already knows the address is a relay. */
    @Test
    fun `relay-specific probe outcomes map straight through`() {
        assertEquals(ConnectFailure.PairingRequired, ConnectFailure.from(ProbeOutcome.PairingRequired))
        assertEquals(
            ConnectFailure.CertificateChanged,
            ConnectFailure.from(ProbeOutcome.CertificateChanged),
        )
    }

    /** A changed key is never a transport failure to retry into; it needs the user to look at it. */
    @Test
    fun `a pin mismatch is reported as a changed certificate either way`() {
        assertEquals(
            ConnectFailure.CertificateChanged,
            ConnectFailure.from(GenerationFailure.StreamFailed(TransportFailure.CERTIFICATE_PIN, null)),
        )
        assertEquals(
            ConnectFailure.CertificateChanged,
            ConnectFailure.from(
                GenerationFailure.StreamFailed(TransportFailure.CERTIFICATE_PIN, null),
                relay = true,
            ),
        )
    }
}
