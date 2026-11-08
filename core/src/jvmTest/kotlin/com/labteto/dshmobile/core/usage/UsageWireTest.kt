package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsageWireTest {
    private class Transport(var value:String="""{"providers":{}}""",val failure:Boolean=false):RpcTransport {
        var path="";var payload:JsonObject?=null
        override suspend fun post(path:String,body:String):RpcHttpResponse {
            this.path=path;payload=Json.parseToJsonElement(body).jsonObject["payload"]!!.jsonObject
            if(failure) throw RpcTransportException(400,"private server message")
            return RpcHttpResponse(200,"""{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""")
        }
        override suspend fun <T> download(path:String,consume:(String?,String?,Source)->T):T=error("unused")
        override suspend fun upload(path:String,contentType:String,contentLength:Long,body:Source,onProgress:((Long)->Unit)?):RpcHttpResponse=error("unused")
    }
    @Test fun statusUsesRawEmptyPayload()=runTest { val t=Transport();assertTrue(DshApiClient(t).subscriptionStatus() is RpcResult.Ok);assertEquals("/api/subscriptions-auth.status",t.path);assertEquals(JsonObject(emptyMap()),t.payload) }
    @Test fun usageUsesRawFieldsAndNeverForce()=runTest { val t=Transport("""{"supported":false}"""); assertTrue(DshApiClient(t).subscriptionUsage("copilot","opaque") is RpcResult.Ok);assertEquals(setOf("provider","account","force"),t.payload!!.keys);assertEquals(false,t.payload!!["force"]!!.jsonPrimitive.boolean) }
    @Test fun walletUsesRemoteArgsAndMetadata()=runTest { val t=Transport("null");assertTrue(DshApiClient(t).accountBalance(AccountUiMetadata("1","ru",10800)) is RpcResult.Ok);assertEquals(setOf("args"),t.payload!!.keys); assertEquals(10800,t.payload!!["args"]!!.jsonObject["client"]!!.jsonObject["timezoneOffsetSeconds"]!!.jsonPrimitive.int) }
    @Test fun badRequestReturnsFailure()=runTest { assertTrue(DshApiClient(Transport(failure=true)).subscriptionStatus() is RpcResult.Err) }
}
