package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.dto.HostDescription

/**
 * What answered — or did not — at one `host:port`.
 *
 * [DiscoveryEngine.probe] used to return `HostDescription?`, which made a firewall, a loopback-only
 * bind, a trust-fence rejection and a typo the same event. They need opposite instructions, so the
 * probe keeps the distinction and the connect screen spends it.
 */
sealed interface ProbeOutcome {

    /**
     * A harness answered.
     *
     * [description] is null when the endpoint proved itself but its home directory was not read —
     * on the LAN sweep, which does not spend a second request for a caption, and on any host whose
     * directory listing is unavailable. Reachability and description were one call through 0.1.1;
     * from 0.1.2 they are not, so "reachable" no longer implies "described".
     */
    data class Reachable(val description: HostDescription?) : ProbeOutcome

    /** HTTP 403 — the harness is there and its `Host` trust fence refused this address. */
    data object TrustFence : ProbeOutcome

    /**
     * HTTP 401 — the harness is there, accepted the address, and has no browser session for this
     * client.
     *
     * New with harness 0.1.2, which authenticates the whole `/api` surface against a signed cookie
     * obtained by exchanging a launch token. Deliberately not folded into [TrustFence]: a 403 is
     * about where the request came from and is fixed on the harness, while this is about who is
     * asking and is fixed by exchanging a token from the harness's startup URL.
     */
    data object Unauthenticated : ProbeOutcome

    /**
     * HTTP 401 from the harness that a relay is forwarding to: the relay reached upstream and was
     * refused there, not here.
     *
     * A relay answers 403 for a device it will not accept — missing, expired and revoked tokens all
     * — and never 401, so a 401 arriving through one can only be the harness's own answer, passed
     * along with its status intact. It is the relay's browser session that is missing, and the
     * relay is fixed on the computer; the phone's credential was accepted to get this far.
     * Deliberately not [PairingRequired] for that reason: pairing again replaces the one thing that
     * already worked.
     */
    data object RelayUnauthenticated : ProbeOutcome

    /**
     * HTTP 403 from a relay: this device has no credential it will accept any more.
     *
     * The same status as [TrustFence], and the relay answers it for a missing, expired and revoked
     * token alike (never 401 — deliberately, so a client that already reads 403 as "reached it, it
     * refused me" keeps a usable hint). What separates the two here is local knowledge: the app
     * knows whether it ever paired with this address.
     */
    data object PairingRequired : ProbeOutcome

    /**
     * The relay's public key is not the one pinned when this device paired.
     *
     * Worth its own outcome because the honest reading is ambiguous and the user is the only one who
     * can resolve it: the relay regenerates its certificate — with a new key — whenever the set of
     * addresses it covers changes, so a harness laptop that moved networks produces exactly this,
     * and so would somebody else answering on that address.
     */
    data object CertificateChanged : ProbeOutcome

    /** The host answered the network but nothing listens on the port. */
    data object Refused : ProbeOutcome

    /** Nothing answered at all: the packets were dropped rather than refused. */
    data object Timeout : ProbeOutcome

    /** The name did not resolve. */
    data object DnsFailure : ProbeOutcome

    /** No route to the host — usually a different network entirely. */
    data object Unreachable : ProbeOutcome

    /** Something is listening, but it does not speak the harness protocol. */
    data object NotAHarness : ProbeOutcome

    /**
     * The socket opened but the TLS handshake failed — a certificate this phone does not trust,
     * or `https://` aimed at a plain-HTTP server.
     */
    data object TlsFailure : ProbeOutcome

    /** Anything else; [detail] is the carrier's own words, for the fallback message. */
    data class Other(val detail: String) : ProbeOutcome
}

/** Connect/read budgets for a probe. */
data class ProbeTimeouts(val connectMs: Long, val readMs: Long) {
    companion object {
        /**
         * The first pass of a sweep: a bare TCP connect, nothing more.
         *
         * On a /24 almost every address is dead or refuses instantly, and both answers arrive in
         * well under this budget. Only the few that open a socket go on to pay for HTTP, so the
         * deadline that decides the length of a scan is this one — not [Sweep].
         */
        val Knock = ProbeTimeouts(connectMs = 300, readMs = 300)

        /** Sweeping 254 addresses: fail fast, most of them are nothing. */
        val Sweep = ProbeTimeouts(connectMs = 700, readMs = 1_500)

        /**
         * One address the user typed: worth more patience than a sweep entry, but still bounded —
         * this runs behind a progress indicator, not a frozen button.
         */
        val Manual = ProbeTimeouts(connectMs = 2_500, readMs = 4_000)
    }
}

