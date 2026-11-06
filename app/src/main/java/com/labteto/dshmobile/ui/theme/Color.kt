package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DSH Mobile "Ink" design tokens.
 *
 * Ported from the phone mocks in `DSH Mobile — UI Design.html` (screens 01–25, shared UI kit
 * `A · Ink`). The palette is the app's own now rather than a copy of the web GUI's cool-blue
 * neutrals: near-black green-tinted ink for the canvas, one step up for cards, and a single blue
 * accent that states read as "live" instead of decorating.
 *
 * The design ships dark only; [Paper] is the light counterpart, built from the same roles so the
 * two schemes switch without any screen knowing which is on. A warm off-white canvas keeps the
 * restrained, papery feel instead of the clinical white of the previous skin.
 *
 * These are raw values. Screens must not reach for them directly except through
 * [DsColors][com.labteto.dshmobile.ui.theme.DsColors] role tokens, so that a theme preset or an
 * accent choice can restyle the whole app in one place.
 */

// ---- Primitive scales -------------------------------------------------------

object Ds {
    // Ink neutrals (dark). The scale is deliberately cool-green rather than blue: pure grey
    // surfaces read as "dead" next to the accent, and a hint of hue keeps the ink from going flat.
    val Ink00 = Color(0xFFFFFFFF)
    val Ink25 = Color(0xFFF5F6F8)
    val Ink50 = Color(0xFFECEDEF)
    val Ink100 = Color(0xFFDFE1E6)
    val Ink200 = Color(0xFFC9CBD1)
    val Ink300 = Color(0xFFA0A3AB)
    val Ink400 = Color(0xFF80848D)
    val Ink500 = Color(0xFF63666E)
    val Ink600 = Color(0xFF4A4D54)
    val Ink700 = Color(0xFF3A3D46)
    val Ink750 = Color(0xFF2A2C33)
    val Ink800 = Color(0xFF23252C)
    val Ink825 = Color(0xFF1C1E24)
    val Ink850 = Color(0xFF1A1C22)
    val Ink875 = Color(0xFF15161B)
    val Ink900 = Color(0xFF121318)
    val Ink925 = Color(0xFF0F1014)
    val Ink950 = Color(0xFF0B0C0F)
    val Ink975 = Color(0xFF08090B)
    val Ink1000 = Color(0xFF050506)

    // Warm paper scale (light). Not a desaturated copy of the ink scale: the light theme keeps a
    // warm cast so the app still feels like paper under daylight instead of turning sterile white.
    val Paper00 = Color(0xFFFFFFFF)
    val Paper25 = Color(0xFFFCFBF8)
    val Paper50 = Color(0xFFF7F6F2)
    val Paper75 = Color(0xFFF3F1EC)
    val Paper100 = Color(0xFFEFEDE7)
    val Paper150 = Color(0xFFEAE7DF)
    val Paper200 = Color(0xFFE3E0D7)
    val Paper300 = Color(0xFFD5D1C6)
    val Paper400 = Color(0xFFB3AFA3)
    val Paper500 = Color(0xFF9A968D)
    val Paper600 = Color(0xFF7A7770)

    // Caption text on paper. 600 reads 4.1:1 on the canvas, short of the 4.5:1 that 11-12sp text
    // needs, and 650 is already the tertiary label; this sits between them and keeps the caption
    // a shade quieter while clearing 4.5:1 on the canvas and the sheet fill.
    val Paper640 = Color(0xFF716E68)
    val Paper650 = Color(0xFF6F6C66)
    val Paper700 = Color(0xFF5F5C57)
    val Paper800 = Color(0xFF3D3B37)
    val Paper900 = Color(0xFF262420)
    val Paper1000 = Color(0xFF1B1A17)

