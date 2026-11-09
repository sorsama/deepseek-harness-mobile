package com.labteto.dshmobile.ui.screens.pair

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.wire.RelayPairing
import com.labteto.dshmobile.core.wire.RelayPairingPayload

/** How far the pairing attempt has got. */
enum class PairStage { Idle, Claiming, Paired }

/**
 * How the relay's identity was established.
 *
 * The difference is real and the screen says so. A scanned QR carries the relay's key, so the very
 * first byte this device sends is verified against it. A typed address carries no key at all — the
 * relay only reveals one in its answer to the claim — so the certificate is trusted on first
 * contact. Both end with the same pin stored; only one of them proved it.
 */
enum class KeyProvenance {
    /** No TLS at all: the relay is running `tls: off`, and the token will travel in the clear. */
    Plaintext,

    /** The key came from the QR and was verified before anything was sent. */
    Verified,

    /** The key was whatever answered at a typed address. */
    TrustedOnFirstUse,
}

data class PairUiState(
    val stage: PairStage = PairStage.Idle,
    val url: String = "",
    val code: String = "",
    /** What the relay's device list will call this device; the platform's own name until it is edited. */
    val deviceName: String = "",
    /** Set once a QR has been read, so the screen can show what it is about to pair with. */
    val scanned: RelayPairingPayload? = null,
    val failure: PairFailure? = null,
    /**
     * The endpoint that was just enrolled; the screen hands this back and closes.
     *
     * A one-shot signal, cleared by [PairController.acknowledgePaired] as the screen acts on it.
     * It cannot be left set: the controller is owned by something that outlives a closed pairing
     * screen (the Android view model is scoped to the activity), and a latched value would close
     * the *next* one the instant it opened, which looks exactly like the button not working.
     */
    val paired: HostConfig? = null,
) {
    val busy: Boolean get() = stage == PairStage.Claiming
}

/** Why a pairing attempt did not produce a credential. */
sealed interface PairFailure {
    /** The scanned code is not a relay pairing payload. */
    data object NotAPairingCode : PairFailure

    /** A pairing payload from a relay newer than this build. */
    data class TooNew(val version: Int) : PairFailure

    /** The code had already expired before it was sent, so there was nothing to try. */
    data object Expired : PairFailure

    /** The relay refused the code: wrong, expired, or already claimed. */
    data object Rejected : PairFailure

    /** Locked out for [seconds]. */
    data class RateLimited(val seconds: Long) : PairFailure

    /** Something answered but does not serve the claim endpoint. */
    data class NotARelay(val authority: String) : PairFailure

    /**
     * The relay is there and its fence refused the address it was reached by.
     *
     * Nothing about the code was wrong — it was never read. Only the relay's operator can widen
     * the set of authorities it answers to, so this is the one pairing failure the phone cannot
     * resolve on its own.
     */
    data class HostRefused(val authority: String) : PairFailure

    /** Nothing usable answered. */
    data class Unreachable(val authority: String) : PairFailure

    /** The key at the address is not the one the QR named. */
    data class CertificateMismatch(val authority: String) : PairFailure

    /** The typed address is not a URL this app can address. */
    data object InvalidUrl : PairFailure

    /** No code was typed. */
    data object InvalidCode : PairFailure
}

/**
 * How this pairing would establish the relay's key, given what has been entered so far.
 *
 * A function of the state rather than a call into the controller, so the screen's notice recomposes
 * with the address field as the user types it.
 */
fun PairUiState.provenance(): KeyProvenance {
    val secure = RelayPairing.address(url.trim())?.secure == true
    return when {
        !secure -> KeyProvenance.Plaintext
        scanned?.fingerprint != null -> KeyProvenance.Verified
        else -> KeyProvenance.TrustedOnFirstUse
    }
}
