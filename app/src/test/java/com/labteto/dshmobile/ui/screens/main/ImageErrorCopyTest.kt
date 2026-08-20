package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The size a limit is reported at.
 *
 * The harness writes its own attachment bounds this way, and the two sides of a refusal have to
 * agree: an image the client turns away for exceeding "3.5MB" must not come back from the host
 * named as 3670016 bytes, or the same limit reads as two different ones.
 */
class ImageErrorCopyTest {

    @Test
    fun `a whole number of megabytes loses its decimal`() {
        assertEquals("100MB", imageSizeText(104_857_600))
        assertEquals("5MB", imageSizeText(5_242_880))
    }

    @Test
    fun `a fractional size keeps one place`() {
        // The shipped per-image cap at harness 0.1.0-rc.8.
        assertEquals("3.5MB", imageSizeText(3_670_016))
    }
}
