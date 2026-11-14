package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a tap that ends a text selection (issue #90), apart from any gesture plumbing.
 *
 * Getting this wrong has two opposite costs. Too eager and the long press that starts a selection,
 * or a scroll over it, ends it at once. Too slow and the bug is back.
 */
class SelectionDismissTest {
    private val slop = 8f
    private val longPressMs = 400L

    @Test
    fun aQuickStillPressIsATap() {
        assertTrue(isDismissTap(0f, 0f, 60, slop, longPressMs))
        assertTrue(isDismissTap(3f, -4f, 120, slop, longPressMs))
    }

    @Test
    fun movingExactlyToTheSlopIsStillATap() {
        assertTrue(isDismissTap(8f, 0f, 90, slop, longPressMs))
        assertTrue(isDismissTap(0f, -8f, 90, slop, longPressMs))
    }

    @Test
    fun movingPastTheSlopIsAScrollOrADragNotATap() {
        assertFalse(isDismissTap(8.5f, 0f, 90, slop, longPressMs))
        assertFalse(isDismissTap(0f, 40f, 90, slop, longPressMs))
        // The diagonal counts: 6 and 6 is about 8.5 away, past an 8px slop.
        assertFalse(isDismissTap(6f, 6f, 90, slop, longPressMs))
    }

    @Test
    fun aPressHeldForALongPressIsNotATap() {
        // That is the gesture that starts a selection; releasing it must not end the selection.
        assertFalse(isDismissTap(0f, 0f, longPressMs, slop, longPressMs))
        assertFalse(isDismissTap(0f, 0f, longPressMs + 250, slop, longPressMs))
        assertTrue(isDismissTap(0f, 0f, longPressMs - 1, slop, longPressMs))
    }

    @Test
    fun theSecondClickOfAQuickPairIsADoubleTapFollowUp() {
        assertTrue(isDoubleTapFollowUp(120, 300))
        assertTrue(isDoubleTapFollowUp(300, 300))
    }

    @Test
    fun aTapWellAfterTheLastOneIsOnItsOwn() {
        assertFalse(isDoubleTapFollowUp(301, 300))
        assertFalse(isDoubleTapFollowUp(5_000, 300))
    }

    @Test
    fun theFirstTapEverIsNotAFollowUp() {
        // The detector starts "far back" so that the first tap has no predecessor.
        val farBack = Long.MIN_VALUE / 2
        assertFalse(isDoubleTapFollowUp(1_000_000L - farBack, 300))
        // A clock that has not moved cannot make a tap follow itself either.
        assertFalse(isDoubleTapFollowUp(-1, 300))
    }
}
