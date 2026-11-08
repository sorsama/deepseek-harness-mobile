package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Turning one picked colour into the full accent ramp the theme paints with.
 *
 * The preset accents each carry a hand-tuned dark and light ramp, because one colour cannot serve
 * both canvases: the pastel that glows on near-black is washed out on paper, and the deep tone that
 * holds on paper goes dead on the dark. A user-picked colour gets the same treatment by rule — the
 * hue and saturation are the user's, the lightness is solved per scheme until the accent is legible
 * where it is used. All of it is pure maths so it can be pinned by tests; nothing here touches
 * Compose state.
 */

/** Smallest contrast the content colour on an accent fill may have (WCAG AA for text). */
const val MIN_ON_ACCENT_CONTRAST = 4.5

/** Smallest contrast an accent may have against the canvas and its own soft tint (WCAG AA, non-text). */
const val MIN_ACCENT_CONTRAST = 3.0

/** Lightness band an accent starts in before it is nudged, per scheme. Keeps the pick recognisable. */
private val DarkLightnessBand = 0.58f..0.82f
private val LightLightnessBand = 0.30f..0.56f

/** One step of the lightness search, and how far a hover state sits from its fill. */
private const val LIGHTNESS_STEP = 0.005f
private const val HOVER_SHIFT = 0.06f

/** Lightness of the soft tint behind accent-coloured content, per scheme. */
private const val DARK_SOFT_LIGHTNESS = 0.12f
private const val LIGHT_SOFT_LIGHTNESS = 0.94f

/**
 * Parse `#RRGGBB` or `RRGGBB` (any case, surrounding whitespace ignored). Anything else — short
 * forms, alpha, stray characters — is rejected rather than guessed at, because the result is
 * persisted and painted across the whole app.
 */
fun parseAccentHex(raw: String?): Color? {
    val digits = raw?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = digits.toInt(16)
    return Color(red = (value shr 16) and 0xFF, green = (value shr 8) and 0xFF, blue = value and 0xFF)
}

/** How far a hex field's text is from being a colour, for feedback while it is being typed. */
enum class HexInput {
    /** Parses; the field may be applied. */
    Valid,

    /** Only hex digits so far, and fewer than six: not an error yet, just not finished. */
    Incomplete,

    /** Cannot become a colour by typing more: a stray character, or too many digits. */
    Invalid,
}

fun classifyHexInput(raw: String): HexInput {
    if (parseAccentHex(raw) != null) return HexInput.Valid
    val digits = raw.trim().removePrefix("#")
    val onlyHex = digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (onlyHex && digits.length < 6) HexInput.Incomplete else HexInput.Invalid
}

