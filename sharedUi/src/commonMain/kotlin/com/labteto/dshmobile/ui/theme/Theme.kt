package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.labteto.dshmobile.ui.components.HapticPlatform
import com.labteto.dshmobile.ui.components.LocalHapticPlatform

/** Full DSH Mobile semantic palette for one scheme. */
data class DsColors(
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgModulePlatform: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val brandPrimary: Color,
    val onBrandPrimary: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val accent: Color,
    val onAccent: Color,
    val accentTertiary: Color,
    val accentHover: Color,
    val accentBorder: Color,
    val hover: Color,
    val hoverSolid: Color,
    val hoverAccent: Color,
    val active: Color,
    val dangerHover: Color,
    val buttonPrimaryHover: Color,
    val buttonPrimaryDimmed: Color,
    val buttonInfoFill: Color,
    val buttonInfoHover: Color,
    val error: Color,
    val errorSecondary: Color,
    val errorTertiary: Color,
    val errorBorder: Color,
    val success: Color,
    val successSecondary: Color,
    val successTertiary: Color,
    val successBorder: Color,
    val warnLabel: Color,
    val warnText: Color = warnLabel,
    val warn: Color,
    val warnSecondary: Color,
    val warnTertiary: Color,
    val warnBorder: Color,
    val toastBg: Color,
    val tooltipBg: Color,
    val userBubble: Color,
    val userBubbleHighlight: Color,
    val composerCard: Color,
    val sidebar: Color,
    val sidebarNavActive: Color,
    val sidebarNavAccent: Color,
    val sidebarNavHover: Color,
    val tipSurface: Color,
    val codeBlockBg: Color,
    val codeBlockBanner: Color,
    val inlineCode: Color,
    val citation: Color,
    val markdownTag: Color,
    val overlayMask: Color,
    // Content colours for the two surfaces whose fill does not invert with the scheme: the toast is
    // always a dark plate, and the error fill is saturated in both schemes.
    val onToast: Color = labelPrimary,
    val onError: Color = Color.White,
    /** Ink text that sits on the amber "attention" fill; amber is the one fill that stays light in
     *  both schemes, so its content colour must too. */
    val onWarn: Color = Color(0xFF1A1205),
    // Scheme-aware syntax highlighting. Defaults keep older constructors source-compatible.
    val syntaxConstant: Color = Ds.SyntaxConstant,
    val syntaxString: Color = Ds.SyntaxString,
    val syntaxComment: Color = Ds.SyntaxComment,
    val syntaxKeyword: Color = Ds.SyntaxKeyword,
    val syntaxParameter: Color = Ds.SyntaxParameter,
    val syntaxFunction: Color = Ds.SyntaxFunction,
    val syntaxPunctuation: Color = Ds.SyntaxPunctuation,
    val syntaxLink: Color = Ds.SyntaxLink,
)

