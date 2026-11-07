package com.labteto.dshmobile.core.wire

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A bidirectional WebSocket to `/api/remote.mux`, on OkHttp: the JVM implementation of
 * [MuxSocket].
 *
 * Its predecessor was downlink-only: `/api/events.mux` and `/api/events.host` closed the socket
 * with 1008 on any client message. The 0.1.2 mux is the opposite — the client must send `open`
 * and `cancel` to get anything at all — so [send] is the substantive difference here, not an
 * extra.
 *
 * The host sends RFC 6455 Ping at `websocketHeartbeatIntervalMs` (30s by default) and OkHttp
 * answers Pong at the protocol layer, so idle liveness needs no application code and no
 * application-level heartbeat frame. There is no Pong deadline upstream; half-open detection is
 * left to TCP.
 *
 * [authorization] rides on the upgrade itself, which is the whole credential for this socket:
 * there is no later request to carry one. `dsh-relay` refuses an unauthenticated upgrade at the
 * handshake, and against a direct 0.1.2 harness the browser-session cookie is required here too —
 * a missing credential costs the entire connection generation rather than one call.
 *
 * Open so a test can stand in for the socket without touching the network; the mux sees only the
 * [MuxSocket] contract either way.
 */
open class WsChannel(
    private val url: String,
    private val client: OkHttpClient,
    private val sink: WsChannelSink,
    private val authorization: String? = null,
    private val cookie: String? = null,
) : MuxSocket {
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var started: Boolean = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sink.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            sink.onMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sink.onClosed(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A rejected upgrade carries its status only on the response; OkHttp reports it as a
            // bare ProtocolException ("Expected HTTP 101 … was '403'"), which no caller can
            // classify without reading English. Re-wrap so a trust-fence rejection of the stream
            // reads the same as one on a POST — and so 401 (no browser session) stays tellable
            // apart from 403 (Host/Origin refused), which now mean different things.
            sink.onClosed(
                if (response != null) {
                    RpcTransportException(response.code, carrierMessage(response.code), t)
                } else {
                    t
                },
            )
        }
    }

    /** Perform the RFC 6455 handshake and begin reading messages. Idempotent. */
    override fun start() {
        if (started) return
        started = true
        val request = Request.Builder()
            .url(url)
            .authorized(authorization)
            .cookied(cookie)
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * Queue one text message. Returns false when the socket is gone or its send buffer is full —
     * the caller must treat that as the stream having failed rather than retrying, because
     * OkHttp has already begun tearing the socket down by then.
     */
    override fun send(text: String): Boolean = webSocket?.send(text) ?: false

    /** Tear the socket down. Idempotent. */
    override fun close() {
        webSocket?.cancel()
        webSocket = null
    }
}
