package com.labteto.dshmobile.ui.theme

import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * Caption text is 11-12sp, so it needs the 4.5:1 of normal text. On paper it was 2.7:1 and carried
 * about sixty text sites (hints, timestamps, stats, line numbers).
 */
class CaptionContrastTest {

    private fun ratio(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) =
        contrastRatio(fg, bg)

    @Test
    fun `light caption clears 4_5 on the canvas and on the card and sheet fills`() {
        val caption = DsLight.labelCaption
        assertTrue("canvas ${ratio(caption, DsLight.bgBase)}", ratio(caption, DsLight.bgBase) >= 4.5)
        assertTrue("card ${ratio(caption, DsLight.bgLayer1)}", ratio(caption, DsLight.bgLayer1) >= 4.5)
        assertTrue("sheet ${ratio(caption, DsLight.bgLayer2)}", ratio(caption, DsLight.bgLayer2) >= 4.5)
    }

    @Test
    fun `dark caption clears 4_5 on every dark fill`() {
        val caption = DsDark.labelCaption
        listOf(
            "canvas" to DsDark.bgBase,
            "card" to DsDark.bgLayer1,
            "sheet" to DsDark.bgLayer2,
            "layer3" to DsDark.bgLayer3,
            "composer" to DsDark.composerCard,
        ).forEach { (name, fill) ->
            assertTrue("$name ${ratio(caption, fill)}", ratio(caption, fill) >= 4.5)
        }
    }

    @Test
    fun `dark caption clears 4_5 on the amoled fills`() {
        // The AMOLED canvas (Theme.kt) only darkens the fills, so this holds with room to spare;
        // the values are repeated here because that mapping is private.
        val caption = DsDark.labelCaption
        listOf(
            "canvas" to androidx.compose.ui.graphics.Color(0xFF000000),
            "card" to androidx.compose.ui.graphics.Color(0xFF0E0F12),
            "sheet and composer" to androidx.compose.ui.graphics.Color(0xFF0A0B0D),
            "layer3" to androidx.compose.ui.graphics.Color(0xFF16181D),
        ).forEach { (name, fill) ->
            assertTrue("amoled $name ${ratio(caption, fill)}", ratio(caption, fill) >= 4.5)
        }
    }

    @Test
    fun `dark caption stays no brighter than the tertiary label`() {
        assertTrue(ratio(DsDark.labelCaption, DsDark.bgBase) <= ratio(DsDark.labelTertiary, DsDark.bgBase))
    }

    @Test
    fun `light caption stays no darker than the tertiary label`() {
        // It is the quieter of the two, so it must not outrank the label above it.
        assertTrue(ratio(DsLight.labelCaption, DsLight.bgBase) <= ratio(DsLight.labelTertiary, DsLight.bgBase))
    }
}
