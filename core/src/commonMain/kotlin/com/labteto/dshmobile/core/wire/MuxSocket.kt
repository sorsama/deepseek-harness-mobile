package com.labteto.dshmobile.core.wire

/**
 * Receives WebSocket carrier events.
 *
 * Every callback may run on the carrier's own socket thread — OkHttp's reader thread on Android,
 * whatever queue the platform socket delivers on elsewhere — and never on a coroutine of the
 * caller. [RemoteStreamMux] is written for that: it touches shared state only through atomics.
 */
interface WsChannelSink {
    /**
     * One complete text message, still unparsed.
     *
     * The carrier deliberately does not decode: harness 0.1.2 multiplexes every logical stream
     * over this one socket, so which parser applies is a function of the message, not the socket.
     * Parsing here would put the mux's dispatch table in the transport.
     */
    fun onMessage(text: String)

    /** The WebSocket handshake completed and the socket is ready. */
    fun onOpen()

    /**
     * The socket closed or failed. `cause` is non-null on a failure, null on a clean close.
     * Every logical stream riding this socket ends with it.
     */
    fun onClosed(cause: Throwable?)
}

/**
 * The one physical socket a [RemoteStreamMux] rides, as the mux sees it: start it, send text on
 * it, tear it down. Nothing else about a WebSocket reaches the shared code.
 *
 * This is the seam between the mux and the platform. On Android it is `WsChannel`, an OkHttp
 * WebSocket; on iOS it is `DarwinMuxSocket`, written on `NSURLSessionWebSocketTask`; `KtorMuxSocket`
 * is the multiplatform reference both are held to ([platformMuxSocketFactory] picks the platform's). The
 * handshake rules — the `Authorization` header, the browser-session cookie, how a rejected
 * upgrade is reported — belong to the implementation and are fixed at construction, which is why
 * none of them appear here. A socket reports back through the [WsChannelSink] it was created with
 * (see [MuxSocketFactory]).
 */
interface MuxSocket {
    /** Perform the RFC 6455 handshake and begin reading messages. Idempotent. */
    fun start()

    /**
     * Queue one text message. Returns false when the socket is gone or its send buffer is full —
     * the caller must treat that as the stream having failed rather than retrying, because the
     * platform has already begun tearing the socket down by then.
     */
    fun send(text: String): Boolean

    /** Tear the socket down. Idempotent. */
    fun close()
}

/**
 * Builds the [MuxSocket] for one mux generation, wired to the [sink] that will hear its events.
 *
 * A factory rather than a socket because a mux owns one socket's lifetime and
 * [ConnectionLoop] builds a fresh mux, and so a fresh socket, for every generation.
 */
typealias MuxSocketFactory = (sink: WsChannelSink) -> MuxSocket
