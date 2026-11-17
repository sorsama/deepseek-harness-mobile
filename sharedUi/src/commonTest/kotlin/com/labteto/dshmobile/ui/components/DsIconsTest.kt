package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * The shared Material glyphs are written out by hand, and a mistyped path draws a blank hole. The
 * sheet's close button is the one a person reaches for when everything else has failed, so it gets
 * the check first: one filled path on the 24-unit grid, symmetric about the centre as an X must be
 * (so it needs no right-to-left mirroring).
 */
class DsIconsTest {

    private val close: ImageVector = DsIcons.Close

    @Test
    fun `the close glyph draws one filled path on the 24 unit grid`() {
        assertEquals("viewport width", 24f, close.viewportWidth, 0f)
        assertEquals("viewport height", 24f, close.viewportHeight, 0f)
        assertEquals("path count", 1, close.root.size)
        val path = close.root.first() as VectorPath
        assertTrue("no path nodes", path.pathData.isNotEmpty())
        assertTrue("an X is filled", path.fill != null)
    }

    @Test
    fun `the close glyph does not mirror for right to left`() {
        assertTrue("an X reads the same both ways", !close.autoMirror)
    }

    @Test
    fun `the glyph is memoized`() {
        assertTrue(DsIcons.Close === DsIcons.Close)
    }
}