    // Kept for the components that still read the old primitive names directly (syntax highlighting,
    // the context meter, the running state dot). Values now come from the Ink/Paper scales.
    val Bluish00 = Color(0xFFFFFFFF)
    val Bluish50 = Color(0xFFF7F6F2)
    val Bluish60 = Color(0xFFF3F1EC)
    val Bluish75 = Color(0xFFEFEDE7)
    val Bluish100 = Color(0xFFEAE7DF)
    val Bluish150 = Color(0xFFE9E6DE)
    val Bluish200 = Color(0xFFE3E0D7)
    val Bluish300 = Color(0xFFD5D1C6)
    val Bluish400 = Color(0xFFB3AFA3)
    val Bluish500 = Color(0xFF9A968D)
    val Bluish600 = Color(0xFF7A7770)
    val Bluish700 = Color(0xFF5F5C57)
    val Bluish750 = Color(0xFF3D3B37)
    val Bluish800 = Color(0xFF2A2C33)
    val Bluish850 = Color(0xFF1A1C22)
    val Bluish875 = Color(0xFF15161B)
    val Bluish900 = Color(0xFF121318)
    val Bluish950 = Color(0xFF0B0C0F)
    val Bluish1000 = Color(0xFF050506)

    // Brand blues. "Ink" is the default accent; the rest of the ramp gives hover and soft washes.
    val Deepseek50 = Color(0xFFEFF3FF)
    val Deepseek100 = Color(0xFFE3EBFF)
    val Deepseek200 = Color(0xFFC9D9FF)
    val Deepseek300 = Color(0xFF9DBBFF)
    val Deepseek400 = Color(0xFF82AAFF)
    val Deepseek450 = Color(0xFF6E9BFF)
    val Deepseek500 = Color(0xFF5B8BFA)
    val Deepseek600 = Color(0xFF3D5FE0)
    val Deepseek800 = Color(0xFF1B2A4A)
    val Deepseek900 = Color(0xFF141A2B)

    // Semantic families.
    val Green100 = Color(0xFFE7F6EC)
    val Green400 = Color(0xFF4ADE80)
    val Green500 = Color(0xFF22C55E)
    val Green900 = Color(0xFF12261A)
    val Red50 = Color(0xFFFDF0EE)
    val Red100 = Color(0xFFFDECEA)
    val Red400 = Color(0xFFF87171)
    val Red500 = Color(0xFFEF4444)
    val Red600 = Color(0xFFD92D20)
    val Red900 = Color(0xFF2A1517)
    val Amber100 = Color(0xFFFDF3E2)
    val Amber400 = Color(0xFFF2B04A)
    val Amber500 = Color(0xFFE09A2E)
    val Amber600 = Color(0xFFB45309)
    val Amber900 = Color(0xFF2A2214)

    // Syntax colors. Two ramps because code is the one place where a single scheme-independent
    // palette always looked wrong: pale tokens on near-black, muddy ones on paper.
    val SyntaxConstantLight = Color(0xFF1C7ED6)
    val SyntaxStringLight = Color(0xFF2F9E44)
    val SyntaxCommentLight = Color(0xFF868E96)
    val SyntaxKeywordLight = Color(0xFFD6336C)
    val SyntaxParameterLight = Color(0xFFE8590C)
    val SyntaxFunctionLight = Color(0xFF6741D9)
    val SyntaxPunctuationLight = Color(0xFF495057)
    val SyntaxLinkLight = Color(0xFF1971C2)
    val SyntaxConstantDark = Color(0xFF82AAFF)
    val SyntaxStringDark = Color(0xFF7FD99A)
    val SyntaxCommentDark = Color(0xFF80848D)
    val SyntaxKeywordDark = Color(0xFFC792EA)
    val SyntaxParameterDark = Color(0xFFF2B04A)
    val SyntaxFunctionDark = Color(0xFF82AAFF)
    val SyntaxPunctuationDark = Color(0xFFC9CBD1)
    val SyntaxLinkDark = Color(0xFF9DBBFF)

