package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.*
import kotlin.math.floor

data class RemainingWindow(val kind: String, val percent: Int, val resetsAt: Long?, val scope: String? = null)
/**
 * Display projection deliberately excludes account identifiers and credentials.
 *
 * A subscription row carries [windows]; the DeepSeek balance row carries [wallets] (recharge
 * balances) and [bonusWallets] (promotional credit, always shown apart from the balance).
 */
data class UsageRow(
    val provider: String,
    val windows: List<RemainingWindow> = emptyList(),
    val wallets: List<CurrencyWallet> = emptyList(),
    val bonusWallets: List<CurrencyWallet> = emptyList(),
    val sampledAt: Long? = null,
    val stale: Boolean = false,
)
fun defaultAccount(status: SubscriptionProviderStatus): String? =
    status.accounts.singleOrNull()?.takeIf { it.isDefault }?.key?.takeIf { it.isNotBlank() }

/**
 * Quota windows the host reported for one provider.
 *
 * There is no list of providers that may have a quota: the host's roster says which do, and a
 * provider it never listed never reaches this function. A window that is out of range or whose
 * reset has passed is dropped rather than drawn as a number nobody measured.
 */
fun quotaRow(provider: String, quota: ProviderQuota, now: Long): UsageRow {
    val stamp = quota.observation?.sampledAt?.takeIf { it > 0 && it <= now }
    return UsageRow(provider, windows = if (!quota.supported) emptyList() else quota.windows.mapNotNull {
        if (!it.usedPercent.isFinite() || it.usedPercent !in 0.0..100.0 || (it.resetsAt != null && it.resetsAt <= now)) null
        else RemainingWindow(it.kind, floor(100 - it.usedPercent).toInt(), it.resetsAt, it.scope)
    }, sampledAt = stamp, stale = stamp == null || quota.observation?.stale != false || now - stamp > 300_000)
}

/** The provider route whose balance the DeepSeek account RPC answers for. */
const val DEEPSEEK_ACCOUNT = "deepseek-account"

private val walletCurrencies = setOf("USD", "CNY")
private fun List<CurrencyWallet>.displayable(): List<CurrencyWallet> =
    filter { it.currency in walletCurrencies && parseDecimal(it.balance) != null }

fun walletRow(provider: String, wallet: AccountWallet?, now: Long): UsageRow {
    val ready = provider == DEEPSEEK_ACCOUNT && wallet?.status == "ready"
    return UsageRow(provider,
        wallets = if (ready) wallet!!.value.displayable() else emptyList(),
        bonusWallets = if (ready) wallet!!.bonusWallets.displayable() else emptyList(),
        sampledAt = now.takeIf { ready })
}

/**
 * A decimal string taken apart without a float or a big number type, so what is shown is exactly
 * what the host sent, cut and never rounded.
 */
class PlainDecimal internal constructor(val negative: Boolean, val whole: String, val fraction: String) {
    val isZero: Boolean get() = whole == "0" && fraction.all { it == '0' }
}
// The grammar the DeepSeek Platform web client hands to big.js, which the harness documents as
// the balance contract: an optional sign, digits with the integer or the fraction part allowed to
// be missing (but not both), and an optional exponent. `0E-16` and `5.0000000000000000` are both
// real balances. NaN, Infinity and anything else are rejected.
private val decimalGrammar = Regex("^(-)?(?:([0-9]+)(?:[.]([0-9]*))?|[.]([0-9]+))(?:[eE]([+-]?[0-9]+))?$")
private const val MAX_DECIMAL_LENGTH = 96
private const val MAX_DECIMAL_EXPONENT = 64
fun parseDecimal(raw: String): PlainDecimal? {
    if (raw.length > MAX_DECIMAL_LENGTH) return null
    val m = decimalGrammar.matchEntire(raw) ?: return null
    val integer = m.groupValues[2]
    val fraction = m.groupValues[3].ifEmpty { m.groupValues[4] }
    val exponent = m.groupValues[5].ifEmpty { "0" }.toIntOrNull()?.takeIf { it in -MAX_DECIMAL_EXPONENT..MAX_DECIMAL_EXPONENT } ?: return null
    val digits = integer + fraction
    val point = integer.length + exponent
    val (whole, frac) = when {
        point <= 0 -> "0" to "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length) to ""
        else -> digits.substring(0, point) to digits.substring(point)
    }
    return PlainDecimal(m.groupValues[1].isNotEmpty(), whole.trimStart('0').ifEmpty { "0" }, frac)
}

