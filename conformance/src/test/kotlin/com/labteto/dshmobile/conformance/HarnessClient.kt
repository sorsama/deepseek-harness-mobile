package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.HarnessSession
import com.labteto.dshmobile.core.wire.KtorMuxSocket
import com.labteto.dshmobile.core.wire.KtorPlainHttp
import com.labteto.dshmobile.core.wire.KtorRpcTransport
import com.labteto.dshmobile.core.wire.MuxSocket
import com.labteto.dshmobile.core.wire.OkHttpPlainHttp
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcTransport
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.WsChannel
import com.labteto.dshmobile.core.wire.dshCarrier
import com.labteto.dshmobile.core.wire.dto.REMOTE_STREAM_MUX_PATH
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Which implementation of the transport seam a conformance run drives the real harness with.
 *
 * `okhttp` (the default) is what the Android app ships. `ktor` is the multiplatform transport the
 * iOS app ships, here on Ktor's pure-Kotlin JVM engine because no iOS device is in the loop: the
 * same `DshApiClient`, `RemoteStreamMux` and fold on top, a different carrier underneath. CI runs the
 * suite once for each, so the pinned harness referees both. Select with
 * `./gradlew :conformance:test -Pdsh.transport=ktor`.
 */
enum class TransportChoice {
    OKHTTP,
    KTOR,
    ;

    companion object {
        /** The transport this run was asked for, from the `dsh.transport` system property. */
        val selected: TransportChoice by lazy {
            when (val name = System.getProperty("dsh.transport")?.trim()?.lowercase().orEmpty()) {
                "", "okhttp" -> OKHTTP
                "ktor" -> KTOR
                else -> error("unknown -Pdsh.transport=$name (expected okhttp or ktor)")
            }
        }
    }
}

/**
 * The shipped client, pointed at a real harness.
 *
 * Nothing here re-describes the protocol: the authentication, the envelopes, the mux and the fold
 * are all `:core` as the app ships it. The only thing this adds is the launch-token exchange, which
 * on a phone is a person pasting the harness's startup line and here is one call — and which is
 * itself worth exercising, because `:mock-harness` implements no `GET /?token=` at all, so the
 * direct-connection authentication tier every non-relay user is on has never had an end-to-end test.
 *
 * The unary calls, the mux and the launch-token exchange (`HarnessSession`) all ride whichever
 * transport [TransportChoice.selected] names.
 */
class HarnessClient(harness: HarnessProcess, existingCookie: String? = null) : AutoCloseable {

    private val choice = TransportChoice.selected

    private val trust: javax.net.ssl.X509TrustManager? = harness.tlsCertificate?.let { file ->
        val certificate = file.inputStream().use { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("harness-test", certificate)
        }
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // The mux is a long-lived socket; the harness pings it every two seconds and OkHttp answers
        // at the protocol layer, so no read deadline may apply to it.
        .pingInterval(0, TimeUnit.SECONDS)
        .apply {
            trust?.let { trust ->
                val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                sslSocketFactory(ssl.socketFactory, trust)
            }
        }
        .build()

    /** Only built for a Ktor run. Trusts the harness's own certificate, as [http] does. */
    private val ktor: HttpClient? = if (choice == TransportChoice.KTOR) {
        HttpClient(CIO) {
            dshCarrier()
            engine {
                https { trust?.let { trustManager = it } }
            }
        }
    } else {
        null
    }

    /** The carrier of the launch-token exchange: the same implementation as the calls it unlocks. */
    private val plain: PlainHttp = when (choice) {
        TransportChoice.OKHTTP -> OkHttpPlainHttp(http)
        TransportChoice.KTOR -> KtorPlainHttp(ktor!!)
    }

    /** The browser-session cookie this client exchanged its launch token for. */
    val cookie: String = existingCookie ?: runBlocking {
        when (val result = HarnessSession.exchange(harness.baseUrl, harness.launchToken, plain)) {
            is SessionExchange.Granted -> result.cookie
            else -> error("the harness refused the launch token: $result")
        }
    }

    val baseUrl: String = harness.baseUrl

    private fun transport(cookie: String?): RpcTransport = when (choice) {
        TransportChoice.OKHTTP -> OkHttpRpcTransport(baseUrl, http, cookie = cookie)
        TransportChoice.KTOR -> KtorRpcTransport(baseUrl, ktor!!, 15_000, 30_000, cookie = cookie)
    }

    private val rpc: RpcTransport = transport(cookie)

    /** The unary half, authenticated. */
    val api: DshApiClient = DshApiClient(rpc)

    /** The class carrying the unary calls; a test pins it so a Ktor run cannot quietly be an OkHttp one. */
    val rpcTransportClass: String get() = rpc::class.simpleName.orEmpty()

    /** The class of the mux socket most recently built, or null before the mux has started. */
    @Volatile
    var muxSocketClass: String? = null
        private set

    /** The stream half. One socket, as the app opens one. */
    val mux: RemoteStreamMux = RemoteStreamMux { sink ->
        val socket: MuxSocket = when (choice) {
            TransportChoice.OKHTTP -> WsChannel(
                url = baseUrl + REMOTE_STREAM_MUX_PATH,
                client = http,
                sink = sink,
                cookie = cookie,
            )
            TransportChoice.KTOR -> KtorMuxSocket(
                url = baseUrl + REMOTE_STREAM_MUX_PATH,
                client = ktor!!,
                sink = sink,
                cookie = cookie,
            )
        }
        muxSocketClass = socket::class.simpleName
        socket
    }

    /** An unauthenticated client against the same harness, for the 401 and fence cases. */
    fun anonymous(): DshApiClient = DshApiClient(transport(null))

    override fun close() {
        runCatching { mux.close() }
        ktor?.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
