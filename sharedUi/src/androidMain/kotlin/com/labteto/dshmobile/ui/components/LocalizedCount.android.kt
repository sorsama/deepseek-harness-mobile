package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** What `Resources.getString(id, int)` does: `String.format` in the configuration's first locale. */
@Composable
actual fun localizedCount(value: Int): String {
    val locale: Locale = LocalConfiguration.current.locales.get(0)
    return String.format(locale, "%d", value)
}

@Composable
actual fun localizedDecimal(value: Double, decimals: Int): String {
    val locale: Locale = LocalConfiguration.current.locales.get(0)
    return String.format(locale, "%.${decimals.coerceIn(0, 6)}f", value)
}

/** [localizedCount] in the configuration's first locale as it is now, kept for a message written later. */
@Composable
actual fun rememberCountFormat(): (Int) -> String {
    val locale: Locale = LocalConfiguration.current.locales.get(0)
    return remember(locale) { { value: Int -> String.format(locale, "%d", value) } }
}
