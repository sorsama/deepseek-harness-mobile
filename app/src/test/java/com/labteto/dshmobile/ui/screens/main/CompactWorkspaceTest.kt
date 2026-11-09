package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactWorkspaceTest {
    private fun rows(count: Int) = (1..count).map { n ->
        SessionRow("s$n", "Session $n", false, false, null, null, null, null, n.toLong(), null)
    }

    @Test fun `running attention extends the ordered prefix instead of hiding a busy session`() {
        val rows = rows(12).map { if (it.sessionId == "s9") it.copy(running = true) else it }
        assertEquals(rows.take(9), compactWorkspaceWindow(rows, 0, emptySet()).rows)
    }

    @Test fun `pending approval question and plan review extend the prefix`() {
        for (kind in listOf("approval", "question", "plan-review")) {
            val rows = rows(12).map { if (it.sessionId == "s8") it.copy(pendingInteraction = kind) else it }
            assertEquals(rows.take(8), compactWorkspaceWindow(rows, 0, emptySet()).rows)
        }
    }

    @Test fun `current beyond fifty reveals the full ordered prefix`() {
        val rows = rows(65)
        assertEquals(rows.take(60), compactWorkspaceWindow(rows, 0, setOf("s60")).rows)
    }

    @Test fun `more reveals five then collapse returns to live attention baseline`() {
        val rows = rows(18).map { if (it.sessionId == "s7") it.copy(running = true) else it }
        val baseline = compactWorkspaceWindow(rows, 0, emptySet())
        assertEquals(7, baseline.rows.size)
        val more = compactWorkspaceWindow(rows, baseline.rows.size + 5, emptySet())
        assertEquals(12, more.rows.size)
        assertEquals(6, more.remaining)
        assertEquals(true, more.canCollapse)
        assertEquals(baseline, compactWorkspaceWindow(rows, 0, emptySet()))
    }

    @Test fun `running child retains its parent prefix while explicit child target opens ancestors`() {
        val rows = rows(60)
        val child = rows(1).single().copy(sessionId = "child", parentSessionId = "s9", origin = "subagent", running = true)
        val children = mapOf("s9" to listOf(child))
        assertEquals(rows.take(9), compactWorkspaceWindow(rows, 0, emptySet(), children).rows)
        assertEquals(setOf("child", "s9"), sessionOpenPath("child", (rows + child).associateBy { it.sessionId }))
    }

    @Test fun `blank roots do not consume prefix and timestamps never reorder`() {
        val rows = rows(9).map { if (it.sessionId == "s2") it.copy(blank = true) else it }
        assertEquals(listOf("s1", "s3", "s4", "s5", "s6"), compactWorkspaceWindow(rows, 0, emptySet()).rows.map { it.sessionId })
    }

    @Test fun `descendant filters preserve ancestor without truncation and guard cycles`() {
        val rows = rows(60)
        val child = rows.first().copy(sessionId = "child", parentSessionId = "s60", origin = "subagent", pendingInteraction = "question")
        val children = mapOf("s60" to listOf(child), "child" to listOf(rows.last()))
        assertEquals(true, sessionTreeMatches(rows.last(), children) { it.pendingInteraction != null })
        assertEquals(false, sessionTreeMatches(rows.first(), children) { it.running })
    }

    @Test fun `empty short and exact groups need no controls`() {
        for (size in listOf(0, 3, 5)) {
            val rows = rows(size)
            val window = compactWorkspaceWindow(rows, 0, emptySet())
            assertEquals(rows, window.rows)
            assertEquals(0, window.remaining)
            assertEquals(false, window.canCollapse)
        }
    }

    @Test fun `fiftieth attention row extends prefix but later attention does not`() {
        val rows = rows(60).map { if (it.sessionId in setOf("s50", "s55")) it.copy(running = true) else it }
        assertEquals(rows.take(50), compactWorkspaceWindow(rows, 0, emptySet()).rows)
    }

    @Test fun `requested full list clamps and collapse is available only above baseline`() {
        val rows = rows(12)
        val full = compactWorkspaceWindow(rows, 100, emptySet())
        assertEquals(rows, full.rows)
        assertEquals(0, full.remaining)
        assertEquals(true, full.canCollapse)
        assertEquals(false, compactWorkspaceWindow(rows, -5, emptySet()).canCollapse)
    }

    @Test fun `drag pickup pins current full prefix before moving attention earlier`() {
        val expansion = WorkspaceExpansion()
        val rows = rows(12)
        val before = compactWorkspaceWindow(rows, expansion.requested("host", "project"), setOf("s12"))
        expansion.retainForDrag("host", "project", before.rows.size)
        val after = compactWorkspaceWindow(rows.reversed(), expansion.requested("host", "project"), setOf("s12"))
        assertEquals(12, after.rows.size)
        expansion.collapse("host", "project")
        assertEquals(5, compactWorkspaceWindow(rows, expansion.requested("host", "project"), emptySet()).rows.size)
    }

    @Test fun `automatic attention watermark survives idle until collapse`() {
        val expansion = WorkspaceExpansion()
        val active = rows(12).map { if (it.sessionId == "s9") it.copy(running = true) else it }
        expansion.observeAttention("host", "project", workspaceAttentionBaseline(active))
        val idle = active.map { it.copy(running = false) }
        assertEquals(9, compactWorkspaceWindow(idle, 0, emptySet(), retainedAttention = expansion.attention("host", "project")).rows.size)
        expansion.collapse("host", "project")
        assertEquals(5, compactWorkspaceWindow(idle, 0, emptySet(), retainedAttention = expansion.attention("host", "project")).rows.size)
        assertEquals(9, compactWorkspaceWindow(active, 0, emptySet(), retainedAttention = expansion.attention("host", "project")).rows.size)
    }

    @Test fun `automatic watermark never retains explicit current target beyond fifty`() {
        val expansion = WorkspaceExpansion()
        val rows = rows(65).map { if (it.sessionId == "s51") it.copy(running = true) else it }
        expansion.observeAttention("host", "project", workspaceAttentionBaseline(rows))
        assertEquals(60, compactWorkspaceWindow(rows, 0, setOf("s60"), retainedAttention = expansion.attention("host", "project")).rows.size)
        assertEquals(5, compactWorkspaceWindow(rows, 0, setOf("s2"), retainedAttention = expansion.attention("host", "project")).rows.size)
        assertEquals(false, compactWorkspaceWindow(rows, 0, emptySet(), retainedAttention = expansion.attention("host", "project")).canCollapse)
    }

    @Test fun `retained attention clamps to shrinking workspace and permits collapse after idle`() {
        val window = compactWorkspaceWindow(rows(7), 0, emptySet(), retainedAttention = 9)
        assertEquals(7, window.rows.size)
        assertEquals(true, window.canCollapse)
    }

    @Test fun `compact policy only applies to actual project workspace All and partial reorder is disabled`() {
        assertEquals(true, usesCompactWorkspace(true, true, false))
        assertEquals(false, usesCompactWorkspace(false, true, false))
        assertEquals(false, usesCompactWorkspace(true, false, false))
        assertEquals(false, usesCompactWorkspace(true, true, true))
        assertEquals(false, canReorderWorkspace(true, compactWorkspaceWindow(rows(9), 0, emptySet())))
        assertEquals(true, canReorderWorkspace(true, compactWorkspaceWindow(rows(9), 9, emptySet())))
        assertEquals(false, canReorderWorkspace(false, null))
    }

    @Test fun `project unit keys include compact controls as part of the drag span`() {
        assertEquals(listOf("header", "row", workspaceControlsKey("project")), projectUnitKeys("header", listOf("row"), "project", true))
        assertEquals(listOf("header"), projectUnitKeys("header", listOf("row"), "project", false))
    }

    @Test fun `automatic attention is bounded to first fifty roots`() {
        val rows = rows(60).map { if (it.sessionId == "s51") it.copy(running = true) else it }
        assertEquals(rows.take(5), compactWorkspaceWindow(rows, 0, emptySet()).rows)
    }
}
