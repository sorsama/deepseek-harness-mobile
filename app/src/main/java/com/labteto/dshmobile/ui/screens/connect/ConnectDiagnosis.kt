package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures

/**
 * Why a connection attempt did not succeed, at the level a person can act on.
 *
 * One step above [ProbeOutcome]: the probe knows what the socket did, this knows what to tell
 * someone standing between a phone and a computer. Deliberately free of Android imports so the whole
 * mapping is unit-testable — the app's tests are plain JVM, with no Robolectric.
 */
sealed interface ConnectFailure {

    /** The address or port was not usable as typed. */
    data object InvalidInput : ConnectFailure

    /** The address is not on this phone's own /24, so nothing here can reach it. */
    data class DifferentSubnet(val localPrefix: String?) : ConnectFailure

    /** Nothing answered — dropped packets. Firewall, or a router isolating wireless clients. */
    data object Timeout : ConnectFailure

    /** Actively refused — the computer is there, the harness is not listening on that port. */
    data object Refused : ConnectFailure

    /** The harness answered and its `Host` trust fence rejected this address. */
    data object TrustFence : ConnectFailure

    /** The name did not resolve on this network. */
    data object DnsFailure : ConnectFailure

    /** Something is listening, but it is not a harness. */
    data object NotAHarness : ConnectFailure

    /** The TLS handshake failed — an untrusted certificate, or `https://` to a plain-HTTP server. */
    data object TlsFailure : ConnectFailure

    /** The API answered but the event streams would not open. */
    data object StreamsBlocked : ConnectFailure

    /** Anything else; [detail] is the carrier's own words. */
    data class Other(val detail: String) : ConnectFailure

    companion object {

        /** Map a pre-flight probe outcome. */
        fun from(outcome: ProbeOutcome): ConnectFailure = when (outcome) {
            is ProbeOutcome.Reachable -> Other("")
            ProbeOutcome.TrustFence -> TrustFence
            ProbeOutcome.Refused -> Refused
            ProbeOutcome.Timeout -> Timeout
            ProbeOutcome.DnsFailure -> DnsFailure
            // No route is a different-network problem; the subnet pre-check catches most of these
            // first, and when it does not, "nothing answered" is the honest reading.
            ProbeOutcome.Unreachable -> Timeout
            ProbeOutcome.NotAHarness -> NotAHarness
            ProbeOutcome.TlsFailure -> TlsFailure
            is ProbeOutcome.Other -> Other(outcome.detail)
        }

        /** Map a failure from inside the connection loop's readiness handshake. */
        fun from(failure: GenerationFailure): ConnectFailure = when (failure) {
            is GenerationFailure.StreamsTimedOut -> StreamsBlocked
            is GenerationFailure.StreamFailed -> fromKind(failure.kind, failure.message, StreamsBlocked)
            is GenerationFailure.DescribeFailed ->
                fromKind(TransportFailures.of(failure.error), failure.error.message, Other(failure.error.message))
        }

        private fun fromKind(
            kind: TransportFailure?,
            message: String?,
            fallback: ConnectFailure,
        ): ConnectFailure = when (kind) {
            TransportFailure.TRUST_FENCE -> TrustFence
            TransportFailure.REFUSED -> Refused
            TransportFailure.TIMEOUT, TransportFailure.UNREACHABLE -> Timeout
            TransportFailure.DNS -> DnsFailure
            TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS -> NotAHarness
            TransportFailure.TLS -> TlsFailure
            TransportFailure.OTHER -> message?.takeIf { it.isNotBlank() }?.let { Other(it) } ?: fallback
            null -> fallback
        }
    }
}
