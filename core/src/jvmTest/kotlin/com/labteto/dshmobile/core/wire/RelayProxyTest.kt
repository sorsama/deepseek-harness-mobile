package com.labteto.dshmobile.core.wire

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RelayProxyTest {
    private suspend fun withRelay(
        redirect: String? = null,
        healthStatus: Int = 200,
        block: suspend (String, List<String>) -> Unit,
    ) {
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.rawPath
            val health = exchange.requestURI.path.endsWith("/relay/health")
            val status = if (health && redirect != null) 302 else if (health) healthStatus else 200
            if (health && redirect != null) exchange.responseHeaders.add("Location", redirect)
            val body = if (health) """{"service":"dsh-relay","ok":true}"""
                else """{"deviceId":"team-device","token":"team-token","expiresAt":9999999}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}", paths) }
        finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun `health and claim retain encoded deployment prefix`() = runBlocking {
        withRelay { origin, paths ->
            val base = "$origin/team%20one/dsh/"
            assertEquals(RelayOrigin.Here(base.trimEnd('/'), false), RelayPairing.locate(base, OkHttpClient()))
            val result = RelayPairing.claim(base, "123456", "phone", OkHttpClient())
            assertEquals("team-token", (result as RelayPairOutcome.Paired).response.token)
            assertEquals(listOf("/team%20one/dsh/relay/health", "/team%20one/dsh/relay/pair"), paths)
        }
    }

    @Test fun `health redirect retains the deployment root before claiming`() = runBlocking {
        withRelay(redirect = "/other/dsh/relay/health") { origin, paths ->
            val located = RelayPairing.locate("$origin/dsh", OkHttpClient()) as RelayOrigin.Redirected
            assertEquals("$origin/other/dsh", located.origin)
            assertTrue(RelayPairing.claim(located.origin, "123456", "phone", OkHttpClient()) is RelayPairOutcome.Paired)
            assertEquals(listOf("/dsh/relay/health", "/other/dsh/relay/pair"), paths)
        }
    }

    @Test fun `untrusted relay keeps its prefix and unrelated redirects are not relays`() = runBlocking {
        withRelay(healthStatus = 403) { origin, _ ->
            assertEquals(RelayOrigin.Untrusted("$origin/dsh", false), RelayPairing.locate("$origin/dsh/", OkHttpClient()))
        }
        withRelay(redirect = "/login") { origin, paths ->
            assertEquals(RelayOrigin.None, RelayPairing.locate("$origin/dsh", OkHttpClient()))
            assertEquals(listOf("/dsh/relay/health"), paths)
        }
    }
}
