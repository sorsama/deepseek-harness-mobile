package com.labteto.dshmobile.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive shell's rules, checked against the devices they were written for.
 *
 * The numbers are real windows in dp: a Pixel 8 (412x915), a Galaxy Z Flip6's inner screen
 * (about 408x997, the tall 22:9 that no 16:9 assumption survives) and cover screen (about 376x391),
 * a Pixel Fold unfolded (841x701, hinge down the middle), a Pixel Tablet (1280x800) and a dual-screen
 * device with a real gap between its panels. A rule that only holds for one of them is the bug this
 * file is here to catch.
 */
class ShellPlanTest {

    private fun plan(w: Number, h: Number, hinge: Hinge? = null, rtl: Boolean = false) =
        planShell(w.toFloat().dp, h.toFloat().dp, hinge, rtl)

    /** A vertical fold: left to right at [x], [gap] wide, from the top of the window to [height]. */
    private fun vertical(x: Float, height: Float, gap: Float = 0f, halfOpened: Boolean = true, separating: Boolean = true, occluding: Boolean = false) =
        Hinge(x, 0f, x + gap, height, vertical = true, halfOpened = halfOpened, separating = separating, occluding = occluding)

    /** A horizontal fold: across the whole [width] at [y], [gap] tall. */
    private fun horizontal(y: Float, width: Float, gap: Float = 0f, halfOpened: Boolean = true, separating: Boolean = true, occluding: Boolean = false) =
        Hinge(0f, y, width, y + gap, vertical = false, halfOpened = halfOpened, separating = separating, occluding = occluding)

    private fun assertDp(expected: Float, actual: Dp, delta: Float = 0.01f) =
        assertEquals(expected, actual.value, delta)

    // ---- Compact: exactly today's phone --------------------------------------------------------

    @Test fun aPhoneIsTheCompactLayoutWithNoPanes() {
        val p = plan(412, 915)
        assertEquals(ShellKind.Compact, p.kind)
        assertEquals(Posture.Flat, p.posture)
        assertEquals(WindowWidthSizeClass.COMPACT, p.widthClass)
        assertFalse(p.listDocked)
        assertFalse(p.detailsDockable)
        assertDp(0f, p.listWidth)
        assertDp(0f, p.detailsWidth)
        assertNull(p.tabletop)
    }

    @Test fun aFlipInnerScreenIsAPhoneNoMatterHowTall() {
        // About 22:9. Nothing in the plan looks at the aspect ratio, only at the two dimensions.
        for ((w, h) in listOf(408 to 997, 412 to 1006, 360 to 880)) {
            assertEquals("$w x $h", ShellKind.Compact, plan(w, h).kind)
        }
        assertEquals(WindowHeightSizeClass.EXPANDED, plan(408, 997).heightClass)
    }

    @Test fun aFlipCoverScreenIsCompactToo() {
        // About 3.4 inches at a high density: short and narrow, both compact.
        val p = plan(376, 391)
        assertEquals(ShellKind.Compact, p.kind)
        assertEquals(WindowHeightSizeClass.COMPACT, p.heightClass)
    }

    @Test fun theSmallestWindowsStayCompact() {
        for ((w, h) in listOf(320 to 320, 280 to 240, 599 to 900)) {
            assertEquals("$w x $h", ShellKind.Compact, plan(w, h).kind)
        }
    }

    // ---- Medium: the list docks ------------------------------------------------------------------

    @Test fun aSmallTabletDocksTheListAndLeavesDetailsAnOverlay() {
        val p = plan(640, 960)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertEquals(WindowWidthSizeClass.MEDIUM, p.widthClass)
        assertTrue(p.listDocked)
        assertFalse(p.detailsDockable)
        // 36% of 640 is 230, under the 300 floor: the list keeps a usable width and the chat the rest.
        assertDp(300f, p.listWidth)
        assertTrue(p.listCanCollapse)
    }

