package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.height
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * How the transcript behaves at its tail while a reply streams.
 *
 * Issue #21: the list was normally ordered, so the row the viewport anchored on sat *above* the
 * growing reply. Every chunk displaced everything after it and each displacement was animated,
 * which read as flicker, and staying at the bottom needed a scroll per frame. Reversed, the
 * newest row is the anchor and none of that happens — these pin the properties that follow from
 * that, rather than the layout flag itself.
 */
class ChatTranscriptTailTest {
    @get:Rule val compose = createComposeRule()

    private fun user(seq: Long, text: String): ChatNode =
        UserMessageNode(seq = seq, messageId = "u$seq", blocks = listOf(ChatBlock("text", text)), sourceKind = "user")

    private fun assistant(seq: Long, text: String, streaming: Boolean = false): ChatNode =
        AssistantMessageNode(
            seq = seq,
            messageId = if (streaming) null else "a$seq",
            turn = 1,
            step = 1,
            blocks = listOf(ChatBlock("text", text)),
            streaming = streaming,
        )

    /** Enough rows to overflow any test viewport, so the tail is genuinely a scroll position. */
    private fun longHistory(): List<ChatNode> =
        (0L until 30L).flatMap { listOf(user(it * 2, "question $it"), assistant(it * 2 + 1, "answer $it")) }

    private class Harness {
        var nodes by mutableStateOf<List<ChatNode>>(emptyList())
        lateinit var listState: LazyListState
    }

    private fun harness(initial: List<ChatNode>, followStreaming: Boolean = true): Harness {
        val h = Harness()
        h.nodes = initial
        compose.setContent {
            h.listState = rememberLazyListState()
            DshTheme {
                ChatTranscript(
                    conversation = ConversationSnapshot(sessionId = "s1", nodes = h.nodes, running = true),
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = h.nodes,
                        running = true,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    listState = h.listState,
                    onLoadOlder = {},
                    followStreaming = followStreaming,
                )
            }
        }
        compose.waitForIdle()
        return h
    }

    private fun Harness.atTail(): Boolean = compose.runOnIdle {
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    }

