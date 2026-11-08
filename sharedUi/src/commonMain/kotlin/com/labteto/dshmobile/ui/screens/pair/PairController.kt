package com.labteto.dshmobile.ui.screens.pair

import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.PairingPayloadResult
import com.labteto.dshmobile.core.wire.RelayAddress
import com.labteto.dshmobile.core.wire.RelayOrigin
import com.labteto.dshmobile.core.wire.RelayPairOutcome
import com.labteto.dshmobile.core.wire.RelayPairing
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.worthAnotherOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Enrolling this device with a `dsh-relay`: everything the Pair screen decides.
 *
 * One exchange, once: the relay mints a bearer token, shows it exactly once, and keeps only a keyed
 * hash afterwards. Everything downstream — the transport, both downlinks, discovery — depends on
 * that token having been stored, so the order here is deliberate: remember the endpoint first so it
 * has an id, store the credential against that id, and only then connect.
 *
 * This is the Android `PairViewModel`'s logic, moved unchanged into common code so iOS runs it too.
 * The view model is now the Android owner (it gives [scope] its lifetime and builds [ports] over the
 * app's own stores); the iOS app builds one of its own over the Darwin client and the Keychain.
 *
 * Everything outside the platform's reach goes through [PairPorts]; the pairing exchange itself
 * (`RelayPairing`) is common code over a `PlainHttp`.
 */
class PairController(
    private val scope: CoroutineScope,
    private val ports: PairPorts,
) {
    private val _state = MutableStateFlow(PairUiState(deviceName = ports.deviceName))
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
                if (!payload.isLive(ports.nowMillis())) {
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
        if (RelayPairing.address(url) == null) {
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
        scope.launch {
            val name = _state.value.deviceName.ifBlank { ports.deviceName }
            val candidates = RelayPairing.candidates(url, alternatives).ifEmpty { listOf(url) }
            var lastFailure: PairFailure = PairFailure.Unreachable(url)
            for (candidate in candidates) {
                when (val attempt = attempt(candidate, code, name, fingerprint)) {
                    is ClaimAttempt.Paired -> {
                        enrol(attempt.address, attempt.outcome, attempt.pin)
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
        val located = ports.withHttp(fingerprint = null, observed = null) { RelayPairing.locate(url, it) }
        val effective = when (located) {
            is RelayOrigin.Redirected -> located.origin
            else -> url
        }
        val parsed = RelayPairing.address(effective)
            ?: return ClaimAttempt.Failed(PairFailure.InvalidUrl, retryable = false)
        val authority = "${parsed.host}:${parsed.port}"
        val observed = ObservedKey()
        // The relay only reveals its key in the claim answer, so a typed https address has to be spoken
        // to before it can be verified. The screen labels that differently from a scanned pairing for
        // exactly that reason.
        val learn = if (fingerprint == null && parsed.secure) observed else null
        val outcome = ports.withHttp(fingerprint, learn) { RelayPairing.claim(effective, code, name, it) }
        return when (outcome) {
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

    private suspend fun enrol(address: RelayAddress, outcome: RelayPairOutcome.Paired, pin: String?) {
        val response = outcome.response
        val config = ports.rememberRelay(
            host = address.host,
            port = address.port,
            basePath = address.encodedPath.trimEnd('/'),
            relay = RelayIdentity(
                deviceId = response.deviceId,
                useTls = address.secure,
                // The relay repeats its pin in the claim answer, which is what makes a typed
                // pairing pinnable at all. Preferring it over the observed key means a relay that
                // renews with the same key keeps working, and a mismatch between the two would
                // already have failed the request.
                fingerprint = response.fingerprint ?: pin,
                tokenExpiresAt = response.expiresAt,
            ),
        )
        ports.storeToken(config.id, response.token)
        _state.update { it.copy(stage = PairStage.Paired, paired = config, failure = null) }
        ports.connect(config)
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

/** One origin's outcome, and whether the next origin the invitation names is worth a try. */
private sealed interface ClaimAttempt {
    data class Paired(
        val address: RelayAddress,
        val outcome: RelayPairOutcome.Paired,
        val pin: String?,
    ) : ClaimAttempt

    data class Failed(val failure: PairFailure, val retryable: Boolean) : ClaimAttempt
}
