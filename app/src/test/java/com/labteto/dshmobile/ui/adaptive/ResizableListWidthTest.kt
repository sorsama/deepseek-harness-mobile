package com.labteto.dshmobile.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ResizableListWidthTest {
    private fun planShell(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, hinge: Hinge? = null) =
        com.labteto.dshmobile.ui.adaptive.planShell(width, height, hinge, false)

    @Test fun noPreferenceKeepsTheDefault() {
        val plan = planShell(800.dp, 900.dp)
        assertEquals(plan.listWidth, resolveListWidth(plan, 800.dp, null, false))
    }

    @Test fun aLargeWindowDoesNotHaveAFixedMaximum() {
        val plan = planShell(2560.dp, 1600.dp)
        assertEquals(1800.dp, resolveListWidth(plan, 2560.dp, 1800.dp, false))
    }

    @Test fun theChatKeepsItsMinimum() {
        val plan = planShell(800.dp, 900.dp)
        assertEquals(479.dp, resolveListWidth(plan, 800.dp, 1000.dp, false))
    }

    @Test fun visibleDetailsReserveRoomForTheChatAndBothDividers() {
        val plan = planShell(1600.dp, 1000.dp)
        assertEquals(778.dp, resolveListWidth(plan, 1600.dp, 1400.dp, true))
    }

    @Test fun theListHasAMinimum() {
        val plan = planShell(800.dp, 900.dp)
        assertEquals(240.dp, resolveListWidth(plan, 800.dp, 20.dp, false))
    }

    @Test fun measuredControlsCanAllowANarrowerTree() {
        val plan = planShell(800.dp, 900.dp)
        assertEquals(180.dp, resolveListWidth(plan, 800.dp, 20.dp, false, minimumContentWidth = 180.dp))
    }

    @Test fun measuredMinimumCannotCrowdOutTheChat() {
        val plan = planShell(800.dp, 900.dp)
        assertEquals(479.dp, resolveListWidth(plan, 800.dp, 20.dp, false, minimumContentWidth = 520.dp))
    }

    @Test fun shrinkingDoesNotDestroyThePreferredWidth() {
        val preferred = 1200.dp
        assertEquals(479.dp, resolveListWidth(planShell(800.dp, 900.dp), 800.dp, preferred, false))
        assertEquals(preferred, resolveListWidth(planShell(2000.dp, 1200.dp), 2000.dp, preferred, false))
    }

    @Test fun hiddenDetailsDoNotReserveSpace() {
        val plan = planShell(1600.dp, 1000.dp)
        assertEquals(1279.dp, resolveListWidth(plan, 1600.dp, 1400.dp, false))
    }

    @Test fun compactDoesNotBecomeADockedList() {
        val plan = planShell(412.dp, 915.dp)
        assertEquals(0.dp, resolveListWidth(plan, 412.dp, 300.dp, false))
    }

    @Test fun aSeparatingHingeKeepsItsBoundary() {
        val hinge = Hinge(400f, 0f, 420f, 900f, true, true, true, true)
        val plan = planShell(900.dp, 900.dp, hinge)
        assertEquals(plan.listWidth, resolveListWidth(plan, 900.dp, 600.dp, false))
    }
}
