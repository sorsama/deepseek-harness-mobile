package com.labteto.dshmobile.core.rates

import com.labteto.dshmobile.core.usage.UsageLabels
import com.labteto.dshmobile.core.wire.dto.CurrencyWallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalanceEstimateTest {
    private val now = 1_795_392_152_000L + 3_600_000L
    private val rates = mapOf(
        "USD" to 1.0, "CNY" to 7.2, "EUR" to 0.86, "JPY" to 150.0, "KRW" to 1400.0, "IDR" to 16_500.0,
        "TWD" to 31.0, "KWD" to 0.3, "BRL" to 5.4, "INR" to 84.0, "THB" to 35.0,
    )
    private val table = RateTable(RateSource.EXCHANGE_RATE_API, fetchedAt = now - 1000, publishedAt = now - 3_600_000, rates = rates)
    private val labels = UsageLabels()

    private fun usd(balance: String) = listOf(CurrencyWallet("USD", balance))
    private fun yuan(balance: String) = listOf(CurrencyWallet("CNY", balance))
    private fun estimate(
        wallets: List<CurrencyWallet>,
        target: String?,
        table: RateTable? = this.table,
        at: Long = now,
        l: UsageLabels = labels,
    ) = balanceEstimateText(wallets, target, table, at, l)

    @Test
    fun `a dollar balance in yuan is the balance times the rate, cut and not rounded`() {
        // 54.12 x 7.2 = 389.664.
        assertEquals("389.66 CNY", estimate(usd("54.12"), "CNY"))
        // 54.19 x 7.2 = 390.168, which a round would call 390.17.
        assertEquals("390.16 CNY", estimate(usd("54.19"), "CNY"))
        // 0.9999 x 0.86 = 0.859914, which a round would call 0.86.
        assertEquals("0.85 EUR", estimate(usd("0.9999"), "EUR"))
    }

    @Test
    fun `whole amounts drop the zero cents the way the balance does and others keep them`() {
        assertEquals("72 CNY", estimate(usd("10"), "CNY"))
        assertEquals("72 CNY", estimate(usd("10.00"), "CNY"))
        assertEquals("86 EUR", estimate(usd("100"), "EUR"))
        // 13 x 7.2 = 93.6: the trailing zero of 93.60 stays, as 54.10 would on the balance.
        assertEquals("93.60 CNY", estimate(usd("13"), "CNY"))
    }

    @Test
    fun `a yuan balance is converted into another currency through the dollar`() {
        // 10 CNY is 10 / 7.2 = 1.3888 USD.
        assertEquals("1.38 USD", estimate(yuan("10"), "USD"))
        assertEquals("1.19 EUR", estimate(yuan("10"), "EUR"))
        assertEquals("208 JPY", estimate(yuan("10"), "JPY"))
    }

    @Test
    fun `currencies with no minor unit carry no decimals and are cut`() {
        assertEquals("8118 JPY", estimate(usd("54.12"), "JPY"))
        assertEquals("75768 KRW", estimate(usd("54.12"), "KRW"))
        assertEquals("892980 IDR", estimate(usd("54.12"), "IDR"))
        // 54.12 x 31 = 1677.72
        assertEquals("1677 TWD", estimate(usd("54.12"), "TWD"))
        // 0.9999 x 150 = 149.985
        assertEquals("149 JPY", estimate(usd("0.9999"), "JPY"))
    }

    @Test
    fun `other currencies use two decimals and the three-decimal ones keep three`() {
        assertEquals("292.24 BRL", estimate(usd("54.12"), "BRL"))
        assertEquals("4546.08 INR", estimate(usd("54.12"), "INR"))
        assertEquals("16.236 KWD", estimate(usd("54.12"), "KWD"))
        for (code in listOf("JPY", "KRW", "IDR", "TWD", "VND", "CLP")) assertEquals(0, BalanceCurrency.fractionDigits(code), code)
        for (code in listOf("KWD", "BHD", "OMR")) assertEquals(3, BalanceCurrency.fractionDigits(code), code)
        val twoDecimals = listOf("USD", "CNY", "EUR", "GBP", "INR", "HKD", "SGD", "AUD", "CAD", "CHF", "RUB", "BRL", "MXN", "THB", "TRY", "SAR")
        for (code in twoDecimals) assertEquals(2, BalanceCurrency.fractionDigits(code), code)
    }

    @Test
    fun `a conversion that is exact on paper is not cut a unit short by floating point`() {
        // A balance of b cents at 1.07 is a whole number of euro cents whenever 100 divides b x 107,
        // and a double holds neither the balance nor the rate exactly.
        val exact = table.copy(rates = rates + ("EUR" to 1.07))
        for (cents in 1..5000) {
            val balance = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
            val units = cents * 107L / 100
            val fraction = units % 100
            val expected = (units / 100).toString() + if (fraction == 0L) "" else "." + fraction.toString().padStart(2, '0')
            assertEquals("$expected EUR", estimate(usd(balance), "EUR", exact), "$balance USD")
        }
    }

    @Test
    fun `something smaller than the smallest unit says so and zero stays zero`() {
        assertEquals("<0.01 EUR", estimate(usd("0.005"), "EUR"))
        assertEquals("<1 JPY", estimate(usd("0.005"), "JPY"))
        assertEquals("<0.001 KWD", estimate(usd("0.001"), "KWD"))
        assertEquals("0 EUR", estimate(usd("0"), "EUR"))
        assertEquals("0 JPY", estimate(usd("0.0"), "JPY"))
        assertEquals("-<0.01 EUR", estimate(usd("-0.005"), "EUR"))
        assertEquals("-12.90 EUR", estimate(usd("-15"), "EUR"))
    }

    @Test
    fun `nothing is shown when the target is the wallet's own currency`() {
        assertNull(estimate(usd("54.12"), "USD"))
        assertNull(estimate(yuan("10"), "CNY"))
        assertNull(estimate(listOf(CurrencyWallet("USD", "1"), CurrencyWallet("USD", "2")), "USD"))
    }

    @Test
    fun `nothing is shown for off, no table, no rate, or a bad balance`() {
        assertNull(estimate(usd("54.12"), null))
        assertNull(estimate(usd("54.12"), "CNY", table = null))
        assertNull(estimate(emptyList(), "CNY"))
        // The target has no rate in this table.
        assertNull(estimate(usd("54.12"), "SAR"))
        // The wallet's own currency has none.
        assertNull(estimate(yuan("10"), "EUR", table.copy(rates = rates - "CNY")))
        assertNull(estimate(usd("not a number"), "CNY"))
        assertNull(estimate(usd("NaN"), "CNY"))
        assertNull(estimate(usd("9".repeat(40)), "CNY"))
    }

    @Test
    fun `a table older than seven days shows nothing and one just inside shows an estimate`() {
        val old = table.copy(fetchedAt = now - RateTable.MAX_AGE_MS, publishedAt = null)
        assertEquals("389.66 CNY", estimate(usd("54.12"), "CNY", old))
        assertNull(estimate(usd("54.12"), "CNY", old, at = now + 1))
        // The age is the feed's own time: fetched a minute ago, but computed eight days ago.
        val stuck = table.copy(fetchedAt = now - 60_000, publishedAt = now - 8 * 24 * 3_600_000L)
        assertNull(estimate(usd("54.12"), "CNY", stuck))
    }

    @Test
    fun `a publish time in the future does not make the table younger than its fetch`() {
        val skewed = table.copy(fetchedAt = now - 8 * 24 * 3_600_000L, publishedAt = now + 3_600_000)
        assertEquals(skewed.fetchedAt, skewed.asOf)
        assertNull(estimate(usd("54.12"), "CNY", skewed))
    }

    @Test
    fun `two wallets are converted one by one and added in the target`() {
        // 54 USD + 10 CNY into EUR: 54 x 0.86 + 10 x 0.86 / 7.2 = 46.44 + 1.1944 = 47.6344.
        assertEquals("47.63 EUR", estimate(listOf(CurrencyWallet("USD", "54"), CurrencyWallet("CNY", "10")), "EUR"))
        // One already in the target still counts toward the total: 10 USD + 72 CNY = 144 CNY.
        assertEquals("144 CNY", estimate(listOf(CurrencyWallet("USD", "10"), CurrencyWallet("CNY", "72")), "CNY"))
        // If any one has no rate there is no total to show.
        assertNull(estimate(listOf(CurrencyWallet("USD", "54"), CurrencyWallet("CNY", "10")), "EUR", table.copy(rates = rates - "CNY")))
    }

    @Test
    fun `the digits, the decimal mark and the grouping follow the reader`() {
        val arabic = UsageLabels(decimalSeparator = "٫", digits = "٠١٢٣٤٥٦٧٨٩", groupingSeparator = "٬")
        assertEquals("٣٨٩٫٦٦ CNY", estimate(usd("54.12"), "CNY", l = arabic))
        assertEquals("٨٬١١٨ JPY", estimate(usd("54.12"), "JPY", l = arabic))
        // In Portuguese "." groups and "," is the decimal mark: neither may be swapped for the other.
        val portuguese = UsageLabels(decimalSeparator = ",", groupingSeparator = ".")
        assertEquals("389,66 CNY", estimate(usd("54.12"), "CNY", l = portuguese))
        assertEquals("892.980 IDR", estimate(usd("54.12"), "IDR", l = portuguese))
        // 1435.55 x 0.86 = 1234.573
        assertEquals("1.234,57 EUR", estimate(usd("1435.55"), "EUR", l = portuguese))
        val french = UsageLabels(decimalSeparator = ",", groupingSeparator = Char(0x202F).toString())
        assertEquals("892" + Char(0x202F) + "980 IDR", estimate(usd("54.12"), "IDR", l = french))
        // Four digits are grouped too, and the default labels group nothing. 1250 x 0.86 = 1075.
        assertEquals("1,075 EUR", estimate(usd("1250"), "EUR", l = UsageLabels(groupingSeparator = ",")))
        assertEquals("1075 EUR", estimate(usd("1250"), "EUR"))
    }

    @Test
    fun `the region currency is resolved only for the region setting`() {
        assertNull(BalanceCurrency.target(BalanceCurrency.OFF, "EUR"))
        assertNull(BalanceCurrency.target(null, "EUR"))
        assertNull(BalanceCurrency.target("garbage", "EUR"))
        assertEquals("EUR", BalanceCurrency.target(BalanceCurrency.REGION, "eur"))
        assertEquals("NOK", BalanceCurrency.target(BalanceCurrency.REGION, "NOK"))
        assertNull(BalanceCurrency.target(BalanceCurrency.REGION, null))
        assertNull(BalanceCurrency.target(BalanceCurrency.REGION, ""))
        assertNull(BalanceCurrency.target(BalanceCurrency.REGION, "EURO"))
        assertNull(BalanceCurrency.target(BalanceCurrency.REGION, "12$"))
        assertEquals("JPY", BalanceCurrency.target("JPY", "EUR"))
        assertEquals("JPY", BalanceCurrency.target("jpy", null))
    }

    @Test
    fun `the stored setting reads as one of the legal values`() {
        assertEquals(BalanceCurrency.OFF, BalanceCurrency.normalize(null))
        assertEquals(BalanceCurrency.OFF, BalanceCurrency.normalize(""))
        assertEquals(BalanceCurrency.OFF, BalanceCurrency.normalize("XYZ"))
        assertEquals(BalanceCurrency.OFF, BalanceCurrency.normalize("NOK"))
        assertEquals(BalanceCurrency.REGION, BalanceCurrency.normalize(" Region "))
        assertEquals("EUR", BalanceCurrency.normalize("eur"))
        assertEquals(20, BalanceCurrency.choices.toSet().size)
        assertTrue(BalanceCurrency.choices.all(BalanceCurrency::isCode))
        assertFalse(BalanceCurrency.OFF.uppercase() in BalanceCurrency.choices)
        assertEquals(
            listOf("USD", "CNY", "EUR", "JPY", "GBP", "KRW", "INR", "HKD", "SGD", "TWD", "AUD", "CAD", "CHF", "RUB", "BRL", "MXN", "THB", "IDR", "TRY", "SAR"),
            BalanceCurrency.choices,
        )
    }
}
