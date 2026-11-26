package com.labteto.dshmobile.ui.screens.main.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep the Browser tab's web view from outliving its panel: one view, destroyed once,
 * never made again after the close, replaced after its renderer died. A fake page stands in for the
 * WebView, so the sequence of calls is exactly what the platform would see.
 */
class WebPageSlotTest {
    private class FakePage(val id: Int, val log: MutableList<String>) : WebPage {
        var teardowns = 0
        var lastTeardownLostRenderer = false

        override fun pause() {
            log += "pause#$id"
        }

        override fun resume() {
            log += "resume#$id"
        }

        override fun teardown(rendererGone: Boolean) {
            teardowns++
            lastTeardownLostRenderer = rendererGone
            log += "teardown#$id"
        }
    }

    private val log = mutableListOf<String>()
    private val made = mutableListOf<FakePage>()

    private fun slot(): WebPageSlot<FakePage> = WebPageSlot { FakePage(made.size + 1, log).also { made += it } }

    @Test
    fun theFirstUseMakesOneViewAndEveryLaterUseGetsTheSameOne() {
        val slot = slot()
        assertNull(slot.current)
        val first = slot.obtain()
        assertSame(first, slot.obtain())
        assertSame(first, slot.current)
        assertEquals(1, made.size)
    }

    @Test
    fun pauseAndResumeReachTheViewOnce() {
        val slot = slot()
        slot.obtain()
        slot.pause()
        slot.pause()
        slot.resume()
        slot.resume()
        assertEquals(listOf("pause#1", "resume#1"), log)
    }

    @Test
    fun aViewMadeWhilePausedStartsPaused() {
        val slot = slot()
        slot.pause()
        assertEquals(emptyList<String>(), log)
        slot.obtain()
        assertEquals(listOf("pause#1"), log)
    }

    @Test
    fun closingDestroysTheViewExactlyOnce() {
        val slot = slot()
        val page = slot.obtain()!!
        slot.close()
        slot.close()
        assertEquals(1, page.teardowns)
        assertFalse(page.lastTeardownLostRenderer)
        assertTrue(slot.closed)
        assertNull(slot.current)
    }

    @Test
    fun nothingIsMadeOrTouchedAfterTheClose() {
        val slot = slot()
        slot.obtain()
        slot.close()
        log.clear()
        assertNull(slot.obtain())
        slot.pause()
        slot.resume()
        assertEquals(1, made.size)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun closingBeforeAnyViewExistedStillRefusesToMakeOne() {
        val slot = slot()
        slot.close()
        assertNull(slot.obtain())
        assertEquals(0, made.size)
    }

    @Test
    fun aLostRendererIsTornDownAtOnceAndTheNextUseMakesANewView() {
        val slot = slot()
        val first = slot.obtain()!!
        assertTrue(slot.rendererGone(first))
        assertEquals(1, first.teardowns)
        assertTrue(first.lastTeardownLostRenderer)
        assertNull(slot.current)
        val second = slot.obtain()!!
        assertNotSame(first, second)
        assertEquals(2, made.size)
        assertSame(second, slot.current)
    }

    @Test
    fun aRendererReportedTwiceOrByAReplacedViewChangesNothing() {
        val slot = slot()
        val first = slot.obtain()!!
        assertTrue(slot.rendererGone(first))
        assertFalse(slot.rendererGone(first))
        val second = slot.obtain()!!
        assertFalse(slot.rendererGone(first))
        assertEquals(1, first.teardowns)
        assertEquals(0, second.teardowns)
        assertSame(second, slot.current)
    }

    @Test
    fun aRendererLostAfterTheCloseIsNotTornDownAgain() {
        val slot = slot()
        val page = slot.obtain()!!
        slot.close()
        assertFalse(slot.rendererGone(page))
        assertEquals(1, page.teardowns)
    }

    @Test
    fun eachViewIsDestroyedOnceAcrossALossAndAClose() {
        val slot = slot()
        val first = slot.obtain()!!
        slot.rendererGone(first)
        val second = slot.obtain()!!
        slot.close()
        slot.close()
        assertEquals(1, first.teardowns)
        assertEquals(1, second.teardowns)
        assertFalse(second.lastTeardownLostRenderer)
    }

    @Test
    fun aViewMadeAfterALossKeepsThePausedState() {
        val slot = slot()
        val first = slot.obtain()!!
        slot.pause()
        slot.rendererGone(first)
        log.clear()
        slot.obtain()
        assertEquals(listOf("pause#2"), log)
    }

    @Test
    fun pausingNeverReachesAViewThatIsGone() {
        val slot = slot()
        val first = slot.obtain()!!
        slot.rendererGone(first)
        log.clear()
        slot.pause()
        slot.resume()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun aViewThatCannotBeMadeGivesNullAndTheNextTryMayWork() {
        var attempts = 0
        val slot = WebPageSlot {
            attempts++
            if (attempts == 1) throw IllegalStateException("no WebView on this phone")
            FakePage(attempts, log)
        }
        assertNull(slot.obtain())
        assertNull(slot.current)
        assertFalse(slot.closed)
        assertEquals(2, slot.obtain()?.id)
    }
}