/** The canonical stored form: `#RRGGBB`, upper-case, alpha dropped. */
fun Color.toAccentHex(): String =
    "#" + listOf(red, green, blue).joinToString("") { channel ->
        (channel * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    }

/**
 * Derive the dark and light ramps for [base].
 *
 * Per scheme the fill keeps [base]'s hue and saturation and starts from its lightness clamped into
 * a band that reads as an accent there. It is then walked — lighter in the dark scheme, darker in
 * the light one — until all three hold, measured on the 8-bit colour that is actually painted:
 *
 * - the content colour on it (near-black or white, whichever is stronger) reaches
 *   [MIN_ON_ACCENT_CONTRAST];
 * - it reaches [MIN_ACCENT_CONTRAST] against the scheme's canvas, so a link or a toggle track is
 *   visible on it;
 * - it reaches [MIN_ACCENT_CONTRAST] against its own soft tint, so the accent glyph on a selected
 *   card is visible too.
 *
 * Black and white always satisfy all three, so the walk ends; it can never fall off an end.
 */
fun deriveAccentRamp(base: Color): AccentRamp {
    val hsl = base.toHsl()
    val dark = deriveScheme(hsl, dark = true)
    val light = deriveScheme(hsl, dark = false)
    return AccentRamp(
        dark = dark.fill, darkHover = dark.hover, darkSoft = dark.soft, onDark = dark.on,
        light = light.fill, lightHover = light.hover, lightSoft = light.soft, onLight = light.on,
    )
}

private class DerivedScheme(val fill: Color, val hover: Color, val soft: Color, val on: Color)

private fun deriveScheme(hsl: Hsl, dark: Boolean): DerivedScheme {
    val canvas = if (dark) DsDark.bgBase else DsLight.bgBase
    val soft = if (dark) {
        hslToColor(hsl.h, (hsl.s * 0.45f).coerceIn(0f, 0.5f), DARK_SOFT_LIGHTNESS)
    } else {
        hslToColor(hsl.h, (hsl.s * 0.65f).coerceIn(0f, 0.75f), LIGHT_SOFT_LIGHTNESS)
    }
    val step = if (dark) LIGHTNESS_STEP else -LIGHTNESS_STEP
    var lightness = hsl.l.coerceIn(if (dark) DarkLightnessBand else LightLightnessBand)
    var fill = hslToColor(hsl.h, hsl.s, lightness)
    var guard = 0
    while (!legible(fill, canvas, soft) && guard++ < MAX_STEPS) {
        lightness = (lightness + step).coerceIn(0f, 1f)
        fill = hslToColor(hsl.h, hsl.s, lightness)
    }
    val on = contentOn(fill)
    // Hover reads as "a little more": lighter on the dark canvas, deeper on paper. If the nudge
    // would cost the content colour or the canvas contrast its minimum, the state keeps the fill —
    // a hover that is invisible is better than one that fails.
    val hoverShift = if (dark) HOVER_SHIFT else -HOVER_SHIFT
    val hover = hslToColor(hsl.h, hsl.s, (lightness + hoverShift).coerceIn(0f, 1f)).takeIf {
        contrastRatio(it, on) >= MIN_ON_ACCENT_CONTRAST && contrastRatio(it, canvas) >= MIN_ACCENT_CONTRAST
    } ?: fill
    return DerivedScheme(fill = fill, hover = hover, soft = soft, on = on)
}

/** Enough steps to cross the whole lightness range from either end, with slack. */
private const val MAX_STEPS = 260

private fun legible(fill: Color, canvas: Color, soft: Color): Boolean =
    contrastRatio(contentOn(fill), fill) >= MIN_ON_ACCENT_CONTRAST &&
        contrastRatio(fill, canvas) >= MIN_ACCENT_CONTRAST &&
        contrastRatio(fill, soft) >= MIN_ACCENT_CONTRAST

/** The stronger of the two content colours on [fill]: the dark scheme's ink, or white. */
private fun contentOn(fill: Color): Color {
    val ink = DsDark.bgBase
    val white = Color.White
    return if (contrastRatio(ink, fill) >= contrastRatio(white, fill)) ink else white
}

/** WCAG 2.x relative luminance of an sRGB colour. */
fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

/** WCAG 2.x contrast ratio between two opaque colours, 1.0 (identical) to 21.0 (black on white). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

// ---------------------------------------------------------------------------------------------
// Colour models. Hand-rolled rather than android.graphics.Color so they run in plain JVM tests.
// ---------------------------------------------------------------------------------------------

/** Hue in degrees [0, 360), saturation and lightness in [0, 1]. */
internal data class Hsl(val h: Float, val s: Float, val l: Float)

/** Hue in degrees [0, 360), saturation and value in [0, 1] — the model the picker's area uses. */
data class Hsv(val h: Float, val s: Float, val v: Float)

private fun hueOf(r: Float, g: Float, b: Float, max: Float, delta: Float): Float {
    if (delta == 0f) return 0f
    val sector = when (max) {
        r -> ((g - b) / delta).mod(6f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return (sector * 60f).mod(360f)
}

internal fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val delta = hi - lo
    val l = (hi + lo) / 2f
    if (delta == 0f) return Hsl(0f, 0f, l)
    val s = (delta / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
    return Hsl(hueOf(r, g, b, hi, delta), s, l)
}

internal fun hslToColor(h: Float, s: Float, l: Float): Color {
    val chroma = (1f - abs(2f * l - 1f)) * s
    return fromChroma(h, chroma, l - chroma / 2f)
}

fun Color.toHsv(): Hsv {
    val r = red
    val g = green
    val b = blue
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val delta = hi - lo
    return Hsv(h = hueOf(r, g, b, hi, delta), s = if (hi == 0f) 0f else delta / hi, v = hi)
}

fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val chroma = v * s
    return fromChroma(h, chroma, v - chroma)
}

/** Shared tail of both conversions: the hue sector's (r, g, b) from [chroma], lifted by [m]. */
private fun fromChroma(h: Float, chroma: Float, m: Float): Color {
    val sector = h.mod(360f) / 60f
    val x = chroma * (1f - abs(sector.mod(2f) - 1f))
    val (r, g, b) = when (sector.toInt()) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    fun channel(value: Float) = ((value + m) * 255f).roundToInt().coerceIn(0, 255)
    return Color(red = channel(r), green = channel(g), blue = channel(b))
}

/**
 * Starting points offered under the picker. Chosen to span the hue wheel at the saturation and
 * lightness the derivation keeps best, and to avoid the preset accents so a quick pick is a new
 * choice rather than a duplicate of a swatch already on the row.
 */
val AccentQuickPicks: List<Color> = listOf(
    Color(red = 0x3B, green = 0x82, blue = 0xF6), // azure
    Color(red = 0x06, green = 0xB6, blue = 0xD4), // cyan
    Color(red = 0x84, green = 0xCC, blue = 0x16), // lime
    Color(red = 0xFA, green = 0xCC, blue = 0x15), // yellow
    Color(red = 0xF9, green = 0x73, blue = 0x16), // orange
    Color(red = 0xEC, green = 0x48, blue = 0x99), // pink
    Color(red = 0xD9, green = 0x46, blue = 0xEF), // fuchsia
    Color(red = 0x64, green = 0x74, blue = 0x8B), // slate
)
