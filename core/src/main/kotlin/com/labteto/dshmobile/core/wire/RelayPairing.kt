package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Device enrolment against a `dsh-relay` listener.
 *
 * This is the one exchange in the app that is not the harness wire protocol: the `/relay` routes
 * belong to the relay itself and are never forwarded upstream, so this carries no `client-request`
 * envelope and answers with plain JSON. It lives in `:core` anyway because the parsing is the part
 * worth testing, and the app module has no JVM test harness of its own for network code.
 *
 * The payload version is checked rather than ignored. Everywhere else this client is deliberately
 * lenient — unknown events, frames and tool cards fall through to passthroughs — but a pairing
 * payload is a credential exchange, and a `v` this build does not understand may carry a field it
 * must not silently skip.
 */
object RelayPairing {

    /** Path of the claim endpoint, and of the operator's pairing page. */
    const val PAIR_PATH: String = "/relay/pair"

    /** Path of the relay's unauthenticated liveness probe. */
    const val HEALTH_PATH: String = "/relay/health"

    /** The only `kind` this client will act on. */
    const val PAYLOAD_KIND: String = "dsh-relay-pair"

    /** What `/relay/health` names itself. */
    const val SERVICE_NAME: String = "dsh-relay"

    /** The highest payload version this build understands. */
    const val PAYLOAD_VERSION: Int = 1

    /**
     * Fallback back-off when a 429 arrives without `Retry-After`.
     *
     * The relay's rate limiter sets that header but its pairing lockout does not, so a client that
     * trusted it to be present would busy-retry against a locked-out address.
     */
    const val DEFAULT_RETRY_AFTER_SECONDS: Long = 60

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Deadlines for the two relay calls.
     *
     * Both are user-initiated and named — someone is watching a spinner — so they get a budget
     * closer to the manual-probe one than to the 30s default a `/api` call carries.
     */
    private const val CONNECT_MS = 8_000L
    private const val READ_MS = 12_000L

    /**
     * Read a scanned QR (or a pasted payload) as a pairing invitation.
     *
     * @param text the decoded QR contents, verbatim.
     */
    fun parsePayload(text: String): PairingPayloadResult {
        val payload = try {
            WireJson.decodeFromString(RelayPairingPayload.serializer(), text)
        } catch (e: SerializationException) {
            return PairingPayloadResult.NotAPairingCode
        } catch (e: IllegalArgumentException) {
            return PairingPayloadResult.NotAPairingCode
        }
        if (payload.kind != PAYLOAD_KIND) return PairingPayloadResult.NotAPairingCode
        if (payload.v > PAYLOAD_VERSION) return PairingPayloadResult.TooNew(payload.v)
        if (payload.url.toHttpUrlOrNull() == null) return PairingPayloadResult.NotAPairingCode
        return PairingPayloadResult.Valid(payload)
    }

    /**
     * Claim a pairing code, minting this device's bearer token.
     *
     * [baseUrl] must be the relay's **primary** origin. The plain-HTTP compatibility listener serves
     * no relay route but `/relay/health`, so a claim addressed to it answers 404 — which is why a
     * payload's `plainUrl` is ignored here and everywhere else.
     *
     * `Content-Type: application/json` is not cosmetic: it is how the relay decides to answer with
     * JSON rather than with an HTML page.
     */
    suspend fun claim(
        baseUrl: String,
        code: String,
        name: String,
        client: OkHttpClient,
    ): RelayPairOutcome {
        val target = relayUrl(baseUrl, PAIR_PATH)
            ?: return RelayPairOutcome.Unreachable(TransportFailure.DNS, "not a usable relay address")
        val body = WireJson.encodeToString(RelayPairRequest.serializer(), RelayPairRequest(code, name))
        val request = Request.Builder()
            .url(target)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        // No redirect chasing here either. A 302 rewrites the POST to a GET, so the claim would
        // arrive as a page view and answer with the claim *form* — HTML, status 200 — which reads
        // as a relay that refused for no stated reason. [locate] resolves the origin first instead.
        return when (val outcome = execute(request, client, followRedirects = false)) {
            is HttpOutcome.Failed -> RelayPairOutcome.Unreachable(
                TransportFailures.classify(outcome.cause),
                outcome.cause.message,
            )
            is HttpOutcome.Answered -> readClaim(outcome)
        }
    }

    /**
     * Whether a `dsh-relay` is listening at [baseUrl] itself.
     *
     * A redirect does not count here — see [locate], which is the call that reports one.
     */
    suspend fun health(baseUrl: String, client: OkHttpClient): Boolean =
        locate(baseUrl, client) is RelayOrigin.Here

