package com.labteto.dshmobile.core.rates

import com.labteto.dshmobile.core.usage.PlainDecimal
import com.labteto.dshmobile.core.usage.UsageLabels
import com.labteto.dshmobile.core.usage.localizedNumber
import com.labteto.dshmobile.core.usage.parseDecimal
import com.labteto.dshmobile.core.wire.dto.CurrencyWallet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ulp

/**
 * What [wallets] come to in [target], as the text that follows the real balance: `390.2 CNY`,
 * `8120 JPY`, `<0.01 EUR`. The caller puts its own "≈" in front, in the reader's language.
 *
 * Null whenever there is nothing honest to add, so the row simply shows the balance alone:
 * - no [target] (the setting is off, or "match my region" found no currency),
 * - no [table], or one older than 7 days at [now],
 * - every wallet is already in [target],
 * - [target] or a wallet's currency has no rate in the table,
 * - a balance that is not a plain decimal, or an amount too large to print.
 *
 * The cross rate goes through the dollar: `balance × rate[target] ÷ rate[wallet]`. When there is
 * more than one wallet they are converted one by one into [target] and added there, which is the
 * one place two currencies may be added: the real balance line still prints them apart.
 *
 * The number is cut toward zero at the currency's own digits, never rounded, exactly as
 * [com.labteto.dshmobile.core.usage.walletText] does for the balance itself, so an estimate cannot
 * read higher than the money. It is written with the reader's digits and decimal mark from
 * [labels], and the ISO code follows the number because `$` and `¥` each stand for several of the
 * twenty currencies on offer (and `¥` is already how the balance writes yuan).
 */
fun balanceEstimateText(
    wallets: List<CurrencyWallet>,
    target: String?,
    table: RateTable?,
    now: Long,
    labels: UsageLabels,
): String? {
    if (target == null || table == null || wallets.isEmpty() || !table.isUsable(now)) return null
    if (wallets.all { it.currency == target }) return null
    var total = 0.0
    for (wallet in wallets) {
        val amount = parseDecimal(wallet.balance)?.toDouble() ?: return null
        total += table.convert(amount, wallet.currency, target) ?: return null
    }
    val number = cutToDigits(total, BalanceCurrency.fractionDigits(target), labels) ?: return null
    return "$number $target"
}

/** The plain decimal as a double. It was checked against the grammar, so the string always parses. */
internal fun PlainDecimal.toDouble(): Double =
    ((if (negative) "-" else "") + whole + "." + fraction.ifEmpty { "0" }).toDouble()

/** 10^0 to 10^3: the digits an estimate can carry. */
private val scales = doubleArrayOf(1.0, 10.0, 100.0, 1000.0)

// Past this many minor units a double stops holding every unit, and nobody holds that much.
private const val MAX_UNITS = 1.0e13

private fun cutToDigits(value: Double, digits: Int, labels: UsageLabels): String? {
    if (!value.isFinite()) return null
    val scale = scales[digits]
    val scaled = abs(value) * scale
    if (scaled >= MAX_UNITS) return null
    // Four units in the last place absorb what the multiply, the divide and the scale-up lost, so
    // an amount that is exactly 390.20 is not cut to 390.19 for being 390.19999999999993 in a double.
    val units = floor(scaled + 4 * scaled.ulp).toLong()
    val scaleUnits = scale.toLong()
    val sign = if (value < 0 && scaled > 0) "-" else ""
    val point = labels.decimalSeparator
    val body = if (units == 0L && scaled > 0) {
        // Something, but less than the smallest unit: say so rather than print a zero that is not.
        "<" + (if (digits == 0) "1" else "0" + point + "0".repeat(digits - 1) + "1")
    } else {
        val whole = grouped((units / scaleUnits).toString(), labels.groupingSeparator)
        val fraction = if (digits == 0) 0L else units % scaleUnits
        if (fraction == 0L) whole else whole + point + fraction.toString().padStart(digits, '0')
    }
    // Built from the reader's own marks, never by replacing a "." afterwards: in Portuguese and
    // Spanish the "." is the grouping mark and the decimal mark is ",".
    return localizedNumber(sign + body, labels)
}

/** [digits] in groups of three from the right, joined by [separator]; unchanged when it is empty. */
private fun grouped(digits: String, separator: String): String {
    if (separator.isEmpty() || digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(separator.reversed()).reversed()
}
