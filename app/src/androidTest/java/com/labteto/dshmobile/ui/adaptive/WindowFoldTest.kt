package com.labteto.dshmobile.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * A fold reaching the planner through the real path: WindowManager's `WindowInfoTracker`, as the
 * activity sees it. WindowManager's testing rule publishes whatever layout info the test chooses,
 * so a Flip propped like a laptop and a book foldable half open are driven into a phone-sized
 * emulator, and what comes out the other side is what the shell would plan on those devices.
 */
class WindowFoldTest {
    private val publisher = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(publisher).around(compose)

    private var hinge by mutableStateOf<Hinge?>(null)
    private var plan by mutableStateOf<ShellPlan?>(null)

    private fun host() {
        compose.setContent {
            hinge = rememberHinge()
            BoxWithConstraints {
                plan = planShell(maxWidth, maxHeight, hinge, rtl = false)
            }
        }
    }

    private fun publish(vararg features: FoldingFeature) {
        compose.runOnUiThread { }
        publisher.overrideWindowLayoutInfo(WindowLayoutInfo(features.toList()))
        compose.waitForIdle()
    }

    @Test fun noFoldMeansNoHinge() {
        host()
        publish()
        assertNull(hinge)
        assertEquals(Posture.Flat, plan!!.posture)
    }

    @Test fun aHalfOpenHorizontalFoldIsATabletopHinge() {
        host()
        publish(
            FoldingFeature(
                activity = compose.activity,
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.HORIZONTAL,
            ),
        )
        val h = hinge
        assertNotNull(h)
        assertFalse("horizontal", h!!.vertical)
        assertTrue(h.halfOpened)
        assertTrue("a half-open fold always splits the window", h.splitsWindow)
        assertEquals(Posture.Tabletop, plan!!.posture)
        assertNotNull(plan!!.tabletop)
    }

    @Test fun aHalfOpenVerticalFoldIsABookHinge() {
        host()
        publish(
            FoldingFeature(
                activity = compose.activity,
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.VERTICAL,
            ),
        )
        val h = hinge!!
        assertTrue("vertical", h.vertical)
        assertTrue(h.splitsWindow)
        // Whether it becomes a book plan depends on the window being wide enough to hold two panes;
        // the hinge itself is what this test is about.
        assertNull(plan!!.tabletop)
    }

    @Test fun aFlatFoldIsReportedButDoesNotSplit() {
        host()
        publish(
            FoldingFeature(
                activity = compose.activity,
                state = FoldingFeature.State.FLAT,
                orientation = FoldingFeature.Orientation.VERTICAL,
            ),
        )
        val h = hinge!!
        assertFalse(h.halfOpened)
        assertFalse("a seamless flat fold does not cut the window", h.splitsWindow)
        assertEquals(Posture.Flat, plan!!.posture)
    }

    @Test fun anOccludingFeatureHasItsThickness() {
        host()
        publish(
            FoldingFeature(
                activity = compose.activity,
                size = 40,
                state = FoldingFeature.State.FLAT,
                orientation = FoldingFeature.Orientation.VERTICAL,
            ),
        )
        val h = hinge!!
        assertTrue("a feature with size occludes", h.occluding)
        assertTrue(h.splitsWindow)
        assertTrue("the band has width", h.right > h.left)
    }

    @Test fun unfoldingClearsTheHinge() {
        host()
        publish(
            FoldingFeature(
                activity = compose.activity,
                state = FoldingFeature.State.HALF_OPENED,
                orientation = FoldingFeature.Orientation.HORIZONTAL,
            ),
        )
        assertEquals(Posture.Tabletop, plan!!.posture)
        publish()
        assertNull(hinge)
        assertEquals(Posture.Flat, plan!!.posture)
    }
}
