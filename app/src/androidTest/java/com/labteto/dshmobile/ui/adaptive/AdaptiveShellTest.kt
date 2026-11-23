package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shell at fixed window sizes, with a fold where the case has one.
 *
 * The container is a fixed `requiredSize` box, so a 1280x800 tablet is tested on whatever device
 * runs the suite, and the hinge is handed to [planShell] as plain numbers: what is being checked
 * here is where the panes land, which is geometry, not what a particular emulator reports.
 * [WindowFoldTest] covers the other half, the platform's fold reaching the planner.
 */
class AdaptiveShellTest {
    @get:Rule val compose = createComposeRule()

    /** What the test changes while the shell is showing; every field is snapshot state. */
    private class Env(size: DpSize) {
        var size by mutableStateOf(size)
        var hinge by mutableStateOf<Hinge?>(null)
        var rtl by mutableStateOf(false)
        var listShown by mutableStateOf(true)
        var detailsShown by mutableStateOf(true)
        var preferredWidth by mutableStateOf<Dp?>(null)
        var selectableChat by mutableStateOf(false)
        var overlay by mutableStateOf(false)
        var contentMinimum = 240.dp
        var allowReveal = true
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
        }
        val lifecycle = (owner.lifecycle as androidx.lifecycle.LifecycleRegistry).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    }

    @Test fun blockedRevealNeverShowsPreviewOrHint() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; detailsShown = false; allowReveal = false }
        val distance = with(compose.density) { 280.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(distance, 0f), 700)
        }
        compose.onNodeWithTag("list-open-hint").assertDoesNotExist()
        compose.onNodeWithTag("list").assertDoesNotExist()
        compose.onNodeWithTag("chat").performTouchInput { up() }
        compose.runOnIdle { assertTrue(!env.listShown) }
    }

    @Test fun slowOpeningKeepsWidthAtRelease() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; preferredWidth = 350.dp; detailsShown = false }
        val distance = with(compose.density) { 275.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(distance, 0f), 900)
        }
        val releaseWidth = bounds("list").width
        compose.onNodeWithTag("chat").performTouchInput { advanceEventTime(300); up() }
        compose.runOnIdle { assertTrue(env.listShown); assertEquals(releaseWidth.value, env.preferredWidth!!.value, 1f) }
        assertEquals(releaseWidth.value, bounds("list").width.value, 1f)
    }

    @Test fun openingShowsReleaseHintAtThreshold() {
        show(DpSize(800.dp, 700.dp)) { listShown = false; preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 270.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(distance, 0f), 700)
        }
        compose.onNodeWithTag("list-open-hint").assertExists()
        assertTrue("hint must sit below the window's top edge", bounds("list-open-hint").top.value > 0f)
        compose.onNodeWithTag("chat").performTouchInput { up() }
        compose.onNodeWithTag("list-open-hint").assertDoesNotExist()
    }

    @Test fun pauseDuringOpeningThenShortWindowStillAcceptsDrag() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 270.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(distance, 0f), 700)
        }
        compose.runOnIdle { env.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) }
        compose.runOnIdle { env.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME) }
        // A queued move from before pause must not resurrect the cancelled drag.
        compose.onNodeWithTag("chat").performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(20f, 0f), 100)
            up()
        }
        compose.runOnIdle { assertTrue("cancelled drag must stay closed", !env.listShown) }
        compose.runOnIdle { env.size = DpSize(800.dp, 420.dp) }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(30f, 100f),
                androidx.compose.ui.geometry.Offset(30f + distance, 100f), 700)
        }
        compose.runOnIdle {
            assertTrue(env.listShown)
            assertTrue(env.preferredWidth!! in 240.dp..270.dp)
        }
    }

    @Test fun smallFastResizeDoesNotCollapseWidePane() {
        val env = show(DpSize(1280.dp, 800.dp)) { preferredWidth = 700.dp; detailsShown = false }
        val distance = with(compose.density) { 45.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(150f, 100f),
                androidx.compose.ui.geometry.Offset(150f - distance, 100f), 40)
        }
        compose.runOnIdle { assertTrue("short resize must not collapse", env.listShown) }
        assertTrue(compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width > 600.dp)
    }

    @Test fun resizingWindowDuringDragDoesNotResetBoundary() {
        val env = show(DpSize(1280.dp, 800.dp)) { preferredWidth = 700.dp; detailsShown = false }
        val distance = with(compose.density) { 25.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(50f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(-distance, 0f), 500)
        }
        compose.runOnIdle { env.size = DpSize(1280.dp, 480.dp) }
        val width = compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width
        assertTrue(width > 650.dp)
        compose.onNodeWithTag("chat").performTouchInput { up() }
    }

    @Test fun cancelledOpeningLeavesStablePanelAndNextDragWorks() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 270.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(distance, 0f), 700)
            cancel()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(30f, 100f),
                androidx.compose.ui.geometry.Offset(30f + distance, 100f), 700)
        }
        compose.runOnIdle {
            assertTrue(env.listShown)
            assertTrue(env.preferredWidth!! in 240.dp..270.dp)
        }
    }

    @Test fun widePaneFollowsMultipleSmallMovesWithoutJump() {
        show(DpSize(1280.dp, 800.dp)) { preferredWidth = 700.dp; detailsShown = false }
        val step = with(compose.density) { 12.dp.toPx() }
        val startX = with(compose.density) { 750.dp.toPx() }
        compose.onNodeWithTag("shell-root").performTouchInput { down(androidx.compose.ui.geometry.Offset(startX, 200f)) }
        var previous = 700.dp
        repeat(8) {
            compose.onNodeWithTag("shell-root").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-step, 0f), 100) }
            val width = compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width
            assertTrue("frame $it: $previous -> $width", width >= previous - 20.dp && width <= previous + 1.dp)
            previous = width
        }
        compose.onNodeWithTag("chat").performTouchInput { up() }
    }

    @Test fun shortDragStartsAtVisibleWideBoundary() {
        show(DpSize(1280.dp, 800.dp)) { preferredWidth = 700.dp; detailsShown = false }
        val before = compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width
        val distance = with(compose.density) { 25.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(80f, 100f))
            moveBy(androidx.compose.ui.geometry.Offset(-distance, 0f), 500)
        }
        val during = compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width
        assertTrue("small drag must not jump", during >= before - 35.dp && during <= before)
        compose.onNodeWithTag("chat").performTouchInput { up() }
    }

    @Test fun reopeningDuringCloseStartsAtCurrentBoundary() {
        val env = show(DpSize(800.dp, 700.dp)) { preferredWidth = 350.dp; detailsShown = false }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { env.listShown = false }
            compose.mainClock.advanceTimeBy(80)
            val before = bounds("list").width
            assertTrue("closing must still be visible: $before", before > 90.dp && before < 350.dp)
            val start = with(compose.density) { 400.dp.toPx() }
            val step = with(compose.density) { 30.dp.toPx() }
            compose.onNodeWithTag("shell-root").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(start, 150f))
                moveBy(androidx.compose.ui.geometry.Offset(step, 0f), 16)
            }
            compose.mainClock.advanceTimeByFrame()
            val after = bounds("list").width
            // Input dispatch advances a frame before horizontal slop is recognized.
            // The closing spring can still move in that frame, but must not reset to zero.
            assertTrue("reopening jumped from $before to $after", after >= before - 40.dp && after <= before + 32.dp)
            compose.onNodeWithTag("shell-root").performTouchInput { cancel() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun contentMinimumAllowsNarrowDockedTree() {
        show(DpSize(800.dp, 700.dp)) { preferredWidth = 180.dp; contentMinimum = 180.dp; detailsShown = false }
        assertEquals(180.dp.value, bounds("list").width.value, 1f)
    }

    @Test fun unpinningListDoesNotMoveDockedDetails() {
        val env = show(DpSize(1280.dp, 800.dp))
        val before = bounds("details")
        compose.runOnIdle { env.overlay = true }
        assertEquals(before, bounds("details"))
        compose.runOnIdle { env.overlay = false }
        assertEquals(before, bounds("details"))
    }

    @Test fun unpinningAndPinningKeepsChatState() {
        val env = show(DpSize(800.dp, 700.dp)) { detailsShown = false }
        compose.onNodeWithTag("chat").performClick()
        compose.runOnIdle { env.overlay = true }
        compose.onNodeWithTag("chat").assertTextEquals("chat:1")
        compose.runOnIdle { env.overlay = false }
        compose.onNodeWithTag("chat").assertTextEquals("chat:1")
    }

    @Test fun longPressSelectionDoesNotResizePane() {
        val env = show(DpSize(800.dp, 700.dp)) { preferredWidth = 300.dp; detailsShown = false; selectableChat = true }
        compose.onNodeWithTag("chat").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(30f, 15f))
            advanceEventTime(800)
            moveBy(androidx.compose.ui.geometry.Offset(100f, 0f), 300)
            up()
        }
        compose.runOnIdle { assertEquals(300.dp, env.preferredWidth); assertTrue(env.listShown) }
    }

    @Test fun horizontalDragOverSelectableTextResizesPane() {
        val env = show(DpSize(800.dp, 700.dp)) { preferredWidth = 300.dp; detailsShown = false; selectableChat = true }
        val distance = with(compose.density) { 50.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(20f, 20f), androidx.compose.ui.geometry.Offset(20f + distance, 20f), 900)
        }
        compose.runOnIdle { assertTrue(env.preferredWidth!! > 300.dp) }
    }

    @Test fun hiddenPanelCanBePulledFromChatBody() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 280.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(80f, center.y),
                androidx.compose.ui.geometry.Offset(80f + distance, center.y), 900)
        }
        compose.runOnIdle { assertTrue(env.listShown); assertTrue(env.preferredWidth!! >= 240.dp) }
    }

    @Test fun panelFollowsFingerBeforeRelease() {
        val env = show(DpSize(800.dp, 700.dp)) { preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 110.dp.toPx() }
        compose.onNodeWithTag("list-resize-handle").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-distance, 0f), 600)
        }
        compose.runOnIdle { assertTrue(env.listShown) }
        assertTrue(compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width < 240.dp)
        compose.onNodeWithTag("list-resize-handle").performTouchInput { advanceEventTime(300); up() }
        compose.runOnIdle { assertTrue(env.listShown) }
        assertEquals(240f, bounds("list").width.value, 1f)
    }

    @Test fun preferredWidthMovesTheBoundary() {
        show(DpSize(1280.dp, 800.dp)) { preferredWidth = 420.dp; detailsShown = false }
        assertEquals(420f, compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width.value, 0.5f)
    }

    @Test fun draggingTheDividerChangesWidthWithoutHidingTheList() {
        val env = show(DpSize(800.dp, 700.dp)) { detailsShown = false }
        compose.onNodeWithTag("list-resize-handle").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(60f, 0f))
        }
        compose.runOnIdle { assertTrue(env.preferredWidth != null); assertTrue(env.listShown) }
    }

    @Test fun swipingListClosedAndEdgeOpenKeepsWidth() {
        val env = show(DpSize(800.dp, 700.dp)) { preferredWidth = 300.dp; detailsShown = false }
        val distance = with(compose.density) { 130.dp.toPx() }
        compose.onNodeWithTag("list").performTouchInput { swipe(center, center - androidx.compose.ui.geometry.Offset(distance, 0f)) }
        compose.runOnIdle { assertTrue(!env.listShown) }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(4f, center.y), androidx.compose.ui.geometry.Offset(4f + distance, center.y), 80)
        }
        compose.runOnIdle { assertTrue(env.listShown); assertEquals(300.dp, env.preferredWidth) }
    }

    @Test fun verticalMovementDoesNotHideTheList() {
        val env = show(DpSize(800.dp, 700.dp)) { detailsShown = false }
        compose.onNodeWithTag("list").performTouchInput {
            swipe(center, center - androidx.compose.ui.geometry.Offset(0f, 200f))
        }
        compose.runOnIdle { assertTrue(env.listShown); assertEquals(null, env.preferredWidth) }
    }

    @Test fun rtlDividerDragUsesTheMirroredDirection() {
        val env = show(DpSize(800.dp, 700.dp)) { rtl = true; preferredWidth = 300.dp; detailsShown = false }
        compose.onNodeWithTag("list-resize-handle").performTouchInput {
            swipe(center, center - androidx.compose.ui.geometry.Offset(90f, 0f))
        }
        compose.runOnIdle { assertTrue(env.preferredWidth!! > 300.dp); assertTrue(env.listShown) }
    }

    @Test fun resizingWindowRestoresPreferredWidth() {
        val env = show(DpSize(1280.dp, 800.dp)) { preferredWidth = 700.dp; detailsShown = false }
        compose.runOnIdle { env.size = DpSize(800.dp, 700.dp) }
        assertEquals(479f, compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width.value, 0.5f)
        compose.runOnIdle { env.size = DpSize(1280.dp, 800.dp) }
        assertEquals(700f, compose.onNodeWithTag("list").getUnclippedBoundsInRoot().width.value, 0.5f)
    }

    @Test fun cancelledSwipeDoesNotHideTheList() {
        val env = show(DpSize(800.dp, 700.dp)) { detailsShown = false }
        compose.onNodeWithTag("list").performTouchInput {
            down(center)
            moveTo(center - androidx.compose.ui.geometry.Offset(180f, 0f))
            cancel()
        }
        compose.runOnIdle { assertTrue(env.listShown) }
    }

    @Test fun swipingInsideChatDoesNotOpenHiddenList() {
        val env = show(DpSize(800.dp, 700.dp)) { listShown = false; detailsShown = false }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(180f, 0f))
        }
        compose.runOnIdle { assertTrue(!env.listShown) }
    }

    private var lastPlan: ShellPlan? = null

    private fun show(size: DpSize, configure: Env.() -> Unit = {}): Env {
        val env = Env(size).apply(configure)
        compose.setContent { Host(env) }
        return env
    }

    @Composable
    private fun Host(env: Env) {
        DshTheme {
            // The anchor is always left to right, so "top start" means the left edge of the test
            // window whatever direction the shell under test is laid out in. The container is
            // measured unbounded, so a 1280dp container sits at 0,0 even on a 411dp phone (a bare
            // requiredSize would be centred, and its left edge would sit at a negative offset).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(env.size.width, env.size.height).testTag("shell-root"),
                ) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (env.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        androidx.lifecycle.compose.LocalLifecycleOwner provides env.owner,
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val base = planShell(maxWidth, maxHeight, env.hinge, env.rtl)
                            val plan = base
                            lastPlan = plan
                            AdaptiveShell(
                                plan = plan,
                                overlayList = env.overlay,
                                drawerState = rememberDrawerState(DrawerValue.Closed),
                                listShown = env.listShown,
                                detailsShown = env.detailsShown,
                                preferredListWidth = env.preferredWidth,
                                minimumListContentWidth = env.contentMinimum,
                                allowListReveal = env.allowReveal,
                                onListWidthChange = { env.preferredWidth = it },
                                onListShownChange = { env.listShown = it },
                                list = { Counter("list") },
                                details = { Box(Modifier.fillMaxSize().testTag("details")) },
                                chat = {
                                    if (env.selectableChat) androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text("Selectable transcript text for horizontal dragging and long press selection", Modifier.fillMaxSize().testTag("chat"))
                                    } else Counter("chat")
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** A pane that remembers how many times it was tapped, so a move that rebuilds it shows. */
    @Composable
    private fun Counter(tag: String) {
        var taps by remember { mutableIntStateOf(0) }
        Box(Modifier.fillMaxSize().testTag(tag).clickable { taps++ }) { Text("$tag:$taps") }
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Float, actual: Dp, what: String = "", delta: Float = 1.5f) =
        assertEquals(what, expected, actual.value, delta)

    private fun bookHinge(x: Float, height: Float, gap: Float = 0f, occluding: Boolean = false) = Hinge(
        left = x, top = 0f, right = x + gap, bottom = height,
        vertical = true, halfOpened = !occluding, separating = true, occluding = occluding,
    )

    @Test fun aPhoneKeepsTheDrawerAndAFullWidthChat() {
        show(DpSize(400.dp, 800.dp))
        assertEquals(ShellKind.Compact, lastPlan!!.kind)
        assertNear(400f, bounds("chat").width, "the chat is the whole window")
        compose.onNodeWithTag("list").assertIsNotDisplayed()
        compose.onNodeWithTag("details").assertDoesNotExist()
    }

    @Test fun aMediumWindowDocksTheListBesideTheChat() {
        show(DpSize(700.dp, 900.dp))
        val plan = lastPlan!!
        assertEquals(ShellKind.ListDocked, plan.kind)
        val list = bounds("list")
        val chat = bounds("chat")
        assertNear(0f, list.left)
        assertNear(plan.listWidth.value, list.width)
        assertTrue("the chat starts where the list ends", chat.left.value >= list.right.value)
        assertNear(700f, chat.right)
        compose.onNodeWithTag("details").assertDoesNotExist()
    }

    @Test fun anExpandedWindowShowsListChatAndDetailsLeftToRight() {
        show(DpSize(1280.dp, 800.dp))
        assertEquals(ShellKind.ThreePane, lastPlan!!.kind)
        val list = bounds("list")
        val chat = bounds("chat")
        val details = bounds("details")
        assertNear(0f, list.left)
        assertTrue(chat.left.value >= list.right.value)
        assertTrue(details.left.value >= chat.right.value - 0.5f)
        assertNear(1280f, details.right)
        assertNear(340f, details.width)
        assertTrue("the chat keeps a real width", chat.width.value >= ChatPaneMinWithDetails.value - 1f)
    }

    @Test fun hidingDetailsHandsItsWidthToTheChat() {
        val env = show(DpSize(1280.dp, 800.dp))
        val before = bounds("chat").width
        compose.runOnIdle { env.detailsShown = false }
        compose.waitForIdle()
        compose.onNodeWithTag("details").assertDoesNotExist()
        assertTrue(bounds("chat").width.value >= before.value + 339f)
    }

    @Test fun hidingTheListHandsItsWidthToTheChat() {
        val env = show(DpSize(700.dp, 900.dp))
        compose.runOnIdle { env.listShown = false }
        compose.waitForIdle()
        compose.onNodeWithTag("list").assertDoesNotExist()
        assertNear(0f, bounds("chat").left)
        assertNear(700f, bounds("chat").right)
    }

    @Test fun aBookHingeSplitsTheListFromTheChatAtTheCrease() {
        // A Pixel Fold half open like a book: a zero-width crease at 420dp.
        show(DpSize(841.dp, 701.dp)) { hinge = bookHinge(420f, 701f) }
        assertEquals(Posture.Book, lastPlan!!.posture)
        val list = bounds("list")
        val chat = bounds("chat")
        assertNear(420f, list.right, "the list is the left half")
        assertTrue("the chat is wholly right of the crease", chat.left.value >= 420f - 0.5f)
        assertNear(841f, chat.right)
    }

    @Test fun theListCannotBeHiddenWhenItIsHalfOfTheSplit() {
        val env = show(DpSize(841.dp, 701.dp)) { hinge = bookHinge(420f, 701f) }
        compose.runOnIdle { env.listShown = false }
        compose.waitForIdle()
        // Hiding it would put the chat across the crease, so the switch does not apply.
        assertNear(420f, bounds("list").right)
        assertTrue(bounds("chat").left.value >= 420f - 0.5f)
    }

    @Test fun nothingSitsOnAHingeThatHasWidth() {
        // Two 540dp panels with a 34dp hinge between them.
        show(DpSize(1114.dp, 754.dp)) { hinge = bookHinge(540f, 754f, gap = 34f, occluding = true) }
        val list = bounds("list")
        val chat = bounds("chat")
        assertTrue("the list ends at or before the hinge", list.right.value <= 540f + 0.5f)
        assertTrue("the chat starts at or after the hinge", chat.left.value >= 574f - 0.5f)
    }

    @Test fun inRightToLeftTheListSitsOnTheRight() {
        show(DpSize(700.dp, 900.dp)) { rtl = true }
        val list = bounds("list")
        val chat = bounds("chat")
        assertNear(700f, list.right)
        assertTrue("the chat is to the list's left", chat.right.value <= list.left.value + 0.5f)
        assertNear(0f, chat.left)
    }

    @Test fun aTabletopHingeKeepsTheSinglePaneShell() {
        // A hinge across the middle: panes would cross it, so the shell stays one pane (a drawer).
        show(DpSize(900.dp, 900.dp)) {
            hinge = Hinge(0f, 450f, 900f, 450f, vertical = false, halfOpened = true, separating = true, occluding = false)
        }
        assertEquals(Posture.Tabletop, lastPlan!!.posture)
        assertEquals(ShellKind.Compact, lastPlan!!.kind)
        assertNear(900f, bounds("chat").width)
    }

    @Test fun aPaneKeepsItsStateWhenTheWindowGrowsPastThePhoneLayout() {
        val env = show(DpSize(400.dp, 800.dp))
        compose.onNodeWithTag("chat").performClick()
        compose.onNodeWithTag("chat").performClick()
        compose.onNodeWithTag("chat").assertTextEquals("chat:2")
        // Unfold: the drawer layout becomes three panes and the chat moves to a different parent.
        compose.runOnIdle { env.size = DpSize(1280.dp, 800.dp) }
        compose.waitForIdle()
        assertEquals(ShellKind.ThreePane, lastPlan!!.kind)
        compose.onNodeWithTag("chat").assertTextEquals("chat:2")
        // And back: folding up must not lose it either.
        compose.runOnIdle { env.size = DpSize(400.dp, 800.dp) }
        compose.waitForIdle()
        assertEquals(ShellKind.Compact, lastPlan!!.kind)
        compose.onNodeWithTag("chat").assertTextEquals("chat:2")
    }

    @Test fun theListKeepsItsStateWhenItMovesFromTheDrawerToAPane() {
        val env = show(DpSize(400.dp, 800.dp))
        // In the drawer the list is off screen, so reach it through the semantics tree.
        compose.onNodeWithTag("list").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { env.size = DpSize(700.dp, 900.dp) }
        compose.waitForIdle()
        assertEquals(ShellKind.ListDocked, lastPlan!!.kind)
        compose.onNodeWithTag("list").assertTextEquals("list:1")
    }
}
