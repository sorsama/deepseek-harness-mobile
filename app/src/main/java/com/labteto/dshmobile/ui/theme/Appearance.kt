package com.labteto.dshmobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Theme preference, mirroring the harness Appearance row (light|dark|system). */
enum class ThemePreference {
    LIGHT, DARK, SYSTEM;

    companion object {
        /** Normalises a stored preference string; unknown values fall back to the system. */
        fun of(value: String?): ThemePreference = when (value?.lowercase()) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

/** The stored form of a preference, for `AppSettings.themePreference`. */
val ThemePreference.storageKey: String
    get() = when (this) {
        ThemePreference.LIGHT -> "light"
        ThemePreference.DARK -> "dark"
        ThemePreference.SYSTEM -> "system"
    }

/**
 * What `AppSettings.themeAccent` holds when the accent is the user's own colour. The colour itself
 * is in `AppSettings.themeCustomAccent`; this key only says which of the two is in force, so the
 * stored colour survives a trip through a preset and back.
 */
const val CUSTOM_ACCENT_KEY = "custom"

/**
 * The eight colours an accent is made of: a fill, its hover, its soft tint and the content colour
 * that sits on the fill, once for the dark scheme and once for the light one.
 *
 * This is what [DshTheme] paints with. It does not matter to the theme whether the ramp came from
 * a hand-tuned [AccentPalette] preset or was derived from a picked colour (see [deriveAccentRamp]):
 * both arrive as the same value.
 */
@Immutable
data class AccentRamp(
    val dark: Color,
    val darkHover: Color,
    val darkSoft: Color,
    val onDark: Color,
    val light: Color,
    val lightHover: Color,
    val lightSoft: Color,
    val onLight: Color,
) {
    fun fill(dark: Boolean): Color = if (dark) this.dark else light
    fun hover(dark: Boolean): Color = if (dark) darkHover else lightHover
    fun soft(dark: Boolean): Color = if (dark) darkSoft else lightSoft
    fun on(dark: Boolean): Color = if (dark) onDark else onLight
}

/**
 * The custom colour in force: the stored hex, but only while the accent key says `custom` and the
 * hex parses. Anything else — a preset key, a missing value, a hand-edited backup — means no custom
 * colour, and the theme falls back to the preset named by the key (Ink when it names none).
 */
fun resolveCustomAccent(accentKey: String?, customHex: String?): Color? =
    if (accentKey == CUSTOM_ACCENT_KEY) parseAccentHex(customHex) else null

/**
 * One accent choice for the customizable theme.
 *
 * Every accent carries a dark and a light ramp rather than one colour that both schemes have to
 * tolerate: the Ink blue that reads as "live" on near-black is unreadable as a button fill on
 * paper, and the dark blue that fixes that looks dead on the dark canvas. [on] is the text/icon
 * colour that sits on the accent — near-black for the pastel dark accents, white for the saturated
 * light ones.
 */
enum class AccentPalette(
    /** Stable key persisted in `AppSettings.themeAccent`. */
    val key: String,
    /** Colour name for accessibility announcements/settings copy. */
    val label: String,
    // Dark scheme.
    val dark: Color,
    val darkHover: Color,
    val darkSoft: Color,
    val onDark: Color,
    // Light scheme.
    val light: Color,
    val lightHover: Color,
    val lightSoft: Color,
    val onLight: Color,
) {
    Ink(
        key = "ink", label = "Ink blue",
        dark = Color(0xFF6E9BFF), darkHover = Color(0xFF82AAFF), darkSoft = Color(0xFF141A2B),
        onDark = Color(0xFF0B0C0F),
        light = Color(0xFF3D5FE0), lightHover = Color(0xFF2F4FCB), lightSoft = Color(0xFFE7ECFB),
        onLight = Color(0xFFFFFFFF),
    ),
    Violet(
        key = "violet", label = "Violet",
        dark = Color(0xFFB69CFF), darkHover = Color(0xFFC7B3FF), darkSoft = Color(0xFF1E1830),
        onDark = Color(0xFF0B0C0F),
        light = Color(0xFF6D4AD8), lightHover = Color(0xFF5C39C7), lightSoft = Color(0xFFEEEAFB),
        onLight = Color(0xFFFFFFFF),
    ),
    Teal(
        key = "teal", label = "Teal",
        dark = Color(0xFF5EEAD4), darkHover = Color(0xFF7FF0DE), darkSoft = Color(0xFF0F2420),
        onDark = Color(0xFF04231E),
        light = Color(0xFF0F8F7E), lightHover = Color(0xFF0B7A6B), lightSoft = Color(0xFFE0F5F1),
        onLight = Color(0xFFFFFFFF),
    ),
    Green(
        key = "green", label = "Green",
        dark = Color(0xFF4ADE80), darkHover = Color(0xFF6EE79B), darkSoft = Color(0xFF12261A),
        onDark = Color(0xFF052013),
        light = Color(0xFF1F9D55), lightHover = Color(0xFF188347), lightSoft = Color(0xFFE7F6EC),
        onLight = Color(0xFFFFFFFF),
    ),
    Amber(
        key = "amber", label = "Amber",
        dark = Color(0xFFF2B04A), darkHover = Color(0xFFF7C36D), darkSoft = Color(0xFF2A2214),
        onDark = Color(0xFF1A1205),
        light = Color(0xFFB45309), lightHover = Color(0xFF9A4707), lightSoft = Color(0xFFFDF1E0),
        onLight = Color(0xFFFFFFFF),
    ),
    Rose(
        key = "rose", label = "Rose",
        dark = Color(0xFFF87171), darkHover = Color(0xFFFA8C8C), darkSoft = Color(0xFF2A1517),
        onDark = Color(0xFF240A0A),
        light = Color(0xFFD92D20), lightHover = Color(0xFFC02118), lightSoft = Color(0xFFFDECEA),
        onLight = Color(0xFFFFFFFF),
    ),
    Mono(
        key = "mono", label = "Mono",
        dark = Color(0xFFECEDEF), darkHover = Color(0xFFFFFFFF), darkSoft = Color(0xFF1A1C22),
        onDark = Color(0xFF0B0C0F),
        light = Color(0xFF1B1A17), lightHover = Color(0xFF2C2B27), lightSoft = Color(0xFFEFEDE7),
        onLight = Color(0xFFF7F6F2),
    );

    /** This preset's two ramps as the value the theme paints with. */
    val ramp: AccentRamp
        get() = AccentRamp(
            dark = dark, darkHover = darkHover, darkSoft = darkSoft, onDark = onDark,
            light = light, lightHover = lightHover, lightSoft = lightSoft, onLight = onLight,
        )

    companion object {
        /**
         * Resolve a stored key; unknown values (and an absent one) mean the Ink default. That
         * includes [CUSTOM_ACCENT_KEY]: a custom colour is not a preset, and it only applies
         * through [ThemeCustomization.customAccent].
         */
        fun of(key: String?): AccentPalette =
            entries.firstOrNull { it.key == key } ?: Ink
    }
}

/** App-level text size multiplier, applied on top of the system font scale. */
enum class FontSizeOption(val key: String, val label: String, val scale: Float) {
    Compact("compact", "Compact", 0.92f),
    Default("default", "Default", 1f),
    Large("large", "Large", 1.08f),
    Larger("larger", "Larger", 1.16f);

    companion object {
        fun of(key: String?): FontSizeOption = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * How hard the app's haptic effects hit. It scales the effects the phone can scale, and on phones
 * that cannot it moves each effect up or down one step of the system's own ladder (tick, click,
 * heavy click). [scale] is the multiplier for the first kind.
 */
enum class HapticStrength(val key: String, val scale: Float) {
    Light("light", 0.6f),
    Normal("normal", 1f),
    Strong("strong", 1.4f),
    ;

    companion object {
        fun of(key: String?): HapticStrength = entries.firstOrNull { it.key == key } ?: Normal
    }
}

/**
 * The full appearance configuration: what the Appearance section writes and what [DshTheme] reads.
 *
 * Kept as one value class rather than five loose settings so previews and tests can describe a
 * theme in a single argument, and so adding an option does not change every call site.
 */
data class ThemeCustomization(
    val preference: ThemePreference = ThemePreference.SYSTEM,
    val accent: AccentPalette = AccentPalette.Ink,
    /**
     * The user's own accent colour, as picked, or null when a preset is in force. When set it wins
     * over [accent]; the ramp is derived from it (see [deriveAccentRamp]).
     */
    val customAccent: Color? = null,
    /** True-black canvas for OLED panels; dark scheme only. */
    val amoled: Boolean = false,
    val fontSize: FontSizeOption = FontSizeOption.Default,
    val haptics: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.Normal,
) {
    /**
     * The ramp [DshTheme] paints with, resolved once here so the theme does not care whether it
     * came from a preset or a picked colour. Recomputed by `copy`, because it is derived from the
     * constructor parameters rather than stored beside them.
     */
    val accentRamp: AccentRamp = customAccent?.let(::deriveAccentRamp) ?: accent.ramp

    companion object {
        val Default = ThemeCustomization()
    }
}