    // Legacy aliases used by Markdown.kt before the scheme-aware syntax tokens landed. The object
    // has to keep them until that call site moves; they resolve to the dark ramp, the scheme the
    // redesigned app is designed in.
    val SyntaxConstant = SyntaxConstantDark
    val SyntaxString = SyntaxStringDark
    val SyntaxComment = SyntaxCommentDark
    val SyntaxKeyword = SyntaxKeywordDark
    val SyntaxParameter = SyntaxParameterDark
    val SyntaxFunction = SyntaxFunctionDark
    val SyntaxPunctuation = SyntaxPunctuationDark
    val SyntaxLink = SyntaxLinkDark

    // Context meter tints (system / tools / messages).
    val MeterSystem = Color(0xFFA0A3AB)
    val MeterTools = Color(0xFFC792EA)
    val MeterMessages = Color(0xFF6E9BFF)
}

// ---- Semantic aliases: dark (Ink) ------------------------------------------

/** Semantic alias tokens for the dark "Ink" theme. */
object DsDark {
    val bgBase = Ds.Ink950
    val bgLayer1 = Ds.Ink875
    val bgLayer2 = Ds.Ink900
    val bgLayer3 = Ds.Ink850
    val bgModulePlatform = Ds.Ink850
    val borderL1 = Ds.Ink825
    val borderL2 = Ds.Ink800
    val borderL3 = Ds.Ink750
    val brandPrimary = Ds.Ink50
    val onBrandPrimary = Ds.Ink950
    val labelPrimary = Ds.Ink50
    val labelSecondary = Ds.Ink300
    val labelTertiary = Ds.Ink400
    val labelCaption = Ds.Ink500
    val labelDimmed = Ds.Ink700
    val accent = Ds.Deepseek450
    val onAccent = Ds.Ink950
    val accentTertiary = Ds.Deepseek900
    val accentHover = Ds.Deepseek400
    val accentBorder = Color(0xFF233256)
    val hover = Color(0x14FFFFFF)
    val hoverSolid = Ds.Ink850
    val hoverAccent = Color(0x3DFFFFFF)
    val active = Color(0x24FFFFFF)
    val dangerHover = Color(0x26F87171)
    val buttonPrimaryHover = Ds.Ink25
    val buttonPrimaryDimmed = Ds.Ink750
    val buttonInfoFill = Ds.Deepseek450
    val buttonInfoHover = Ds.Ink400
    val error = Ds.Red400
    val errorSecondary = Color(0xFFF25A5A)
    val errorTertiary = Ds.Red900
    val errorBorder = Color(0xFF3A2024)
    val success = Ds.Green400
    val successSecondary = Ds.Green500
    val successTertiary = Ds.Green900
    val successBorder = Color(0xFF1E3A2A)
    val warnLabel = Ds.Amber400
    val warnText = Color(0xFFE8C88A)
    val warn = Ds.Amber400
    val warnSecondary = Color(0xFFF7C36D)
    val warnTertiary = Ds.Amber900
    val warnBorder = Color(0xFF3A3220)
    val toastBg = Ds.Ink750
    val tooltipBg = Ds.Ink850
    val userBubble = Ds.Ink850
    val userBubbleHighlight = Ds.Ink800
    val composerCard = Ds.Ink900
    val sidebar = Ds.Ink950
    val sidebarNavActive = Ds.Ink850
    val sidebarNavAccent = Ds.Deepseek900
    val sidebarNavHover = Ds.Ink875
    val tipSurface = Ds.Ink875
    val codeBlockBg = Ds.Ink900
    val codeBlockBanner = Ds.Ink875
    val inlineCode = Ds.Ink800
    val citation = Ds.Ink850
    val markdownTag = Ds.Ink800
    val overlayMask = Color(0x99000000)
    val syntaxConstant = Ds.SyntaxConstantDark
    val syntaxString = Ds.SyntaxStringDark
    val syntaxComment = Ds.SyntaxCommentDark
    val syntaxKeyword = Ds.SyntaxKeywordDark
    val syntaxParameter = Ds.SyntaxParameterDark
    val syntaxFunction = Ds.SyntaxFunctionDark
    val syntaxPunctuation = Ds.SyntaxPunctuationDark
    val syntaxLink = Ds.SyntaxLinkDark
}

