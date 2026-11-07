package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.PluginInstallOptions
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProxyTransportTest {
    @Test fun `token and slow plugin requests use the same prefixed root`() = runBlocking {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/proxy/") { exchange ->
            paths += exchange.requestURI.path
            if (exchange.requestMethod == "GET") {
                exchange.responseHeaders.add("Set-Cookie", "dsh-auth-fixture=test; Path=/proxy/; HttpOnly")
                exchange.responseHeaders.add("Location", "/proxy/")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val request = WireJson.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                Thread.sleep(150)
                val bytes = """{"type":"server-response","rpcId":${request["rpcId"]},"result":{"ok":true,"value":{"changed":true,"application":"applied","stage":"install","target":"fixture"}}}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/proxy"
            val session = HarnessSession.exchange(base, "fixture", okhttp3.OkHttpClient())
            assertTrue("$session", session is SessionExchange.Granted)
            val api = DshApiClient(OkHttpRpcTransport(base, readTimeoutMs = 30))
            assertEquals("applied", api.installPlugin("fixture", PluginInstallOptions(requestId = "request")).featureValue().application)
            assertEquals(listOf("/proxy/", "/proxy/api/pluginManager/installBundle"), paths)
        } finally { server.stop(0); executor.shutdownNow() }
    }
}

