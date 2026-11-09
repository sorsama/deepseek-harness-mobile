package com.labteto.dshmobile.core.wire

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A loopback HTTP server that remembers what it was sent, for tests that need to look at the bytes
 * a transport actually put on the wire (the `Host` it spelled, the credential it attached, the path
 * it resolved to) rather than only at what came back.
 */
internal class RecordingServer : AutoCloseable {

    /** One request as it arrived. Header names are lower-cased, values are the first one sent. */
    class Seen(
        val method: String,
        val rawPath: String,
        val rawQuery: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    val seen = CopyOnWriteArrayList<Seen>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        server.executor = executor
    }

    /** Serve [path] (and everything under it) with [handler], after recording the request. */
    fun route(path: String, handler: (HttpExchange, ByteArray) -> Unit): RecordingServer {
        server.createContext(path) { exchange ->
            val body = exchange.requestBody.readBytes()
            seen += Seen(
                method = exchange.requestMethod,
                rawPath = exchange.requestURI.rawPath,
                rawQuery = exchange.requestURI.rawQuery,
                headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() },
                body = body,
            )
            try {
                handler(exchange, body)
            } catch (e: java.io.IOException) {
                // The client hung up mid-response, which several tests do on purpose.
            } finally {
                exchange.close()
            }
        }
        return this
    }

    fun start(): RecordingServer {
        server.start()
        return this
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

/** Answer [exchange] with [status] and [body] in one piece. */
internal fun HttpExchange.reply(
    status: Int,
    body: ByteArray = ByteArray(0),
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
) {
    contentType?.let { responseHeaders.add("Content-Type", it) }
    headers.forEach { (k, v) -> responseHeaders.add(k, v) }
    if (body.isEmpty()) {
        sendResponseHeaders(status, -1)
    } else {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}

internal fun HttpExchange.reply(status: Int, text: String, contentType: String? = null, headers: Map<String, String> = emptyMap()) =
    reply(status, text.toByteArray(), contentType, headers)
