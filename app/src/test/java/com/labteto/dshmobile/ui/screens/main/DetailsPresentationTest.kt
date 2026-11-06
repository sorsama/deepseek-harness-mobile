package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsPresentationTest {

    // ----- plan switch -------------------------------------------------------------------

    @Test
    fun `the switch shows the request until the host agrees, then the host`() {
        assertTrue(planSwitchChecked(observed = false, pending = true))
        assertFalse(planSwitchChecked(observed = true, pending = false))
        assertTrue(planSwitchChecked(observed = true, pending = null))
        assertFalse(planSwitchChecked(observed = false, pending = null))
    }

    @Test
    fun `a request is let go once the host reports that position`() {
        assertTrue(planPendingSettled(observed = true, pending = true))
        assertTrue(planPendingSettled(observed = false, pending = false))
    }

    @Test
    fun `a request is kept while the host still disagrees or has not said`() {
        assertFalse(planPendingSettled(observed = false, pending = true))
        assertFalse(planPendingSettled(observed = true, pending = false))
        assertFalse(planPendingSettled(observed = null, pending = true))
    }

    @Test
    fun `no request means nothing to settle`() {
        assertFalse(planPendingSettled(observed = true, pending = null))
        assertFalse(planPendingSettled(observed = null, pending = null))
    }

    // ----- subagent rows -----------------------------------------------------------------

    @Test
    fun `one-shot and continuable children can be opened`() {
        assertTrue(subagentOpenable(SubagentListEntry.ChildOneShot(id = "c1", activity = "idle", hasChildren = false)))
        assertTrue(
            subagentOpenable(
                SubagentListEntry.ChildContinuable(id = "c2", activity = "running", hasChildren = false, label = "worker"),
            ),
        )
    }

    @Test
    fun `an external child has no local transcript to open`() {
        assertFalse(subagentOpenable(SubagentListEntry.ChildExternal(id = "e1", activity = "running")))
    }

    @Test
    fun `a diagnostic and an unknown row are notes, not sessions`() {
        assertFalse(subagentOpenable(SubagentListEntry.Diagnostic(id = "d1", reason = "unreadable log")))
        assertFalse(
            subagentOpenable(
                com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry(
                    "future-kind",
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
            ),
        )
    }

    @Test
    fun `a plan switch round trip reads as the request then the host`() {
        // Tap on while the host says off: the switch is on at once.
        var observed = false
        var pending: Boolean? = true
        assertEquals(true, planSwitchChecked(observed, pending))
        // The host reports on; the request is let go and the switch still reads on.
        observed = true
        assertTrue(planPendingSettled(observed, pending))
        pending = null
        assertEquals(true, planSwitchChecked(observed, pending))
    }
}
