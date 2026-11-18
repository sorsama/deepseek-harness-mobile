package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.RpcError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketQueryTest {
    private val url = "https://market.example/index.json"

    private fun JsonObject.query(): JsonObject = getValue("query").jsonObject

    @Test fun anEmptyQueryAsksForIndexOrderWithOnlyThePage() {
        val args = marketSearchArgs(url, MarketQuery(), 0, 50)
        // The host matches parameter names exactly: `url` and `query`, nothing else.
        assertEquals(setOf("url", "query"), args.keys)
        assertEquals(url, args.getValue("url").jsonPrimitive.content)
        val query = args.query()
        assertEquals(setOf("sort", "offset", "limit"), query.keys)
        assertEquals("index", query.getValue("sort").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(0, query.getValue("offset").jsonPrimitive.int)
        assertEquals(50, query.getValue("limit").jsonPrimitive.int)
    }

    @Test fun textAsksForTheBestMatchAndIsTrimmedAndCapped() {
        val query = marketSearchArgs(url, MarketQuery("  review  "), 0, 50).query()
        assertEquals("review", query.getValue("text").jsonPrimitive.content)
        assertEquals("relevance", query.getValue("sort").jsonObject.getValue("kind").jsonPrimitive.content)
        // Blank text is no text: it must not turn the order into relevance.
        val blank = marketSearchArgs(url, MarketQuery("   "), 0, 50).query()
        assertFalse(blank.containsKey("text"))
        assertEquals("index", blank.getValue("sort").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(MarketLimits.SEARCH_TEXT, MarketQuery().withText("x".repeat(5000)).text.length)
        assertEquals(MarketLimits.SEARCH_TEXT, marketSearchArgs(url, MarketQuery("x".repeat(5000)), 0, 50).query().getValue("text").jsonPrimitive.content.length)
    }

    @Test fun filtersAndThePinnedDocumentAreSentOnlyWhenSet() {
        val query = marketSearchArgs(url, MarketQuery("", "productivity", listOf("official", "experimental")), 50, 50, fetchedAt = 1794979596810, refresh = true).query()
        assertEquals("productivity", query.getValue("category").jsonPrimitive.content)
        assertEquals(listOf("official", "experimental"), query.getValue("labels").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1794979596810, query.getValue("fetchedAt").jsonPrimitive.long)
        assertTrue(query.getValue("refresh").jsonPrimitive.boolean)
        assertEquals(50, query.getValue("offset").jsonPrimitive.int)
        val plain = marketSearchArgs(url, MarketQuery(), 0, 50).query()
        assertFalse(plain.containsKey("fetchedAt")); assertFalse(plain.containsKey("refresh")); assertFalse(plain.containsKey("category")); assertFalse(plain.containsKey("labels"))
    }

    @Test fun thePageIsHeldToWhatTheHostAccepts() {
        // The host refuses a limit outside 1..100 and a negative offset (captured: plugin-market/invalid-query).
        assertEquals(100, marketSearchArgs(url, MarketQuery(), 0, 500).query().getValue("limit").jsonPrimitive.int)
        assertEquals(1, marketSearchArgs(url, MarketQuery(), 0, 0).query().getValue("limit").jsonPrimitive.int)
        assertEquals(0, marketSearchArgs(url, MarketQuery(), -5, 10).query().getValue("offset").jsonPrimitive.int)
    }

    @Test fun categoriesAreSingleChoiceAndLabelsAddUp() {
        val query = MarketQuery().toggleCategory("tools").toggleLabel("official").toggleLabel("experimental")
        assertEquals("tools", query.category)
        assertEquals(listOf("official", "experimental"), query.labels)
        assertEquals("productivity", query.toggleCategory("productivity").category)
        assertNull(query.toggleCategory("tools").category)
        assertEquals(listOf("experimental"), query.toggleLabel("official").labels)
        assertTrue(MarketQuery().isUnfiltered)
        assertFalse(MarketQuery("a").isUnfiltered)
        assertFalse(MarketQuery(category = "x").isUnfiltered)
        assertTrue(MarketQuery("  ").isUnfiltered)
        assertTrue(query.withoutFilters().isUnfiltered)
        assertEquals("kept", query.withText("kept").withoutFilters().text)
    }

    @Test fun aFilterTheMarketNoLongerDeclaresLeavesTheQuery() {
        val header = MarketFixtures.page("search-all").header
        val same = MarketQuery("x", "productivity", listOf("official"))
        assertSame(same, same.declaredIn(header))
        val reduced = MarketQuery("x", "gone", listOf("official", "also-gone")).declaredIn(header)
        assertNull(reduced.category)
        assertEquals(listOf("official"), reduced.labels)
        assertEquals("x", reduced.text)
    }

    @Test fun everyHostFailureHasItsOwnMeaning() {
        assertEquals(MarketFailure.Fetch("HTTP 404"), MarketFixtures.error("search-fetch-failed").toMarketFailure())
        val invalid = MarketFixtures.error("search-invalid-index").toMarketFailure() as MarketFailure.InvalidIndex
        assertEquals("$.plugins[0].name", invalid.path)
        assertEquals("must be a valid npm package name", invalid.reason)
        assertEquals(MarketFailure.UnknownIndex, MarketFixtures.error("search-unknown-index").toMarketFailure())
        val range = MarketFixtures.error("search-bad-limit").toMarketFailure() as MarketFailure.Other
        assertEquals("plugin-market/invalid-query", range.code)
        assertEquals(MarketFailure.Unavailable, RpcError("capability-unavailable", "gone").toMarketFailure())
        assertEquals(MarketFailure.SettingsUnavailable, RpcError("plugin-market/settings-unavailable", "x").toMarketFailure())
        assertEquals(MarketFailure.Malformed, RpcError("plugin-market/malformed", "x").toMarketFailure())
        val url = RpcError("plugin-market/invalid-index-url", "dup", JsonObject(mapOf("url" to JsonPrimitive("u"), "reason" to JsonPrimitive("duplicate")))).toMarketFailure()
        assertEquals(MarketFailure.InvalidUrl("duplicate"), url)
    }

    @Test fun aFailureTextFromAnIndexOwnerIsCleanedAndCapped() {
        val hostile = "Could not fetch‮ gpj: " + "x".repeat(5000)
        val failure = RpcError("plugin-market/fetch-failed", "m", JsonObject(mapOf("reason" to JsonPrimitive(hostile)))).toMarketFailure() as MarketFailure.Fetch
        assertTrue(failure.reason!!.length <= MarketLimits.REASON)
        assertFalse(failure.reason!!.contains('‮'))
    }
}