object DsThemeTokens {
    val light = DsColors(
        bgBase = DsLight.bgBase, bgLayer1 = DsLight.bgLayer1, bgLayer2 = DsLight.bgLayer2,
        bgLayer3 = DsLight.bgLayer3, bgModulePlatform = DsLight.bgModulePlatform,
        borderL1 = DsLight.borderL1, borderL2 = DsLight.borderL2, borderL3 = DsLight.borderL3,
        brandPrimary = DsLight.brandPrimary, onBrandPrimary = DsLight.onBrandPrimary,
        labelPrimary = DsLight.labelPrimary, labelSecondary = DsLight.labelSecondary,
        labelTertiary = DsLight.labelTertiary, labelCaption = DsLight.labelCaption,
        labelDimmed = DsLight.labelDimmed,
        accent = DsLight.accent, onAccent = DsLight.onAccent,
        accentTertiary = DsLight.accentTertiary, accentHover = DsLight.accentHover,
        accentBorder = DsLight.accentBorder,
        hover = DsLight.hover, hoverSolid = DsLight.hoverSolid, hoverAccent = DsLight.hoverAccent,
        active = DsLight.active, dangerHover = DsLight.dangerHover,
        buttonPrimaryHover = DsLight.buttonPrimaryHover, buttonPrimaryDimmed = DsLight.buttonPrimaryDimmed,
        buttonInfoFill = DsLight.buttonInfoFill, buttonInfoHover = DsLight.buttonInfoHover,
        error = DsLight.error, errorSecondary = DsLight.errorSecondary, errorTertiary = DsLight.errorTertiary,
        errorBorder = DsLight.errorBorder,
        success = DsLight.success, successSecondary = DsLight.successSecondary,
        successTertiary = DsLight.successTertiary, successBorder = DsLight.successBorder,
        warnLabel = DsLight.warnLabel, warnText = DsLight.warnText, warn = DsLight.warn,
        warnSecondary = DsLight.warnSecondary, warnTertiary = DsLight.warnTertiary,
        warnBorder = DsLight.warnBorder,
        toastBg = DsLight.toastBg, tooltipBg = DsLight.tooltipBg,
        userBubble = DsLight.userBubble, userBubbleHighlight = DsLight.userBubbleHighlight,
        composerCard = DsLight.composerCard,
        sidebar = DsLight.sidebar, sidebarNavActive = DsLight.sidebarNavActive,
        sidebarNavAccent = DsLight.sidebarNavAccent, sidebarNavHover = DsLight.sidebarNavHover,
        tipSurface = DsLight.tipSurface,
        codeBlockBg = DsLight.codeBlockBg, codeBlockBanner = DsLight.codeBlockBanner,
        inlineCode = DsLight.inlineCode, citation = DsLight.citation,
        markdownTag = DsLight.markdownTag, overlayMask = DsLight.overlayMask,
        onToast = Ds.Paper50, onError = Color.White, onWarn = Ds.Paper00,
        syntaxConstant = DsLight.syntaxConstant, syntaxString = DsLight.syntaxString,
        syntaxComment = DsLight.syntaxComment, syntaxKeyword = DsLight.syntaxKeyword,
        syntaxParameter = DsLight.syntaxParameter, syntaxFunction = DsLight.syntaxFunction,
        syntaxPunctuation = DsLight.syntaxPunctuation, syntaxLink = DsLight.syntaxLink,
    )

