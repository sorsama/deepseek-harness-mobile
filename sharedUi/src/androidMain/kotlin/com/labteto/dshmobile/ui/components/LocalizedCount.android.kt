package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** What `Resources.getString(id, int)` does: `String.format` in the configuration's first locale. */
@Composable
actual fun localizedCount(value: Int): String {
    val locale: Locale = LocalConfiguration.current.locales.get(0)
    return String.format(locale, "%d", value)
}