    @Test fun theListIsAThirdOfTheWindowWithinItsLimits() {
        assertDp(300f, plan(640, 900).listWidth)
        assertDp(300f, plan(800, 1000).listWidth)
        assertDp(324f, plan(900, 1000).listWidth)
        assertDp(360f, plan(1000, 1000).listWidth)
    }

    @Test fun aLandscapePhoneIsNeverMoreThanTheListBesideTheChat() {
        // 915 wide is the expanded width class, but 412 tall is not room for three panes.
        val p = plan(915, 412)
        assertEquals(WindowWidthSizeClass.EXPANDED, p.widthClass)
        assertEquals(WindowHeightSizeClass.COMPACT, p.heightClass)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertDp(0f, p.detailsWidth)
        assertDp(329.4f, p.listWidth)
    }

    @Test fun aWindowTooNarrowToKeepAChatBesideTheListFallsBackToThePhoneLayout() {
        // 600 is medium by width class, but 300 for the list leaves the chat only 300: under its 320
        // minimum, so the drawer stays. The first window that fits both is 620.
        assertEquals(ShellKind.Compact, plan(599.9f, 900).kind)
        assertEquals(ShellKind.Compact, plan(600, 900).kind)
        val p = plan(620, 900)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertTrue(620f - p.listWidth.value >= ChatPaneMin.value)
    }

    // ---- Expanded: three panes ---------------------------------------------------------------------

    @Test fun aTabletInLandscapeShowsListChatAndDetails() {
        val p = plan(1280, 800)
        assertEquals(ShellKind.ThreePane, p.kind)
        assertEquals(WindowWidthSizeClass.EXPANDED, p.widthClass)
        assertTrue(p.detailsDockable)
        assertDp(360f, p.listWidth)
        assertDp(340f, p.detailsWidth)
        // What is left for the chat is a real chat pane.
        assertTrue(1280f - p.listWidth.value - p.detailsWidth.value >= ChatPaneMinWithDetails.value)
    }

    @Test fun detailsOnlyDocksWhenTheChatKeepsItsMinimum() {
        // 360 + 340 + 480 = 1180 is the first window that fits all three.
        assertEquals(ShellKind.ThreePane, plan(1180, 800).kind)
        assertEquals(ShellKind.ListDocked, plan(1179, 800).kind)
        // A small tablet in landscape: expanded by width, but a chat squeezed between two panes is
        // worse than Details as a sheet.
        val small = plan(960, 600)
        assertEquals(WindowWidthSizeClass.EXPANDED, small.widthClass)
        assertEquals(ShellKind.ListDocked, small.kind)
        assertFalse(small.detailsDockable)
    }

    @Test fun aTabletInPortraitIsMedium() {
        val p = plan(800, 1280)
        assertEquals(WindowWidthSizeClass.MEDIUM, p.widthClass)
        assertEquals(ShellKind.ListDocked, p.kind)
    }

    @Test fun theReadingWidthCapIsCentredWithinTheChatPane() {
        assertEquals(ReadingWidthMax, plan(1280, 800).readingWidth)
        assertDp(0f, readingGutter(paneWidth = 620.dp, readingWidth = ReadingWidthMax))
        assertDp(0f, readingGutter(paneWidth = ReadingWidthMax, readingWidth = ReadingWidthMax))
        // A 980dp pane holds a 780dp column with 100dp each side.
        assertDp(100f, readingGutter(paneWidth = 980.dp, readingWidth = ReadingWidthMax))
        assertDp(0f, readingGutter(paneWidth = 400.dp, readingWidth = Dp.Unspecified))
    }

    @Test fun theReadingWidthIsInTheComfortableRange() {
        assertTrue(ReadingWidthMax >= 720.dp && ReadingWidthMax <= 840.dp)
    }

    // ---- A vertical hinge: the boundary lands on it --------------------------------------------------