    val dark = DsColors(
        bgBase = DsDark.bgBase, bgLayer1 = DsDark.bgLayer1, bgLayer2 = DsDark.bgLayer2,
        bgLayer3 = DsDark.bgLayer3, bgModulePlatform = DsDark.bgModulePlatform,
        borderL1 = DsDark.borderL1, borderL2 = DsDark.borderL2, borderL3 = DsDark.borderL3,
        brandPrimary = DsDark.brandPrimary, onBrandPrimary = DsDark.onBrandPrimary,
        labelPrimary = DsDark.labelPrimary, labelSecondary = DsDark.labelSecondary,
        labelTertiary = DsDark.labelTertiary, labelCaption = DsDark.labelCaption,
        labelDimmed = DsDark.labelDimmed,
        accent = DsDark.accent, onAccent = DsDark.onAccent,
        accentTertiary = DsDark.accentTertiary, accentHover = DsDark.accentHover,
        accentBorder = DsDark.accentBorder,
        hover = DsDark.hover, hoverSolid = DsDark.hoverSolid, hoverAccent = DsDark.hoverAccent,
        active = DsDark.active, dangerHover = DsDark.dangerHover,
        buttonPrimaryHover = DsDark.buttonPrimaryHover, buttonPrimaryDimmed = DsDark.buttonPrimaryDimmed,
        buttonInfoFill = DsDark.buttonInfoFill, buttonInfoHover = DsDark.buttonInfoHover,
        error = DsDark.error, errorSecondary = DsDark.errorSecondary, errorTertiary = DsDark.errorTertiary,
        errorBorder = DsDark.errorBorder,
        success = DsDark.success, successSecondary = DsDark.successSecondary,
        successTertiary = DsDark.successTertiary, successBorder = DsDark.successBorder,
        warnLabel = DsDark.warnLabel, warnText = DsDark.warnText, warn = DsDark.warn,
        warnSecondary = DsDark.warnSecondary, warnTertiary = DsDark.warnTertiary,
        warnBorder = DsDark.warnBorder,
        toastBg = DsDark.toastBg, tooltipBg = DsDark.tooltipBg,
        userBubble = DsDark.userBubble, userBubbleHighlight = DsDark.userBubbleHighlight,
        composerCard = DsDark.composerCard,
        sidebar = DsDark.sidebar, sidebarNavActive = DsDark.sidebarNavActive,
        sidebarNavAccent = DsDark.sidebarNavAccent, sidebarNavHover = DsDark.sidebarNavHover,
        tipSurface = DsDark.tipSurface,
        codeBlockBg = DsDark.codeBlockBg, codeBlockBanner = DsDark.codeBlockBanner,
        inlineCode = DsDark.inlineCode, citation = DsDark.citation,
        markdownTag = DsDark.markdownTag, overlayMask = DsDark.overlayMask,
        onToast = Ds.Ink50, onError = Color(0xFF240A0A), onWarn = Color(0xFF1A1205),
        syntaxConstant = DsDark.syntaxConstant, syntaxString = DsDark.syntaxString,
        syntaxComment = DsDark.syntaxComment, syntaxKeyword = DsDark.syntaxKeyword,
        syntaxParameter = DsDark.syntaxParameter, syntaxFunction = DsDark.syntaxFunction,
        syntaxPunctuation = DsDark.syntaxPunctuation, syntaxLink = DsDark.syntaxLink,
    )
}

/**
 * Re-accent a palette. Soft/border tints are recomputed from the new accent, not left behind.
 *
 * Takes a resolved [AccentRamp] so a preset and a derived custom colour are the same thing here.
 * Public because the custom-accent picker (in the app module) previews a ramp in the scheme the app
 * is *not* in.
 */
fun DsColors.withAccent(accent: AccentRamp, dark: Boolean): DsColors {
    val fill = accent.fill(dark)
    val hover = accent.hover(dark)
    val soft = accent.soft(dark)
    val on = accent.on(dark)
    val border = fill.copy(alpha = 0.34f).compositeOver(soft)
    return copy(
        accent = fill,
        accentHover = hover,
        accentTertiary = soft,
        onAccent = on,
        accentBorder = border,
        buttonInfoFill = fill,
        buttonInfoHover = hover,
        sidebarNavAccent = soft,
        citation = soft,
    )
}

/**
 * The true-black canvas for OLED panels.
 *
 * Only the canvas rungs move: cards stay one step up so elevation still reads, and the brand/tint
 * colours are untouched — a "black theme" that also darkens the accent just looks like a bug.
 */
private fun DsColors.amoled(): DsColors = copy(
    bgBase = Color(0xFF000000),
    bgLayer1 = Color(0xFF0E0F12),
    bgLayer2 = Color(0xFF0A0B0D),
    bgLayer3 = Color(0xFF16181D),
    composerCard = Color(0xFF0A0B0D),
    sidebar = Color(0xFF000000),
    codeBlockBg = Color(0xFF08090B),
    codeBlockBanner = Color(0xFF0E0F12),
    toastBg = Color(0xFF1C1E24),
)

/** The full palette as a CompositionLocal. */
val LocalDsColors = staticCompositionLocalOf { DsThemeTokens.light }

/** The active appearance configuration, for screens that show what is selected. */
val LocalThemeCustomization = staticCompositionLocalOf { ThemeCustomization.Default }

/** Whether the user wants touch feedback; read by [rememberDsHaptics]. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** How strong the user wants touch feedback; read by [rememberDsHaptics]. */
val LocalHapticStrength = staticCompositionLocalOf { HapticStrength.Normal }

object DsTheme {
    val colors: DsColors
        @Composable get() = LocalDsColors.current

