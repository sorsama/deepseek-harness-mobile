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
                        .requiredSize(env.size.width, env.size.height),
                ) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (env.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val plan = planShell(maxWidth, maxHeight, env.hinge, env.rtl)
                            lastPlan = plan
                            AdaptiveShell(
                                plan = plan,
                                drawerState = rememberDrawerState(DrawerValue.Closed),
                                listShown = env.listShown,
                                detailsShown = env.detailsShown,
                                list = { Counter("list") },
                                details = { Box(Modifier.fillMaxSize().testTag("details")) },
                                chat = { Counter("chat") },
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
