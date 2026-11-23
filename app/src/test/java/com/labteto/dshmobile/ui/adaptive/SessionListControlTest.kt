package com.labteto.dshmobile.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListControlTest {
    @Test fun closedOverlayOffersToShowTheList() {
        assertEquals(
            ListControl.Expand,
            sessionListControl(listDocked = false, overlayList = true, drawerOpen = false, canCollapse = true, listShown = false),
        )
    }

    @Test fun openOverlayOffersToHideTheList() {
        assertEquals(
            ListControl.Collapse,
            sessionListControl(listDocked = false, overlayList = true, drawerOpen = true, canCollapse = true, listShown = true),
        )
    }

    @Test fun collapsedDockedListOffersToShowAgain() {
        assertEquals(
            ListControl.Expand,
            sessionListControl(listDocked = true, overlayList = false, drawerOpen = false, canCollapse = true, listShown = false),
        )
    }

    @Test fun phoneKeepsTheOriginalDrawerControl() {
        assertEquals(
            ListControl.OpenDrawer,
            sessionListControl(listDocked = false, overlayList = false, drawerOpen = false, canCollapse = true, listShown = false),
        )
    }
}