    @Test fun aSeamlessFlatFoldIsNotARoadblock() {
        // A Pixel Fold lying open: a crease, but one picture. Laid out like any 841dp window.
        val flat = vertical(x = 420.5f, height = 701f, halfOpened = false, separating = false)
        val p = plan(841, 701, flat)
        assertEquals(Posture.Flat, p.posture)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertDp(302.76f, p.listWidth)
        assertDp(0f, p.listGap)
        assertTrue(p.listCanCollapse)
    }

    @Test fun aBookPostureMakesTheListTheWholeLeftHalf() {
        val hinge = vertical(x = 420f, height = 701f)
        val p = plan(841, 701, hinge)
        assertEquals(Posture.Book, p.posture)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertDp(420f, p.listWidth)
        assertDp(0f, p.listGap)
        // The list is one half of the split: hiding it would put the chat on the crease.
        assertFalse(p.listCanCollapse)
        // 841 - 420 = 421 for the chat, which is under the 820 that docking Details needs.
        assertDp(0f, p.detailsWidth)
    }

    @Test fun aHingeWithAGapLeavesTheGapEmpty() {
        // A dual-screen device: two panels of 540 with a 34dp hinge between them.
        val hinge = vertical(x = 540f, height = 754f, gap = 34f, halfOpened = false, occluding = true)
        val p = plan(1114, 754, hinge)
        assertEquals(Posture.Book, p.posture)
        assertDp(540f, p.listWidth)
        assertDp(34f, p.listGap)
        // list + gap + chat fill the window exactly: 540 + 34 + 540.
        assertDp(1114f, p.listWidth + p.listGap + 540.dp)
    }

    @Test fun anOccludingHingeSplitsEvenWhenTheDeviceCallsItFlat() {
        val hinge = vertical(x = 540f, height = 754f, gap = 34f, halfOpened = false, separating = false, occluding = true)
        assertEquals(Posture.Book, plan(1114, 754, hinge).posture)
    }

    @Test fun aWideBookWindowDocksDetailsInTheHalfBesideTheChat() {
        // Two 900dp halves: the list takes one, the chat and Details share the other (480 + 340 <= 900).
        val hinge = vertical(x = 900f, height = 900f)
        val p = plan(1800, 900, hinge)
        assertEquals(ShellKind.ThreePane, p.kind)
        assertDp(900f, p.listWidth)
        assertDp(340f, p.detailsWidth)
    }

    @Test fun inRightToLeftTheListIsTheRightHandHalf() {
        // The hinge is physical: 450dp from the left of an 800dp window.
        val hinge = vertical(x = 450f, height = 900f)
        val ltr = plan(800, 900, hinge, rtl = false)
        val rtl = plan(800, 900, hinge, rtl = true)
        assertEquals(Posture.Book, ltr.posture)
        assertDp(450f, ltr.listWidth)
        // Start is the right-hand side now, and that half is 350dp wide.
        assertEquals(Posture.Book, rtl.posture)
        assertDp(350f, rtl.listWidth)
    }

    @Test fun aHingeTooCloseToAnEdgeIsIgnoredRatherThanLeavingTheListUnusable() {
        // A half-width split-screen window with the fold 100dp from its left edge: 100 < the list minimum.
        val hinge = vertical(x = 100f, height = 900f)
        val p = plan(700, 900, hinge)
        assertEquals(Posture.Flat, p.posture)
        assertEquals(ShellKind.ListDocked, p.kind)
        assertTrue(p.listCanCollapse)
        // And one 100dp from the right edge leaves the chat nothing.
        assertEquals(Posture.Flat, plan(700, 900, vertical(x = 600f, height = 900f)).posture)
    }

    @Test fun aWindowTooNarrowForPanesIgnoresAVerticalHinge() {
        // Phone-width windows have a drawer, and a drawer slides over whatever is under it.
        val p = plan(500, 900, vertical(x = 250f, height = 900f))
        assertEquals(ShellKind.Compact, p.kind)
        assertEquals(Posture.Flat, p.posture)
    }

