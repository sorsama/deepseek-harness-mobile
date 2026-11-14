package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.usage.*
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable internal fun quotaLabels(): UsageLabels {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val symbols = java.text.DecimalFormatSymbols.getInstance(locale)
    return UsageLabels(
        stringResource(R.string.quota_compact_session), stringResource(R.string.quota_compact_week),
        stringResource(R.string.quota_compact_day), stringResource(R.string.quota_compact_hour), stringResource(R.string.quota_compact_minute),
        symbols.decimalSeparator.toString(), (0..9).map { symbols.zeroDigit + it }.joinToString(""),
    )
}

/** Visible-only local clock; repository coalesces the card and footer's simultaneous refreshes. */
@Composable internal fun rememberQuotaClock(store: SessionStore, identity: Any?, includeAll: Boolean = false): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(store, lifecycle, identity, includeAll) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Read the clock after the refresh, not before: a wallet row stamps itself with the
            // time the fetch completed, so a clock sampled first would be a few milliseconds
            // older than that stamp and usageFooter would reject the row as a future sample.
            while (true) { store.refreshQuota(includeAll); now = System.currentTimeMillis(); delay(60_000) }
        }
    }
    return now
}

/** What the composer footer says about usage, kept to a few words; empty when there is nothing to add. */
@Composable internal fun usageFooterText(snapshot: UsageSnapshot, provider: String?, limit: LimitHit?, now: Long): String {
    limit.appliesTo(provider)?.let { return stringResource(if (it.kind == LimitKind.QUOTA) R.string.quota_limit_short else R.string.quota_rate_short) }
    val labels = quotaLabels()
    val windows = usageFooter(snapshot.rows.firstOrNull { it.provider == provider }, now, false, labels)
    if (windows.isNotEmpty()) return windows
    return (snapshot.balance as? BalanceState.Ready)?.let { usageFooter(it.row, now, false, labels) }.orEmpty()
}

