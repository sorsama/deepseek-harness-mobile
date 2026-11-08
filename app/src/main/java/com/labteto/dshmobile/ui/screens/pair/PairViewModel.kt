package com.labteto.dshmobile.ui.screens.pair

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HarnessClientFactory
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.RelayCredentialStore
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.PairingPayloadResult
import com.labteto.dshmobile.core.wire.RelayOrigin
import com.labteto.dshmobile.core.wire.RelayPairOutcome
import com.labteto.dshmobile.core.wire.RelayPairing
import com.labteto.dshmobile.core.wire.RelayPairingPayload
import com.labteto.dshmobile.core.wire.RelayTls
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.claim
import com.labteto.dshmobile.core.wire.locate
import com.labteto.dshmobile.core.wire.worthAnotherOrigin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject

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
    val deviceName: String = defaultDeviceName(),
    /** Set once a QR has been read, so the screen can show what it is about to pair with. */
    val scanned: RelayPairingPayload? = null,
    val failure: PairFailure? = null,
    /**
     * The endpoint that was just enrolled; the screen hands this back and closes.
     *
     * A one-shot signal, cleared by [PairViewModel.acknowledgePaired] as the screen acts on it.
     * It cannot be left set: this ViewModel is scoped to the activity rather than to the screen,
     * so it outlives a closed pairing screen — and a latched value would close the *next* one the
     * instant it opened, which looks exactly like the button not working.
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
 * Enrolling this device with a `dsh-relay`.
 *
 * One exchange, once: the relay mints a bearer token, shows it exactly once, and keeps only a keyed
 * hash afterwards. Everything downstream — the transport, both downlinks, discovery — depends on
 * that token having been stored, so the order here is deliberate: remember the endpoint first so it
 * has an id, store the credential against that id, and only then connect.
 */
