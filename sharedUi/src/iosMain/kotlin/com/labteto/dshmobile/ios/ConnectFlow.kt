package com.labteto.dshmobile.ios

import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.ConnectionState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.KtorRpcTransport
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.platformMuxSocketFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Why a connect attempt did not reach a harness; the screen turns each into words. */
internal enum class ConnectFailure {
    /** What was typed is not an address. Nothing was sent. */
    INVALID_ADDRESS,

    /** Something is there and nothing listens on the port. */
    REFUSED,

    /** Nothing answered at all: dropped packets, a firewall. */
    TIMEOUT,

    /** The name did not resolve. */
    DNS,

    /** No route to the address, usually another network. */
    UNREACHABLE,

    /** The harness is there and its `Host` fence refused this address (HTTP 403). */
    TRUST_FENCE,

    /** The harness is there and has no browser session for this device (HTTP 401). */
    UNAUTHENTICATED,

    /** Something answered, but it does not speak the harness protocol. */
    NOT_A_HARNESS,

    /** The TLS handshake failed. */
    TLS,

    /** Anything else; `detail` carries the carrier's own words. */
    OTHER,
}

/** The honest result of one connect attempt: a harness that answered, or the reason none did. */
internal sealed interface ConnectOutcome {
    /**
     * A harness answered the probe and completed the mux handshake.
     *
     * There is no harness name or version here because there is none on the wire: harness 0.1.2
     * removed `host.describe`, and the ready frame carries only the host's home directory. What the
     * Android app shows beside it is this client's pinned protocol baseline, so this does too.
     */
    data class Connected(val authority: String, val home: String, val protocol: String) : ConnectOutcome

    data class Failed(
        val failure: ConnectFailure,
        val authority: String,
        val port: Int,
        val detail: String?,
    ) : ConnectOutcome
}

/** One line a script can read back, e.g. `DSH_CONNECT_RESULT connected home=/Users/demo`. */
internal fun ConnectOutcome.logLine(): String = when (this) {
    is ConnectOutcome.Connected -> "DSH_CONNECT_RESULT connected authority=$authority home=$home protocol=$protocol"
    is ConnectOutcome.Failed -> "DSH_CONNECT_RESULT failed kind=$failure authority=$authority detail=${detail.orEmpty()}"
}

/** Budgets as the Android Connect screen spends them on an address someone typed. */
private const val CONNECT_TIMEOUT_MS = 2_500L
private const val READ_TIMEOUT_MS = 4_000L

/** The whole attempt, including the mux handshake's own deadlines, must end by here. */
private const val ATTEMPT_DEADLINE_MS = 12_000L

/**
 * Find out whether a harness answers at [raw], the way the Android app's connect does it.
 *
 * Two questions, in order. First one unary call, `session/canOpenWorkspacePath` (what
 * `DiscoveryEngine.probe` asks): it needs no arguments, every harness serves it, and its failures
 * carry HTTP-level detail, so a refusal, a fence (403), a missing session (401) and "not a harness"
 * all arrive as distinct, classified answers. Then the real readiness handshake: open the
 * `/api/remote.mux` WebSocket and read the `$events` ready frame through [ConnectionLoop], which is
 * what makes the Android app say Connected. The unary call rides [newClient]'s client, the Darwin
 * engine on iOS, and the WebSocket is the platform's own mux socket (`DarwinMuxSocket`, straight on
 * `NSURLSessionWebSocketTask`, through [platformMuxSocketFactory]), so a success here is a unary
 * call and a WebSocket both working over `NSURLSession`, and a refused upgrade reports its status.
 *
 * Nothing is kept: the client is closed when the attempt ends. A connection that lives on (the loop
 * that reconnects, the session list) is the next step of the port.
 */
