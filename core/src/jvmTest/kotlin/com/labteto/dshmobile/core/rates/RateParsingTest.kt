package com.labteto.dshmobile.core.rates

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateParsingTest {

    private fun er(rates: String, result: String = "success", base: String = "USD", published: String = "1795392152") =
        """{"result":"$result","provider":"https://www.exchangerate-api.com","time_last_update_unix":$published,"base_code":"$base","rates":{$rates}}"""

    @Test
    fun `exchange rate api table is read with its publish time in milliseconds`() {
        val parsed = assertNotNull(RateParsing.exchangeRateApi(er(""""USD":1,"EUR":0.86,"CNY":7.1,"JPY":155.25""")))
        assertEquals(1_795_392_152_000L, parsed.publishedAt)
        assertEquals(mapOf("EUR" to 0.86, "CNY" to 7.1, "JPY" to 155.25, "USD" to 1.0), parsed.rates)
    }

    @Test
    fun `the dollar is pinned to exactly one whatever the feed says`() {
        val parsed = assertNotNull(RateParsing.exchangeRateApi(er(""""USD":3.5,"EUR":0.86""")))
        assertEquals(1.0, parsed.rates.getValue("USD"))
        val missing = assertNotNull(RateParsing.exchangeRateApi(er(""""EUR":0.86""")))
        assertEquals(1.0, missing.rates.getValue("USD"))
    }

    @Test
    fun `exchange rate api answers that are not a success are refused`() {
        assertNull(RateParsing.exchangeRateApi(er(""""EUR":0.86""", result = "error")))
        assertNull(RateParsing.exchangeRateApi("""{"result":"error","error-type":"quota-reached"}"""))
        assertNull(RateParsing.exchangeRateApi("""{"rates":{"EUR":0.86}}"""))
        // Another base would make every cross rate wrong by that base's own rate.
        assertNull(RateParsing.exchangeRateApi(er(""""EUR":0.86""", base = "EUR")))
    }

    @Test
    fun `things that are not a rate table are refused`() {
        assertNull(RateParsing.exchangeRateApi(""))
        assertNull(RateParsing.exchangeRateApi("<html>captive portal</html>"))
        assertNull(RateParsing.exchangeRateApi("[1,2,3]"))
        assertNull(RateParsing.exchangeRateApi("""{"result":"success","rates":[]}"""))
        assertNull(RateParsing.exchangeRateApi(er("")))
        assertNull(RateParsing.currencyApi("not json"))
        assertNull(RateParsing.currencyApi("""{"date":"2026-11-21"}"""))
        assertNull(RateParsing.currencyApi("""{"date":"2026-11-21","usd":{}}"""))
    }

    @Test
    fun `a bad rate is dropped and the rest of the table is kept`() {
        // Seven bad values under well-formed codes, then four bad codes, around three good rates.
        val rates = listOf(
            "\"EUR\":0.86", "\"ZRO\":0", "\"NGT\":-3", "\"STR\":\"0.5\"", "\"NIL\":null", "\"INF\":1e999",
            "\"TNY\":1e-30", "\"WDE\":5e12", "\"AB\":3", "\"ABCD\":2", "\"KR1\":4", "\"jpy\":155.0", "\"CNY\":7.1",
        ).joinToString(",")
        val parsed = assertNotNull(RateParsing.exchangeRateApi(er(rates)))
        assertEquals(setOf("EUR", "JPY", "CNY", "USD"), parsed.rates.keys)
        assertTrue(parsed.rates.values.all { it.isFinite() && it > 0 })
    }

    @Test
    fun `a table whose every rate is bad is refused`() {
        assertNull(RateParsing.exchangeRateApi(er(""""EUR":0,"JPY":-1,"CNY":"7.1"""")))
    }

    @Test
    fun `currency api table is read with a date at midnight UTC and upper-cased codes`() {
        val body = """{"date":"2026-11-21","usd":{"1inch":17.29,"aave":0.0108,"eur":0.86,"cny":7.1,"jpy":155.25,"usd":1,"try":42.5,"btc":1.1e-5}}"""
        val parsed = assertNotNull(RateParsing.currencyApi(body))
        assertEquals(LocalDate.of(2026, 11, 21).toEpochDay() * 86_400_000L, parsed.publishedAt)
        // Tickers longer than three letters are not codes and are dropped; a three-letter one such
        // as BTC is kept, and is simply never looked up because it is not on offer.
        assertEquals(setOf("EUR", "CNY", "JPY", "USD", "TRY", "BTC"), parsed.rates.keys)
        assertEquals(42.5, parsed.rates.getValue("TRY"))
    }

    @Test
    fun `a missing or malformed date keeps the rates and leaves the publish time unknown`() {
        assertNull(assertNotNull(RateParsing.currencyApi("""{"usd":{"eur":0.86}}""")).publishedAt)
        assertNull(assertNotNull(RateParsing.currencyApi("""{"date":"yesterday","usd":{"eur":0.86}}""")).publishedAt)
        assertNull(assertNotNull(RateParsing.currencyApi("""{"date":"2026-13-40","usd":{"eur":0.86}}""")).publishedAt)
    }

    @Test
    fun `dates become epoch milliseconds the way java time does`() {
        for (text in listOf("1970-01-01", "1969-12-31", "2000-02-29", "2000-03-01", "2024-02-29", "2026-11-21", "2038-01-19", "2100-03-01")) {
            assertEquals(LocalDate.parse(text).toEpochDay() * 86_400_000L, RateParsing.utcMidnight(text), text)
        }
        assertNull(RateParsing.utcMidnight("2026-1-5"))
        assertNull(RateParsing.utcMidnight("26-11-21"))
        assertNull(RateParsing.utcMidnight("2026/11/21"))
    }

    @Test
    fun `an unusable table survives no round trip through the sanity check`() {
        val good = RateTable(RateSource.CURRENCY_API, 1, null, mapOf("USD" to 1.0, "EUR" to 0.86))
        assertTrue(good.isSane())
        assertFalse(good.copy(rates = emptyMap()).isSane())
        assertFalse(good.copy(rates = mapOf("USD" to 1.0, "eur" to 0.86)).isSane())
        assertFalse(good.copy(rates = mapOf("USD" to 1.0, "EUR" to Double.NaN)).isSane())
        assertFalse(good.copy(rates = mapOf("USD" to 1.0, "EUR" to 0.0)).isSane())
    }
}
