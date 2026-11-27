package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Paging older history in by reading up to it: the transcript opens on the newest page of a long session (60 messages
 * of 92, as the demo harness's long session does), and the reader's drag up to the oldest loaded message is what asks
 * for the page before it.
 *
 * Pinned because the trigger and the tail following meet here. The page lands while the reader is up at the top, and
 * the transcript must leave them there instead of pinning the newest message again, which only holds when the
 * transcript has seen the reader leave the tail, that is a scroll in progress. The iOS chat pane's debug scenario
 * (`-DshChatScroll older`) drives the list the same way from code; the second test is that drive.
 */
class ChatTranscriptPagingTest {
    @get:Rule val compose = createComposeRule()

    private fun user(seq: Long, text: String): ChatNode =
        UserMessageNode(seq = seq, messageId = "u$seq", blocks = listOf(ChatBlock("text", text)), sourceKind = "user")

    private fun assistant(seq: Long, text: String): ChatNode =
        AssistantMessageNode(seq = seq, messageId = "a$seq", turn = 1, step = 1, blocks = listOf(ChatBlock("text", text)))

    /** The turns in [range], a question and its answer each. */
    private fun turns(range: IntRange): List<ChatNode> =
        range.flatMap { listOf(user(it * 2L, "question $it"), assistant(it * 2L + 1, "answer $it")) }

    private class Harness {
        var nodes by mutableStateOf<List<ChatNode>>(emptyList())
        var hasMore by mutableStateOf(true)
        var loadingOlder by mutableStateOf(false)
        var asked = 0
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
    }

    /** The newest page: turns 16 to 45, 60 messages, with older history left to load. */
    private fun harness(): Harness {
        val h = Harness()
        h.nodes = turns(16..45)
        compose.setContent {
            h.listState = rememberLazyListState()
            h.scope = rememberCoroutineScope()
            DshTheme {
                ChatTranscript(
                    conversation = ConversationSnapshot(sessionId = "s1", nodes = h.nodes, hasMore = h.hasMore),
                    loading = false,
                    loadingOlder = h.loadingOlder,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = h.nodes, running = false, cwd = null),
                    listState = h.listState,
                    onLoadOlder = {
                        h.asked++
                        h.loadingOlder = true
                    },
                )
            }
        }
        compose.waitForIdle()
        return h
    }

    /** What the store does when the page it was asked for arrives: the 32 older messages, and nothing before them. */
    private fun Harness.landOlderPage() {
        compose.runOnIdle {
            nodes = turns(0..15) + nodes
            hasMore = false
            loadingOlder = false
        }
        compose.waitForIdle()
    }

    private fun Harness.asked(): Int = compose.runOnIdle { asked }

    private fun Harness.atTail(): Boolean = compose.runOnIdle {
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    }

    /** A finger's swipe down, which in this newest-at-the-bottom list moves toward the older messages. */
    private fun swipeTowardOlder() = compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }

    @Test
    fun aDragUpToTheOldestMessagePagesInTheOneBeforeAndLeavesTheReaderThere() {
        val h = harness()
        assertTrue(h.atTail())

        var swipes = 0
        while (h.asked() == 0 && swipes++ < 40) swipeTowardOlder()
        assertEquals("the transcript asks for one page", 1, h.asked())

        h.landOlderPage()
        assertTrue("the landed page leaves the reader up in the history, not at the newest message", !h.atTail())

        swipes = 0
        while (swipes++ < 40) swipeTowardOlder()
        compose.onNodeWithText("question 0").assertIsDisplayed()
        assertEquals("nothing older is asked for once the start has loaded", 1, h.asked())
    }

    @Test
    fun aScrollSessionDrivenFromCodeReadsAsTheReadersDrag() {
        val h = harness()

        compose.runOnIdle { h.scope.launch { dragToOldestLoaded(h.listState) } }
        compose.waitForIdle()
        assertEquals("the transcript asks for one page", 1, h.asked())

        h.landOlderPage()
        assertTrue("the landed page leaves the reader up in the history, not at the newest message", !h.atTail())

        compose.runOnIdle { h.scope.launch { dragToOldestLoaded(h.listState) } }
        compose.waitForIdle()
        compose.onNodeWithText("question 0").assertIsDisplayed()
    }

    /** The iOS pane's `dragToOldestLoaded` (IosChatPane.kt, iosMain), copied: a step a frame in one scroll session. */
    private suspend fun dragToOldestLoaded(state: LazyListState) {
        state.scroll(MutatePriority.UserInput) {
            var still = 0
            repeat(600) {
                val info = state.layoutInfo
                val step = ((info.viewportEndOffset - info.viewportStartOffset) * 0.6f).coerceAtLeast(1f)
                if (scrollBy(step) < 0.5f) {
                    if (++still >= 3) return@scroll
                } else {
                    still = 0
                }
                withFrameNanos { }
            }
        }
    }
}
