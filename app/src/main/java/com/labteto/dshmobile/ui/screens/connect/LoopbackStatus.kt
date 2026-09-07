package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.dto.HostDescription

/**
 * What the harness on this phone is doing, as the "this phone" card shows it.
 *
 * Four states, because four different things can be done about them: nothing is listening
 * (start it), it is up but has not signed this app in (sign in), it is ready (connect), or the
 * port belongs to something else (nothing this app can fix). A probe outcome carries more
 * distinctions than that — a timeout and a refusal are the same "down" on loopback, where no
 * firewall stands between the app and the port.
 */
sealed interface LoopbackStatus {
    /** Not probed yet, or the port was just changed. */
    data object Unknown : LoopbackStatus

    /** Nothing answered on the port. */
    data class Down(val outcome: ProbeOutcome) : LoopbackStatus

    /** A harness answered 401: up, and waiting for this app to present its launch token. */
    data object NeedsSignIn : LoopbackStatus

    /** A harness answered; [description] when it also named its home directory. */
    data class Ready(val description: HostDescription?) : LoopbackStatus

    /** Something answered on the port and it is not a harness this app can talk to. */
    data class Foreign(val outcome: ProbeOutcome) : LoopbackStatus
}

/** Map a probe outcome onto the card's four states. */
fun ProbeOutcome.toLoopbackStatus(): LoopbackStatus = when (this) {
    is ProbeOutcome.Reachable -> LoopbackStatus.Ready(description)
    ProbeOutcome.Unauthenticated -> LoopbackStatus.NeedsSignIn
    ProbeOutcome.Refused, ProbeOutcome.Timeout, ProbeOutcome.Unreachable, ProbeOutcome.DnsFailure ->
        LoopbackStatus.Down(this)
    // A loopback record is never a relay, so a relay-flavoured outcome here means the app's own
    // bookkeeping is off; it is still "something answered that we cannot use".
    ProbeOutcome.TrustFence,
    ProbeOutcome.PairingRequired,
    ProbeOutcome.CertificateChanged,
    ProbeOutcome.NotAHarness,
    ProbeOutcome.TlsFailure,
    is ProbeOutcome.Other,
    -> LoopbackStatus.Foreign(this)
}
