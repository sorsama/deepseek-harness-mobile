package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * When a session row draws the mascot. It is the app's one expressive element, so it marks a session
 * worth noticing (running, or waiting for the person) and every other row carries a quiet dot, the
 * same size slot, so titles line up.
 */
class SessionRowLeadTest {

    private fun session(running: Boolean = false, pending: String? = null) = SessionRow(
        sessionId = "s",
        title = "s",
        running = running,
        blank = false,
        parentSessionId = null,
        origin = null,
        cwd = null,
        agentPreset = null,
        updatedAt = 0L,
        pendingInteraction = pending,
    )

    @Test
    fun `an idle session has no mascot`() {
        assertFalse(rowShowsMascot(session()))
        assertEquals(RowLead.Quiet, rowLeadOf(session()))
    }

    @Test
    fun `a running session shows the mascot`() {
        assertTrue(rowShowsMascot(session(running = true)))
        assertEquals(RowLead.Running, rowLeadOf(session(running = true)))
    }

    @Test
    fun `a session waiting for the person shows the mascot, whatever it is waiting for`() {
        for (kind in listOf("approval", "question", "plan-review")) {
            assertTrue(rowShowsMascot(session(pending = kind)))
            assertEquals(RowLead.NeedsYou, rowLeadOf(session(pending = kind)))
        }
    }

    @Test
    fun `a session that is running and waiting is shown as waiting`() {
        // The person is the one who has to act, so the amber wins over the accent.
        assertEquals(RowLead.NeedsYou, rowLeadOf(session(running = true, pending = "approval")))
    }

    @Test
    fun `an archived row never shows the mascot`() {
        assertFalse(rowShowsMascot(session(running = true), archived = true))
        assertFalse(rowShowsMascot(session(pending = "question"), archived = true))
        assertEquals(RowLead.Quiet, rowLeadOf(session(), archived = true))
    }
}