    /**
     * Find the relay at, or named by, [baseUrl].
     *
     * `/relay/health` is the one route a relay serves without a credential, on both listeners. It
     * names the product and nothing about the harness behind it, so this is the whole of what an
     * unpaired device may learn: enough to tell a relay from a bare harness, and nothing a port
     * scan would not already have.
     *
     * Redirects are **not** followed. Since 0.1.1 the plugin registers `/relay` on the harness's
     * own web server and redirects it to the relay's listener, which makes the harness address a
     * usable way in — but only if the answer is read rather than chased. Letting the HTTP client
     * follow it would report a relay living at the harness's port, and every later call would then
     * be addressed to the wrong listener. Worse for the claim: a 302 turns a POST into a GET, so
     * the request would arrive as a page view and answer with markup instead of a token.
     */
    suspend fun locate(baseUrl: String, client: OkHttpClient): RelayOrigin {
        val target = relayUrl(baseUrl, HEALTH_PATH) ?: return RelayOrigin.None
        val request = Request.Builder().url(target).header("Accept", "application/json").get().build()
        val answered = execute(request, client, followRedirects = false) as? HttpOutcome.Answered
            ?: return RelayOrigin.None
        if (answered.status in 300..399) {
            val moved = answered.location?.let { target.resolve(it) } ?: return RelayOrigin.None
            // Only a redirect that still points at this path is the plugin handing us its listener.
            // Anything else is some other server's routing, and following it would be guesswork.
            if (moved.encodedPath != HEALTH_PATH) return RelayOrigin.None
            return RelayOrigin.Redirected(originOf(moved), moved.scheme == "https")
        }
        // A relay that refuses the `Host` it was reached by is still a relay, and saying "nothing
        // there" about one is how a working setup looks like a missing one. Its fence runs before
        // any route, so even the unauthenticated liveness probe gets this — an address the relay
        // does not know itself by (an emulator's host alias, a name it was never told) answers 403
        // to everything, and only its operator can widen that.
        if (answered.status == 403) return RelayOrigin.Untrusted(originOf(target), target.scheme == "https")
        if (answered.status != 200) return RelayOrigin.None
        val named = runCatching {
            WireJson.decodeFromString(RelayHealth.serializer(), answered.body).service == SERVICE_NAME
        }.getOrDefault(false)
        if (!named) return RelayOrigin.None
        return RelayOrigin.Here(originOf(target), target.scheme == "https")
    }

    /** Scheme, host and port of [url], with no path — the form every other call takes. */
    private fun originOf(url: okhttp3.HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    /** Resolve [path] against a relay origin, or null when the origin is not a usable URL. */
    private fun relayUrl(baseUrl: String, path: String) =
        baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.encodedPath(path)?.build()

    private fun readClaim(response: HttpOutcome.Answered): RelayPairOutcome = when (response.status) {
        200 -> runCatching {
            RelayPairOutcome.Paired(WireJson.decodeFromString(RelayPairResponse.serializer(), response.body))
        }.getOrElse { RelayPairOutcome.NotARelay }
        // Two different 403s, told apart by the body. The relay answers `pairing-failed` for a code
        // it will not accept; its DNS-rebinding fence answers plain text, before any route is
        // reached at all. Reporting a refused `Host` as a bad code sends the user to reload a
        // pairing page that was never the problem.
        403 -> if (isPairingRefusal(response.body)) RelayPairOutcome.Rejected else RelayPairOutcome.HostRefused
        429 -> RelayPairOutcome.RateLimited(
            response.retryAfter?.toLongOrNull()?.coerceAtLeast(1) ?: DEFAULT_RETRY_AFTER_SECONDS,
        )
        // 404 is the compatibility listener, or a plain harness with nothing at this path. Either
        // way this address is not a relay the app can pair with, which is what the user needs told
        // rather than "HTTP 404".
        else -> RelayPairOutcome.NotARelay
    }

    /** Whether a 403 body is the relay refusing the *code* rather than the address. */
    private fun isPairingRefusal(body: String): Boolean = runCatching {
        WireJson.decodeFromString(RelayRefusal.serializer(), body).error == "pairing-failed"
    }.getOrDefault(false)

    private sealed interface HttpOutcome {
        data class Answered(
            val status: Int,
            val body: String,
            val retryAfter: String?,
            val location: String?,
        ) : HttpOutcome

        data class Failed(val cause: IOException) : HttpOutcome
    }

    private suspend fun execute(
        request: Request,
        client: OkHttpClient,
        followRedirects: Boolean = true,
    ): HttpOutcome =
        suspendCancellableCoroutine { continuation ->
            val call = client.newBuilder()
                .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
                .followRedirects(followRedirects)
                .followSslRedirects(followRedirects)
                .build()
                .newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(HttpOutcome.Failed(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val answered = HttpOutcome.Answered(
                            status = resp.code,
                            body = resp.body?.string().orEmpty(),
                            retryAfter = resp.header("Retry-After"),
                            location = resp.header("Location"),
                        )
                        if (continuation.isActive) continuation.resume(answered)
                    }
                }
            })
        }
}

