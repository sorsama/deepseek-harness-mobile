package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient

/**
 * The fixtures as a separate process, which the `ios-app` CI job starts before it runs the tests
 * (`mock-harness --ci-fixtures`, see `CiFixtures` in `:mock-harness`): a simulator shares the Mac's
 * network, so `127.0.0.1` is that process. The ports are compiled in because a Kotlin/Native test
 * has no other way to be told. If nothing is listening, every test fails with a refused connection,
 * which is the right answer to "you forgot to start the harness".
 *
 * The mux socket is the one the iOS app rides on, [DarwinMuxSocket], built through the same
 * [platformMuxSocketFactory] the app calls. Each one prints what `NSURLSession` told it (`MUX_TRACE`,
 * with the socket's number), so a run's log says which delegate event decided each outcome.
 */
internal actual suspend fun openContractRig(): ContractRig = object : ContractRig {
    override val plainUrl = "http://127.0.0.1:3181"
    override val sessionUrl = "http://127.0.0.1:3182"
    override val relayUrl = "http://127.0.0.1:3183"
    override val deadUrl = "http://127.0.0.1:3189"
    override val keepAliveUrl = "http://127.0.0.1:3184"
    override val dropUrl = "http://127.0.0.1:3185"
    override fun newClient(): HttpClient = darwinHttpClient()

    /** Tests run one at a time, so a plain counter does for numbering the trace. */
    private var sockets = 0

    override fun newMuxSocket(
        client: HttpClient,
        url: String,
        sink: WsChannelSink,
        authorization: String?,
        cookie: String?,
        connectTimeoutMs: Long,
    ): MuxSocket {
        val socket = platformMuxSocketFactory(url, authorization, cookie, connectTimeoutMs)(sink)
        val number = ++sockets
        (socket as DarwinMuxSocket).diagnostics = { line -> println("MUX_TRACE #$number ${url.substringAfter("://")}: $line") }
        return socket
    }
}
