package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsageWireTest {
    private class Transport(var value:String="""{"providers":{}}""",val failure:Boolean=false,val status:Int=400,val error:String?=null):RpcTransport {
        var path="";var payload:JsonObject?=null
        override suspend fun post(path:String,body:String):RpcHttpResponse {
            this.path=path;payload=Json.parseToJsonElement(body).jsonObject["payload"]!!.jsonObject
            if(failure) throw RpcTransportException(status,"private server message")
            if(error!=null) return RpcHttpResponse(200,"""{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"$error","message":"no such method"}}}""")
            return RpcHttpResponse(200,"""{"type":"server-response","rpcId":"r","result":{"ok":true,"value":$value}}""")
        }
        override suspend fun <T> download(path:String,consume:(String?,String?,Source)->T):T=error("unused")
        override suspend fun upload(path:String,contentType:String,contentLength:Long,body:Source,onProgress:((Long)->Unit)?):RpcHttpResponse=error("unused")
    }
    @Test fun statusUsesRawEmptyPayload()=runTest { val t=Transport();assertTrue(DshApiClient(t).subscriptionStatus() is RpcResult.Ok);assertEquals("/api/subscriptions-auth.status",t.path);assertEquals(JsonObject(emptyMap()),t.payload) }
    @Test fun usageUsesRawFieldsAndNeverForce()=runTest { val t=Transport("""{"supported":false}"""); assertTrue(DshApiClient(t).subscriptionUsage("copilot","opaque") is RpcResult.Ok);assertEquals(setOf("provider","account","force"),t.payload!!.keys);assertEquals(false,t.payload!!["force"]!!.jsonPrimitive.boolean) }
    @Test fun walletUsesRemoteArgsAndMetadata()=runTest { val t=Transport("null");assertTrue(DshApiClient(t).accountBalance(AccountUiMetadata("1","ru",10800)) is RpcResult.Ok);assertEquals(setOf("args"),t.payload!!.keys); assertEquals(10800,t.payload!!["args"]!!.jsonObject["client"]!!.jsonObject["timezoneOffsetSeconds"]!!.jsonPrimitive.int) }
    @Test fun badRequestReturnsFailure()=runTest { assertTrue(DshApiClient(Transport(failure=true)).subscriptionStatus() is RpcResult.Err) }
    // What today's harnesses answer for `subscriptions-auth.*`: nothing mounts it, so the route is a 404 or a gateway "unavailable".
    @Test fun aHostWithoutSubscriptionsAuthReadsAsUnsupportedOverARouteMiss()=runTest { assertEquals(UsageFetch.Unsupported,DshApiClient(Transport(failure=true,status=404)).subscriptionStatus().toUsageFetch()) }
    @Test fun aHostWithoutSubscriptionsAuthReadsAsUnsupportedOverAGatewayError()=runTest {
        assertEquals(UsageFetch.Unsupported,DshApiClient(Transport(error="gateway/invocation-unavailable")).subscriptionStatus().toUsageFetch())
        assertEquals(UsageFetch.Unsupported,DshApiClient(Transport(error="gateway/definition-unavailable")).subscriptionUsage("codex","a").toUsageFetch())
    }
    @Test fun anAuthorizationRefusalIsNotEvidenceOfAMissingMethod()=runTest {
        assertEquals(UsageFetch.Failed,DshApiClient(Transport(failure=true,status=403)).subscriptionStatus().toUsageFetch())
        assertEquals(UsageFetch.Failed,DshApiClient(Transport(failure=true,status=401)).accountBalance(AccountUiMetadata("1","en",0)).toUsageFetch())
        assertEquals(UsageFetch.Failed,DshApiClient(Transport(failure=true,status=500)).subscriptionStatus().toUsageFetch())
    }
    @Test fun signedOutAccountIsANullValueNotAnError()=runTest { assertEquals(UsageFetch.Ok(null),DshApiClient(Transport("null")).accountBalance(AccountUiMetadata("1","en",0)).toUsageFetch()) }
    @Test fun failedStatusAndBonusWalletsDecodeFromTheDocumentedShape()=runTest {
        val failed=DshApiClient(Transport("""{"status":"failed"}""")).accountBalance(AccountUiMetadata("1","en",0)).toUsageFetch()
        assertEquals("failed",(failed as UsageFetch.Ok).value!!.status)
        val ready=DshApiClient(Transport("""{"status":"ready","value":[{"currency":"USD","balance":"0E-16"}],"bonusWallets":[{"currency":"CNY","balance":"5.0000000000000000"}]}""")).accountBalance(AccountUiMetadata("1","en",0)).toUsageFetch()
        val wallet=(ready as UsageFetch.Ok).value!!
        assertEquals("0E-16",wallet.value.single().balance);assertEquals("5.0000000000000000",wallet.bonusWallets.single().balance)
    }
}
