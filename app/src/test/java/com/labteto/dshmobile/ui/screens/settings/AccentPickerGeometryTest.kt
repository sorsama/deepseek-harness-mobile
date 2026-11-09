package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the picker's thumbs sit.
 *
 * The bug this guards against: a thumb offset by [thumbCentre]'s arithmetic used to be applied on
 * top of a centre alignment, which pushed it half the control to the right and off the screen, and
 * left it parked there while the finger moved. The contract is simple and worth pinning down —
 * the thumb's centre stays inside `[radius, size - radius]` for every fraction, and it moves with
 * the fraction.
 */
class AccentPickerGeometryTest {

    private val size = 353f
    private val thumb = 26f

    @Test
    fun `the ends sit one radius inside the control so the whole ring is drawn`() {
        assertEquals(thumb / 2f, thumbCentre(0f, size, thumb), 0.001f)
        assertEquals(size - thumb / 2f, thumbCentre(1f, size, thumb), 0.001f)
    }

    @Test
    fun `the middle lands on the middle`() {
        assertEquals(size / 2f, thumbCentre(0.5f, size, thumb), 0.001f)
    }

    @Test
    fun `travel is proportional and never leaves the control`() {
        var previous = Float.NEGATIVE_INFINITY
        for (step in 0..100) {
            val centre = thumbCentre(step / 100f, size, thumb)
            assertTrue("centre $centre must be within the control", centre >= thumb / 2f)
            assertTrue("centre $centre must be within the control", centre <= size - thumb / 2f)
            assertTrue("centre must advance with the fraction", centre > previous)
            previous = centre
        }
        // A quarter of the travel is a quarter of the way across the movable span.
        assertEquals(
            thumb / 2f + (size - thumb) / 4f,
            thumbCentre(0.25f, size, thumb),
            0.001f,
        )
    }

    @Test
    fun `fractions outside zero to one are clamped rather than pushed out`() {
        assertEquals(thumbCentre(0f, size, thumb), thumbCentre(-0.4f, size, thumb), 0.001f)
        assertEquals(thumbCentre(1f, size, thumb), thumbCentre(1.7f, size, thumb), 0.001f)
    }

    @Test
    fun `a control narrower than the thumb centres it instead of producing negative travel`() {
        assertEquals(10f, thumbCentre(0f, 20f, thumb), 0.001f)
        assertEquals(10f, thumbCentre(1f, 20f, thumb), 0.001f)
        assertEquals(size / 2f, thumbCentre(0.3f, size, size), 0.001f)
    }
}
