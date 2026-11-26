package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A command the reader ran is told as a system line, not as the terminal rows the agent's tools use.
 *
 * The payloads are the ones the harness logs: `command/run` is `{ commandId, name, args?, source }`
 * with `source` always `{ kind: "user" }`, and `args` is the raw text after the name, so a
 * `/permission read-only` records `" read-only"` with its leading space. `command/done` is
 * `{ commandId, kind: "success" | "error", text? }`; for `/permission` the text is `preset <value>`.
 */
class SystemLinesTest {
    private fun run(seq: Long, id: String, name: String, args: String? = null, source: String? = """{"kind":"user"}""") =
        CommandNode(
            seq, "command/run",
            Json.parseToJsonElement(
                buildString {
                    append("""{"commandId":"$id","name":"$name"""")
                    if (args != null) append(""","args":"$args"""")
                    if (source != null) append(""","source":$source""")
                    append("}")
                },
            ),
        )

    private fun done(seq: Long, id: String, kind: String = "success", text: String? = null) =
        CommandNode(
            seq, "command/done",
            Json.parseToJsonElement(
                buildString {
                    append("""{"commandId":"$id","kind":"$kind"""")
                    if (text != null) append(""","text":"$text"""")
                    append("}")
                },
            ),
        )

    private fun line(vararg nodes: ChatNode): SystemLine? {
        val list = nodes.toList()
        return systemLineFor(list.filterIsInstance<CommandNode>().first { it.isRun }, list)
    }

    // ---- /permission -----------------------------------------------------------------------

    @Test fun `a permission switch names the preset it recorded`() {
        // Exactly what the harness logs for the composer's chip, leading space included.
        val line = line(run(10, "c1", "permission", " read-only"), done(11, "c1", text = "preset read-only"))
        assertEquals(SystemLine.PermissionChanged("read-only"), line)
    }

    @Test fun `a permission switch to full access carries its wire id`() {
        val line = line(run(10, "c1", "permission", " danger-full-access"), done(11, "c1", text = "preset danger-full-access"))
        assertEquals(SystemLine.PermissionChanged("danger-full-access"), line)
    }

    @Test fun `a run that has not settled yet already reads as the change`() {
        assertEquals(SystemLine.PermissionChanged("workspace-write"), line(run(10, "c1", "permission", " workspace-write")))
    }

    @Test fun `a host that records no input is read from the settlement`() {
        val line = line(run(10, "c1", "permission"), done(11, "c1", text = "preset workspace-write"))
        assertEquals(SystemLine.PermissionChanged("workspace-write"), line)
    }

    @Test fun `when the node does not say which preset the line falls back to the unnamed one`() {
        assertEquals(SystemLine.PermissionChanged(null), line(run(10, "c1", "permission")))
        assertEquals(SystemLine.PermissionChanged(null), line(run(10, "c1", "permission"), done(11, "c1", text = "ok")))
        // More than one token is not a preset id.
        assertEquals(SystemLine.PermissionChanged(null), line(run(10, "c1", "permission", " read only")))
    }

    @Test fun `a bare permission command only lists the table, which is not a change`() {
        val line = line(run(10, "c1", "permission", ""), done(11, "c1", text = "current preset workspace-write (available: read-only)"))
        assertEquals(SystemLine.Ran("/permission"), line)
    }

    @Test fun `a refused permission switch says so`() {
        val line = line(run(10, "c1", "permission", " yolo"), done(11, "c1", kind = "error", text = "unknown preset yolo"))
        assertEquals(SystemLine.PermissionFailed, line)
    }

    @Test fun `a done for another command does not settle this one`() {
        val line = line(run(10, "c1", "permission", " read-only"), done(11, "other", kind = "error"))
        assertEquals(SystemLine.PermissionChanged("read-only"), line)
    }

    // ---- other commands --------------------------------------------------------------------

    @Test fun `compact is the reader compacting, and its summary stays the agent's row`() {
        assertEquals(SystemLine.Compacted, line(run(10, "c1", "compact", ""), done(11, "c1")))
        assertEquals(SystemLine.RanFailed("/compact"), line(run(10, "c1", "compact", ""), done(11, "c1", kind = "error")))
    }

    @Test fun `plan is read the way the harness reads it, off leaves and anything else enters`() {
        assertEquals(SystemLine.PlanMode(false), line(run(10, "c1", "plan", " off")))
        assertEquals(SystemLine.PlanMode(true), line(run(10, "c1", "plan", "")))
        assertEquals(SystemLine.PlanMode(true), line(run(10, "c1", "plan", " sketch the migration")))
        assertEquals(SystemLine.RanFailed("/plan"), line(run(10, "c1", "plan", " off"), done(11, "c1", kind = "error")))
    }

    @Test fun `any other command is an unnamed ran line carrying its slash name`() {
        assertEquals(SystemLine.Ran("/xyz"), line(run(10, "c1", "xyz", " whatever it was given"), done(11, "c1")))
        assertEquals(SystemLine.Ran("/goal"), line(run(10, "c1", "goal", " ship it")))
        assertEquals(SystemLine.RanFailed("/xyz"), line(run(10, "c1", "xyz"), done(11, "c1", kind = "error", text = "nope")))
    }

    // ---- who issued it ---------------------------------------------------------------------

    @Test fun `a run without a source is still the reader's, as older logs have none`() {
        assertEquals(SystemLine.Ran("/xyz"), line(run(10, "c1", "xyz", source = null)))
    }

    @Test fun `a source kind this build has not seen is not claimed as you`() {
        val node = run(10, "c1", "permission", " read-only", source = """{"kind":"plugin"}""")
        assertNull(systemLineFor(node, listOf(node)))
        assertTrue(!node.isUserIssued)
    }

    @Test fun `only a run draws a line, and only one with a name`() {
        val settled = done(11, "c1")
        assertNull(systemLineFor(settled, listOf(settled)))
        val nameless = CommandNode(10, "command/run", Json.parseToJsonElement("""{"commandId":"c1","source":{"kind":"user"}}"""))
        assertNull(systemLineFor(nameless, listOf(nameless)))
    }

    // ---- what reaches the transcript -------------------------------------------------------

    @Test fun `the done that settles a run is folded into the run's line`() {
        val nodes = listOf(run(10, "c1", "permission", " read-only"), done(11, "c1", text = "preset read-only"))
        assertEquals(setOf(11L), foldedCommandDones(nodes))
        assertEquals(listOf(10L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `every pair folds on its own id`() {
        val nodes = listOf(
            run(10, "a", "permission", " read-only"), done(11, "a"),
            run(20, "b", "permission", " workspace-write"), done(21, "b"),
        )
        assertEquals(listOf(10L, 20L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `a done whose run is on an earlier page says nothing when it went through`() {
        val nodes = listOf(done(11, "gone"))
        assertEquals(setOf(11L), foldedCommandDones(nodes))
        assertTrue(renderableChatNodes(nodes, running = false).isEmpty())
    }

    @Test fun `a done whose run is on an earlier page keeps its row when it failed`() {
        val nodes = listOf(done(11, "gone", kind = "error", text = "nope"))
        assertTrue(foldedCommandDones(nodes).isEmpty())
        assertEquals(listOf(11L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `a run that is not the reader's keeps both of its rows`() {
        val nodes = listOf(run(10, "c1", "xyz", source = """{"kind":"plugin"}"""), done(11, "c1"))
        assertTrue(foldedCommandDones(nodes).isEmpty())
        assertEquals(listOf(10L, 11L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `the agent's rows draw exactly as before around a reader's command`() {
        val user = UserMessageNode(1, "m1", listOf(ChatBlock("text", "hello")), "user")
        val call = ToolCallNode(seq = 2, callId = "c", name = "read", arguments = "{}", turn = 1, step = 1)
        val reply = AssistantMessageNode(3, "a3", 1, 1, listOf(ChatBlock("text", "done")))
        val failure = TurnErrorNode(4, "no key", "AUTH")
        val plan = PlanModeNode(7, active = true)
        val compaction = CompactionNode(8, "compaction/summary", Json.parseToJsonElement("{}"))
        val nodes = listOf(
            user, call, reply, failure,
            run(5, "p", "permission", " read-only"), done(6, "p"),
            plan, compaction,
        )
        // Only the command's own done is gone; every row the agent or the harness drew is still there.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L), renderableChatNodes(nodes, running = false).map { it.seq })
    }
}
