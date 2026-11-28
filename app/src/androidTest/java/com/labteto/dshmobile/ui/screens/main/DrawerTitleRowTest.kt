package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The drawer's "Sessions" title is never cut, and none of its actions is pushed out of the pane.
 *
 * A docked list on a landscape phone has about 225dp of content once the cutout, the bars and the
 * drawer's own padding have their share, and the title (30sp) and the three 48dp actions did not fit:
 * the title read "Ses…". The row now steps the title down a size, then moves Sort into the More menu,
 * then puts the actions under the title, then wraps it, so the name survives any width and any text
 * size. The three actions here are drawn like the drawer's: Search, Order and More, with Order the one
 * that folds.
 */
class DrawerTitleRowTest {

    @get:Rule val compose = createComposeRule()

    private fun show(widthDp: Int, fontScale: Float = 1f, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                DshTheme {
                    Box(Modifier.width(widthDp.dp).testTag("root")) {
                        DrawerTitleRow(
                            title = "Sessions",
                            subtitle = "C:\\demo\\user-m4a\\website · DeepSeek-V4.1-Flash",
                            actionCount = 3,
                            foldedActionCount = 2,
                        ) { sortFolded ->
                            DsIconButton(icon = DsIcons.Add, contentDescription = "Search", onClick = {})
                            if (!sortFolded) DsIconButton(icon = DsIcons.Add, contentDescription = "Order", onClick = {})
                            DsIconButton(icon = DsIcons.Add, contentDescription = "More", onClick = {})
                        }
                    }
                }
            }
        }
    }

    private fun titleLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText("Sessions").fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** The title keeps every letter, and every action that is drawn is inside the pane. */
    private fun assertTitleWholeAndActionsInside(widthDp: Int, allowWrap: Boolean = false) {
        val layout = titleLayout()
        assertEquals("the title keeps every letter", "Sessions", layout.layoutInput.text.text)
        // Not `hasVisualOverflow`: with softWrap off Compose reports it for any text narrower than the room offered.
        // A cut title is one whose visible end is short of its text, or that is wider than the width it was given.
        val last = layout.lineCount - 1
        assertEquals("the title is whole at ${widthDp}dp, not ellipsized or clipped", "Sessions".length, layout.getLineEnd(last, visibleEnd = true))
        assertFalse("the title does not run past its own width at ${widthDp}dp", layout.didOverflowHeight)
        for (line in 0..last) {
            assertTrue("line $line of the title fits the width it was laid out in at ${widthDp}dp", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        if (!allowWrap) assertTrue("a one-line title is laid out at its natural width (${layout.size.width} of ${layout.multiParagraph.maxIntrinsicWidth}) at ${widthDp}dp", layout.size.width >= layout.multiParagraph.maxIntrinsicWidth - 1f)
        if (!allowWrap) assertEquals("the title stays on one line at ${widthDp}dp", 1, layout.lineCount)
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        listOf("Search", "More").forEach { description ->
            val bounds = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            assertTrue("$description starts inside the pane at ${widthDp}dp (left ${bounds.left})", bounds.left >= root.left - 0.5.dp)
            assertTrue("$description ends inside the pane at ${widthDp}dp (right ${bounds.right} of ${root.right})", bounds.right <= root.right + 0.5.dp)
        }
    }

    @Test
    fun aPhoneDrawerKeepsTheTitleBesideEveryAction() {
        show(widthDp = 340)
        assertTitleWholeAndActionsInside(340)
        val title = compose.onNodeWithText("Sessions").getUnclippedBoundsInRoot()
        val order = compose.onNodeWithContentDescription("Order").getUnclippedBoundsInRoot()
        assertTrue("on a phone the actions stay on the title's line", order.top < title.bottom)
    }

    @Test
    fun aDockedPaneOfThreeHundredDpDoesNotCutTheTitle() {
        show(widthDp = 300)
        assertTitleWholeAndActionsInside(300)
        compose.onNodeWithContentDescription("Order").assertExists()
    }

    @Test
    fun aNarrowPaneKeepsOneLineByFoldingSortIntoMore() {
        // What a landscape phone's docked list has left once the cutout and the bars have their share.
        show(widthDp = 224)
        assertTitleWholeAndActionsInside(224)
        compose.onNodeWithContentDescription("Order").assertDoesNotExist()
    }

    @Test
    fun largerTextInThatPaneDoesNotCutTheTitleEither() {
        show(widthDp = 224, fontScale = 1.3f)
        assertTitleWholeAndActionsInside(224)
    }

    @Test
    fun theActionsMoveUnderTheTitleWhenNothingCanShareItsLine() {
        show(widthDp = 160)
        assertTitleWholeAndActionsInside(160)
        val title = compose.onNodeWithText("Sessions").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription("Search").getUnclippedBoundsInRoot()
        assertTrue("the actions sit under the title (title bottom ${title.bottom}, action top ${search.top})", search.top >= title.bottom - 1.dp)
        compose.onNodeWithContentDescription("Order").assertExists()
    }

    @Test
    fun theLargestTextInANarrowPaneWrapsTheTitleRatherThanCutIt() {
        show(widthDp = 160, fontScale = 2f)
        assertTitleWholeAndActionsInside(160, allowWrap = true)
    }

    @Test
    fun inARightToLeftLanguageTheTitleSitsAtTheRightAndTheActionsAtTheLeft() {
        show(widthDp = 340, direction = LayoutDirection.Rtl)
        assertTitleWholeAndActionsInside(340)
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Sessions").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithContentDescription("Search").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
        assertTrue("the title starts at the right edge (title right ${title.right}, pane right ${root.right})", title.right >= root.right - 1.dp)
        assertTrue("the actions are at the left edge (More left ${more.left})", more.left <= root.left + 1.dp)
        assertTrue("Search is to the right of More, as the order mirrors", search.left > more.left)
    }
}
