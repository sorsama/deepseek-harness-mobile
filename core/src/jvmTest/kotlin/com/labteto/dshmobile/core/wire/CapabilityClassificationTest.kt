package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class CapabilityClassificationTest {
    private class ErrorTransport(val code: String) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val id = WireJson.parseToJsonElement(body).jsonObject.getValue("rpcId")
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":$id,"result":{"ok":false,"error":{"code":"$code","message":"gateway refusal","details":{"endpoint":"schedule/catalog"}}}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    @Test fun `gateway missing and withdrawn exports normalize to the HTTP 404 capability code`() = runTest {
        for (code in listOf("gateway/invocation-unavailable", "gateway/definition-unavailable")) {
            val error = (DshApiClient(ErrorTransport(code)).automationCatalog() as RpcResult.Err).error
            assertEquals("capability-unavailable", error.code)
            assertEquals("gateway refusal", error.message)
            assertEquals("schedule/catalog", error.details.jsonObject.getValue("endpoint").jsonPrimitive.content)
            assertEquals(error, RpcError(code, error.message, error.details).classifyCapability())
        }
    }

    @Test fun `ordinary lookup auth and invocation failures are not missing capabilities`() = runTest {
        for (code in listOf("gateway/lookup-not-found", "gateway/context-not-found", "gateway/arguments-invalid", "unauthenticated", "internal")) {
            val error = (DshApiClient(ErrorTransport(code)).automationCatalog() as RpcResult.Err).error
            assertEquals(code, error.code)
        }
    }
}
