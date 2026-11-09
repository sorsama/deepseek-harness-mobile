package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.mockharness.CiFixtures
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.ServerSocket

/** The fixtures in-process, on ports the OS picks, with Ktor's CIO engine as the carrier. */
internal actual suspend fun openContractRig(): ContractRig {
    check(CiFixtures.sessionMode.token == ContractFixtures.SESSION_TOKEN) { "ContractFixtures drifted from CiFixtures" }
    check(CiFixtures.sessionMode.cookie == ContractFixtures.SESSION_COOKIE) { "ContractFixtures drifted from CiFixtures" }
    check(CiFixtures.relayMode.token == ContractFixtures.RELAY_TOKEN) { "ContractFixtures drifted from CiFixtures" }
    check(CiFixtures.EXPORT_SIZE == ContractFixtures.EXPORT_SIZE && CiFixtures.BLOB_SIZE == ContractFixtures.BLOB_SIZE) {
        "ContractFixtures drifted from CiFixtures"
    }
    check(CiFixtures.exportBytes().contentEquals(ContractFixtures.exportBytes())) { "ContractFixtures drifted from CiFixtures" }
    check(CiFixtures.blobBytes().contentEquals(ContractFixtures.blobBytes())) { "ContractFixtures drifted from CiFixtures" }
    check(CiFixtures.STREAM_UNICODE == ContractFixtures.STREAM_UNICODE && CiFixtures.STREAM_LARGE_CHARS == ContractFixtures.STREAM_LARGE_CHARS) {
        "ContractFixtures drifted from CiFixtures"
    }
    check(CiFixtures.WS_CLOSE_GREETING == ContractFixtures.WS_CLOSE_GREETING && CiFixtures.WS_ALIVE == ContractFixtures.WS_ALIVE) {
        "ContractFixtures drifted from CiFixtures"
    }
    val servers = CiFixtures.start()
    val closedPort = ServerSocket(0).use { it.localPort }
    return object : ContractRig {
        override val plainUrl = servers.plainUrl
        override val sessionUrl = servers.sessionUrl
        override val relayUrl = servers.relayUrl
        override val deadUrl = "http://127.0.0.1:$closedPort"
        override val keepAliveUrl = servers.keepAliveUrl
        override val dropUrl = servers.dropUrl
        override fun newClient(): HttpClient = HttpClient(CIO) { dshCarrier() }

        override fun newMuxSocket(
            client: HttpClient,
            url: String,
            sink: WsChannelSink,
            authorization: String?,
            cookie: String?,
            connectTimeoutMs: Long,
        ): MuxSocket = KtorMuxSocket(url, client, sink, authorization, cookie, connectTimeoutMs)
    }
}
