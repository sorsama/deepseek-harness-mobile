package com.labteto.dshmobile.core.rates

import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.PlainHttpRequest
import com.labteto.dshmobile.core.wire.PlainHttpResponse
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** A carrier that answers by URL from a script and remembers every request it was given. */
private class FakeHttp : PlainHttp {
    val requests = mutableListOf<PlainHttpRequest>()
    var primary: () -> PlainHttpResponse = { throw IOException("offline") }
    var fallback: () -> PlainHttpResponse = { throw IOException("offline") }

    override suspend fun execute(request: PlainHttpRequest): PlainHttpResponse {
        requests += request
        return when (request.url) {
            ExchangeRates.EXCHANGE_RATE_API_URL -> primary()
            ExchangeRates.CURRENCY_API_URL -> fallback()
            else -> error("unexpected url ${request.url}")
        }
    }

    fun urls() = requests.map { it.url }
}

private class MemoryStore(var text: String? = null) : RateStore {
    var saves = 0
    var failSave = false
    override suspend fun load(): String? = text
    override suspend fun save(text: String) {
        if (failSave) throw IOException("disk full")
        this.text = text
        saves++
    }
}

class ExchangeRatesTest {
    private var now = 1_795_392_152_000L + 3 * HOUR
    private val http = FakeHttp()
    private val store = MemoryStore()

    private fun engine(store: RateStore = this.store) = ExchangeRates(http, store) { now }

    /** The feed's own day: computed a little before `now`, so a fresh fetch is a day-old table at worst. */
    private fun erBody(published: Long = now - 3 * HOUR, eur: Double = 0.86) =
        """{"result":"success","base_code":"USD","time_last_update_unix":${published / 1000},"rates":{"USD":1,"EUR":$eur,"CNY":7.2,"JPY":150}}"""

    private fun fawazBody(date: String = LocalDate.of(2026, 11, 22).toString(), eur: Double = 0.85) =
        """{"date":"$date","usd":{"usd":1,"eur":$eur,"cny":7.1,"jpy":149,"btc":0.00001,"1inch":17.0}}"""

    private fun ok(body: String) = PlainHttpResponse(200, body)

