package com.labteto.dshmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The UI font (Geist, four weights), loaded the way the platform loads fonts.
 *
 * Android reads the TTFs as Android font resources (`sharedUi/src/androidMain/res/font`), exactly
 * as the app always did; iOS reads the same files as Compose resources and hands the bytes to the
 * Skia text engine. The two copies of each file are held byte-identical by a test.
 */
internal expect fun createUiFontFamily(): FontFamily

/** The monospace font (Geist Mono, three weights); see [createUiFontFamily]. */
internal expect fun createCodeFontFamily(): FontFamily

/**
 * The platform text style every [DsType] role carries.
 *
 * Android keeps `includeFontPadding = true` (see the note on [DsType]); other platforms have no such
 * setting and return null.
 */
internal expect fun createPlatformTextStyle(): PlatformTextStyle?

/**
 * DSH Mobile type scale, from the "A · Ink" design kit, with one important local adjustment:
 * line heights are generous enough for the fallback fonts the app actually uses.
 *
 * Geist carries Latin only; Thai, Devanagari, Bengali, Arabic and CJK fall back to Noto. Those
 * fallback faces have taller ascent/descent than Geist, and stacked Thai marks sit above the
 * Latin ascent line. When the line box is set tighter than the fallback's metrics, Compose's text
 * clipping cuts the tops of the glyphs — a Thai title looked "chopped" while the identical English
 * string was fine. So every role below reserves at least ~1.5× its size for the line, and
 * `includeFontPadding` is on so the platform's own ascent/descent padding is not trimmed away.
 */
object DsType {
    val uiFont: FontFamily = createUiFontFamily()

    val codeFont: FontFamily = createCodeFontFamily()

    /** Keeps platform font padding in the line box where the platform has it; see the class note. */
    private val padded: PlatformTextStyle? = createPlatformTextStyle()

    private fun text(
        fontWeight: FontWeight,
        size: Int,
        lineHeight: Int,
        letterSpacing: Float = 0f,
        mono: Boolean = false,
    ) = TextStyle(
        fontFamily = if (mono) codeFont else uiFont,
        fontWeight = fontWeight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
        platformStyle = padded,
    )

    // ---- Display / screen titles -------------------------------------------

    /** Screen hero (design h1, 30px). */
    val display24 = text(FontWeight.SemiBold, 30, 40, letterSpacing = -1.05f)

    /** Sheet / dialog hero (26px). */
    val hero26 = text(FontWeight.SemiBold, 26, 36, letterSpacing = -0.85f)

    /** Section / sheet title (design h2, 20px). */
    val large20 = text(FontWeight.SemiBold, 20, 29, letterSpacing = -0.5f)

    /** Compact top-bar title (design 17px semibold, −0.02em). */
    val title17 = text(FontWeight.SemiBold, 17, 26, letterSpacing = -0.34f)

    /** Uppercase section label (design `.lbl`, 12px + .06em). */
    val label12 = text(FontWeight.Medium, 12, 18, letterSpacing = 0.72f)

    // ---- Body ----------------------------------------------------------------

    val base16 = text(FontWeight.Normal, 16, 26)
    val base16Strong = text(FontWeight.Medium, 16, 26)
    val std14 = text(FontWeight.Normal, 14, 22)
    val std14Strong = text(FontWeight.Medium, 14, 22)
    val rowTitle = text(FontWeight.Medium, 15, 23)
    /** Chat prose / settings row body (design body large, 15px). */
    val body15 = text(FontWeight.Normal, 15, 23)
    val small13 = text(FontWeight.Normal, 13, 20)
    val small13Strong = text(FontWeight.Medium, 13, 20)
    val xsmall12 = text(FontWeight.Normal, 12, 19)
    val caption11 = text(FontWeight.Normal, 11, 17)
    val caption11Strong = text(FontWeight.Medium, 11, 17)

    // ---- Controls / chrome ---------------------------------------------------

    /** Button label, normal (design `.btn`, 14px medium). */
    val button = text(FontWeight.Medium, 14, 20)
    /** Large button label (design `.btn.lg`, 15px semibold). */
    val buttonLarge = text(FontWeight.SemiBold, 15, 22)
    val tabText = text(FontWeight.Medium, 13, 20)
    val dockTitle = text(FontWeight.Medium, 13, 20)
    val statsText = text(FontWeight.Normal, 12, 18, mono = true)
    /** Monospace metadata in cards ("$ npm test -- --run"). */
    val mono13 = text(FontWeight.Normal, 13, 21, mono = true)
    val mono12 = text(FontWeight.Normal, 12, 19, mono = true)

    /** Generic list/meta row paragraph (kept from the pre-Ink scale; several screens still read it). */
    val rowText = text(FontWeight.Normal, 14, 22)

    // ---- Markdown ------------------------------------------------------------

    val mdH1 = text(FontWeight.Bold, 24, 34, letterSpacing = -0.6f)
    val mdH2 = text(FontWeight.Bold, 22, 32, letterSpacing = -0.5f)
    val mdH3 = text(FontWeight.SemiBold, 19, 29, letterSpacing = -0.3f)
    val mdH4 = text(FontWeight.SemiBold, 16, 27)
    val mdBody = text(FontWeight.Normal, 16, 26)
    val mdSmall = text(FontWeight.Normal, 14, 23)
    val mdCode = text(FontWeight.Normal, 13, 22, mono = true)

    // ---- Chat ----------------------------------------------------------------

    val bubbleText = text(FontWeight.Normal, 16, 26)
}

/** Material 3 mapping: sizes follow DsType, colors come from DsColors. */
val DsTypography = Typography(
    displayLarge = DsType.display24,
    displayMedium = DsType.hero26,
    headlineMedium = DsType.large20,
    titleLarge = DsType.large20,
    titleMedium = DsType.rowTitle,
    titleSmall = DsType.std14Strong,
    bodyLarge = DsType.base16,
    bodyMedium = DsType.std14,
    bodySmall = DsType.small13,
    labelLarge = DsType.button,
    labelMedium = DsType.small13Strong,
    labelSmall = DsType.caption11,
)