private fun money(wallet: CurrencyWallet, labels: UsageLabels): String {
    val value = parseDecimal(wallet.balance) ?: return ""
    val cents = value.fraction.take(2).padEnd(2, '0')
    val symbol = if (wallet.currency == "USD") "$" else "¥"
    val sign = if (value.negative && !value.isZero) "-" else ""
    val body = if (value.whole == "0" && cents == "00" && !value.isZero) "<0.01" else value.whole + (if (cents == "00") "" else ".$cents")
    return localizedNumber((sign + body + symbol).replace(".", labels.decimalSeparator), labels)
}
/** One wallet as the card and footer print it, such as `54.12$` or `10¥`. */
fun walletText(wallet: CurrencyWallet, labels: UsageLabels): String = money(wallet, labels)

data class UsageLabels(
    val session: String = "5h", val week: String = "week",
    val day: String = "d", val hour: String = "h", val minute: String = "m",
    val decimalSeparator: String = ".", val digits: String = "0123456789",
)
fun localizedNumber(value: String, labels: UsageLabels): String = value.map { char ->
    if (char in '0'..'9' && labels.digits.length == 10) labels.digits[char - '0'] else char
}.joinToString("")

/**
 * A token count, short: `842`, `12.4k`, `3.2M`. The tenth is cut, not rounded, so a count never
 * reads higher than it is; the digits and the decimal mark follow the reader's locale.
 */
fun compactCount(value: Long, labels: UsageLabels): String {
    val count = value.coerceAtLeast(0)
    fun tenths(units: Long, suffix: String) = (units / 10).toString() + (units % 10).takeIf { it != 0L }?.let { ".$it" }.orEmpty() + suffix
    val text = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> tenths(count / 100, "k")
        count < 1_000_000_000 -> tenths(count / 100_000, "M")
        else -> tenths(count / 100_000_000, "B")
    }
    return localizedNumber(text.replace(".", labels.decimalSeparator), labels)
}
/** A whole percent such as `82%`, with the reader's digits. */
fun percentText(percent: Int, labels: UsageLabels): String = localizedNumber("$percent%", labels)

fun usageFooter(row: UsageRow?, now: Long, ru: Boolean, labels: UsageLabels? = null): String {
    val text = labels ?: if (ru) UsageLabels("5ч", "нед", "д", "ч", "м") else UsageLabels()
    if (row == null || row.stale || row.sampledAt?.let { now - it > 300_000 || it > now } == true) return ""
    if (row.wallets.isNotEmpty()) return row.wallets.joinToString(" · ") { money(it, text) }
    // Scoped and unknown-duration windows remain in Details, never relabelled as a 5-hour allowance.
    val windows = row.windows.filter { it.scope == null && it.kind in setOf("session", "weekly") && (it.resetsAt == null || it.resetsAt > now) }.groupBy { it.kind }.mapNotNull { (_, list) -> list.singleOrNull() }
    val pieces = listOfNotNull(windows.firstOrNull { it.kind == "session" }?.let { text.session + ":" + localizedNumber("${it.percent}%", text) }, windows.firstOrNull { it.kind == "weekly" }?.let { text.week + ":" + localizedNumber("${it.percent}%", text) }).toMutableList()
    resetClock(windows.firstOrNull { it.kind == "weekly" }?.resetsAt, now, ru, text).takeIf { it.isNotEmpty() }?.let { pieces.add(it) }
    return pieces.joinToString(" · ")
}
fun resetClock(resetAt: Long?, now: Long, ru: Boolean, labels: UsageLabels? = null): String {
    val text = labels ?: if (ru) UsageLabels("5ч", "нед", "д", "ч", "м") else UsageLabels()
    if (resetAt == null || resetAt <= now) return ""
    val minutes = (resetAt - now) / 60_000
    val days = minutes / 1440; val hours = minutes / 60 % 24
    return localizedNumber(when { days > 0 -> "$days${text.day} $hours${text.hour}"
        hours > 0 -> "$hours${text.hour} ${minutes % 60}${text.minute}"
        else -> "${minutes.coerceAtLeast(1)}${text.minute}" }, text)
}
