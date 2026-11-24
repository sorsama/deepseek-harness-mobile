package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.core.rates.BalanceCurrency
import com.labteto.dshmobile.core.rates.RateSource
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.LocalDsSheetHide
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.appLocale
import com.labteto.dshmobile.ui.screens.main.deviceRegionCurrency
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.util.Currency
import java.util.Locale

/**
 * The "Also show balance in" row: its label, what is picked and a chevron. It opens
 * [BalanceCurrencySheet], so the 22 choices cost one line of the page instead of a menu taller
 * than the screen.
 */
@Composable
internal fun BalanceCurrencyRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var open by remember { mutableStateOf(false) }
    val current = BalanceCurrency.normalize(settings.balanceCurrency)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .clickable(role = Role.Button) {
                haptics.tap()
                open = true
            }
            .testTag("settings-balance-currency"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.balance_currency_title),
            Modifier.weight(1f).padding(vertical = DsSpacing.small),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.width(DsSpacing.small))
        Text(
            when (current) {
                BalanceCurrency.OFF -> stringResource(R.string.balance_currency_off)
                BalanceCurrency.REGION -> stringResource(R.string.balance_currency_region)
                else -> current
            },
            style = DsType.small13,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The arrow mirrors in right-to-left languages.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
    }
    if (open) {
        BalanceCurrencySheet(selected = current, onSelect = onSelect, onDismiss = { open = false })
    }
}

/**
 * Off, match my region, or one of the twenty currencies, each with its code and its name in the
 * app's language. Picking one stores it and slides the sheet away; the page behind has already
 * changed by then.
 */
@Composable
internal fun BalanceCurrencySheet(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val locale = appLocale()
    // "EUR · Euro": the region's own currency, so the row says what it would pick.
    val region = remember(locale) { deviceRegionCurrency()?.let { code -> code + currencyName(code, locale)?.let { " · $it" }.orEmpty() } }
    val uriHandler = LocalUriHandler.current
    DsBottomSheet(
        title = stringResource(R.string.balance_currency_title),
        subtitle = stringResource(R.string.balance_currency_hint),
        onDismiss = onDismiss,
    ) {
        val hide = LocalDsSheetHide.current
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .testTag("balance-currency-list"),
        ) {
            val pick: (String) -> Unit = { value ->
                // Stored first, then the sheet slides away over a page that already shows the pick.
                onSelect(value)
                hide { onDismiss() }
            }
            CurrencyOption(BalanceCurrency.OFF, stringResource(R.string.balance_currency_off), null, selected, pick)
            CurrencyOption(
                BalanceCurrency.REGION,
                stringResource(R.string.balance_currency_region),
                region?.let { stringResource(R.string.balance_currency_region_hint, it) }
                    ?: stringResource(R.string.balance_currency_region_none),
                selected,
                pick,
            )
            BalanceCurrency.choices.forEach { code ->
                CurrencyOption(code, code, currencyName(code, locale), selected, pick)
            }
        }
        // Their free terms ask for this link wherever the rates are used.
        Text(
            stringResource(R.string.balance_rates_credit),
            style = DsType.caption11,
            color = colors.labelTertiary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .heightIn(min = DsSpacing.touchTarget)
                .clickable(role = Role.Button) { runCatching { uriHandler.openUri(RateSource.EXCHANGE_RATE_API.url) } }
                .testTag("balance-rates-credit"),
        )
    }
}

/** The currency's name in [locale] (`Euro`, `欧元`, `يورو`), or null for a code this device does not know. */
private fun currencyName(code: String, locale: Locale): String? =
    runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrNull()?.takeIf { it.isNotBlank() && it != code }

@Composable
private fun CurrencyOption(value: String, title: String, hint: String?, selected: String, onPick: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .selectable(selected = value == selected, role = Role.RadioButton) {
                haptics.selection()
                onPick(value)
            }
            .testTag("balance-currency-$value"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        RadioButton(
            selected = value == selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.labelTertiary),
        )
        Column(Modifier.weight(1f).padding(vertical = DsSpacing.small)) {
            Text(title, style = DsType.std14, color = colors.labelPrimary)
            if (hint != null) Text(hint, style = DsType.caption11, color = colors.labelTertiary)
        }
    }
}
