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
    fun `light caption stays no darker than the tertiary label`() {
        // It is the quieter of the two, so it must not outrank the label above it.
        assertTrue(ratio(DsLight.labelCaption, DsLight.bgBase) <= ratio(DsLight.labelTertiary, DsLight.bgBase))
    }
}
