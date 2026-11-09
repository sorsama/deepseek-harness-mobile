package com.labteto.dshmobile.ui.theme

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * The appearance preferences are persisted as strings, so every read path has to normalise them the
 * same way. A typo in the stored value must never crash the app or drop the user to a scheme they
 * did not choose — it falls back to the Ink default.
 */
class AppearanceTest {

    @Test
    fun themePreference_readsStoredStrings() {
        assertEquals(ThemePreference.LIGHT, ThemePreference.of("light"))
        assertEquals(ThemePreference.DARK, ThemePreference.of("dark"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.of("system"))
        // Case-insensitive: older builds wrote lower-case, a hand-edited backup may not.
        assertEquals(ThemePreference.DARK, ThemePreference.of("DARK"))
    }

    @Test
    fun themePreference_fallsBackToSystem() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.of(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.of(""))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.of("purple"))
    }

    @Test
    fun accent_readsEveryKey_andFallsBackToInk() {
        AccentPalette.entries.forEach { accent ->
            assertEquals(accent, AccentPalette.of(accent.key))
        }
        assertEquals(AccentPalette.Ink, AccentPalette.of(null))
        assertEquals(AccentPalette.Ink, AccentPalette.of("ultraviolet"))
    }

    @Test
    fun accentKeysAreUnique_andEveryAccentHasBothSchemes() {
        assertEquals(AccentPalette.entries.size, AccentPalette.entries.map { it.key }.toSet().size)
        AccentPalette.entries.forEach { accent ->
            // Dark and light ramps must actually differ for the pastel dark accents: using the same
            // fill on paper would fail contrast, which is why the palette carries both.
            assertTrue("${accent.key} dark/light fills should differ", accent.dark != accent.light)
        }
    }

    @Test
    fun fontSize_readsStoredKeys() {
        FontSizeOption.entries.forEach { option ->
            assertEquals(option, FontSizeOption.of(option.key))
        }
        assertEquals(FontSizeOption.Default, FontSizeOption.of(null))
        assertEquals(FontSizeOption.Default, FontSizeOption.of("huge"))
    }

    @Test
    fun fontSizeScalesAreOrdered_andDefaultIsNeutral() {
        assertEquals(1f, FontSizeOption.Default.scale)
        assertTrue(FontSizeOption.Compact.scale < FontSizeOption.Default.scale)
        assertTrue(FontSizeOption.Default.scale < FontSizeOption.Large.scale)
        assertTrue(FontSizeOption.Large.scale < FontSizeOption.Larger.scale)
    }

    @Test
    fun defaultCustomization_isInkInTheSystemScheme() {
        val defaults = ThemeCustomization.Default
        assertEquals(ThemePreference.SYSTEM, defaults.preference)
        assertEquals(AccentPalette.Ink, defaults.accent)
        assertEquals(false, defaults.amoled)
        assertEquals(FontSizeOption.Default, defaults.fontSize)
        assertEquals(true, defaults.haptics)
    }
}