@Composable fun UsageCard(
    snapshot: UsageSnapshot,
    provider: String?,
    now: Long,
    route: String? = null,
    tokens: TokenUsageView? = null,
    limit: LimitHit? = null,
    onExpanded: (Boolean) -> Unit = {},
    onRefresh: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) { onExpanded(expanded) }
    val colors = DsTheme.colors
    val labels = quotaLabels()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val balance = snapshot.balance
    // The quota-window section exists only when the host reports windows. A host that has no
    // subscription service, or a provider it lists no quota for, leaves no empty "unknown" rows.
    val rows = snapshot.rows.filter { it.windows.isNotEmpty() && (expanded || it.provider == provider) }.sortedBy { it.provider != provider }
    val canRefresh = snapshot.subscriptions == true || balance != BalanceState.Hidden
    DsCard(modifier = Modifier.fillMaxWidth().testTag("quota-card"), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Activity, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.labelSecondary)
            Spacer(Modifier.width(DsSpacing.small))
            Text(stringResource(R.string.quota_title), style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.weight(1f))
            if (canRefresh) DsIconButton(FeatherIcons.RefreshCw, stringResource(if (snapshot.loading) R.string.quota_checking else R.string.quota_refresh),
                onRefresh, enabled = !snapshot.loading, iconSize = 18.dp, modifier = Modifier.testTag("quota-refresh"))
        }

        // This session: what the harness counted for it, whichever provider answered.
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall), modifier = Modifier.testTag("usage-session")) {
            Text(route ?: stringResource(R.string.quota_session_title), style = DsType.caption11, color = colors.labelTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tokens != null && tokens.inputTokens + tokens.outputTokens > 0) {
                UsageLine(stringResource(R.string.quota_tokens_input), compactCount(tokens.inputTokens, labels), "usage-input")
                UsageLine(stringResource(R.string.quota_tokens_output), compactCount(tokens.outputTokens, labels), "usage-output")
                if (tokens.cacheReadTokens > 0) tokens.cacheHitRatio?.let {
                    UsageLine(stringResource(R.string.quota_tokens_cache), percentText((it * 100).toInt(), labels), "usage-cache")
                }
                UsageLine(stringResource(R.string.quota_tokens_total), compactCount(tokens.inputTokens + tokens.outputTokens, labels), "usage-total")
            } else {
                Text(stringResource(R.string.quota_session_empty), style = DsType.small13, color = colors.labelTertiary, modifier = Modifier.testTag("usage-empty"))
            }
        }

        limit.appliesTo(provider)?.let { hit ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = DsSpacing.small).testTag("usage-limit")) {
                StateDot(if (hit.kind == LimitKind.QUOTA) StateDotState.Error else StateDotState.Warning, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (hit.kind == LimitKind.QUOTA) R.string.quota_limit_reached else R.string.quota_rate_limited),
                    style = DsType.small13, color = if (hit.kind == LimitKind.QUOTA) colors.error else colors.labelPrimary)
            }
        }

        when (balance) {
            BalanceState.Hidden -> Unit
            BalanceState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall), modifier = Modifier.padding(top = DsSpacing.small).testTag("usage-balance-failed")) {
                Text(stringResource(R.string.quota_balance_title), style = DsType.caption11, color = colors.labelTertiary)
                Text(stringResource(R.string.quota_balance_failed), style = DsType.small13, color = colors.labelTertiary)
            }
            is BalanceState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall), modifier = Modifier.padding(top = DsSpacing.small).testTag("usage-balance")) {
                Text(stringResource(R.string.quota_balance_title), style = DsType.caption11, color = colors.labelTertiary)
                Text(balance.row.wallets.joinToString(" · ") { walletText(it, labels) }, style = DsType.rowTitle, color = colors.labelPrimary)
                balance.row.bonusWallets.forEach {
                    Text(stringResource(R.string.quota_balance_bonus, walletText(it, labels)), style = DsType.small13, color = colors.labelSecondary)
                }
                balance.row.sampledAt?.let {
                    val updated = stringResource(R.string.quota_updated, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it)))
                    Text(if (now - it > 300_000) updated + " · " + stringResource(R.string.quota_stale) else updated, style = DsType.caption11, color = colors.labelTertiary)
                }
            }
        }

        rows.forEachIndexed { rowIndex, row ->
            Text(row.provider, style = DsType.caption11, color = colors.labelTertiary, modifier = Modifier.padding(top = DsSpacing.small))
            row.windows.forEachIndexed { windowIndex, window ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    val label = window.scope ?: when (window.kind) {
                        "session" -> stringResource(R.string.quota_short)
                        "weekly" -> stringResource(R.string.quota_week)
                        else -> stringResource(R.string.quota_other)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("quota-window-$rowIndex-$windowIndex")) {
                        Text(label, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(DsSpacing.small))
                        Text(localizedNumber("${window.percent}%", labels), style = DsType.mono13, color = colors.labelPrimary)
                    }
                    val ratio = (window.percent / 100f).coerceIn(0f, 1f)
                    val remaining = stringResource(R.string.quota_remaining_percent, localizedNumber("${window.percent}%", labels))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.bgModulePlatform)
                        .testTag("quota-bar-$rowIndex-$windowIndex").semantics { contentDescription = "$label · $remaining"; progressBarRangeInfo = ProgressBarRangeInfo(ratio, 0f..1f) }) {
                        Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(colors.accent))
                    }
                    window.resetsAt?.let { reset ->
                        val clock = resetClock(reset, now, false, labels)
                        val absolute = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(reset))
                        Text(stringResource(R.string.quota_reset, listOf(clock, absolute).filter { it.isNotEmpty() }.joinToString(" · ")),
                            style = DsType.caption11, color = colors.labelTertiary)
                    }
                }
            }
            val metadata = buildList {
                row.sampledAt?.let { add(stringResource(R.string.quota_updated, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it)))) }
                snapshot.checkedAt?.let { add(stringResource(R.string.quota_checked, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it)))) }
            }
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = DsType.caption11, color = colors.labelTertiary)
            if (row.stale || row.sampledAt?.let { now - it > 300_000 } == true) Text(stringResource(R.string.quota_stale), style = DsType.caption11, color = colors.labelTertiary)
        }
        if (snapshot.subscriptions == true) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp), modifier = Modifier.testTag("quota-all")) {
                Text(stringResource(if (expanded) R.string.quota_current else R.string.quota_all), style = DsType.small13)
            }
        }
    }
}

@Composable private fun UsageLine(label: String, value: String, tag: String) {
    val colors = DsTheme.colors
    // One label and its number read as one item ("Input, 12.4k") rather than two loose texts.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(label, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(DsSpacing.small))
        Text(value, style = DsType.mono13, color = colors.labelPrimary)
    }
}