internal suspend fun connectToHarness(
    raw: String,
    newClient: () -> HttpClient = ::newHarnessHttpClient,
): ConnectOutcome {
    val address = parseHarnessAddress(raw)
        ?: return ConnectOutcome.Failed(ConnectFailure.INVALID_ADDRESS, raw.trim(), DEFAULT_HARNESS_PORT, null)
    fun failed(failure: ConnectFailure, detail: String?) =
        ConnectOutcome.Failed(failure, address.authority, address.port, detail)

    val client = newClient()
    try {
        val transport = try {
            KtorRpcTransport(address.baseUrl, client, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (e: IllegalArgumentException) {
            return failed(ConnectFailure.INVALID_ADDRESS, e.message)
        }
        val probe = DshApiClient(transport).sessionCanOpenWorkspacePath()
        if (probe is RpcResult.Err) {
            return failed(failureOf(TransportFailures.of(probe.error)), probe.error.message)
        }
        return try {
            withTimeout(ATTEMPT_DEADLINE_MS) { handshake(address) }
        } catch (e: TimeoutCancellationException) {
            failed(ConnectFailure.TIMEOUT, "the harness did not finish its handshake")
        }
    } finally {
        client.close()
    }
}

/** The mux half of a connect: open the socket, read `ready`, report either, and let go. */
private suspend fun handshake(address: HarnessAddress): ConnectOutcome {
    val result = CompletableDeferred<ConnectOutcome>()
    val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) = Unit

        override fun onConnected(generation: HostGeneration) {
            result.complete(
                ConnectOutcome.Connected(address.authority, generation.description.home, DshCore.PROTOCOL_BASELINE),
            )
        }

        override fun onStateChange(state: ConnectionState) = Unit

        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            val (kind, detail) = when (failure) {
                is GenerationFailure.MuxTimedOut -> ConnectFailure.TIMEOUT to "the event stream did not open in time"
                is GenerationFailure.MuxFailed -> failureOf(failure.kind) to failure.message
                is GenerationFailure.ReadyFailed -> ConnectFailure.OTHER to failure.error.message
            }
            result.complete(ConnectOutcome.Failed(kind, address.authority, address.port, detail))
        }
    }
    // Checked once here, where the address is known; the loop builds a socket from it for every generation.
    val socketFactory = platformMuxSocketFactory(address.muxUrl, connectTimeoutMs = CONNECT_TIMEOUT_MS)
    val loop = ConnectionLoop(
        muxFactory = { RemoteStreamMux(socketFactory) },
        sinks = sinks,
    )
    loop.start()
    try {
        return result.await()
    } finally {
        // The loop would go on to reconnect after a failure, and would hold the socket after a
        // success. This attempt is over either way.
        loop.stop()
    }
}

/** The screen's categories for a transport verdict; `null` (an error with no verdict) is [ConnectFailure.OTHER]. */
internal fun failureOf(kind: TransportFailure?): ConnectFailure = when (kind) {
    TransportFailure.REFUSED -> ConnectFailure.REFUSED
    TransportFailure.TIMEOUT -> ConnectFailure.TIMEOUT
    TransportFailure.DNS -> ConnectFailure.DNS
    TransportFailure.UNREACHABLE -> ConnectFailure.UNREACHABLE
    TransportFailure.TRUST_FENCE -> ConnectFailure.TRUST_FENCE
    TransportFailure.UNAUTHENTICATED -> ConnectFailure.UNAUTHENTICATED
    // The probe sends no arguments, so nothing it sends can be too large: a 413 means whatever
    // answered is not the harness, as on the Android screen.
    TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS, TransportFailure.TOO_LARGE -> ConnectFailure.NOT_A_HARNESS
    TransportFailure.TLS, TransportFailure.CERTIFICATE_PIN -> ConnectFailure.TLS
    // A throttle and a dead upstream describe a real harness address, so they show the carrier's
    // own wording rather than "not a harness".
    TransportFailure.RATE_LIMITED, TransportFailure.UPSTREAM_DOWN, TransportFailure.OTHER, null -> ConnectFailure.OTHER
}
