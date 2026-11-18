package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketLoaderTest {
    private fun entry(n: Int) = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry("@example/dsh-n$n"))))!!.entries.single()
    private val header = MarketFixtures.page("search-all").header

    /** A host with [total] plugins that answers like `pluginMarket/search`: the page asked for, from one document at a time. */
    private class FakeHost(val total: Int, val make: (Int) -> MarketEntry, var fetchedAt: Long = 1) {
        val calls = mutableListOf<List<Any?>>()
        var onCall: (Int) -> Unit = {}
        val fetch: MarketPageFetch = { offset, limit, pinned, refresh ->
            calls += listOf(offset, limit, pinned, refresh)
            val end = minOf(offset + limit, total)
            val answer = MarketPage(
                header = MarketFixtures.page("search-all").header, fetchedAt = fetchedAt,
                entries = (offset until end).map(make), total = total, nextOffset = end.takeIf { it < total },
            )
            // A change on the host lands after this answer, so it shows in the next one.
            onCall(calls.size)
            RpcResult.Ok(answer)
        }
    }

    private fun load(host: FakeHost, count: Int, refresh: Boolean = false): MarketLoad = kotlinx.coroutines.runBlocking { loadMarketEntries(count, refresh, host.fetch) }

    @Test fun aSmallMarketIsOneRequestAndTheFirstOneCarriesRefresh() {
        val host = FakeHost(3, ::entry)
        val loaded = (load(host, 50, refresh = true) as MarketLoad.Loaded).page
        assertEquals(3, loaded.entries.size)
        assertNull(loaded.nextOffset)
        assertEquals(1, host.calls.size)
        assertEquals(listOf<Any?>(0, 50, null, true), host.calls.single())
    }

    @Test fun laterPagesPinTheFirstPagesDocumentAndRefreshIsAskedOnce() {
        val host = FakeHost(230, ::entry)
        val loaded = (load(host, 230, refresh = true) as MarketLoad.Loaded).page
        // 230 asked for, 300 is the cap, so the whole market loads: 100 + 100 + 30.
        assertEquals(230, loaded.entries.size)
        assertEquals(listOf<Any?>(0, 100, null, true), host.calls[0])
        assertEquals(listOf<Any?>(100, 100, 1L, false), host.calls[1])
        assertEquals(listOf<Any?>(200, 30, 1L, false), host.calls[2])
        assertEquals(3, host.calls.size)
    }

    @Test fun aListingNeverHoldsMoreThanTheCap() {
        val host = FakeHost(5_000, ::entry)
        val loaded = (load(host, 5_000) as MarketLoad.Loaded).page
        assertEquals(MarketLimits.MAX_LOADED, loaded.entries.size)
        assertEquals(5_000, loaded.total)
    }

    @Test fun aDocumentReplacedWhileReadingStartsOverOnce() {
        val host = FakeHost(150, ::entry)
        // The host's document is replaced after the first page.
        host.onCall = { call -> if (call == 1) host.fetchedAt = 2 }
        val loaded = (load(host, 150) as MarketLoad.Loaded).page
        assertEquals(150, loaded.entries.size)
        assertEquals(2L, loaded.fetchedAt)
        assertEquals((0 until 150).map { "@example/dsh-n$it" }, loaded.entries.map { it.name })
    }

    @Test fun aDocumentReplacedTwiceIsReportedInsteadOfMixed() {
        val host = FakeHost(150, ::entry)
        host.onCall = { call -> if (call == 1 || call == 3) host.fetchedAt += 1 }
        assertEquals(MarketLoad.Changed, load(host, 150))
    }

    @Test fun aFailureAnywhereIsReportedWithTheHostsReason() {
        var calls = 0
        val fetch: MarketPageFetch = { _, _, _, _ ->
            if (++calls == 1) RpcResult.Ok(MarketPage(header, 1, (0 until 100).map(::entry), 150, 100))
            else RpcResult.Err(RpcError("plugin-market/fetch-failed", "m", kotlinx.serialization.json.JsonObject(mapOf("reason" to kotlinx.serialization.json.JsonPrimitive("HTTP 503")))))
        }
        val result = kotlinx.coroutines.runBlocking { loadMarketEntries(150, false, fetch) }
        assertEquals(MarketLoad.Failed(MarketFailure.Fetch("HTTP 503")), result)
    }

    @Test fun aHostThatRepeatsItselfIsStoppedAndEntriesAreShownOnce() {
        // It always answers the same page and always says there is more.
        val fetch: MarketPageFetch = { _, _, _, _ -> RpcResult.Ok(MarketPage(header, 1, (0 until 10).map(::entry), 10_000, 10)) }
        val loaded = (kotlinx.coroutines.runBlocking { loadMarketEntries(300, false, fetch) } as MarketLoad.Loaded).page
        assertEquals(10, loaded.entries.size)
        assertNull(loaded.nextOffset)
    }

    @Test fun aHostThatKeepsSendingEmptyPagesIsStoppedByTheRequestCap() {
        var calls = 0
        val fetch: MarketPageFetch = { offset, _, _, _ -> calls++; RpcResult.Ok(MarketPage(header, 1, emptyList(), 10_000, offset + 50)) }
        val result = kotlinx.coroutines.runBlocking { loadMarketEntries(300, false, fetch) }
        assertTrue(calls <= 15)
        assertTrue(result is MarketLoad.Loaded)
        assertNull((result as MarketLoad.Loaded).page.nextOffset)
    }

    @Test fun showMoreAppendsTheNextPageOfTheSameDocument() = runTest {
        val host = FakeHost(120, ::entry)
        val first = (loadMarketEntries(50, false, host.fetch) as MarketLoad.Loaded).page
        assertEquals(50, first.nextOffset)
        val more = (loadMoreMarketEntries(first, host.fetch) as MarketLoad.Loaded).page
        assertEquals(100, more.entries.size)
        assertEquals(listOf<Any?>(50, 50, 1L, false), host.calls.last())
        val last = (loadMoreMarketEntries(more, host.fetch) as MarketLoad.Loaded).page
        assertEquals(120, last.entries.size)
        assertNull(last.nextOffset)
        // Nothing left: no request.
        val calls = host.calls.size
        assertEquals(last, (loadMoreMarketEntries(last, host.fetch) as MarketLoad.Loaded).page)
        assertEquals(calls, host.calls.size)
    }

    @Test fun showMoreAfterTheDocumentChangedReloadsFromTheStart() = runTest {
        val host = FakeHost(120, ::entry)
        val first = (loadMarketEntries(50, false, host.fetch) as MarketLoad.Loaded).page
        host.fetchedAt = 9
        val more = (loadMoreMarketEntries(first, host.fetch) as MarketLoad.Loaded).page
        assertEquals(9L, more.fetchedAt)
        assertEquals(100, more.entries.size)
        assertEquals((0 until 100).map { "@example/dsh-n$it" }, more.entries.map { it.name })
    }

    @Test fun showMoreStopsAtTheCap() = runTest {
        val host = FakeHost(1_000, ::entry)
        var page = (loadMarketEntries(MarketLimits.MAX_LOADED - 50, false, host.fetch) as MarketLoad.Loaded).page
        page = (loadMoreMarketEntries(page, host.fetch) as MarketLoad.Loaded).page
        assertEquals(MarketLimits.MAX_LOADED, page.entries.size)
        assertNull(page.nextOffset)
    }

    @Test fun showMoreFailureLeavesTheCallerWithTheFailureAndItsOwnPage() = runTest {
        val host = FakeHost(120, ::entry)
        val first = (loadMarketEntries(50, false, host.fetch) as MarketLoad.Loaded).page
        val result = loadMoreMarketEntries(first) { _, _, _, _ -> RpcResult.Err(RpcError("capability-unavailable", "x")) }
        assertEquals(MarketLoad.Failed(MarketFailure.Unavailable), result)
    }
}
