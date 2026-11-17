package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.connection.WorkStepDisplay
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #96 again, with automatic translation on.
 *
 * Every translation, manual or automatic, makes the host write a `plugin:translator/request` and a
 * `plugin:translator/result` into the session. With the switch on a long session can carry dozens
 * of such pairs, one after each opened thought, so the guarantee has to hold at that volume, in
 * every work-step level, and for both the replayed history and events that arrive live.
 */
class AutoTranslationTranscriptTest {
    private fun request(seq: Long) = OtherNode(seq, "plugin:translator/request", JsonNull, ignorable = true)
    private fun result(seq: Long) = OtherNode(seq, "plugin:translator/result", JsonNull, ignorable = true)

    /** [turns] finished turns, each: prompt, a reasoning reply, and the pair its translation wrote. */
    private fun session(turns: Int): List<ChatNode> = buildList {
        var seq = 0L
        repeat(turns) { t ->
            add(TurnStartNode(seq++, t + 1))
            add(UserMessageNode(seq++, "u$t", listOf(ChatBlock("text", "question $t")), "user"))
            add(
                AssistantMessageNode(
                    seq++, "a$t", t + 1, 1,
                    listOf(ChatBlock("reasoning", "I need to think about $t"), ChatBlock("text", "answer $t")),
                ),
            )
            add(request(seq++))
            add(result(seq++))
        }
    }

    private fun flatten(rows: List<TranscriptRow>): List<ChatNode> = rows.flatMap { row ->
        when (row) {
            is TranscriptRow.Message -> listOf(row.node)
            is TranscriptRow.Tools -> row.calls
            is TranscriptRow.Context -> row.nodes
            is TranscriptRow.Process -> flatten(row.rows)
        }
    }

    @Test fun `translator records never become rows at any work-step level`() {
        val nodes = session(turns = 30) // sixty records
        val turnStarts = nodes.filterIsInstance<TurnStartNode>().map { it.seq }
        WorkStepDisplay.entries.forEach { level ->
            val policy = workStepPolicyFor(level)
            val grouped = applyStepGrouping(
                groupToolRows(renderableChatNodes(nodes, running = false)),
                policy.stepGrouping, turnStarts, running = false,
            )
            val rows = if (policy.foldCompletedTurns) foldProcessRows(grouped, turnStarts, false) else grouped
            val drawn = flatten(rows)
            assertFalse("level $level drew a translator record", drawn.any { it is OtherNode && it.type.startsWith("plugin:translator") })
            // The conversation itself is all there: 30 prompts and 30 replies.
            assertEquals(30, drawn.count { it is UserMessageNode })
            assertEquals(30, drawn.count { it is AssistantMessageNode })
        }
    }

    @Test fun `the pair a translation writes while a turn is running adds no row either`() {
        val nodes = session(turns = 2) + listOf(
            TurnStartNode(100, 3),
            UserMessageNode(101, "u3", listOf(ChatBlock("text", "now")), "user"),
            request(102), result(103),
        )
        val drawn = renderableChatNodes(nodes, running = true)
        assertTrue(drawn.none { it is OtherNode })
    }

    @Test fun `replayed and live translator events fold the same and stay in the journal`() {
        val events = (0 until 12).flatMap { i ->
            listOf(
                SessionEventEnvelope("plugin:translator/request", i * 2L, 1, buildJsonObject { put("provider", "deepseek-account") }, ignorable = true),
                SessionEventEnvelope("plugin:translator/result", i * 2L + 1, 2, buildJsonObject { put("requestSeq", i * 2) }, ignorable = true),
            )
        }
        // History replay: the whole log folded at once.
        val replayed = EventFold("s1").fold(events)
        // Live: one event at a time, as the socket delivers them.
        val live = EventFold.Incremental(com.labteto.dshmobile.core.session.ConversationSnapshot("s1", lastSeq = -1), "s1")
        var liveSnapshot = live.snapshot()
        events.forEach { liveSnapshot = live.apply(it) ?: liveSnapshot }

        assertTrue(renderableChatNodes(replayed.nodes, running = false).isEmpty())
        assertTrue(renderableChatNodes(liveSnapshot.nodes, running = false).isEmpty())
        // Trajectory, which lists everything raw, still has them.
        assertEquals(24, replayed.journal.size)
        assertEquals(24, liveSnapshot.journal.size)
    }

    // The engine and the translation UI take a key and some text and give text back. If they could
    // reach the session fold they could add to the transcript, so the dependency stays out.
    @Test fun `the translation code cannot reach the transcript`() {
        val root = File("src/main/java/com/labteto/dshmobile").let { if (it.exists()) it else File("app/src/main/java/com/labteto/dshmobile") }
        val files = File(root, "translation").listFiles { f -> f.extension == "kt" }.orEmpty().toList() +
            File(root, "ui/screens/main/ReasoningTranslation.kt")
        assertTrue("no translation sources found under ${root.absolutePath}", files.size >= 4)
        val forbidden = listOf("EventFold", "ConversationSnapshot", "SessionStore", "OtherNode", "renderableChatNodes", "ChatNode")
        files.forEach { file ->
            val code = file.readText().lineSequence().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
            forbidden.forEach { name ->
                assertFalse("${file.name} refers to $name", Regex("\\b$name\\b").containsMatchIn(code.replace("rememberSessionStore", "")))
            }
        }
    }
}
