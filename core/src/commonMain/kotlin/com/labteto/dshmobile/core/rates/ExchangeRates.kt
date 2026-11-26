package com.labteto.dshmobile.core.rates

import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.PlainHttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Where the last good [RateTable] is kept between launches: one string, written whole.
 * The platform supplies it (a file on Android); the format is [ExchangeRates]'s own.
 */
interface RateStore {
    suspend fun load(): String?
    suspend fun save(text: String)
}

/**
 * The exchange-rate table behind the approximate balance, with the rules for when it is fetched
 * and when it may be shown.
 *
 * - A table fetched less than 24 hours ago is used as it is: no request.
 * - Otherwise the first feed is asked, then the second if the first gives nothing usable. What
 *   comes back is checked ([RateParsing]) and, if it is good, replaces the cache.
 * - If both fail the old table stays, and nothing is tried again for [RETRY_AFTER_FAILURE_MS], so
 *   a phone with no network does not ask on every refresh of the card.
 * - A table whose rates are more than 7 days old is not returned ([RateTable.isUsable]), and a feed
 *   that answers with such a table counts as having failed, so a feed stuck on an old day does not
 *   hide a fresh one behind it.
 *
 * Requests are plain GETs with no headers of their own, no cookies and no body. Nothing about the
 * account, the harness or the user is in them, and nothing of the sort is stored beside the rates.
 * The caller decides whether to ask at all: with the setting off, nothing here is called.
 *
 * [http] is the platform's carrier (OkHttp on Android, Ktor on iOS) and [clock] is epoch
 * milliseconds; both are injected so the rules can be tested without a network or a wait.
 */
class ExchangeRates(
    private val http: PlainHttp,
    private val store: RateStore,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private var loaded = false
    private var cached: RateTable? = null
    private var lastFailureAt: Long? = null

    /**
     * The table to convert with, refreshed first if it is due and [allowNetwork] is set; null when
     * there is none that may be shown.
     */
    suspend fun table(allowNetwork: Boolean = true): RateTable? = lock.withLock {
        val now = clock()
        var table = cachedTable()
        if (allowNetwork && isDue(table, now) && mayRetry(now)) {
            val fresh = fetch(now)
            if (fresh != null) {
                table = fresh
                cached = fresh
                lastFailureAt = null
                // A full disk or a failed write loses the cache for the next launch, nothing more.
                try { store.save(Json.encodeToString(RateTable.serializer(), fresh)) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            } else {
                lastFailureAt = now
            }
        }
        table?.takeIf { it.isUsable(now) }
    }

    private suspend fun cachedTable(): RateTable? {
        if (!loaded) {
            cached = try {
                store.load()?.let { Json.decodeFromString(RateTable.serializer(), it) }?.takeIf { it.isSane() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            loaded = true
        }
        return cached
    }

    // A fetch time in the future means the phone's clock moved back; the table is treated as due
    // rather than trusted for however long the jump is.
    private fun isDue(table: RateTable?, now: Long): Boolean =
        table == null || table.fetchedAt > now || now - table.fetchedAt >= RateTable.REFRESH_MS

    private fun mayRetry(now: Long): Boolean {
        val failed = lastFailureAt ?: return true
        return now < failed || now - failed >= RETRY_AFTER_FAILURE_MS
    }

    // A feed that answers with rates already past the 7 day limit has not helped: the next one is
    // asked, and if that is no better the old table is left alone.
    private suspend fun fetch(now: Long): RateTable? {
        get(EXCHANGE_RATE_API_URL)?.let(RateParsing::exchangeRateApi)
            ?.let { RateTable(RateSource.EXCHANGE_RATE_API, now, it.publishedAt, it.rates) }
            ?.takeIf { it.isUsable(now) }?.let { return it }
        get(CURRENCY_API_URL)?.let(RateParsing::currencyApi)
            ?.let { RateTable(RateSource.CURRENCY_API, now, it.publishedAt, it.rates) }
            ?.takeIf { it.isUsable(now) }?.let { return it }
        return null
    }

    /** The body of a 200 answer, or null for any other outcome. A cancelled caller is not an outcome. */
    private suspend fun get(url: String): String? = try {
        val answer = http.execute(
            PlainHttpRequest(method = "GET", url = url, connectTimeoutMs = CONNECT_TIMEOUT_MS, readTimeoutMs = READ_TIMEOUT_MS),
        )
        answer.body.takeIf { answer.status == 200 && it.length <= MAX_BODY_CHARS }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val EXCHANGE_RATE_API_URL = "https://open.er-api.com/v6/latest/USD"
        const val CURRENCY_API_URL = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json"

        /** After both feeds fail, nothing is asked again for this long. */
        const val RETRY_AFTER_FAILURE_MS: Long = 30L * 60 * 1000

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 15_000L

        // Either feed is under 10 kB; this only stops a runaway answer from being parsed.
        private const val MAX_BODY_CHARS = 1_000_000
    }
}
