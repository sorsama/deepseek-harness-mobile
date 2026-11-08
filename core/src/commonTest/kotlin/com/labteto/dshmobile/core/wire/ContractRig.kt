package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient

/**
 * Where the transport contract in [HarnessContractTest] finds a harness, per platform.
 *
 * The same assertions run on the JVM (against fixture harnesses started in-process, over Ktor's CIO
 * engine) and on an iOS simulator (against the same fixtures run as a separate `mock-harness
 * --ci-fixtures` process, over the Darwin engine). That is what makes a difference between the two
 * a finding rather than a guess: the JVM run says what the contract is, and the iOS run says
 * whether `NSURLSession` keeps it. (There is no platform exemption left: M3a pinned two Darwin mux
 * differences, and the Darwin mux socket fixed them.) The fixtures themselves are `CiFixtures` in `:mock-harness`, and
 * the constants below must match it (the JVM rig checks).
 */
internal interface ContractRig {
    /** A harness with no authentication. */
    val plainUrl: String

    /** A harness 0.1.2 in its browser-session posture: 401 until the launch token is exchanged. */
    val sessionUrl: String

    /** A `dsh-relay`: 403 until the bearer token is sent. */
    val relayUrl: String

    /** An address nothing listens on. */
    val deadUrl: String

    /**
     * A WebSocket server that pings every 100 ms and disconnects a client that has not answered within
     * 500 ms (`/ci/ws-keepalive`: it says `alive` after 1.5 s). It is a separate server because the
     * harness fixtures never ping.
     */
    val keepAliveUrl: String

    /** A server that completes a WebSocket handshake and then drops the connection, with no Close frame, 300 ms later. */
    val dropUrl: String

    /** A fresh client of the platform's engine, built the way the app builds one. Close it after use. */
    fun newClient(): HttpClient

    /**
     * The mux socket this platform's app rides on, for the contract the mux assumes of every socket:
     * `DarwinMuxSocket` on iOS, and on the JVM the Ktor reference ([KtorMuxSocket] over [client]; the
     * Android app's own socket is `WsChannel`, which the JVM-only `MuxSocketContractTest` holds to the
     * same contract). A refused 401 or 403 upgrade carries its real status, a 3 MiB message arrives
     * whole and a host-initiated close is a clean one on both, which is the point of running one set of
     * assertions against both.
     */
    fun newMuxSocket(
        client: HttpClient,
        url: String,
        sink: WsChannelSink,
        authorization: String? = null,
        cookie: String? = null,
        connectTimeoutMs: Long = 30_000,
    ): MuxSocket
}

internal object ContractFixtures {
    const val SESSION_TOKEN = "launch-token-test"
    const val SESSION_COOKIE = "dsh-auth-test=session-test"
    const val RELAY_TOKEN = "relay-test-token"
    const val HOME = "C:\\Users\\demo"
    const val CLIENT_ID = "mock-client"
    const val EXPORT_SIZE = 300 * 1024
    const val BLOB_SIZE = 40 * 1024
    const val STREAM_LARGE_CHARS = 3 shl 20
    const val STREAM_UNICODE = "w\u00f6rld \uD83D\uDC0B \u65E5\u672C\u8A9E"

    /** What `/ci/ws-close` says before it closes the socket normally, and what `/ci/ws-keepalive` says once it has been pinged enough. */
    const val WS_CLOSE_GREETING = "goodbye"
    const val WS_ALIVE = "alive"

    fun exportBytes(): ByteArray = ByteArray(EXPORT_SIZE) { ((it * 31 + 7) and 0xFF).toByte() }

    fun blobBytes(): ByteArray = ByteArray(BLOB_SIZE) { ((it * 17 + 3) and 0xFF).toByte() }
}

/** Opens the platform's rig. Called once per test run. */
internal expect suspend fun openContractRig(): ContractRig
