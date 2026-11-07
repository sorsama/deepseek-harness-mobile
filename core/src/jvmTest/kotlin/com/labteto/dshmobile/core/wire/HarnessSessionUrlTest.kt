package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class HarnessSessionUrlTest {
    @Test fun `root exchange is identical with or without trailing slash`() = runBlocking {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.toString())
            exchange.responseHeaders.add("Set-Cookie", "dsh-auth-test=session; Path=/; HttpOnly")
            exchange.responseHeaders.add("Location", "./")
            exchange.sendResponseHeaders(303, -1)
            exchange.close()
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val client = OkHttpClient()
            assertEquals(SessionExchange.Granted("dsh-auth-test=session"), HarnessSession.exchange(root, "abc", client))
            assertEquals(SessionExchange.Granted("dsh-auth-test=session"), HarnessSession.exchange("$root/", "abc", client))
            assertEquals(listOf("/?token=abc", "/?token=abc"), paths)
        } finally { server.stop(0) }
    }
}
