package com.labteto.dshmobile.ui.screens.main

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.core.rates.BalanceCurrency
import com.labteto.dshmobile.core.rates.RateSource
import com.labteto.dshmobile.core.rates.RateTable
import com.labteto.dshmobile.core.rates.balanceEstimateText
import com.labteto.dshmobile.core.usage.UsageLabels
import com.labteto.dshmobile.core.wire.dto.CurrencyWallet
import com.labteto.dshmobile.rates.ExchangeRateRepository
import com.labteto.dshmobile.ui.SessionStoreEntryPoint
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.rememberHostsStore
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What a card that prints the DeepSeek balance adds after it: ` · ≈ 389.66 CNY`.
 *
 * [text] is null whenever there is nothing honest to add (the setting is off, the region has no
 * currency, the table is missing or too old, the wallet is already in that currency), so the
 * caller prints the balance alone and never an empty "≈".
 */
@Stable
internal class BalanceEstimator(
    private val target: String?,
    /** The table the estimates come from, for the source note; null when there is none to show. */
    val table: RateTable?,
    private val now: Long,
    private val labels: UsageLabels,
    private val format: String,
) {
    /** The estimate for [wallets], with its "≈", or null. Spaces are non-breaking so it wraps whole. */
    fun text(wallets: List<CurrencyWallet>): String? =
        balanceEstimateText(wallets, target, table, now, labels)
            ?.let { format.format(it).replace(' ', NO_BREAK_SPACE) }

    /** [line], the wallets as the card prints them, followed by the estimate when there is one. */
    fun withEstimate(line: String, wallets: List<CurrencyWallet>): String =
        text(wallets)?.let { "$line · $it" } ?: line

    companion object {
        /** An estimator that never estimates: what a test pins the card to, whatever the device's settings say. */
        val Off = BalanceEstimator(target = null, table = null, now = 0L, labels = UsageLabels(), format = "≈ %1\$s")
    }
}

/**
 * Pins [rememberBalanceEstimator] to a given estimator, for a test: with it provided, the settings
 * store, the rate repository and the network are not touched, so a card's exact text does not depend
 * on what "Also show balance in" is set to on the device the test runs on. Null (the default) is production.
 */
internal val LocalBalanceEstimatorOverride = compositionLocalOf<BalanceEstimator?> { null }

private val NO_BREAK_SPACE = Char(0x00A0)

/**
 * The locale the strings on screen are in.
 *
 * `LocalConfiguration` is the device's in a sheet or a dialog window below Android 13, while the
 * strings (and so everything written beside them: a date, a currency name, the digits) come from the
 * app's own language, so a sheet would name the currencies in the phone's language under an
 * English title. `LocalResources` is what `stringResource` reads, and it follows configuration
 * changes, so the locale taken from it always agrees with the words beside it.
 */
@Composable
internal fun appLocale(): Locale = LocalResources.current.configuration.locales[0]

/** The currency of the device's region, or null when it has none or the locale carries no region. */
internal fun deviceRegionCurrency(): String? = runCatching {
    // The device's locale, not the app's language override: "match my region" is about where the phone is.
    val locale = android.content.res.Resources.getSystem().configuration.locales[0]
    java.util.Currency.getInstance(locale)?.currencyCode
}.getOrNull()

/** The process-wide rate table, resolved once per composition. */
@Composable
internal fun rememberExchangeRates(): ExchangeRateRepository {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).exchangeRates()
    }
}

/**
 * The estimator for the balance on this screen.
 *
 * Call it only where a balance is on screen: while the setting is on, composing it is what asks the
 * repository to bring the table up to date (a no-op within 24 hours of the last fetch, and nothing
 * at all while the setting is off). It re-checks hourly while it stays on screen, so a card left
 * open overnight still picks up the day's rates.
 */
@Composable
internal fun rememberBalanceEstimator(labels: UsageLabels = quotaLabels()): BalanceEstimator {
    LocalBalanceEstimatorOverride.current?.let { return it }
    val hostsStore = rememberHostsStore()
    val settings by hostsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val repository = rememberExchangeRates()
    val target = remember(settings.balanceCurrency) { BalanceCurrency.target(settings.balanceCurrency, deviceRegionCurrency()) }
    val table by repository.table.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        if (target != null) while (true) {
            repository.refresh()
            delay(REFRESH_CHECK_MS)
        }
    }
    val format = stringResource(R.string.balance_estimate)
    // Read when the table arrives, not on every frame: the 7 day limit does not need a ticking clock.
    val now = remember(table, target) { System.currentTimeMillis() }
    return remember(target, table, now, labels, format) { BalanceEstimator(target, table, now, labels, format) }
}

private const val REFRESH_CHECK_MS = 60L * 60 * 1000

/**
 * "Rates from ExchangeRate-API, updated 21 Nov", as a link to the feed's own site, which its free
 * terms ask for wherever its rates are shown. The date is the day the feed computed the rates.
 */
@Composable
internal fun RatesSourceNote(table: RateTable, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    val locale = appLocale()
    val date = remember(table.asOf, locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(table.asOf)) }
    Text(
        stringResource(
            if (table.source == RateSource.EXCHANGE_RATE_API) R.string.balance_rates_source else R.string.balance_rates_source_fallback,
            date,
        ),
        style = DsType.caption11,
        color = colors.labelTertiary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(table.source.url) } },
    )
}
