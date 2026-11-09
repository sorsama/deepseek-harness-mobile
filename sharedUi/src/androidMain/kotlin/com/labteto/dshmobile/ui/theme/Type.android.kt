package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.labteto.dshmobile.shared.R

/** Android loads the fonts as Android font resources, as the app always has. */
internal actual fun createUiFontFamily(): FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

internal actual fun createCodeFontFamily(): FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

/** Keeps the platform's own ascent and descent padding in the line box; see the note on `DsType`. */
internal actual fun createPlatformTextStyle(): PlatformTextStyle? = PlatformTextStyle(includeFontPadding = true)
