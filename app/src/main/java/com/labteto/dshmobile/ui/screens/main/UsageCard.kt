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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.usage.*
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
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

@Composable fun UsageCard(snapshot: UsageSnapshot, provider: String?, now: Long, onExpanded: (Boolean) -> Unit = {}, onRefresh: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) { onExpanded(expanded) }
    val colors = DsTheme.colors
    val labels = quotaLabels()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    DsCard(modifier = Modifier.fillMaxWidth().testTag("quota-card"), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Activity, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.labelSecondary)
            Spacer(Modifier.width(DsSpacing.small))
            Text(stringResource(R.string.quota_title), style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.weight(1f))
            DsIconButton(FeatherIcons.RefreshCw, stringResource(if (snapshot.loading) R.string.quota_checking else R.string.quota_refresh),
                onRefresh, enabled = !snapshot.loading, iconSize = 18.dp, modifier = Modifier.testTag("quota-refresh"))
        }
        val rows = if (expanded) {
            val all = snapshot.rows.associateBy { it.provider }.toMutableMap()
            if (provider != null && provider !in all) all[provider] = UsageRow(provider)
            all.values.sortedBy { it.provider != provider }
        } else listOf(snapshot.rows.firstOrNull { it.provider == provider } ?: UsageRow(provider.orEmpty()))
        rows.forEachIndexed { rowIndex, row ->
            if (row.provider.isNotEmpty()) Text(row.provider, style = DsType.caption11, color = colors.labelTertiary)
            if (row.wallets.isNotEmpty()) {
                row.wallets.forEach { wallet ->
                    Text(usageFooter(row.copy(wallets = listOf(wallet), stale = false, sampledAt = null), now, false, labels), style = DsType.rowTitle, color = colors.labelPrimary)
                }
            }
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
            if (row.windows.isEmpty() && row.wallets.isEmpty()) Text(stringResource(R.string.quota_unknown), style = DsType.small13, color = colors.labelTertiary)
            val metadata = buildList {
                row.sampledAt?.let { add(stringResource(R.string.quota_updated, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it)))) }
                snapshot.checkedAt?.let { add(stringResource(R.string.quota_checked, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it)))) }
            }
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = DsType.caption11, color = colors.labelTertiary)
            if (row.stale || row.sampledAt?.let { now - it > 300_000 } == true) Text(stringResource(R.string.quota_stale), style = DsType.caption11, color = colors.labelTertiary)
        }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp), modifier = Modifier.testTag("quota-all")) {
            Text(stringResource(if (expanded) R.string.quota_current else R.string.quota_all), style = DsType.small13)
        }
    }
}
