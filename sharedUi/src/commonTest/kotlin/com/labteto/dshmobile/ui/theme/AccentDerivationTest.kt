package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNotNull
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test
import kotlin.math.abs

/**
 * The custom accent: parsing what the user types, and the guarantee that whatever they pick comes
 * out legible in both schemes.
 *
 * The guarantees are checked over a sweep rather than a handful of cases, because the failure that
 * matters is the colour nobody thought to try — a pale yellow on paper, a saturated blue on the
 * dark canvas, pure white, pure black, grey.
 */
class AccentDerivationTest {

    private val darkCanvas = DsDark.bgBase
    private val lightCanvas = DsLight.bgBase

    // ----- Parsing -----------------------------------------------------------------------

    @Test
    fun `hex parses with or without the hash in any case`() {
        val expected = Color(red = 0x6E, green = 0x9B, blue = 0xFF)
        assertEquals(expected, parseAccentHex("#6E9BFF"))
        assertEquals(expected, parseAccentHex("6E9BFF"))
        assertEquals(expected, parseAccentHex("#6e9bff"))
        assertEquals(expected, parseAccentHex("  #6e9BfF \n"))
    }

    @Test
    fun `hex that is not exactly six digits is rejected`() {
        listOf(
            null, "", "#", "  ", "#123", "123456789", "#1234567", "#12345", "##6E9BFF",
            "#6E9BFG", "#6E 9BFF", "0x6E9BFF", "#6E9BFF80", "rgb(1,2,3)", "blue",
        ).forEach { assertNull("'$it' must not parse", parseAccentHex(it)) }
    }

    @Test
    fun `typing a hex is incomplete until it is six digits and invalid once it cannot be a colour`() {
        assertEquals(HexInput.Valid, classifyHexInput("#6E9BFF"))
        assertEquals(HexInput.Valid, classifyHexInput("6e9bff"))
        // Mid-typing: not an error to be shown yet.
        listOf("", "#", "#6", "#6E9BF", "6E9B").forEach {
            assertEquals("'$it'", HexInput.Incomplete, classifyHexInput(it))
        }
        // Typing more cannot fix these.
        listOf("#6E9BFG", "#6E9BFF0", "hello", "#12 34", "##123456").forEach {
            assertEquals("'$it'", HexInput.Invalid, classifyHexInput(it))
        }
    }

    @Test
    fun `hex writes back in the canonical upper-case form`() {
        assertEquals("#6E9BFF", Color(red = 0x6E, green = 0x9B, blue = 0xFF).toAccentHex())
        assertEquals("#000000", Color.Black.toAccentHex())
        assertEquals("#FFFFFF", Color.White.toAccentHex())
        assertEquals("#010A0F", Color(red = 1, green = 10, blue = 15).toAccentHex())
        assertEquals("#6E9BFF", parseAccentHex("6e9bff")!!.toAccentHex())
    }

    // ----- The guarantees ----------------------------------------------------------------

    @Test
    fun `every sampled colour yields a legible ramp in both schemes`() {
        samples().forEach { base ->
            val ramp = deriveAccentRamp(base)
            val label = base.toAccentHex()

            assertContrast("$label dark on-colour", ramp.onDark, ramp.dark, MIN_ON_ACCENT_CONTRAST)
            assertContrast("$label dark vs canvas", ramp.dark, darkCanvas, MIN_ACCENT_CONTRAST)
            assertContrast("$label dark vs soft", ramp.dark, ramp.darkSoft, MIN_ACCENT_CONTRAST)
            assertContrast("$label dark hover on-colour", ramp.onDark, ramp.darkHover, MIN_ON_ACCENT_CONTRAST)
            assertContrast("$label dark hover vs canvas", ramp.darkHover, darkCanvas, MIN_ACCENT_CONTRAST)

            assertContrast("$label light on-colour", ramp.onLight, ramp.light, MIN_ON_ACCENT_CONTRAST)
            assertContrast("$label light vs canvas", ramp.light, lightCanvas, MIN_ACCENT_CONTRAST)
            assertContrast("$label light vs soft", ramp.light, ramp.lightSoft, MIN_ACCENT_CONTRAST)
            assertContrast("$label light hover on-colour", ramp.onLight, ramp.lightHover, MIN_ON_ACCENT_CONTRAST)
            assertContrast("$label light hover vs canvas", ramp.lightHover, lightCanvas, MIN_ACCENT_CONTRAST)
        }
    }

    @Test
    fun `the true black canvas is no harder on the accent than the standard one`() {
        // The OLED canvas is darker than the dark scheme's, so a fill that clears the standard
        // canvas clears it with room to spare.
        samples().forEach { base ->
            val ramp = deriveAccentRamp(base)
            assertContrast("${base.toAccentHex()} on black", ramp.dark, Color.Black, MIN_ACCENT_CONTRAST)
        }
    }

