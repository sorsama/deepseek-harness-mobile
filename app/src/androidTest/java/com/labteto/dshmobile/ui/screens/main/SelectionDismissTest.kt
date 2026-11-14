package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A tap anywhere in the transcript ends a text selection made in it (issue #90).
 *
 * Every paragraph and every Thinking panel is its own SelectionContainer, and a container only
 * clears itself for a tap inside its own bounds. These pin the tap on a different paragraph, on the
 * inside of an expanded Thinking panel, and on a card's header, and the things that must not
 * change with it: the tap still reaches what it landed on, a draft keeps its focus when nothing is
 * selected, and the long press that starts a selection does not end it.
 *
 * The toolbar is a recording fake: it is what the platform shows (Copy / Select all) while a
 * selection is live, and it is told to hide when the selection is released, so it is the one
 * observable of a selection that does not depend on how the container is built.
 */
class SelectionDismissTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int) = context.getString(id)

    private class RecordingToolbar : TextToolbar {
        var shown = false
        override val status: TextToolbarStatus get() = if (shown) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
        override fun hide() { shown = false }
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) { shown = true }
    }

    private val toolbar = RecordingToolbar()

    private val first = "First paragraph of the reply, plain prose to select."
    private val second = "Second paragraph of the reply, also plain prose."
    private val thought = "Thinking it over before answering, in plain words."

    private fun assistant(seq: Long, vararg blocks: ChatBlock): ChatNode =
        AssistantMessageNode(seq = seq, messageId = "a$seq", turn = 1, step = 1, blocks = blocks.toList())

    private fun text(value: String) = ChatBlock("text", value)

    private fun reasoning(value: String) = ChatBlock("reasoning", value)

    @Composable
    private fun Transcript(nodes: List<ChatNode>, modifier: Modifier = Modifier) {
        DshTheme {
            ChatTranscript(
                conversation = ConversationSnapshot(sessionId = "s1", nodes = nodes),
                loading = false,
                loadingOlder = false,
                loadOlderFailed = false,
                context = ChatNodeContext(
                    nodes = nodes,
                    running = false,
                    cwd = "/home/me/project",
                    onOpenSubagent = {},
                    onBranchFrom = {},
                    onFeedback = { _, _ -> },
                ),
                listState = rememberLazyListState(),
                onLoadOlder = {},
                modifier = modifier,
            )
        }
    }

    private fun transcript(nodes: List<ChatNode>) {
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) { Transcript(nodes) }
        }
        compose.waitForIdle()
    }

    // The unmerged tree on purpose. A message is one clickable column (it toggles the action row), so
    // in the merged tree the node matching a paragraph is the whole column, and its centre can fall
    // on a Thinking header above the text rather than on the text.
    private fun textNode(textStart: String) =
        compose.onNodeWithText(textStart, substring = true, useUnmergedTree = true)

    private fun select(textStart: String) {
        textNode(textStart).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun tap(textStart: String) {
        textNode(textStart).performTouchInput { click(centerLeft.copy(x = 12f)) }
        compose.waitForIdle()
    }

    @Test
    fun aLongPressStartsASelectionAndItStaysUntilSomethingElseIsTapped() {
        transcript(listOf(assistant(2, text(first), text(second))))

        select("First paragraph")

        assertTrue("Holding a word should select it and show Copy / Select all", toolbar.shown)
    }

    @Test
    fun tappingAnotherParagraphClearsTheSelection() {
        transcript(listOf(assistant(2, text(first), text(second))))
        select("First paragraph")
        assertTrue(toolbar.shown)

        tap("Second paragraph")

        assertFalse("The highlight, handles and toolbar should be gone", toolbar.shown)
    }

    @Test
    fun tappingTheSameParagraphClearsTheSelection() {
        transcript(listOf(assistant(2, text(first), text(second))))
        select("First paragraph")
        assertTrue(toolbar.shown)

        tap("First paragraph")

        assertFalse(toolbar.shown)
    }

    @Test
    fun tappingInsideAnExpandedThinkingPanelClearsTheSelection() {
        transcript(listOf(assistant(2, reasoning(thought), text(first))))
        compose.onNodeWithText(string(R.string.chat_thought_process)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(thought, substring = true).assertExists()

        select("First paragraph")
        assertTrue(toolbar.shown)
        tap(thought)

        assertFalse("A tap inside an expanded card used to leave the selection up", toolbar.shown)
    }

    @Test
    fun selectingInsideAThinkingPanelAndTappingTheReplyClearsIt() {
        transcript(listOf(assistant(2, reasoning(thought), text(first))))
        compose.onNodeWithText(string(R.string.chat_thought_process)).performClick()
        compose.waitForIdle()

        select(thought)
        assertTrue(toolbar.shown)
        tap("First paragraph")

        assertFalse(toolbar.shown)
    }

    @Test
    fun theTapThatClearsASelectionStillReachesWhatItLandedOn() {
        transcript(listOf(assistant(2, reasoning(thought), text(first))))
        select("First paragraph")
        assertTrue(toolbar.shown)

        // The header is a button that opens the panel. It must still open on the tap that clears.
        compose.onNodeWithText(string(R.string.chat_thought_process)).performClick()
        compose.waitForIdle()

        assertFalse(toolbar.shown)
        compose.onNodeWithText(thought, substring = true).assertExists()
    }

    @Test
    fun aTapOnTheTranscriptLeavesADraftsFocusAloneWhenNothingIsSelected() {
        val draftFocus = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                Column(Modifier.fillMaxWidth()) {
                    var draft by remember { mutableStateOf("") }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(draftFocus),
                    )
                    Transcript(listOf(assistant(2, text(first), text(second))), Modifier.height(400.dp))
                    LaunchedEffect(Unit) { draftFocus.requestFocus() }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        tap("Second paragraph")

        // No selection was live, so the keyboard stays with the draft.
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(false, toolbar.shown)
    }
}
