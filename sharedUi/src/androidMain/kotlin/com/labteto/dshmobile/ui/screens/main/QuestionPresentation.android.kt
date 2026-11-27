package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.util.Locale

@Composable
internal actual fun countdownNumber(seconds: Int): String = countdownNumber(seconds, LocalConfiguration.current.locales[0])

/** [seconds] in [locale]'s own numbering system, as `NumberFormat` writes a whole number there. */
internal fun countdownNumber(seconds: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(seconds)