/**
 * How many times a connect probes an address before it says nothing answered: a [ProbeOutcome.Timeout] is asked once
 * more, a fresh request with the same [ProbeTimeouts.Manual] budget.
 *
 * The first request a device makes after it starts or wakes can pay for its network stack starting up, and on a slow
 * network or a busy phone that alone can outlast the budget, while the same request a moment later answers at once.
 * iOS CI showed exactly that (PR #162): the first connect to a harness on a freshly booted simulator timed out on
 * 127.0.0.1, and the next launch connected. Saying "Timeout" there sends the person to check a firewall that is fine.
 * One more try is cheap next to that, and bounded: a host that is really gone is reported after two budgets.
 */
const val CONNECT_PROBE_ATTEMPTS = 2

/**
 * Whether a connect asks again after its probe came back [outcome] on attempt number [attempt] (the first is 1).
 *
 * Only a timeout, which is the one outcome that is not an answer: a refusal, a sign-in or trust-fence status, a TLS or
 * DNS failure, "no route" and "not a harness" all came from something that replied, and asking again would only say
 * the same thing later.
 */
fun retriesConnectProbe(outcome: ProbeOutcome, attempt: Int): Boolean =
    outcome is ProbeOutcome.Timeout && attempt < CONNECT_PROBE_ATTEMPTS

/**
 * What a refused probe means, given whether the address is a relay this device paired with.
 *
 * Free function for the same reason as [sameSubnet]: the statuses are the part worth pinning, and
 * reaching them through [DiscoveryEngine] needs the credential stores. [detail] is the carrier's
 * own wording, kept for the outcomes that have nothing more specific to say.
 */
fun probeOutcomeOf(kind: TransportFailure?, relay: Boolean, detail: String): ProbeOutcome =
    when (kind) {
        // The relay answers the same 403 whether it has never seen this device, the token expired,
        // or the operator revoked it, so the status alone cannot separate "pair again" from the
        // harness's own `Host` fence. What the app already knows about the address can. A harness
        // reached directly now answers 401 for the same class of fact — no browser session — which
        // is its own outcome rather than a fence.
        TransportFailure.TRUST_FENCE ->
            if (relay) ProbeOutcome.PairingRequired else ProbeOutcome.TrustFence
        // The same 401 behind a relay is one hop further out: the relay accepted this device — it
        // answers 403 when it does not — and the harness refused the relay's own request, so there
        // is nothing here for this phone to pair again.
        TransportFailure.UNAUTHENTICATED ->
            if (relay) ProbeOutcome.RelayUnauthenticated else ProbeOutcome.Unauthenticated
        TransportFailure.CERTIFICATE_PIN -> ProbeOutcome.CertificateChanged
        TransportFailure.REFUSED -> ProbeOutcome.Refused
        TransportFailure.TIMEOUT -> ProbeOutcome.Timeout
        TransportFailure.DNS -> ProbeOutcome.DnsFailure
        TransportFailure.UNREACHABLE -> ProbeOutcome.Unreachable
        // The probe sends no arguments, so nothing it sends can be too large: a 413 here means
        // whatever is listening is not the harness.
        TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS, TransportFailure.TOO_LARGE ->
            ProbeOutcome.NotAHarness
        TransportFailure.TLS -> ProbeOutcome.TlsFailure
        // A throttle and a dead upstream both describe a real harness address — one asking us to
        // wait, one with nothing behind it — so neither may read as "not a harness", which is the
        // one verdict that sends someone to re-check the address. Their own carrier wording already
        // says which, so it is what gets shown.
        TransportFailure.RATE_LIMITED, TransportFailure.UPSTREAM_DOWN,
        TransportFailure.OTHER, null,
        -> ProbeOutcome.Other(detail)
    }