// ---- Semantic aliases: light (Paper) --------------------------------------

/** Semantic alias tokens for the light "Paper" theme. */
object DsLight {
    val bgBase = Ds.Paper50
    val bgLayer1 = Ds.Paper25
    val bgLayer2 = Ds.Paper75
    val bgLayer3 = Ds.Paper150
    val bgModulePlatform = Ds.Paper150
    val borderL1 = Ds.Paper150
    val borderL2 = Ds.Paper200
    val borderL3 = Ds.Paper300
    val brandPrimary = Ds.Paper1000
    val onBrandPrimary = Ds.Paper50
    val labelPrimary = Ds.Paper1000
    val labelSecondary = Ds.Paper700
    val labelTertiary = Ds.Paper650
    val labelCaption = Ds.Paper640
    val labelDimmed = Ds.Paper300
    val accent = Ds.Deepseek600
    val onAccent = Ds.Paper00
    val accentTertiary = Color(0xFFE7ECFB)
    val accentHover = Color(0xFF2F4FCB)
    val accentBorder = Color(0xFFC7D3F7)
    val hover = Color(0x0F26241F)
    val hoverSolid = Ds.Paper100
    val hoverAccent = Color(0x2426241F)
    val active = Color(0x1A26241F)
    val dangerHover = Color(0x0DD92D20)
    val buttonPrimaryHover = Ds.Paper800
    val buttonPrimaryDimmed = Ds.Paper150
    val buttonInfoFill = Ds.Deepseek600
    val buttonInfoHover = Color(0xFF2F4FCB)
    // Text variants, per the Paper contrast table: the saturated fills fail small-text AA on
    // paper, so `error`/`success` here are the readable text tones and the fill tones live in the
    // secondary slots.
    val error = Color(0xFFB4261A)
    val errorSecondary = Ds.Red600
    val errorTertiary = Ds.Red100
    val errorBorder = Color(0xFFF3C9C2)
    val success = Color(0xFF1B7F45)
    val successSecondary = Ds.Green500
    val successTertiary = Ds.Green100
    val successBorder = Color(0xFFBFE3CC)
    val warnLabel = Color(0xFFB45309)
    val warnText = Color(0xFF8A4A06)
    val warn = Color(0xFFC77A0A)
    val warnSecondary = Color(0xFFE09A2E)
    val warnTertiary = Ds.Amber100
    val warnBorder = Color(0xFFF0D8AE)
    val toastBg = Ds.Paper1000
    val tooltipBg = Ds.Paper800
    val userBubble = Ds.Paper150
    val userBubbleHighlight = Ds.Paper200
    val composerCard = Ds.Paper00
    val sidebar = Ds.Paper50
    val sidebarNavActive = Ds.Paper150
    val sidebarNavAccent = Color(0xFFE7ECFB)
    val sidebarNavHover = Ds.Paper100
    val tipSurface = Ds.Paper100
    val codeBlockBg = Ds.Paper75
    val codeBlockBanner = Ds.Paper100
    val inlineCode = Ds.Paper150
    val citation = Color(0xFFE7ECFB)
    val markdownTag = Ds.Paper150
    // Warm-tinted dim rather than pure black: a black scrim on paper reads as a hole in the page.
    val overlayMask = Color(0x731B1A17)
    val syntaxConstant = Ds.SyntaxConstantLight
    val syntaxString = Ds.SyntaxStringLight
    val syntaxComment = Ds.SyntaxCommentLight
    val syntaxKeyword = Ds.SyntaxKeywordLight
    val syntaxParameter = Ds.SyntaxParameterLight
    val syntaxFunction = Ds.SyntaxFunctionLight
    val syntaxPunctuation = Ds.SyntaxPunctuationLight
    val syntaxLink = Ds.SyntaxLinkLight
}