@HiltViewModel
class PairViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val credentials: RelayCredentialStore,
    private val clientFactory: HarnessClientFactory,
    private val connectionManager: ConnectionManager,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(PairUiState())
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    /** Prefill the address, e.g. when re-pairing a relay that revoked this device. */
    fun prefill(url: String) {
        if (_state.value.url.isNotBlank()) return
        _state.update { it.copy(url = url) }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, failure = null) }

    fun setCode(value: String) =
        _state.update { it.copy(code = value.filter { c -> c.isDigit() }, failure = null) }

    fun setDeviceName(value: String) = _state.update { it.copy(deviceName = value) }

    fun clearFailure() = _state.update { it.copy(failure = null) }

    /**
     * A QR was decoded.
     *
     * The claim runs immediately rather than filling the form and waiting for a tap: the code is
     * single-use and lives for five minutes by default, and a scan is already an unambiguous "yes".
     */
    fun onScanned(text: String) {
        when (val parsed = RelayPairing.parsePayload(text)) {
            is PairingPayloadResult.NotAPairingCode ->
                _state.update { it.copy(failure = PairFailure.NotAPairingCode) }
            is PairingPayloadResult.TooNew ->
                _state.update { it.copy(failure = PairFailure.TooNew(parsed.version)) }
            is PairingPayloadResult.Valid -> {
                val payload = parsed.payload
                if (!payload.isLive(System.currentTimeMillis())) {
                    _state.update { it.copy(failure = PairFailure.Expired) }
                    return
                }
                _state.update {
                    it.copy(scanned = payload, url = payload.url, code = payload.code, failure = null)
                }
                claim(payload.url, payload.code, payload.fingerprint, payload.urls.orEmpty())
            }
        }
    }

    /** Claim a code the user typed, against an address they typed. */
    fun submit() {
        val current = _state.value
        val url = current.url.trim()
        if (url.toHttpUrlOrNull() == null) {
            _state.update { it.copy(failure = PairFailure.InvalidUrl) }
            return
        }
        if (current.code.isBlank()) {
            _state.update { it.copy(failure = PairFailure.InvalidCode) }
            return
        }
        // No fingerprint: a typed address carries no key, so this is the trust-on-first-use path.
        claim(url, current.code, fingerprint = null)
    }

    /**
     * Claim [code] at the first address that answers, of the ones [url] stands for.
     *
     * [alternatives] are the other origins a scanned invitation named, in its order. A relay on a
     * machine with more than one network address cannot know which one this phone is on, so it
     * names them all; trying only the first is what turns a perfectly good QR into "could not
     * reach 192.168.1.122" while the phone is on another subnet. Only failures that say nothing
     * about the code are retried — see [worthAnotherOrigin].
     */
    private fun claim(
        url: String,
        code: String,
        fingerprint: String?,
        alternatives: List<String> = emptyList(),
    ) {
        _state.update { it.copy(stage = PairStage.Claiming, failure = null) }
        viewModelScope.launch {
            val name = _state.value.deviceName.ifBlank { defaultDeviceName() }
            val candidates = RelayPairing.candidates(url, alternatives).ifEmpty { listOf(url) }
            var lastFailure: PairFailure = PairFailure.Unreachable(url)
            for (candidate in candidates) {
                when (val attempt = attempt(candidate, code, name, fingerprint)) {
                    is ClaimAttempt.Paired -> {
                        enrol(attempt.url, attempt.outcome, attempt.pin)
                        return@launch
                    }
                    is ClaimAttempt.Failed -> {
                        lastFailure = attempt.failure
                        if (!attempt.retryable) {
                            fail(attempt.failure)
                            return@launch
                        }
                    }
                }
            }
            fail(lastFailure)
        }
    }

    /**
     * One origin's worth of the claim: find the relay, speak to it with the right key, ask.
     *
     * The redirect is resolved here rather than followed by the HTTP client because it names a
     * different scheme and port, which decides both the pin to present and what gets remembered,
     * and a 302 would rewrite the claim's POST to a GET. A probe that answers nothing still gets an
     * attempt: health is unauthenticated and always served, so refusing to try on its silence would
     * turn one unanswered probe into a pairing that cannot be completed at all.
     */
    private suspend fun attempt(
        url: String,
        code: String,
        name: String,
        fingerprint: String?,
    ): ClaimAttempt {
        val effective = when (val located = RelayPairing.locate(url, okHttpClient)) {
            is RelayOrigin.Redirected -> located.origin
            else -> url
        }
        val parsed = effective.toHttpUrlOrNull()
            ?: return ClaimAttempt.Failed(PairFailure.InvalidUrl, retryable = false)
        val authority = "${parsed.host}:${parsed.port}"
        val secure = parsed.scheme == "https"
        val observed = ObservedKey()
        val client = when {
            fingerprint != null -> clientFactory.httpClient(fingerprint)
            // The relay only reveals its key in the claim answer, so a typed https address has to
            // be spoken to before it can be verified. The screen labels that differently from a
            // scanned pairing for exactly that reason.
            secure -> RelayTls.trustOnFirstUseClient(okHttpClient, observed)
            else -> okHttpClient
        }
        return when (val outcome = RelayPairing.claim(effective, code, name, client)) {
            is RelayPairOutcome.Paired ->
                ClaimAttempt.Paired(parsed, outcome, fingerprint ?: observed.pin)
            RelayPairOutcome.Rejected ->
                ClaimAttempt.Failed(PairFailure.Rejected, retryable = false)
            RelayPairOutcome.HostRefused ->
                ClaimAttempt.Failed(PairFailure.HostRefused(authority), outcome.worthAnotherOrigin())
            RelayPairOutcome.NotARelay ->
                ClaimAttempt.Failed(PairFailure.NotARelay(authority), outcome.worthAnotherOrigin())
            is RelayPairOutcome.RateLimited ->
                ClaimAttempt.Failed(PairFailure.RateLimited(outcome.retryAfterSeconds), retryable = false)
            is RelayPairOutcome.Unreachable -> ClaimAttempt.Failed(
                if (outcome.kind == TransportFailure.CERTIFICATE_PIN) {
                    PairFailure.CertificateMismatch(authority)
                } else {
                    PairFailure.Unreachable(authority)
                },
                outcome.worthAnotherOrigin(),
            )
        }
    }

    private suspend fun enrol(
        url: okhttp3.HttpUrl,
        outcome: RelayPairOutcome.Paired,
        pin: String?,
    ) {
        val response = outcome.response
        val config = hostsStore.rememberHost(
            name = url.host,
            host = url.host,
            port = url.port,
            isLoopback = false,
            basePath = url.encodedPath.trimEnd('/'),
            relay = RelayIdentity(
                deviceId = response.deviceId,
                useTls = url.scheme == "https",
                // The relay repeats its pin in the claim answer, which is what makes a typed
                // pairing pinnable at all. Preferring it over the observed key means a relay that
                // renews with the same key keeps working, and a mismatch between the two would
                // already have failed the request.
                fingerprint = response.fingerprint ?: pin,
                tokenExpiresAt = response.expiresAt,
            ),
        )
        credentials.put(config.id, response.token)
        _state.update { it.copy(stage = PairStage.Paired, paired = config, failure = null) }
        connectionManager.connect(config)
    }

    /**
     * Consume the [PairUiState.paired] signal.
     *
     * Called by the screen as it closes, so reopening pairing later starts from Idle rather than
     * re-firing the close it already handled.
     */
    fun acknowledgePaired() {
        _state.update { it.copy(stage = PairStage.Idle, paired = null) }
    }

    private fun fail(failure: PairFailure) {
        _state.update { it.copy(stage = PairStage.Idle, failure = failure) }
    }

}

/**
 * How this pairing would establish the relay's key, given what has been entered so far.
 *
 * A function of the state rather than a call into the view model, so the screen's notice recomposes
 * with the address field as the user types it.
 */
fun PairUiState.provenance(): KeyProvenance {
    val secure = url.trim().toHttpUrlOrNull()?.scheme == "https"
    return when {
        !secure -> KeyProvenance.Plaintext
        scanned?.fingerprint != null -> KeyProvenance.Verified
        else -> KeyProvenance.TrustedOnFirstUse
    }
}

/**
 * A name the operator will recognise in the relay's device list.
 *
 * `Build.MODEL` rather than a generated id: the list is read by a person deciding what to revoke,
 * and "Pixel 8" answers that question where a UUID does not.
 */
private fun defaultDeviceName(): String =
    listOfNotNull(Build.MANUFACTURER?.takeIf { Build.MODEL?.startsWith(it, ignoreCase = true) == false }, Build.MODEL)
        .joinToString(" ")
        .ifBlank { "Android device" }

/** One origin's outcome, and whether the next origin the invitation names is worth a try. */
private sealed interface ClaimAttempt {
    data class Paired(
        val url: okhttp3.HttpUrl,
        val outcome: RelayPairOutcome.Paired,
        val pin: String?,
    ) : ClaimAttempt

    data class Failed(val failure: PairFailure, val retryable: Boolean) : ClaimAttempt
}