/** What a pairing QR carries. Mirrors `PairingPayload` in the plugin's `src/auth/pairing.ts`. */
@Serializable
data class RelayPairingPayload(
    /** Payload version. Anything above [RelayPairing.PAYLOAD_VERSION] is refused. */
    val v: Int,
    /** Discriminator; only `dsh-relay-pair` is acted on. */
    val kind: String,
    /** Origin of the primary listener, scheme included. This is the one to use. */
    val url: String,
    /**
     * Origin of the plain-HTTP compatibility listener, when one is running.
     *
     * Read but deliberately unused. It exists for clients that cannot hold a credential; this one
     * can, and that listener serves neither the claim endpoint nor the configuration plane.
     */
    val plainUrl: String? = null,
    /** Base64 SHA-256 of the DER SubjectPublicKeyInfo; absent when the relay serves plaintext. */
    val fingerprint: String? = null,
    /** The single-use pairing code. */
    val code: String,
    /** Epoch millis the code stops being claimable. */
    val expiresAt: Long,
) {
    /** Whether the code is still claimable at [now]. */
    fun isLive(now: Long): Boolean = now < expiresAt

    /** Whether the credential would travel in the clear on this relay. */
    val isPlaintext: Boolean get() = fingerprint == null
}

/** How a scanned or pasted payload was read. */
sealed interface PairingPayloadResult {
    /** A payload this build understands. */
    data class Valid(val payload: RelayPairingPayload) : PairingPayloadResult

    /** Not a relay pairing payload at all — some other QR, or malformed JSON. */
    data object NotAPairingCode : PairingPayloadResult

    /** A pairing payload from a newer relay than this build knows how to honour. */
    data class TooNew(val version: Int) : PairingPayloadResult
}

/** The claim request body. `name` is what the operator sees in the relay's device list. */
@Serializable
data class RelayPairRequest(val code: String, val name: String)

/** A successful claim. The token is shown once and never again — store it, do not re-fetch it. */
@Serializable
data class RelayPairResponse(
    val deviceId: String,
    val token: String,
    val expiresAt: Long,
    /** Repeated from the QR, so a passcode pairing also learns the pin. */
    val fingerprint: String? = null,
)

/** `/relay/health`'s answer. */
@Serializable
private data class RelayHealth(val service: String = "", val ok: Boolean = false)

/** The shape a relay route uses to refuse; the fence answers plain text instead. */
@Serializable
private data class RelayRefusal(val error: String = "", val message: String = "")

/** How a claim ended. */
sealed interface RelayPairOutcome {
    /** Enrolled; [response] carries the credential. */
    data class Paired(val response: RelayPairResponse) : RelayPairOutcome

    /** The relay refused the code: wrong, expired, or already claimed. */
    data object Rejected : RelayPairOutcome

    /** Too many attempts from this address; wait [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Long) : RelayPairOutcome

    /** Something answered, but it is not a relay serving the claim endpoint. */
    data object NotARelay : RelayPairOutcome

    /**
     * A relay is there, and its fence refused the address it was reached by.
     *
     * Not a wrong code — the code was never read. The relay answers only to authorities it knows:
     * loopback, its own addresses, and whatever its operator listed. Only they can add one.
     */
    data object HostRefused : RelayPairOutcome

    /** Nothing usable answered; [kind] is why, including a certificate-pin mismatch. */
    data class Unreachable(val kind: TransportFailure, val message: String?) : RelayPairOutcome
}

/** Where a relay is, relative to an address that was asked. */
sealed interface RelayOrigin {
    /** A relay answered at the address as given; [origin] is that address, normalised. */
    data class Here(val origin: String, val secure: Boolean) : RelayOrigin

    /**
     * Not the relay, but it said where the relay is.
     *
     * This is the harness's own port. `dsh-relay` 0.1.1 registers `/relay` on the harness web
     * server and redirects it to its own listener, which turns the address people already know —
     * the one the app has been telling them for five releases — into a way in.
     */
    data class Redirected(val origin: String, val secure: Boolean) : RelayOrigin

    /**
     * A relay is there, and it refuses the address it was reached by.
     *
     * Its DNS-rebinding fence runs before every route, so this is what an address the relay does
     * not know itself by gets — even from the liveness probe. Worth reporting rather than hiding:
     * the relay is running, on the right port, one entry in its `publicHostnames` away.
     */
    data class Untrusted(val origin: String, val secure: Boolean) : RelayOrigin

    /** Nothing there speaks for a relay. */
    data object None : RelayOrigin
}
