package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the reader did that is not a chat message reads as a quiet system line in the transcript,
 * apart from the agent's own rows.
 *
 * Before, `/permission` drew the terminal row the agent's tools use (`>_ /permission`) and a second
 * one for its settlement (`>_ /command/done`), which read as the agent shelling out. These pin the
 * transcript end of that: one centred, muted, non-interactive line, in both reading directions.
 */
class SystemLineTranscriptTest {
    @get:Rule val compose = createComposeRule()

    private fun user(seq: Long, text: String): ChatNode =
        UserMessageNode(seq = seq, messageId = "u$seq", blocks = listOf(ChatBlock("text", text)), sourceKind = "user")

    private fun assistant(seq: Long, text: String): ChatNode =
        AssistantMessageNode(seq = seq, messageId = "a$seq", turn = 1, step = 1, blocks = listOf(ChatBlock("text", text)))

    /** The two halves the harness logs for one command, in its own payload shape. */
    private fun command(seq: Long, id: String, name: String, args: String, doneText: String): List<ChatNode> = listOf(
        CommandNode(
            seq, "command/run",
            Json.parseToJsonElement("""{"commandId":"$id","name":"$name","args":"$args","source":{"kind":"user"}}"""),
        ),
        CommandNode(
            seq + 1, "command/done",
            Json.parseToJsonElement("""{"commandId":"$id","kind":"success","text":"$doneText"}"""),
        ),
    )

    private fun show(nodes: List<ChatNode>, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ChatTranscript(
                        conversation = ConversationSnapshot(sessionId = "s1", nodes = nodes, running = false),
                        loading = false,
                        loadingOlder = false,
                        loadOlderFailed = false,
                        context = ChatNodeContext(
                            nodes = nodes,
                            running = false,
                            cwd = null,
                            onOpenSubagent = {},
                            onBranchFrom = {},
                            onFeedback = { _, _ -> },
                        ),
                        listState = rememberLazyListState(),
                        onLoadOlder = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aPermissionChangeIsOneMutedLineAndNotTwoTerminalRows() {
        show(
            listOf(user(1, "first message"), assistant(2, "agent reply")) +
                command(3, "c1", "permission", " read-only", "preset read-only") +
                listOf(user(5, "second message")),
        )
        compose.onNodeWithContentDescription("You changed permission to Read Only").assertIsDisplayed()
        // Neither half of the old terminal rows is drawn any more.
        compose.onNodeWithText("/permission").assertDoesNotExist()
        compose.onNodeWithText("/command/done", substring = true).assertDoesNotExist()
    }

    @Test
    fun theLineHasNoClickTarget() {
        show(command(3, "c1", "permission", " workspace-write", "preset workspace-write"))
        compose.onNodeWithContentDescription("You changed permission to Workspace Write").assertHasNoClickAction()
    }

    @Test
    fun theAgentsRowsAndTheReadersMessagesAreStillThere() {
        show(
            listOf(user(1, "first message"), assistant(2, "agent reply")) +
                command(3, "c1", "permission", " read-only", "preset read-only") +
                listOf(user(5, "second message")),
        )
        compose.onNodeWithText("first message").assertIsDisplayed()
        compose.onNodeWithText("agent reply").assertIsDisplayed()
        compose.onNodeWithText("second message").assertIsDisplayed()
    }

    @Test
    fun anUnnamedCommandReadsAsYouRanIt() {
        show(command(3, "c1", "xyz", " anything", "ok"))
        compose.onNodeWithContentDescription("You ran /xyz").assertIsDisplayed()
    }

    @Test
    fun theLineIsCentred() = assertCentred(LayoutDirection.Ltr)

    @Test
    fun theLineIsCentredRightToLeftToo() = assertCentred(LayoutDirection.Rtl)

    private fun assertCentred(direction: LayoutDirection) {
        show(command(3, "c1", "permission", " read-only", "preset read-only"), direction)
        val line = compose.onNodeWithContentDescription("You changed permission to Read Only")
        line.assertIsDisplayed()
        val root = compose.onRoot().getBoundsInRoot()
        val bounds = line.getBoundsInRoot()
        // The line spans the row (hairlines either side of the sentence), so its middle is the
        // transcript's middle whichever way the layout reads.
        assertEquals(
            (root.left + root.right).value / 2f,
            (bounds.left + bounds.right).value / 2f,
            1.5f,
        )
        // One quiet line, not a block.
        assertTrue("the line is ${bounds.height} tall", bounds.height < 48.dp)
    }
}
