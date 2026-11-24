package com.labteto.dshmobile.core.rates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Which public feed a [RateTable] came from. The Account page names it, because its terms ask for that. */
@Serializable
enum class RateSource(val url: String) {
    /** open.er-api.com, the free tier of ExchangeRate-API. */
    EXCHANGE_RATE_API("https://www.exchangerate-api.com"),

    /** The `@fawazahmed0/currency-api` files on jsDelivr, used only when the first feed fails. */
    CURRENCY_API("https://github.com/fawazahmed0/exchange-api"),
}

/**
 * One day's exchange rates, per 1 US dollar, as the app keeps them between launches.
 *
 * It holds no account data, no user identifier and nothing the request carried: only public
 * rates and two timestamps. [fetchedAt] is when this phone got the table (the 24 hour rule runs
 * on it), [publishedAt] when the feed says it computed it (the 7 day rule runs on it, so a feed
 * that keeps serving an old day cannot pass for fresh). Keys are upper-case ISO codes; every value
 * is finite and above zero, which [RateParsing] enforces and [RateTable.isSane] re-checks for a
 * table read back from disk.
 */
@Serializable
data class RateTable(
    val source: RateSource,
    val fetchedAt: Long,
    val publishedAt: Long? = null,
    val rates: Map<String, Double>,
) {
    /**
     * When the rates are from, never later than [fetchedAt]: a feed clock ahead of the phone's
     * would otherwise make a table look younger than the moment it arrived.
     */
    val asOf: Long get() = publishedAt?.let { minOf(it, fetchedAt) } ?: fetchedAt

    /** How old the rates are at [now], zero when the phone's clock is behind [asOf]. */
    fun ageMs(now: Long): Long = (now - asOf).coerceAtLeast(0)

    /** Whether [now] is still inside the window in which these rates may be shown. */
    fun isUsable(now: Long): Boolean = isSane() && ageMs(now) <= MAX_AGE_MS

    fun isSane(): Boolean = rates.isNotEmpty() && rates.all { (code, rate) -> BalanceCurrency.isCode(code) && rate.isUsableRate() }

    /** [amount] of [from] in [to], through the dollar; null when either currency has no rate. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val source = rates[from] ?: return null
        val target = rates[to] ?: return null
        val converted = amount * target / source
        return converted.takeIf { it.isFinite() }
    }

    companion object {
        /** A table is fetched again once it is this old. */
        const val REFRESH_MS: Long = 24L * 60 * 60 * 1000

        /** Past this age no estimate is shown at all, however the fetch went. */
        const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

// A rate outside this range is not a currency but a bad feed. The widest real ones are a few
// hundred thousand (the Iranian rial) and the narrowest are well above 1e-6; the bounds only keep
// a product or a quotient from overflowing.
private const val MIN_RATE = 1e-12
private const val MAX_RATE = 1e12
internal fun Double.isUsableRate(): Boolean = isFinite() && this >= MIN_RATE && this <= MAX_RATE

/** What a feed said, already checked: the rates it gave and when it says it computed them. */
class ParsedRates(val publishedAt: Long?, val rates: Map<String, Double>)

/**
 * Reads the two feeds. Each returns null for anything that is not a usable table (not JSON, not
 * the shape, a failure marker), and drops single entries that are not a finite number above zero
 * rather than refusing the whole table over one bad currency.
 */
object RateParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * `GET https://open.er-api.com/v6/latest/USD`:
     * `{"result":"success","base_code":"USD","time_last_update_unix":1795392152,"rates":{"EUR":0.86,…}}`.
     */
    fun exchangeRateApi(body: String): ParsedRates? {
        val root = parseObject(body) ?: return null
        if (root.string("result") != "success") return null
        // A table against another base would make every cross rate wrong by that base's own rate.
        root.string("base_code")?.let { if (it != "USD") return null }
        val rates = collect(root["rates"] as? JsonObject ?: return null) ?: return null
        val published = root["time_last_update_unix"]?.let { (it as? JsonPrimitive)?.longOrNull }
            ?.takeIf { it > 0 }
            ?.let { it * 1000 }
        return ParsedRates(published, rates)
    }

    /**
     * `GET https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json`:
     * `{"date":"2026-11-21","usd":{"eur":0.86,…}}`. The file also lists crypto-currencies, so only
     * three-letter keys are read, and only the ones a rate table can use.
     */
    fun currencyApi(body: String): ParsedRates? {
        val root = parseObject(body) ?: return null
        val rates = collect(root["usd"] as? JsonObject ?: return null) ?: return null
        val published = root.string("date")?.let(::utcMidnight)
        return ParsedRates(published, rates)
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The valid entries, upper-cased, with the dollar pinned to exactly 1; null when none other survive. */
    private fun collect(raw: JsonObject): Map<String, Double>? {
        val rates = LinkedHashMap<String, Double>()
        for ((key, value) in raw) {
            val code = key.uppercase()
            if (!BalanceCurrency.isCode(code) || code == "USD") continue
            val rate = numberOrNull(value) ?: continue
            if (rate.isUsableRate()) rates[code] = rate
        }
        if (rates.isEmpty()) return null
        rates["USD"] = 1.0
        return rates
    }

    // A JSON number only: a string "0.86" is not what either feed sends, and accepting it would
    // let a proxy's error page with a quoted value through.
    private fun numberOrNull(element: JsonElement): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull
    }

    /** `2026-11-21` as milliseconds since the epoch at 00:00 UTC; null for anything else. */
    internal fun utcMidnight(date: String): Long? {
        val parts = date.split('-')
        if (parts.size != 3) return null
        val year = parts[0].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val month = parts[1].takeIf { it.length == 2 }?.toIntOrNull() ?: return null
        val day = parts[2].takeIf { it.length == 2 }?.toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        // Days since 1970-01-01 (Howard Hinnant's civil-calendar algorithm), so no date library is needed.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146_097 + doe - 719_468
        return days * 86_400_000L
    }
}