    private fun Harness.firstVisibleKey(): Any? =
        compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key }

    // By hand rather than through scrollToItem: following only changes its mind at the end of a
    // scroll session, and a programmatic scroll can open and close one inside a single frame.
    // In reverse layout a downward drag pulls older rows into view.
    private fun scrollBackByHand() {
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    private fun Harness.returnToTheBottomByHand() {
        repeat(10) {
            if (atTail()) return
            compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertTrue("never got back to the bottom", atTail())
    }

    @Test
    fun opensOnTheNewestMessageWithoutScrolling() {
        val h = harness(longHistory())

        // Index 0 is the newest row and the list starts there, so landing on the tail costs no
        // scroll at all — which is what removed the per-chunk scroll that caused the flicker.
        compose.runOnIdle {
            assertEquals(0, h.listState.firstVisibleItemIndex)
            assertEquals(0, h.listState.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithText("answer 29").assertIsDisplayed()
        compose.onNodeWithText("answer 0").assertDoesNotExist()
    }

    @Test
    fun aGrowingTailExtendsInPlaceAndNeverMovesTheAnchor() {
        val history = longHistory()
        val h = harness(history + assistant(1000, "Hel", streaming = true))

        // Grow the streaming row the way a live reply does, many times over.
        listOf("Hello", "Hello the", "Hello there", "Hello there, this is a much longer reply " +
            "that wraps onto several lines and makes the row substantially taller than it was")
            .forEach { text ->
                compose.runOnIdle { h.nodes = history + assistant(1000, text, streaming = true) }
                compose.waitForIdle()
                // The anchor is the growing row itself, so it never shifts as the text lands.
                compose.runOnIdle {
                    assertEquals(0, h.listState.firstVisibleItemIndex)
                    assertEquals(0, h.listState.firstVisibleItemScrollOffset)
                }
            }
        compose.onNodeWithText("Hello there, this is a much longer reply", substring = true).assertIsDisplayed()
    }

    @Test
    fun readingHistoryMidTurnIsUndisturbedByTheTail() {
        val history = longHistory()
        val h = harness(history + assistant(1000, "start", streaming = true))

        // Scroll back the way a reader does while a turn runs.
        compose.runOnIdle { runBlocking { h.listState.scrollToItem(12) } }
        compose.waitForIdle()
        val anchor = compose.runOnIdle { h.listState.firstVisibleItemIndex }
        assertEquals(12, anchor)

        compose.runOnIdle {
            h.nodes = history + assistant(1000, "start, and then a great deal more text arrives " +
                "while the reader is looking somewhere else entirely", streaming = true)
        }
        compose.waitForIdle()

        // The tail grew off-screen below; nothing the reader is looking at moved. There is no
        // freeze-and-catch-up here because reverse layout makes one unnecessary.
        compose.runOnIdle { assertEquals(anchor, h.listState.firstVisibleItemIndex) }
    }

    @Test
    fun aRowArrivingBelowTheTailIsPinnedIntoView() {
        // Issue #56. The tail stops being one growing row whenever a turn adds a row under it: a
        // settled message, then a tool card or the next segment. The layout anchors the previous
        // bottom row by key, so without a correction each arrival lands one row past the edge.
        val history = longHistory()
        val h = harness(history)

        val arrivals = listOf(
            assistant(60, "a settled reply"),
            user(61, "a follow-up question"),
            assistant(1000, "the next segment, still streaming", streaming = true),
        )
        arrivals.indices.forEach { i ->
            compose.runOnIdle { h.nodes = history + arrivals.take(i + 1) }
            compose.waitForIdle()
            assertTrue("row ${i + 1} landed out of view", h.atTail())
        }
        compose.onNodeWithText("the next segment, still streaming").assertIsDisplayed()
    }

    @Test
    fun aRowArrivingWhileTheReaderIsInHistoryLeavesThemThere() {
        val history = longHistory()
        val h = harness(history)
        scrollBackByHand()
        assertFalse(h.atTail())
        val anchor = h.firstVisibleKey()

        compose.runOnIdle { h.nodes = history + assistant(60, "a reply nobody is watching") }
        compose.waitForIdle()

        // Following is off, so the key anchoring is left alone and nothing the reader sees moves.
        assertEquals(anchor, h.firstVisibleKey())
        assertFalse(h.atTail())
    }

    @Test
    fun followingResumesOnceTheReaderIsBackAtTheBottom() {
        val history = longHistory()
        val h = harness(history)
        scrollBackByHand()
        h.returnToTheBottomByHand()

        compose.runOnIdle { h.nodes = history + assistant(60, "a reply after the round trip") }
        compose.waitForIdle()

        assertTrue(h.atTail())
        compose.onNodeWithText("a reply after the round trip").assertIsDisplayed()
    }

    @Test
    fun turningFollowingOffLeavesAnArrivalBelowTheViewport() {
        val history = longHistory()
        val h = harness(history, followStreaming = false)
        val anchor = compose.onNodeWithText("answer 29").getUnclippedBoundsInRoot()

        compose.runOnIdle { h.nodes = history + assistant(60, "a reply nobody asked to follow") }
        compose.waitForIdle()

        // No pin: the reader keeps the row they were on, and the arrival waits below for the jump
        // pill rather than pulling the page down. The pill counts it, because nobody read it.
        assertFalse(h.atTail())
        assertEquals(anchor.top, compose.onNodeWithText("answer 29").getUnclippedBoundsInRoot().top)
        compose.onNodeWithContentDescription("1 new").assertExists()
    }

    @Test
    fun aGrowingReplyHoldsStillWhenTheReaderIsInsideIt() {
        // One row taller than the screen: the anchor is still index 0, and without the freeze the
        // row grows away from its fixed bottom edge, dragging the text up toward the newest line.
        val reply = "unique-start\n" + (1..200).joinToString("\n") { "line $it" }
        val h = harness(longHistory() + assistant(1000, reply, streaming = true))

        // A slow, short drag inside the tall row, so it cannot fling past it into history.
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeDown(startY = centerY - 100f, endY = centerY + 100f, durationMillis = 600)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, h.listState.firstVisibleItemIndex)
            assertTrue(h.listState.firstVisibleItemScrollOffset > 0)
        }
        val before = compose.onNodeWithText("unique-start", substring = true).getUnclippedBoundsInRoot()

        compose.runOnIdle {
            h.nodes = longHistory() + assistant(
                1000,
                reply + "\n" + (201..400).joinToString("\n") { "line $it" },
                streaming = true,
            )
        }
        compose.waitForIdle()

        val after = compose.onNodeWithText("unique-start", substring = true).getUnclippedBoundsInRoot()
        assertEquals(before.top, after.top)
    }

    @Test
    fun theStreamingRowSurvivesItsOwnSettlement() {
        val history = longHistory()
        val h = harness(history + assistant(1000, "provisional text", streaming = true))
        compose.onNodeWithText("provisional text").assertIsDisplayed()

        // The settlement arrives with a different, durable seq. Keyed on seq the row would be
        // destroyed and rebuilt here, which read as a pop at the end of every reply.
        compose.runOnIdle { h.nodes = history + assistant(60, "provisional text and its ending") }
        compose.waitForIdle()

        compose.onNodeWithText("provisional text and its ending").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, h.listState.firstVisibleItemIndex) }
    }

    @Test
    fun aTranscriptShorterThanTheViewportSitsAtTheBottom() {
        // What the explicit bottom alignment used to buy: a two-message session belongs above the
        // composer, not stranded under the tab strip.
        val h = harness(listOf(user(0, "only question"), assistant(1, "only answer")))

        compose.onNodeWithText("only question").assertIsDisplayed()
        compose.onNodeWithText("only answer").assertIsDisplayed()

        // Screen-space bounds rather than LazyList offsets: under reverseLayout those are reported
        // along a mirrored axis and are easy to read backwards.
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val question = compose.onNodeWithText("only question").getUnclippedBoundsInRoot()
        val answer = compose.onNodeWithText("only answer").getUnclippedBoundsInRoot()

        // Reversing the list must not reverse what the reader sees: oldest still above newest.
        assertTrue("question should sit above answer", question.top < answer.top)
        // And the pair sits at the bottom, with the blank space above rather than below.
        assertTrue("transcript should sit at the bottom", question.top.value > root.height.value * 0.75f)
        assertTrue("answer should reach the bottom", answer.bottom.value > root.height.value * 0.9f)
    }
}