    // ---- Tabletop: transcript above the hinge, controls below ------------------------------------------

    @Test fun aFlipPoppedOpenLikeALaptopIsATabletop() {
        // A Flip's inner screen, 412 x 900, folded half open across its middle.
        val hinge = horizontal(y = 450f, width = 412f)
        val p = plan(412, 900, hinge)
        assertEquals(Posture.Tabletop, p.posture)
        assertEquals(ShellKind.Compact, p.kind)
        assertEquals(TabletopSplit(450.dp, 450.dp), p.tabletop)
    }

    @Test fun aTabletopDropsTheDockedPanesEvenOnAWideWindow() {
        // A book foldable propped half open with the hinge across its width.
        val hinge = horizontal(y = 420f, width = 841f, gap = 4f)
        val p = plan(841, 841, hinge)
        assertEquals(Posture.Tabletop, p.posture)
        assertEquals(ShellKind.Compact, p.kind)
        assertFalse(p.listDocked)
        assertNotNull(p.tabletop)
        assertDp(420f, p.tabletop!!.top)
        assertDp(424f, p.tabletop!!.bottom)
    }

    @Test fun aFlatHorizontalCreaseIsNotATabletop() {
        // A Pixel Fold held in portrait and fully open: horizontal crease, one picture.
        val flat = horizontal(y = 420f, width = 700f, halfOpened = false, separating = false)
        val p = plan(700, 841, flat)
        assertEquals(Posture.Flat, p.posture)
        assertNull(p.tabletop)
        assertEquals(ShellKind.ListDocked, p.kind)
    }

    @Test fun aTabletopWithNoRoomAboveOrBelowIsIgnored() {
        assertEquals(Posture.Flat, plan(412, 900, horizontal(y = 60f, width = 412f)).posture)
        assertEquals(Posture.Flat, plan(412, 900, horizontal(y = 800f, width = 412f)).posture)
    }

    // ---- The tabletop column --------------------------------------------------------------------------------

    private val tabletop = TabletopSplit(450.dp, 450.dp)

    @Test fun theTranscriptEndsAtTheHingeAndTheControlsGetTheRest() {
        // The chat column starts 150dp down (status bar, top bar) and runs to the bottom of a 900dp window.
        val r = tabletopRegions(columnTop = 150.dp, columnHeight = 750.dp, split = tabletop)!!
        assertDp(300f, r.topHeight)
        assertDp(0f, r.gap)
    }

    @Test fun aHingeWithThicknessLeavesAGap() {
        val r = tabletopRegions(150.dp, 750.dp, TabletopSplit(440.dp, 460.dp))!!
        assertDp(290f, r.topHeight)
        assertDp(20f, r.gap)
    }

    @Test fun theKeyboardShrinksTheBottomHalfNotTheHingesPlace() {
        // 300dp of keyboard: the column now ends at 600. The hinge is still at 450, so the bottom
        // half is 150dp, which is exactly the controls' minimum.
        val r = tabletopRegions(150.dp, 450.dp, tabletop)!!
        assertDp(300f, r.topHeight)
        assertDp(0f, r.gap)
    }

    @Test fun aKeyboardThatEatsTheBottomHalfMakesTheTranscriptYield() {
        // The column ends at 560: only 110dp below the hinge. The controls keep their 150dp by
        // taking it from the transcript, and the hinge no longer divides anything.
        val r = tabletopRegions(150.dp, 410.dp, TabletopSplit(440.dp, 460.dp))!!
        assertDp(260f, r.topHeight)
        assertDp(0f, r.gap)
    }

    @Test fun noRoomForBothHalvesIsNoSplit() {
        // A keyboard so tall nothing fits above the controls.
        assertNull(tabletopRegions(150.dp, 250.dp, tabletop))
        // A hinge that sits in the top bar.
        assertNull(tabletopRegions(columnTop = 400.dp, columnHeight = 500.dp, split = tabletop))
    }
}
