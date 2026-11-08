package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.KtorMuxSocket
import com.labteto.dshmobile.core.wire.KtorPlainHttp
import com.labteto.dshmobile.core.wire.KtorRpcTransport
import com.labteto.dshmobile.core.wire.MuxSocket
import com.labteto.dshmobile.core.wire.OkHttpPlainHttp
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.RpcTransport
import com.labteto.dshmobile.core.wire.WsChannel
import com.labteto.dshmobile.core.wire.WsChannelSink
import com.labteto.dshmobile.core.wire.dshCarrier
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import okhttp3.OkHttpClient

/**
 * The two implementations of the transport seam, so the end-to-end tests against the mock harness
 * run once on each.
 *
 * `OKHTTP` is what this app ships. `KTOR` is the multiplatform transport the iOS app ships, here on
 * a JVM engine (CIO), which shares no HTTP stack with OkHttp. A test opts in with
 * `@RunWith(Parameterized::class)` and [parameters] and builds its transports and sockets through
 * [rpc] / [socket].
 *
 * This is the same small rig `:core`'s own tests use (`TransportRig` there). It is repeated rather
 * than shared because a module cannot see another module's test sources, and a one-file test module
 * for forty lines would cost more than it saves. The Android app itself never loads the Ktor
 * classes: its build excludes Ktor from the `:core` dependency, and this file re-adds it for tests.
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

        private val okHttp: OkHttpClient by lazy { OkHttpClient() }

        private val ktor: HttpClient by lazy { HttpClient(CIO) { dshCarrier() } }
    }
}
