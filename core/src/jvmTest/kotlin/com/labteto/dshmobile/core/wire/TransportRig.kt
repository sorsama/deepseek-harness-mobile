package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import okhttp3.OkHttpClient

/**
 * The two implementations of the transport seam, so one contract test runs against both.
 *
 * `OKHTTP` is what the Android app ships. `KTOR` is the multiplatform implementation the iOS app
 * will ship, here on a JVM engine (CIO): a pure-Kotlin HTTP stack that shares nothing with OkHttp,
 * so a test passing for both is a statement about the seam rather than about one library. A test
 * class opts in with `@RunWith(Parameterized::class)` and [parameters]; it then builds every
 * transport and socket through [rpc] / [socket] and never names a concrete class.
 */
enum class TransportKind {
    OKHTTP,
    KTOR,
    ;

    /** A unary/stream HTTP transport for [baseUrl]. */
    fun rpc(
        baseUrl: String,
        connectTimeoutMs: Long = 30_000,
        readTimeoutMs: Long = 30_000,
        authorization: String? = null,
        cookie: String? = null,
    ): RpcTransport = when (this) {
        OKHTTP -> OkHttpRpcTransport(
            baseUrl,
            okHttp,
            connectTimeoutMs,
            readTimeoutMs,
            authorization = authorization,
            cookie = cookie,
        )
        KTOR -> KtorRpcTransport(baseUrl, ktor, connectTimeoutMs, readTimeoutMs, authorization, cookie)
    }

    /** The carrier of the launch-token exchange and the relay calls. */
    fun plain(): PlainHttp = when (this) {
        OKHTTP -> OkHttpPlainHttp(okHttp)
        KTOR -> KtorPlainHttp(ktor)
    }

    /** A mux socket to [url] reporting to [sink]. */
    fun socket(
        url: String,
        sink: WsChannelSink,
        authorization: String? = null,
        cookie: String? = null,
    ): MuxSocket = when (this) {
        OKHTTP -> WsChannel(url, okHttp, sink, authorization, cookie)
        KTOR -> KtorMuxSocket(url, ktor, sink, authorization, cookie)
    }

    companion object {
        /** JUnit `Parameterized` source: one run per implementation. */
        @JvmStatic
        fun parameters(): List<TransportKind> = entries

        // Shared for the life of the test JVM, as the app shares its OkHttp client: a client per
        // test would leave a thread pool behind each one.
        private val okHttp: OkHttpClient by lazy { OkHttpClient() }

        private val ktor: HttpClient by lazy { HttpClient(CIO) { dshCarrier() } }
    }
}
