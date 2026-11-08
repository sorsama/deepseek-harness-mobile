package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException

/**
 * Where a mux socket's upgrade request goes: the hand-spelled `Host` header and the `ws(s)://` URL.
 *
 * Shared by every carrier that is not OkHttp ([KtorMuxSocket], and the iOS `DarwinMuxSocket`), so
 * they cannot disagree about it: a reverse-proxy prefix stays in the path, the default port is
 * dropped from `Host`, an IPv6 literal is bracketed, credentials in the URL are refused.
 */
internal class MuxTarget(val hostHeader: String, val target: String)

/**
 * Parse a mux address, `http(s)://` as `resolveHarnessUrl` produces or `ws(s)://`.
 *
 * @throws IllegalArgumentException when it is not an address a harness could be at.
 */
internal fun muxTargetOf(url: String): MuxTarget {
    val secure = url.startsWith("https://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true)
    val httpForm = (if (secure) "https://" else "http://") + url.substringAfter("://")
    val endpoint = HarnessEndpoint.of(httpForm)
    return MuxTarget(
        hostHeader = endpoint.hostHeader,
        target = (if (secure) "wss://" else "ws://") + endpoint.hostHeader + Url(httpForm).encodedPathAndQuery,
    )
}

/**
 * A bidirectional WebSocket to `/api/remote.mux` on Ktor: the multiplatform implementation of
 * [MuxSocket]. It is the JVM-tested reference (it runs on CIO in the contract tests and the conformance
 * suite) and it is **not** what the iOS app rides on: on the Darwin engine Ktor's WebSocket loses the
 * status of a refused 401 upgrade and caps messages at 1 MiB, so iOS uses `DarwinMuxSocket`, written
 * directly on `NSURLSessionWebSocketTask`. `WsChannel` is the OkHttp counterpart and the contract is the same:
 *
 * - [authorization] and [cookie] ride on the upgrade request itself, which is the whole credential
 *   for this socket; `dsh-relay` refuses an upgrade without the token at the handshake, and a
 *   direct 0.1.2 harness refuses one without its browser-session cookie. `Host` is spelled with
 *   [hostHeaderFor], as the unary transport does.
 * - Text frames are handed to [WsChannelSink.onMessage] whole and undecoded; [WsChannelSink.onOpen]
 *   follows a completed handshake; [WsChannelSink.onClosed] is `null` on a clean close.
 * - A rejected upgrade is reported as an [RpcTransportException] carrying the HTTP status and
 *   [carrierMessage]'s text, exactly as `WsChannel` re-wraps OkHttp's bare `ProtocolException`.
 *   That keeps 401 (no browser session: re-pair) tellable from 403 (the Host/Origin fence:
 *   reconfigure) when a stream that would not open is the only thing the connect screen sees.
 * - [send] returns false once the socket is gone, and before [start]; it accepts messages while the
 *   handshake is still in flight (they are written as soon as it completes), and refuses once more
 *   than 16 MiB are waiting, as OkHttp does. [start] and [close] are idempotent. A socket the
 *   caller [close]d reports nothing further: the mux already knows.
 *
 * **Threading.** The sink is called from this socket's own reader coroutine, one callback at a time
 * and in order, on a background dispatcher — never from the caller's thread, which is the contract
 * `RemoteStreamMux` is written for. State shared with callers ([send], [close]) is atomics and a
 * channel; nothing here holds a lock while it calls out, so a sink that blocks briefly or a send
 * from inside a callback cannot deadlock the socket.
 *
 * **Keep-alive.** The harness sends RFC 6455 pings and the peer must answer with pongs. Ktor's
 * `DefaultWebSocketSession` answers every incoming ping automatically, whether or not a client
 * ping interval is set, which is what the CIO engine uses; the Darwin engine delegates the whole
 * protocol to `NSURLSessionWebSocketTask`, which answers pings below Ktor (Ktor's Darwin session
 * surfaces only data and string messages). So there is no application-level heartbeat here either.
 * `MuxSocketContractTest` proves the CIO case against a server that disconnects a client that
 * does not pong.
 *
 * The [client] must have [WebSockets] installed (see [dshCarrier]). [url] may be `http(s)://` as
 * `resolveHarnessUrl` produces, or `ws(s)://`.
 */
class KtorMuxSocket(
    url: String,
    private val client: HttpClient,
    private val sink: WsChannelSink,
    private val authorization: String? = null,
    private val cookie: String? = null,
    connectTimeoutMs: Long = 30_000,
) : MuxSocket {

    private val hostHeader: String
    private val target: String
    private val connectTimeout: Long = if (connectTimeoutMs <= 0) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else connectTimeoutMs

    init {
        require(client.pluginOrNull(WebSockets) != null) {
            "the HttpClient has no WebSockets plugin; build it with dshCarrier() applied"
        }
        val muxTarget = muxTargetOf(url)
        hostHeader = muxTarget.hostHeader
        target = muxTarget.target
    }

    private val started = atomic(false)
    private val closedLocally = atomic(false)
    private val terminated = atomic(false)

    /** Bytes accepted by [send] and not yet written, against the 16 MiB cap. */
    private val queuedBytes = atomic(0L)

    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    override fun start() {
        if (closedLocally.value || !started.compareAndSet(false, true)) return
        job = scope.launch { run() }
    }

    override fun send(text: String): Boolean {
        if (!started.value || closedLocally.value || terminated.value) return false
        val bytes = text.encodeToByteArray()
        if (queuedBytes.addAndGet(bytes.size.toLong()) > MAX_QUEUE_BYTES || !outbox.trySend(bytes).isSuccess) {
            queuedBytes.addAndGet(-bytes.size.toLong())
            return false
        }
        return true
    }

    override fun close() {
        if (!closedLocally.compareAndSet(false, true)) return
        outbox.close()
        // Abrupt, like OkHttp's `cancel()`: the mux closes a generation because it is over, often
        // because the link is already dead, and a polite close handshake has nobody to talk to.
        scope.cancel()
    }

    private suspend fun run() {
        var failure: Throwable? = null
        try {
            client.prepareGet {
                url(target)
                header(HttpHeaders.Host, hostHeader)
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
                if (cookie != null) header(HttpHeaders.Cookie, cookie)
                timeout {
                    connectTimeoutMillis = connectTimeout
                    // The mux is idle for as long as the host has nothing to say, and the harness
                    // pings to prove the link is alive; no read deadline may apply to it.
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                val status = response.status.value
                // Judged on the status rather than on the exception the WebSocket plugin would
                // raise, whose only record of it is English text.
                if (status != HTTP_SWITCHING_PROTOCOLS) throw RpcTransportException(status, carrierMessage(status))
                val session = response.body<DefaultClientWebSocketSession>()
                sink.onOpen()
                failure = pump(session)
            }
        } catch (e: CancellationException) {
            // The caller closed the socket (or the scope went away): nothing to report.
            return
        } catch (e: Throwable) {
            failure = e
        }
        if (!closedLocally.value && terminated.compareAndSet(false, true)) {
            outbox.close()
            sink.onClosed(failure)
        }
    }

    /**
     * Read frames until the socket ends, writing queued messages as they arrive.
     *
     * Returns null for a clean close and the cause for a failure of either direction.
     */
    private suspend fun pump(session: DefaultClientWebSocketSession): Throwable? {
        var writeFailure: Throwable? = null
        var readFailure: Throwable? = null
        coroutineScope {
            val writer = launch {
                try {
                    for (bytes in outbox) {
                        session.outgoing.send(Frame.Text(true, bytes))
                        queuedBytes.addAndGet(-bytes.size.toLong())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    writeFailure = e
                    // A socket we cannot write to is a failed socket; end the read side with it.
                    session.cancel()
                }
            }
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) sink.onMessage(frame.readText())
                }
                // The incoming channel also ends, without an error, when the connection drops with
                // no Close frame (Ktor's session and the Darwin engine both do this). OkHttp reports
                // that as a failure, and so must this: "the host closed the stream" and "the link
                // died" are different explanations for the same reconnect.
                val reason = withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { session.closeReason.await() }
                if (reason != null && reason.code == CloseReason.Codes.CLOSED_ABNORMALLY.code) {
                    readFailure = IOException("websocket closed without a close frame: ${reason.message}")
                }
            } catch (e: CancellationException) {
                if (writeFailure == null) throw e
            } catch (e: Throwable) {
                readFailure = e
            } finally {
                writer.cancel()
            }
        }
        return writeFailure ?: readFailure
    }

    private companion object {
        const val HTTP_SWITCHING_PROTOCOLS = 101

        /** How long to wait for the close reason once the incoming channel has ended. */
        const val CLOSE_REASON_WAIT_MS = 1_000L

        /** OkHttp's own cap on queued outgoing bytes. */
        const val MAX_QUEUE_BYTES = 16L * 1024 * 1024
    }
}
