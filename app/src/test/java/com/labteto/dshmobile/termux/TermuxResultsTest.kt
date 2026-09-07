package com.labteto.dshmobile.termux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Termux reports two verdicts at once — its own (`err`) and the script's (`exitCode`) — and the
 * app must never read one as the other: a script that exited 2 still *ran*, and a policy refusal
 * that never ran anything must not look like a script failure.
 */
class TermuxResultsTest {

    @Test
    fun `err of -1 means the command ran, whatever it exited with`() {
        assertEquals(
            TermuxResult.Ok("DSHM:exited\ntail", "", 2),
            classifyResult(TermuxResultFields("DSHM:exited\ntail", "", exitCode = 2, err = -1, errmsg = null)),
        )
    }

    @Test
    fun `the allow-external-apps refusal is named`() {
        val fields = TermuxResultFields(
            stdout = "",
            stderr = "",
            exitCode = -1,
            err = 1,
            errmsg = "RunCommandService: The allow-external-apps property is not set to true in termux.properties",
        )
        assertEquals(TermuxResult.ExternalAppsDisabled, classifyResult(fields))
    }

    @Test
    fun `any other Termux error keeps its code and message`() {
        assertEquals(
            TermuxResult.Failed(3, "executable not found"),
            classifyResult(TermuxResultFields("", "", -1, 3, "executable not found")),
        )
    }
}