    @Test
    fun `the on-colour is always the near-black ink or white`() {
        samples().forEach { base ->
            val ramp = deriveAccentRamp(base)
            listOf(ramp.onDark, ramp.onLight).forEach {
                assertTrue("${base.toAccentHex()} on-colour $it", it == DsDark.bgBase || it == Color.White)
            }
        }
    }

    @Test
    fun `the hover state is a nudge in the right direction or the fill itself`() {
        samples().forEach { base ->
            val ramp = deriveAccentRamp(base)
            val label = base.toAccentHex()
            assertTrue(
                "$label dark hover should be lighter than, or equal to, its fill",
                relativeLuminance(ramp.darkHover) >= relativeLuminance(ramp.dark),
            )
            assertTrue(
                "$label light hover should be darker than, or equal to, its fill",
                relativeLuminance(ramp.lightHover) <= relativeLuminance(ramp.light),
            )
        }
    }

    @Test
    fun `the soft tints stay on their own side of the canvas`() {
        samples().forEach { base ->
            val ramp = deriveAccentRamp(base)
            assertTrue("${base.toAccentHex()} dark soft", relativeLuminance(ramp.darkSoft) < 0.08)
            assertTrue("${base.toAccentHex()} light soft", relativeLuminance(ramp.lightSoft) > 0.75)
        }
    }

    // ----- Character: the pick should still read as the pick -----------------------------

    @Test
    fun `a colour that is already legible in the dark scheme keeps its fill there`() {
        val ink = AccentPalette.Ink.dark
        assertClose(ink, deriveAccentRamp(ink).dark)
        val violet = AccentPalette.Violet.dark
        assertClose(violet, deriveAccentRamp(violet).dark)
    }

    @Test
    fun `a colour that is already legible on paper keeps its fill there`() {
        val ink = AccentPalette.Ink.light
        assertClose(ink, deriveAccentRamp(ink).light)
        val green = AccentPalette.Green.light
        assertClose(green, deriveAccentRamp(green).light)
    }

    @Test
    fun `hue survives in both schemes for a saturated pick`() {
        listOf(0f, 40f, 90f, 150f, 200f, 250f, 300f, 340f).forEach { hue ->
            val base = hsvToColor(hue, 0.8f, 0.9f)
            val ramp = deriveAccentRamp(base)
            assertTrue("hue $hue dark", hueDistance(hue, ramp.dark.toHsv().h) < 8f)
            assertTrue("hue $hue light", hueDistance(hue, ramp.light.toHsv().h) < 8f)
        }
    }

    @Test
    fun `a pale pick is darkened on paper and a deep pick is lifted on the dark canvas`() {
        val pale = parseAccentHex("#FFF2A8")!!
        assertTrue(relativeLuminance(deriveAccentRamp(pale).light) < relativeLuminance(pale))

        val deep = parseAccentHex("#0A1A5C")!!
        assertTrue(relativeLuminance(deriveAccentRamp(deep).dark) > relativeLuminance(deep))
    }

    @Test
    fun `deriving is deterministic`() {
        val base = parseAccentHex("#7A3FF2")!!
        assertEquals(deriveAccentRamp(base), deriveAccentRamp(base))
    }

    // ----- Colour models -----------------------------------------------------------------

    @Test
    fun `hsv and rgb round trip`() {
        samples().forEach { base ->
            val hsv = base.toHsv()
            assertClose(base, hsvToColor(hsv.h, hsv.s, hsv.v), tolerance = 1)
        }
    }

    @Test
    fun `hsl and rgb round trip`() {
        samples().forEach { base ->
            val hsl = base.toHsl()
            assertClose(base, hslToColor(hsl.h, hsl.s, hsl.l), tolerance = 1)
        }
    }

    @Test
    fun `hsv hits the corners of the picker area`() {
        assertEquals(Color.Black, hsvToColor(200f, 0.7f, 0f))
        assertEquals(Color.White, hsvToColor(200f, 0f, 1f))
        assertEquals(Color(red = 255, green = 0, blue = 0), hsvToColor(0f, 1f, 1f))
        assertEquals(Color(red = 0, green = 0, blue = 255), hsvToColor(240f, 1f, 1f))
        // The hue wheel wraps: 360° is red again, and a negative angle lands on the same colour.
        assertEquals(hsvToColor(0f, 1f, 1f), hsvToColor(360f, 1f, 1f))
        assertEquals(hsvToColor(300f, 1f, 1f), hsvToColor(-60f, 1f, 1f))
    }

