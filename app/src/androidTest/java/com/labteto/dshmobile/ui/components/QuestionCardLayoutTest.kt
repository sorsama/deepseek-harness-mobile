package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The layout rules of the question card: the action row is pinned and whole, the body scrolls above it,
 * and a card with no room folds to its strip instead of losing its action row.
 *
 * The 0.25.0 release screenshot showed the free-text field cut off at its bottom edge by Skip / Submit
 * with nothing to say that the body scrolls. These tests pin what holds at every height, because the
 * defect was never one height: a clip in portrait, and in a phone's landscape a card reduced to its
 * header with no action row at all.
 *
 * A node below the fold of a *working* scroll reports `Rect.Zero` exactly as a starved one does, so the
 * action row is asserted on its own bounds, and "the body scrolls" on the scroll semantics, never on
 * `assertIsDisplayed()` (which passes for a clipped node). The cue itself is paint, not layout, so what is
 * checked is the condition it is drawn on: a body that can scroll forward when the card is short, and
 * one that cannot when everything fits.
 */
class QuestionCardLayoutTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val submit get() = context.getString(R.string.questions_submit)
    private val viewQuestions get() = context.getString(R.string.ux_b_view_questions)

    // Skip and Next are in the shared resources only, which the app module cannot read (`Res` is internal
    // to :sharedUi), so these two are the English words; the run is in the emulator's default locale.
    private val skip = "Skip"
    private val next = "Next"

    /** The card of the release screenshot: a question, two options with a description, and the field. */
    private val retry = AskUserQuestionItem(
        id = "retry",
        header = "Retries",
        question = "Should a refused upload be retried later?",
        options = listOf(
            AskUserQuestionOption(
                label = "No, drop it (Recommended)",
                description = "The server refused it, so the same upload would be refused again.",
            ),
            AskUserQuestionOption(label = "Yes, once", description = "Try again on the next sync, then drop it."),
        ),
    )

    /** Short enough to fit in any card the tests make: a question and the free-text field. */
    private val short = AskUserQuestionItem(id = "short", question = "Which name?")

    private fun show(height: () -> Int, questions: List<AskUserQuestionItem>) {
        compose.setContent {
            DshTheme {
                Box(Modifier.height(height().dp)) {
                    QuestionsPanel(
                        requestKey = "evt-1",
                        questions = questions,
                        onSubmit = { _: AskUserQuestionAnswer -> null },
                        onDismiss = { null },
                    )
                }
            }
        }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    /** The scrolling body's own viewport: the one node with a scroll action. */
    private fun body() = compose.onNode(hasScrollAction())

    private fun canScrollForward(node: SemanticsNodeInteraction): Boolean {
        val range = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: error("the body has no vertical scroll range")
        return range.value() < range.maxValue()
    }

    private fun scrolledBy(node: SemanticsNodeInteraction): Float {
        val range = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?: error("the body has no vertical scroll range")
        return range.value()
    }

    @Test
    fun theActionRowStaysWholeAndBelowTheBodyAtEveryHeightTheCardIsNotFolded() {
        var height by mutableStateOf(700)
        show({ height }, listOf(retry))

        // 240dp is the floor under which the plain card folds; everything above it keeps its body.
        for (dp in listOf(700, 500, 360, 300, 260, 240)) {
            height = dp
            compose.waitForIdle()
            val limit = with(compose.density) { dp.dp.toPx() }
            val submitBounds = bounds(compose.onNodeWithText(submit))
            val skipBounds = bounds(compose.onNodeWithText(skip))
            val bodyBounds = bounds(body())

            assertNotEquals("Submit was clipped out at ${dp}dp", Rect.Zero, submitBounds)
            assertNotEquals("Skip was clipped out at ${dp}dp", Rect.Zero, skipBounds)
            assertTrue("Submit ends past the card at ${dp}dp", submitBounds.bottom <= limit + 1f)
            assertTrue("the body overlaps the action row at ${dp}dp", bodyBounds.bottom <= submitBounds.top + 1f)
            assertTrue("the body has no height at ${dp}dp", bodyBounds.height > 0f)
        }
    }

    @Test
    fun theBodyScrollsWhenTheCardIsShortAndDoesNotWhenEverythingFits() {
        var height by mutableStateOf(700)
        show({ height }, listOf(retry))

        // The release screenshot: two options and the field are taller than the 360dp card.
        assertTrue("the cut field should be reachable by scrolling", canScrollForward(body()))
        height = 260
        compose.waitForIdle()
        assertTrue("a shorter card still scrolls", canScrollForward(body()))
    }

    @Test
    fun aCardWhoseContentFitsHasNothingToScroll() {
        show({ 700 }, listOf(short))
        assertFalse("nothing is cut, so there is nothing to scroll to", canScrollForward(body()))
    }

    @Test
    fun aStarvedCardFoldsToItsStripAndOffersTheSheet() {
        // About what a phone in landscape leaves for the card with the composer showing.
        show({ 150 }, listOf(retry))

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        // The strip names its way out: the sheet has its own window, and the action row with it.
        compose.onAllNodesWithContentDescription(viewQuestions).assertCountEquals(1)
    }

    @Test
    fun aSecondQuestionOpensAtTheTopOfItsOwnBody() {
        val first = AskUserQuestionItem(
            id = "first",
            question = "Which approach should we take for the migration?",
            detail = (1..8).joinToString(" ") { "Sentence $it of a detail body long enough to fill the card." },
        )
        val second = retry
        show({ 700 }, listOf(first, second))

        // Type into the field at the foot of the first body, which scrolls it, then move on.
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("a migration plan")
        compose.waitForIdle()
        assertTrue("the first body should have scrolled to its field", scrolledBy(body()) > 0f)

        compose.onNodeWithText(next).performClick()
        compose.waitForIdle()

        assertEquals("the second question should start at its top", 0f, scrolledBy(body()), 0.5f)
    }
}
