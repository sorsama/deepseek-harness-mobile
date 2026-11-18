package com.labteto.dshmobile.ui.screens.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the "no model provider is ready" bar folds to one line: when it would take too much screen. */
class SetupNoticeTest {

    @Test
    fun `a notice that takes a small share of the screen stays whole`() {
        // 13sp text on a 800dp screen: two or three lines and the button.
        assertFalse(setupNoticeIsCompact(fullHeightDp = 120f, screenHeightDp = 800f))
        assertFalse(setupNoticeIsCompact(fullHeightDp = 150f, screenHeightDp = 800f))
    }

    @Test
    fun `a notice at the limit stays whole and past it folds`() {
        val screen = 800f
        assertFalse(setupNoticeIsCompact(fullHeightDp = screen * SETUP_NOTICE_MAX_SHARE, screenHeightDp = screen))
        assertTrue(setupNoticeIsCompact(fullHeightDp = screen * SETUP_NOTICE_MAX_SHARE + 1f, screenHeightDp = screen))
    }

    @Test
    fun `the words at twice the text size on a phone fold the bar`() {
        // Five lines of 26sp text (about 34dp each) plus the button and the padding: over a fifth of 780dp.
        assertTrue(setupNoticeIsCompact(fullHeightDp = 5 * 34f + 48f + 24f, screenHeightDp = 780f))
    }

    @Test
    fun `a short screen folds sooner and an unknown one never does`() {
        assertTrue(setupNoticeIsCompact(fullHeightDp = 120f, screenHeightDp = 400f))
        assertFalse(setupNoticeIsCompact(fullHeightDp = 500f, screenHeightDp = 0f))
    }
}