    val appearance: ThemeCustomization
        @Composable get() = LocalThemeCustomization.current
}

private fun materialLightScheme(c: DsColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentTertiary,
    onPrimaryContainer = c.accent,
    secondary = c.labelSecondary,
    onSecondary = c.bgBase,
    secondaryContainer = c.accentTertiary,
    onSecondaryContainer = c.labelPrimary,
    tertiary = c.warn,
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgLayer1,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgModulePlatform,
    onSurfaceVariant = c.labelSecondary,
    surfaceContainerLowest = c.bgBase,
    surfaceContainerLow = c.bgLayer1,
    surfaceContainer = c.bgLayer1,
    surfaceContainerHigh = c.bgLayer2,
    surfaceContainerHighest = c.bgLayer3,
    outline = c.borderL2,
    outlineVariant = c.borderL1,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorTertiary,
    onErrorContainer = c.error,
    inverseSurface = c.toastBg,
    inverseOnSurface = c.onToast,
    scrim = c.overlayMask,
)

private fun materialDarkScheme(c: DsColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentTertiary,
    onPrimaryContainer = c.accent,
    secondary = c.labelSecondary,
    onSecondary = c.bgBase,
    secondaryContainer = c.accentTertiary,
    onSecondaryContainer = c.labelPrimary,
    tertiary = c.warn,
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgLayer1,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgModulePlatform,
    onSurfaceVariant = c.labelSecondary,
    surfaceContainerLowest = c.bgBase,
    surfaceContainerLow = c.bgLayer1,
    surfaceContainer = c.bgLayer1,
    surfaceContainerHigh = c.bgLayer2,
    surfaceContainerHighest = c.bgLayer3,
    outline = c.borderL2,
    outlineVariant = c.borderL1,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorTertiary,
    onErrorContainer = c.error,
    inverseSurface = c.toastBg,
    inverseOnSurface = c.onToast,
    scrim = c.overlayMask,
)

/** True when the active appearance resolves to the dark scheme right now. */
@Composable
fun isDsDark(customization: ThemeCustomization): Boolean = when (customization.preference) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
}

/**
 * The DSH Mobile theme: Ink in the dark, Paper in the light, with the user's accent, OLED canvas,
 * text size and haptics applied on top.
 *
 * Text size is applied by scaling the local [Density]'s font scale rather than by regenerating
 * every text style: every `sp` in the app (including Material components and the user's system
 * font size) then scales together, and no screen has to be aware that the setting exists.
 *
 * [hapticPlatform] is the host's half of the haptic vocabulary (Android's engine, iOS's feedback
 * generators). Left null, whatever an enclosing composition provided stays in force, and with none
 * at all the app is silent.
 */
@Composable
fun DshTheme(
    customization: ThemeCustomization = ThemeCustomization.Default,
    hapticPlatform: HapticPlatform? = null,
    content: @Composable () -> Unit,
) {
    val dark = isDsDark(customization)
    val ds = (if (dark) DsThemeTokens.dark else DsThemeTokens.light)
        .withAccent(customization.accentRamp, dark)
        .let { if (dark && customization.amoled) it.amoled() else it }
    val scheme = if (dark) materialDarkScheme(ds) else materialLightScheme(ds)
    val density = LocalDensity.current
    val scaledDensity = Density(density = density.density, fontScale = density.fontScale * customization.fontSize.scale)
    CompositionLocalProvider(
        LocalDsColors provides ds,
        LocalThemeCustomization provides customization,
        LocalHapticsEnabled provides customization.haptics,
        LocalHapticStrength provides customization.hapticStrength,
        LocalHapticPlatform provides (hapticPlatform ?: LocalHapticPlatform.current),
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DsTypography,
            shapes = DsMaterialShapes,
            content = content,
        )
    }
}

/** Convenience for previews that only care about the scheme. */
@Composable
fun DshTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) = DshTheme(customization = ThemeCustomization(preference = preference), content = content)
