package com.labteto.dshmobile.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class ListDragSettlementTest {
    @Test fun releaseJustBelowWorkingMinimumKeepsTreeOpen() =
        assertEquals(240f, settleListDrag(220f, 0f, 240f, 320f, 700f))
    @Test fun releaseAtHideThresholdCloses() =
        assertEquals(0f, settleListDrag(192f, 0f, 240f, 320f, 700f))
    @Test fun releaseJustAboveHideThresholdKeepsTreeOpen() =
        assertEquals(240f, settleListDrag(193f, 0f, 240f, 320f, 700f))
    @Test fun slowPartialOpeningReturnsClosed() = assertEquals(0f, settleListDrag(120f, 0f, 240f, 320f, 700f))
    @Test fun slowOpeningKeepsChosenWidth() = assertEquals(290f, settleListDrag(290f, 0f, 240f, 320f, 700f))
    @Test fun quickOpeningRestoresPreviousWidth() = assertEquals(320f, settleListDrag(80f, 1000f, 240f, 320f, 700f))
    @Test fun quickClosingNearMinimumCloses() = assertEquals(0f, settleListDrag(240f, -1000f, 240f, 320f, 700f))
    @Test fun quickResizeOfWidePaneDoesNotClose() = assertEquals(650f, settleListDrag(650f, -1000f, 240f, 700f, 900f))
    @Test fun limitStillReservesChat() = assertEquals(700f, settleListDrag(900f, 0f, 240f, 320f, 700f))
}
