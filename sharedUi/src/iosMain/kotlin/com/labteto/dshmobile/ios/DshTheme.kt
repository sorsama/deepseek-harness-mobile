package com.labteto.dshmobile.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A small Material 3 theme in the Android app's colours: its blue accent on warm paper in the light
 * scheme and green-tinted ink in the dark one (`Ds.Paper*` and `Ds.Ink*` in the Android app's
 * `Color.kt`). The Android app's own token system (accent derivation, theme presets) is not ported
 * here; that is part of the full UI port.
 */
private val Accent = Color(0xFF486CFB)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFCFBF8),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFF7F6F2),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFEFEDE7),
    onSurfaceVariant = Color(0xFF5F5C57),
    outline = Color(0xFF9A968D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8DA3FF),
    onPrimary = Color(0xFF0B0C0F),
    background = Color(0xFF121318),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF1A1C22),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF23252C),
    onSurfaceVariant = Color(0xFFA0A3AB),
    outline = Color(0xFF63666E),
)

@Composable
internal fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme) {
        Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background, content = content)
    }
}
