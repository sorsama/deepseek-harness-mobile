package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * A failure has to arrive at the app with its *kind* intact.
 *
 * `RpcError.code` answers "can this build do that" (`forbidden` / `capability-unavailable`), which
 * is not the same question as "why did the wire fail". The connect screen needs the second one to
 * tell a firewall from a harness still bound to loopback, and the only other way to recover it
 * would be matching on English message text.
 */
class DshApiClientDetailsTest {

    private class ThrowingTransport(private val error: Throwable) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse = throw error
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("not used")
        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = throw error
    }

    private class FixedTransport(private val response: RpcHttpResponse) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse = response
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("not used")
        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = response
    }

    private fun client(transport: RpcTransport) = DshApiClient(transport = transport)

    /**
     * Any unary call would do; this one takes no arguments and every deployment composes it.
     * `host.describe` used to play that role and no longer exists.
     */
    private suspend fun probe(transport: RpcTransport) =
        client(transport).sessionCanOpenWorkspacePath()

    private suspend fun failureOf(transport: RpcTransport): TransportFailure? =
        when (val r = probe(transport)) {
            is RpcResult.Ok -> error("expected a failure")
            is RpcResult.Err -> TransportFailures.of(r.error)
        }

    @Test
    fun `a trust fence rejection keeps both its code and its kind`() = runTest {
        val transport = ThrowingTransport(RpcTransportException(403, carrierMessage(403)))
        when (val r = probe(transport)) {
            is RpcResult.Ok -> error("expected a failure")
            is RpcResult.Err -> {
                assertEquals("forbidden", r.error.code)
                assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.of(r.error))
                assertEquals(403, TransportFailures.statusOf(r.error))
            }
        }
    }

    @Test
    fun `a refused connection is distinguishable from a timeout`() = runTest {
        assertEquals(
            TransportFailure.REFUSED,
            failureOf(ThrowingTransport(RpcTransportException(0, "transport failure", ConnectException()))),
        )
        assertEquals(
            TransportFailure.TIMEOUT,
            failureOf(ThrowingTransport(RpcTransportException(0, "transport failure", SocketTimeoutException()))),
        )
    }

    @Test
    fun `a 404 reads as a missing capability`() = runTest {
        assertEquals(
            TransportFailure.NOT_FOUND,
            failureOf(ThrowingTransport(RpcTransportException(404, carrierMessage(404)))),
        )
    }

    /** Something else on the port answers 200 with HTML; that is not a transport error at all. */
    @Test
    fun `a non-harness 200 is marked as such`() = runTest {
        val html = FixedTransport(RpcHttpResponse(200, "<html><body>router admin</body></html>"))
        assertEquals(TransportFailure.NOT_A_HARNESS, failureOf(html))
    }

    /** A well-formed envelope whose value does not match the expected schema lands the same way. */
    @Test
    fun `a decodable envelope with the wrong value shape is marked as such`() = runTest {
        // The envelope parses and reports success; only the value inside it is not what the
        // method declares. Something else answering on the port lands here rather than in the
        // carrier-failure path, so the marker has to be set here too.
        val body = """{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"nope":1}}}"""
        val result = client(FixedTransport(RpcHttpResponse(200, body))).settingsDescribe()
        assertEquals(TransportFailure.NOT_A_HARNESS, TransportFailures.of((result as RpcResult.Err).error))
    }

    @Test
    fun `a value decode that throws outside SerializationException still returns a result`() = runTest {
        // Decoding an object where a primitive is declared surfaces from inside kotlinx as an
        // IndexOutOfBoundsException, not a SerializationException. This method promises an
        // RpcResult either way; an escaping exception would crash the connect screen instead of
        // reporting that the port is not a harness.
        val body = """{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"nope":1}}}"""
        val result = probe(FixedTransport(RpcHttpResponse(200, body)))
        assertEquals(TransportFailure.NOT_A_HARNESS, TransportFailures.of((result as RpcResult.Err).error))
    }
}
