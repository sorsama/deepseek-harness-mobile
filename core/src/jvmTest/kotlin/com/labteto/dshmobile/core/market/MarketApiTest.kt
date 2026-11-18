package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.MARKET_MALFORMED
import com.labteto.dshmobile.core.wire.RpcHttpResponse
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.RpcTransport
import com.labteto.dshmobile.core.wire.marketIndexes
import com.labteto.dshmobile.core.wire.marketSearch
import com.labteto.dshmobile.core.wire.marketSetIndexes
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketApiTest {
    /** Records the request and answers with a whole captured envelope (or any body). */
    private class Transport(private val answer: String) : RpcTransport {
        var path = ""; var payload: JsonObject? = null
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            payload = Json.parseToJsonElement(body).jsonObject["payload"]!!.jsonObject
            return RpcHttpResponse(200, answer)
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: Source, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    private fun captured(name: String) = MarketFixtures.envelope(name).toString()

    @Test fun searchPostsTheHostMethodsOwnArgumentsAndParsesTheRealAnswer() = runTest {
        val transport = Transport(captured("search-all"))
        val result = DshApiClient(transport).marketSearch("http://127.0.0.1:56276/index.json", MarketQuery("review"), 0, 50)
        assertEquals("/api/pluginMarket/search", transport.path)
        val args = transport.payload!!.getValue("args").jsonObject
        assertEquals(setOf("url", "query"), args.keys)
        assertEquals("relevance", args.getValue("query").jsonObject.getValue("sort").jsonObject.getValue("kind").jsonPrimitive.content)
        val page = (result as RpcResult.Ok).value
        assertEquals(5, page.entries.size)
    }

    @Test fun aHostWithoutTheMarketAnswersCapabilityUnavailable() = runTest {
        val body = """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"gateway/invocation-unavailable","message":"no such method"}}}"""
        val result = DshApiClient(Transport(body)).marketIndexes()
        val failure = (result as RpcResult.Err).error.toMarketFailure()
        assertEquals(MarketFailure.Unavailable, failure)
    }

    @Test fun indexesParseTheRealSourceList() = runTest {
        val transport = Transport(captured("indexes-final"))
        val sources = (DshApiClient(transport).marketIndexes() as RpcResult.Ok).value
        assertEquals("/api/pluginMarket/listIndexes", transport.path)
        assertEquals(emptyMap<String, Any>(), transport.payload!!.getValue("args").jsonObject.toMap())
        assertEquals(3, sources.size)
    }

    @Test fun anAnswerThatIsNotAMarketAnswerIsAnErrorNotACrash() = runTest {
        val body = """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":"surprise"}}"""
        val search = DshApiClient(Transport(body)).marketSearch("u", MarketQuery(), 0, 50)
        assertEquals(MARKET_MALFORMED, (search as RpcResult.Err).error.code)
        assertEquals(MarketFailure.Malformed, search.error.toMarketFailure())
        assertTrue(DshApiClient(Transport(body)).marketIndexes() is RpcResult.Err)
    }

    @Test fun theHostsRefusalsComeBackAsTheirOwnFailures() = runTest {
        val unknown = DshApiClient(Transport(captured("search-unknown-index"))).marketSearch("https://example.invalid/none.json", MarketQuery(), 0, 50)
        assertEquals(MarketFailure.UnknownIndex, (unknown as RpcResult.Err).error.toMarketFailure())
        val invalid = DshApiClient(Transport(captured("search-invalid-index"))).marketSearch("u", MarketQuery(), 0, 50)
        assertEquals("$.plugins[0].name", ((invalid as RpcResult.Err).error.toMarketFailure() as MarketFailure.InvalidIndex).path)
    }

    @Test fun setIndexesSendsTheWholeListAsUrls() = runTest {
        val transport = Transport("""{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}""")
        val result = DshApiClient(transport).marketSetIndexes(listOf("https://a.example/i.json", "https://b.example/i.json"))
        assertTrue(result is RpcResult.Ok)
        assertEquals("/api/pluginMarket/setIndexes", transport.path)
        val args = transport.payload!!.getValue("args").jsonObject
        assertEquals(setOf("urls"), args.keys)
        assertEquals(listOf("https://a.example/i.json", "https://b.example/i.json"), args.getValue("urls").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aRefusedSourceKeepsTheHostsReason() = runTest {
        val body = """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"code":"plugin-market/invalid-index-url","message":"Index source listed twice: u","details":{"url":"u","reason":"duplicate"}}}}"""
        val result = DshApiClient(Transport(body)).marketSetIndexes(listOf("u", "u"))
        assertEquals(MarketFailure.InvalidUrl("duplicate"), (result as RpcResult.Err).error.toMarketFailure())
    }
}