    @Test
    fun `contrast ratio matches the WCAG reference values`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 1e-9)
        // #767676 on white is the textbook 4.54:1.
        assertEquals(4.54, contrastRatio(Color(red = 0x76, green = 0x76, blue = 0x76), Color.White), 0.01)
        assertEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.White, Color.Black), 1e-12)
    }

    @Test
    fun `the quick picks are valid colours and are derivable`() {
        assertTrue(AccentQuickPicks.size >= 6)
        assertEquals(AccentQuickPicks.size, AccentQuickPicks.toSet().size)
        AccentQuickPicks.forEach { assertNotNull(parseAccentHex(it.toAccentHex())) }
    }

    // ----- Resolution from settings ------------------------------------------------------

    @Test
    fun `a custom colour applies only while the key says custom and the hex parses`() {
        val expected = Color(red = 0x6E, green = 0x9B, blue = 0xFF)
        assertEquals(expected, resolveCustomAccent(CUSTOM_ACCENT_KEY, "#6E9BFF"))
        assertEquals(expected, resolveCustomAccent(CUSTOM_ACCENT_KEY, "6e9bff"))

        // A stored colour behind a preset is kept for the swatch but does not paint.
        assertNull(resolveCustomAccent("ink", "#6E9BFF"))
        assertNull(resolveCustomAccent(null, "#6E9BFF"))
        // Damaged or missing data falls through to the preset path rather than to a bad colour.
        assertNull(resolveCustomAccent(CUSTOM_ACCENT_KEY, null))
        assertNull(resolveCustomAccent(CUSTOM_ACCENT_KEY, ""))
        assertNull(resolveCustomAccent(CUSTOM_ACCENT_KEY, "not-a-colour"))
    }

    @Test
    fun `the key custom is not a preset and falls back to Ink`() {
        assertEquals(AccentPalette.Ink, AccentPalette.of(CUSTOM_ACCENT_KEY))
        assertTrue(AccentPalette.entries.none { it.key == CUSTOM_ACCENT_KEY })
    }

    @Test
    fun `the customization carries the ramp the theme paints with`() {
        // A preset: the ramp is the preset's own.
        assertEquals(AccentPalette.Teal.ramp, ThemeCustomization(accent = AccentPalette.Teal).accentRamp)
        assertEquals(AccentPalette.Ink.ramp, ThemeCustomization.Default.accentRamp)

        // A custom colour wins over the preset and is derived from the pick.
        val pick = parseAccentHex("#D946EF")!!
        val custom = ThemeCustomization(accent = AccentPalette.Teal, customAccent = pick)
        assertEquals(deriveAccentRamp(pick), custom.accentRamp)

        // `copy` re-resolves: clearing the custom colour goes back to the preset.
        assertEquals(AccentPalette.Teal.ramp, custom.copy(customAccent = null).accentRamp)
        assertEquals(deriveAccentRamp(pick), ThemeCustomization.Default.copy(customAccent = pick).accentRamp)
    }

    @Test
    fun `every preset ramp round trips through the value the theme reads`() {
        AccentPalette.entries.forEach { palette ->
            val ramp = palette.ramp
            assertEquals(palette.dark, ramp.fill(dark = true))
            assertEquals(palette.light, ramp.fill(dark = false))
            assertEquals(palette.darkHover, ramp.hover(dark = true))
            assertEquals(palette.lightHover, ramp.hover(dark = false))
            assertEquals(palette.darkSoft, ramp.soft(dark = true))
            assertEquals(palette.lightSoft, ramp.soft(dark = false))
            assertEquals(palette.onDark, ramp.on(dark = true))
            assertEquals(palette.onLight, ramp.on(dark = false))
        }
    }

    // ----- Helpers -----------------------------------------------------------------------

    /** The extremes, the greys, every hue at a spread of saturations and values, and the presets. */
    private fun samples(): List<Color> = buildList {
        add(Color.Black)
        add(Color.White)
        for (v in listOf(0.05f, 0.2f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 1f)) {
            val grey = (v * 255).toInt()
            add(Color(red = grey, green = grey, blue = grey))
        }
        for (hue in 0 until 360 step 15) {
            for (s in listOf(0.1f, 0.35f, 0.65f, 0.9f, 1f)) {
                for (v in listOf(0.12f, 0.3f, 0.5f, 0.7f, 0.85f, 1f)) add(hsvToColor(hue.toFloat(), s, v))
            }
        }
        AccentPalette.entries.forEach { add(it.dark); add(it.light) }
        addAll(AccentQuickPicks)
        add(parseAccentHex("#FFEB3B")!!) // pale yellow: the classic failure on paper
        add(parseAccentHex("#0000FF")!!) // pure blue: dark by luminance, saturated by hue
        add(parseAccentHex("#00FF00")!!)
    }

    private fun assertContrast(what: String, a: Color, b: Color, minimum: Double) {
        val ratio = contrastRatio(a, b)
        assertTrue("$what: contrast $ratio is below $minimum", ratio >= minimum)
    }

    private fun assertClose(expected: Color, actual: Color, tolerance: Int = 3) {
        fun channel(c: Float) = (c * 255f).toInt()
        listOf(
            channel(expected.red) to channel(actual.red),
            channel(expected.green) to channel(actual.green),
            channel(expected.blue) to channel(actual.blue),
        ).forEach { (e, a) ->
            assertTrue("expected ${expected.toAccentHex()} but was ${actual.toAccentHex()}", abs(e - a) <= tolerance)
        }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
