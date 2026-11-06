package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    @Test
    fun `a wide picture fits by its width and a tall one by its height`() {
        assertEquals(400f to 200f, ZoomMath.baseSize(400f, 800f, 2000f, 1000f, fill = false))
        assertEquals(400f to 800f, ZoomMath.baseSize(400f, 800f, 1000f, 2000f, fill = false))
        // A picture smaller than the viewport is scaled up to fit, not left tiny.
        assertEquals(400f to 200f, ZoomMath.baseSize(400f, 800f, 40f, 20f, fill = false))
    }

    @Test
    fun `fill covers the viewport on both axes`() {
        val (w, h) = ZoomMath.baseSize(400f, 800f, 2000f, 1000f, fill = true)
        assertEquals(1600f, w, 0.001f)
        assertEquals(800f, h, 0.001f)
    }

    @Test
    fun `an empty picture or viewport has no size`() {
        assertEquals(0f to 0f, ZoomMath.baseSize(0f, 800f, 10f, 10f, fill = false))
        assertEquals(0f to 0f, ZoomMath.baseSize(400f, 800f, 0f, 10f, fill = true))
    }

    @Test
    fun `panning is only allowed once the picture is bigger than the window`() {
        assertEquals(0f, ZoomMath.maxPan(400f, 400f, 1f), 0f)
        assertEquals(0f, ZoomMath.maxPan(400f, 200f, 1.5f), 0f)
        assertEquals(200f, ZoomMath.maxPan(400f, 400f, 2f), 0.001f)
        assertEquals(500f, ZoomMath.maxPan(400f, 400f, 3.5f), 0.001f)
    }

    @Test
    fun `zooming about the centre does not move the picture`() {
        assertEquals(0f, ZoomMath.zoomAbout(0f, 0f, 1f, 2f), 0.001f)
        assertEquals(30f, ZoomMath.zoomAbout(30f, 30f, 1f, 4f), 0.001f)
    }

    @Test
    fun `zooming about a point keeps that point under the finger`() {
        // A point at +100 from the centre, zoom 1 to 2 with no offset: the picture shifts left by 100.
        val offset = ZoomMath.zoomAbout(0f, 100f, 1f, 2f)
        assertEquals(-100f, offset, 0.001f)
        // The content point that was at +100 is now at offset + 2 * 100 = 100 again.
        assertEquals(100f, offset + 2f * 100f, 0.001f)
    }

    @Test
    fun `zooming back out undoes a zoom in`() {
        val zoomedIn = ZoomMath.zoomAbout(0f, 80f, 1f, 3f)
        val back = ZoomMath.zoomAbout(zoomedIn, 80f, 3f, 1f)
        assertEquals(0f, back, 0.001f)
    }

    @Test
    fun `clamps hold values inside their limits`() {
        assertEquals(50f, ZoomMath.clampPan(80f, 50f), 0f)
        assertEquals(-50f, ZoomMath.clampPan(-80f, 50f), 0f)
        assertEquals(10f, ZoomMath.clampPan(10f, 50f), 0f)
        assertEquals(1f, ZoomMath.clampScale(0.4f, 1f, 8f), 0f)
        assertEquals(8f, ZoomMath.clampScale(12f, 1f, 8f), 0f)
    }
}
