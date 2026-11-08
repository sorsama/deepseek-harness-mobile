package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.*
import kotlin.math.floor

val subscriptionProviders = setOf("codex", "claude", "grok", "copilot", "antigravity")
data class RemainingWindow(val kind: String, val percent: Int, val resetsAt: Long?, val scope: String? = null)
/** Display projection deliberately excludes account identifiers and credentials. */
data class UsageRow(val provider: String, val windows: List<RemainingWindow> = emptyList(), val wallets: List<CurrencyWallet> = emptyList(), val sampledAt: Long? = null, val stale: Boolean = false)
fun defaultAccount(status: SubscriptionProviderStatus): String? =
    status.accounts.singleOrNull()?.takeIf { it.isDefault }?.key?.takeIf { it.isNotBlank() }
fun quotaRow(provider: String, quota: ProviderQuota, now: Long): UsageRow {
    val stamp = quota.observation?.sampledAt?.takeIf { it > 0 && it <= now }
    return UsageRow(provider, windows = if (provider !in subscriptionProviders || !quota.supported) emptyList() else quota.windows.mapNotNull {
        if (!it.usedPercent.isFinite() || it.usedPercent !in 0.0..100.0 || (it.resetsAt != null && it.resetsAt <= now)) null
        else RemainingWindow(it.kind, floor(100 - it.usedPercent).toInt(), it.resetsAt, it.scope)
    }, sampledAt = stamp, stale = stamp == null || quota.observation?.stale != false || now - stamp > 300_000)
}
private val decimal = Regex("^[0-9]+(?:\\.[0-9]+)?$")
fun walletRow(provider: String, wallet: AccountWallet?, now: Long): UsageRow = UsageRow(provider,
    wallets = if (provider != "deepseek-account" || wallet?.status != "ready") emptyList() else wallet.value.filter { it.currency in setOf("USD", "CNY") && decimal.matches(it.balance) },
    sampledAt = now.takeIf { provider == "deepseek-account" && wallet?.status == "ready" })
private fun money(wallet: CurrencyWallet, labels: UsageLabels): String {
    val whole = wallet.balance.substringBefore('.').trimStart('0').ifEmpty { "0" }
    val fraction = wallet.balance.substringAfter('.', "").take(2).padEnd(2, '0')
    val symbol = if (wallet.currency == "USD") "$" else "¥"
    val value = if (whole == "0" && fraction == "00" && wallet.balance.any { it in '1'..'9' }) "<0.01$symbol"
        else whole + (if (fraction == "00") "" else ".$fraction") + symbol
    return localizedNumber(value.replace(".", labels.decimalSeparator), labels)
}
data class UsageLabels(
    val session: String = "5h", val week: String = "week",
    val day: String = "d", val hour: String = "h", val minute: String = "m",
    val decimalSeparator: String = ".", val digits: String = "0123456789",
)
fun localizedNumber(value: String, labels: UsageLabels): String = value.map { char ->
    if (char in '0'..'9' && labels.digits.length == 10) labels.digits[char - '0'] else char
}.joinToString("")
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
