package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionOutcomeTest {

    @Test
    fun `done succeeded and failed did not`() {
        assertTrue(ActionOutcome.Done(Unit).succeeded)
        assertTrue(ActionOutcome.Done("new-session-id").succeeded)
        assertFalse(ActionOutcome.Failed().succeeded)
        assertFalse(ActionOutcome.Failed("busy").succeeded)
    }

    @Test
    fun `a failure with no reason is just our own sentence`() {
        assertEquals("Couldn't rename this session.", failureNotice("Couldn't rename this session.", null))
        assertEquals("Couldn't rename this session.", failureNotice("Couldn't rename this session.", ""))
        assertEquals("Couldn't rename this session.", failureNotice("Couldn't rename this session.", "  \n \t "))
    }

    @Test
    fun `the host reason follows our sentence`() {
        assertEquals(
            "Couldn't stop that job. Session is busy.",
            failureNotice("Couldn't stop that job.", "Session is busy."),
        )
    }

    @Test
    fun `a multi line reason is collapsed to one line`() {
        assertEquals(
            "Couldn't fork this session. first second",
            failureNotice("Couldn't fork this session.", "  first\n\n  second \r\n"),
        )
    }

    @Test
    fun `a long reason is cut and marked`() {
        val long = "x".repeat(500)
        val notice = failureNotice("Couldn't do it.", long)
        assertTrue(notice.startsWith("Couldn't do it. xxx"))
        assertTrue(notice.endsWith("…"))
        // Our sentence, one space, then at most 120 characters of the reason.
        assertTrue(notice.length <= "Couldn't do it. ".length + 120)
    }

    @Test
    fun `a reason right at the limit is kept whole`() {
        val exact = "y".repeat(120)
        assertEquals("Label. $exact", failureNotice("Label.", exact))
    }
}
