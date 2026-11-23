package com.labteto.dshmobile.core.rates

/**
 * The "Also show balance in" setting: which currency, if any, the DeepSeek balance is also
 * estimated in.
 *
 * The stored value is one of [OFF], [REGION] or an ISO 4217 code from [choices]. Anything else
 * (a hand-edited store, a code a later build dropped) reads as [OFF], so a bad value can never
 * start a network request nobody asked for.
 */
object BalanceCurrency {
    const val OFF = "off"

    /** Follow the device: the currency of the phone's region, when it has one. */
    const val REGION = "region"

    /** The fixed list Settings offers, in the order it shows them. */
    val choices: List<String> = listOf(
        "USD", "CNY", "EUR", "JPY", "GBP", "KRW", "INR", "HKD", "SGD", "TWD",
        "AUD", "CAD", "CHF", "RUB", "BRL", "MXN", "THB", "IDR", "TRY", "SAR",
    )

    /** A stored value as one of the legal ones. */
    fun normalize(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.equals(REGION, ignoreCase = true)) return REGION
        val code = text.uppercase()
        return if (code in choices) code else OFF
    }

    /**
     * The code to convert into, or null for "show nothing".
     *
     * [regionCurrency] is what the platform says the device's region pays in (`Currency` on the
     * JVM, `NSLocale` on iOS); it is only read for [REGION]. A region whose currency is not an
     * ISO-shaped code, or that the platform cannot name, resolves to null.
     */
    fun target(setting: String?, regionCurrency: String?): String? = when (val value = normalize(setting)) {
        OFF -> null
        REGION -> regionCurrency?.trim()?.uppercase()?.takeIf { isCode(it) }
        else -> value
    }

    /** Three ASCII capitals, which is what every key of a rate table is. */
    fun isCode(text: String): Boolean = text.length == 3 && text.all { it in 'A'..'Z' }

    // The digits a price in this currency is written with. JPY and KRW have no minor unit in ISO
    // 4217. IDR and TWD do on paper, but nobody prices anything in them: 54 USD is about 880 000
    // rupiah, and a cut-off 40.12 of them is noise. The others are the ISO zero- and
    // three-decimal currencies, for a region that is not in [choices].
    private val noDecimals = setOf(
        "JPY", "KRW", "IDR", "TWD", "VND", "CLP", "ISK", "PYG", "UGX", "RWF", "XAF", "XOF", "XPF",
        "KMF", "GNF", "DJF", "BIF", "VUV",
    )
    private val threeDecimals = setOf("KWD", "BHD", "OMR", "JOD", "TND", "IQD", "LYD")

    /** How many decimals an estimate in [code] carries: 0, 2 or 3. */
    fun fractionDigits(code: String): Int = when (code) {
        in noDecimals -> 0
        in threeDecimals -> 3
        else -> 2
    }
}
