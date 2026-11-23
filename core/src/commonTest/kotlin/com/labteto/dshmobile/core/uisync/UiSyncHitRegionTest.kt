package com.labteto.dshmobile.core.uisync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CSS-pixel rectangles from the page, turned into view pixels, and who gets each touch. */
class UiSyncHitRegionTest {

    private val pet = UiSyncRect("pet", x = 294.0, y = 631.0, w = 72.0, h = 73.0)
    private val treat = UiSyncRect("treat", x = 300.0, y = 760.0, w = 80.0, h = 36.0)

    @Test
    fun `rectangles scale by the page dpr into view pixels`() {
        val region = UiSyncHitRegion.of(UiSyncPageMessage.HitRects(dpr = 2.0, viewport = null, rects = listOf(pet)), fallbackScale = 3f)
        assertEquals(1, region.count)
        assertEquals(listOf(588f, 1262f, 732f, 1408f), region.bounds(0))
        assertTrue(region.contains(588f, 1262f))
        assertTrue(region.contains(731.9f, 1407.9f))
        // Half open: the right and bottom edges belong to the chat below.
        assertFalse(region.contains(732f, 1300f))
        assertFalse(region.contains(600f, 1408f))
        assertFalse(region.contains(587.9f, 1300f))
    }

    @Test
    fun `the display density stands in when the page sent no dpr`() {
        val region = UiSyncHitRegion.of(UiSyncPageMessage.HitRects(dpr = null, viewport = null, rects = listOf(pet)), fallbackScale = 2.625f)
        assertEquals(listOf(294f * 2.625f, 631f * 2.625f, 366f * 2.625f, 704f * 2.625f), region.bounds(0))
    }

    @Test
    fun `a point inside any rectangle is inside the region`() {
        val region = UiSyncHitRegion.of(listOf(pet, treat), scale = 1f)
        assertTrue(region.contains(300f, 700f))
        assertTrue(region.contains(379f, 795f))
        assertFalse(region.contains(10f, 10f))
        assertFalse(region.contains(200f, 780f))
    }

    @Test
    fun `nothing is inside an empty region or at a point that is not a number`() {
        assertTrue(UiSyncHitRegion.Empty.isEmpty)
        assertFalse(UiSyncHitRegion.Empty.contains(0f, 0f))
        val region = UiSyncHitRegion.of(listOf(pet), 1f)
        assertFalse(region.contains(Float.NaN, 650f))
        assertFalse(region.contains(300f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `an unusable scale or empty rectangles give the empty region`() {
        assertSame(UiSyncHitRegion.Empty, UiSyncHitRegion.of(listOf(pet), 0f))
        assertSame(UiSyncHitRegion.Empty, UiSyncHitRegion.of(listOf(pet), Float.NaN))
        assertSame(UiSyncHitRegion.Empty, UiSyncHitRegion.of(emptyList(), 2f))
        val flat = UiSyncRect("flat", 10.0, 10.0, 1e-9, 5.0)
        assertSame(UiSyncHitRegion.Empty, UiSyncHitRegion.of(listOf(flat), 1f))
        val huge = UiSyncRect("huge", 1e39, 0.0, 10.0, 10.0)
        assertSame(UiSyncHitRegion.Empty, UiSyncHitRegion.of(listOf(huge), 1f))
    }

    @Test
    fun `a gesture that starts on an item goes to the web view whole`() {
        val router = UiSyncTouchRouter()
        router.region = UiSyncHitRegion.of(listOf(pet), 1f)
        router.enabled = true
        assertTrue(router.onDown(300f, 650f))
        assertTrue(router.gestureToWeb)
        router.onGestureEnd()
        assertFalse(router.gestureToWeb)
    }

    @Test
    fun `a gesture that starts outside every item stays native`() {
        val router = UiSyncTouchRouter()
        router.region = UiSyncHitRegion.of(listOf(pet, treat), 1f)
        router.enabled = true
        // The composer, the transcript, the top bar: all outside the plugin items.
        assertFalse(router.onDown(100f, 780f))
        assertFalse(router.onDown(200f, 300f))
        assertFalse(router.gestureToWeb)
    }

    @Test
    fun `a layer that is not enabled takes nothing even on an item`() {
        val router = UiSyncTouchRouter()
        router.region = UiSyncHitRegion.of(listOf(pet), 1f)
        assertFalse(router.onDown(300f, 650f))
        assertFalse(router.claims(300f, 650f))
        router.enabled = true
        assertTrue(router.claims(300f, 650f))
        router.region = UiSyncHitRegion.Empty
        assertFalse(router.claims(300f, 650f))
    }
}