    @Test
    fun `the first call asks the primary feed and keeps what it gets`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val table = assertNotNull(engine().table())
        assertEquals(RateSource.EXCHANGE_RATE_API, table.source)
        assertEquals(now, table.fetchedAt)
        assertEquals(0.86, table.rates.getValue("EUR"))
        assertEquals(listOf(ExchangeRates.EXCHANGE_RATE_API_URL), http.urls())
        assertEquals(1, store.saves)
    }

    @Test
    fun `a table under 24 hours old is used with no request and one at 24 hours is fetched again`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val rates = engine()
        rates.table()
        now += DAY - 1
        assertEquals(0.86, rates.table()!!.rates.getValue("EUR"))
        assertEquals(1, http.requests.size)
        now += 1
        http.primary = { ok(erBody(published = now, eur = 0.9)) }
        assertEquals(0.9, rates.table()!!.rates.getValue("EUR"))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `the cache outlives the process`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        engine().table()
        // A new engine on the same store, a day minus a minute later: no request at all.
        now += DAY - 60_000
        val table = assertNotNull(engine().table())
        assertEquals(0.86, table.rates.getValue("EUR"))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `when the primary fails the fallback feed is used`() = runBlocking<Unit> {
        http.primary = { PlainHttpResponse(503, "busy") }
        http.fallback = { ok(fawazBody()) }
        val table = assertNotNull(engine().table())
        assertEquals(RateSource.CURRENCY_API, table.source)
        assertEquals(0.85, table.rates.getValue("EUR"))
        assertEquals(listOf(ExchangeRates.EXCHANGE_RATE_API_URL, ExchangeRates.CURRENCY_API_URL), http.urls())
    }

    @Test
    fun `an unreadable, failed or throwing primary all lead to the fallback`() = runBlocking<Unit> {
        val broken = listOf<() -> PlainHttpResponse>(
            { ok("<html>sign in to the Wi-Fi</html>") },
            { ok("""{"result":"error","error-type":"quota-reached"}""") },
            { ok("""{"result":"success","base_code":"USD","rates":{"EUR":0,"CNY":-1}}""") },
            { PlainHttpResponse(302, "") },
            { PlainHttpResponse(404, erBody()) },
            { throw IOException("connection reset") },
        )
        for (answer in broken) {
            val fresh = FakeHttp().also { it.primary = answer; it.fallback = { ok(fawazBody()) } }
            val table = assertNotNull(ExchangeRates(fresh, MemoryStore()) { now }.table())
            assertEquals(RateSource.CURRENCY_API, table.source)
        }
    }

    @Test
    fun `when both feeds fail the old table is still used`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val rates = engine()
        rates.table()
        now += 3 * DAY
        http.primary = { throw IOException("offline") }
        val table = assertNotNull(rates.table())
        assertEquals(0.86, table.rates.getValue("EUR"))
    }

    @Test
    fun `after both feeds fail nothing is asked again for half an hour`() = runBlocking<Unit> {
        val rates = engine()
        assertNull(rates.table())
        assertEquals(2, http.requests.size)
        now += ExchangeRates.RETRY_AFTER_FAILURE_MS - 1
        assertNull(rates.table())
        assertNull(rates.table())
        assertEquals(2, http.requests.size)
        now += 1
        http.primary = { ok(erBody()) }
        assertNotNull(rates.table())
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `with no cache and no network there is no table`() = runBlocking<Unit> {
        assertNull(engine().table())
    }

    @Test
    fun `a cache older than seven days is not used even when no feed answers`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val rates = engine()
        assertNotNull(rates.table())
        http.primary = { throw IOException("offline") }
        // Exactly seven days after the rates' own time they may still be shown; a millisecond later they may not.
        now = (now - 3 * HOUR) + RateTable.MAX_AGE_MS
        assertNotNull(rates.table())
        now += 1
        assertNull(rates.table())
    }

    @Test
    fun `a feed that keeps serving an old day is not shown and the next feed is asked`() = runBlocking<Unit> {
        http.primary = { ok(erBody(published = now - 8 * DAY)) }
        assertNull(engine().table())
        assertEquals(listOf(ExchangeRates.EXCHANGE_RATE_API_URL, ExchangeRates.CURRENCY_API_URL), http.urls())
        assertEquals(0, store.saves)
    }

    @Test
    fun `a fresh fallback beats a primary stuck on an old day`() = runBlocking<Unit> {
        http.primary = { ok(erBody(published = now - 8 * DAY)) }
        http.fallback = { ok(fawazBody()) }
        val table = assertNotNull(engine().table())
        assertEquals(RateSource.CURRENCY_API, table.source)
    }

    @Test
    fun `with network not allowed the cache is returned and nothing is requested`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val rates = engine()
        assertNull(rates.table(allowNetwork = false))
        assertEquals(0, http.requests.size)
        rates.table()
        now += 2 * DAY
        assertNotNull(rates.table(allowNetwork = false))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a corrupt or insane cache is ignored and replaced`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        for (junk in listOf("{", "not json", "[]", """{"source":"EXCHANGE_RATE_API","fetchedAt":1,"rates":{"EUR":0}}""", """{"source":"EXCHANGE_RATE_API","fetchedAt":1,"rates":{}}""")) {
            val before = http.requests.size
            val table = assertNotNull(ExchangeRates(http, MemoryStore(junk)) { now }.table(), junk)
            assertEquals(0.86, table.rates.getValue("EUR"))
            assertEquals(before + 1, http.requests.size)
        }
    }

    @Test
    fun `a clock that jumped back does not trust a table fetched in the future`() = runBlocking<Unit> {
        http.primary = { ok(erBody()) }
        val rates = engine()
        rates.table()
        now -= 5 * DAY
        http.primary = { ok(erBody(published = now - HOUR, eur = 0.9)) }
        assertEquals(0.9, rates.table()!!.rates.getValue("EUR"))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `a failed write does not lose the table for this run`() = runBlocking<Unit> {
        store.failSave = true
        http.primary = { ok(erBody()) }
        val rates = engine()
        assertNotNull(rates.table())
        assertNotNull(rates.table())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `requests are bare GETs with no headers, no body and nothing about the user`() = runBlocking<Unit> {
        http.primary = { PlainHttpResponse(500, "") }
        http.fallback = { ok(fawazBody()) }
        engine().table()
        assertEquals(2, http.requests.size)
        for (request in http.requests) {
            assertEquals("GET", request.method)
            assertTrue(request.headers.isEmpty(), "no headers beyond the carrier's own defaults")
            assertNull(request.body)
            assertNull(request.bodyContentType)
            assertTrue(request.readBody)
            assertTrue(request.connectTimeoutMs in 1..30_000 && request.readTimeoutMs in 1..30_000)
            assertTrue(request.url.startsWith("https://"))
            assertTrue('?' !in request.url, "no query string")
        }
        assertEquals("https://open.er-api.com/v6/latest/USD", http.requests[0].url)
        assertEquals("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json", http.requests[1].url)
    }

    @Test
    fun `an answer far bigger than either feed is refused`() = runBlocking<Unit> {
        http.primary = { ok(erBody() + " ".repeat(1_100_000)) }
        http.fallback = { ok(fawazBody()) }
        assertEquals(RateSource.CURRENCY_API, engine().table()!!.source)
    }

    @Test
    fun `cancelling the caller is not a failed fetch`() = runBlocking<Unit> {
        http.primary = { throw CancellationException("gone") }
        val rates = engine()
        assertFailsWith<CancellationException> { rates.table() }
        // It did not start the half-hour back-off, and did not try the second feed.
        assertEquals(1, http.requests.size)
        http.primary = { ok(erBody()) }
        assertNotNull(rates.table())
    }
}
