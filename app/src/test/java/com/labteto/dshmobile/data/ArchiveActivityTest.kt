package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.JobStatus
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.SessionActivity
import com.labteto.dshmobile.core.wire.dto.SessionActivityItem
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tapping Archive on a session known to be busy opens "Stop and archive" at once, and a session
 * that turns out to be idle still ends in a normal archive. These pin both decisions.
 */
class ArchiveActivityTest {

    private fun job(id: String, label: String, finishedAt: Long? = null) = JobView(
        id = id,
        kind = "bash",
        label = label,
        status = if (finishedAt == null) JobStatus.RUNNING else JobStatus.COMPLETED,
        startedAt = 1_000L,
        finishedAt = finishedAt,
    )

    private fun child(id: String, activity: String, label: String? = null) =
        SubagentListEntry.ChildOneShot(id = id, activity = activity, hasChildren = false, label = label)

    private fun sessionActive(details: String): RpcResult.Err =
        RpcResult.Err(RpcError("workspace/session-active", "busy", Json.parseToJsonElement(details)))

    private val archived = RpcResult.Ok(WorkspaceArchiveValue(archivedSessionIds = listOf("s")))

    // ------------------------------------------------------------------ which dialog opens
    @Test
    fun `an idle session opens the plain confirm`() {
        assertTrue(knownArchiveActivity(turnRunning = false).isEmpty())
        assertNull(archiveStopDialogFor("s", knownArchiveActivity(turnRunning = false)))
    }

    @Test
    fun `a running turn opens stop and archive`() {
        val dialog = archiveStopDialogFor("s", knownArchiveActivity(turnRunning = true))
        assertEquals("s", dialog?.sessionId)
        assertEquals(listOf(SessionActivity(kind = "turn")), dialog?.activity)
    }

    @Test
    fun `a running background job opens stop and archive with its command line`() {
        val known = knownArchiveActivity(
            turnRunning = false,
            jobs = listOf(job("bash-1", "bash /tmp/monitor60.sh > /tmp/monitor60.log 2>&1")),
        )
        val dialog = archiveStopDialogFor("s", known)
        assertEquals(
            listOf(
                SessionActivity(
                    kind = "job",
                    items = listOf(SessionActivityItem("bash-1", "bash /tmp/monitor60.sh > /tmp/monitor60.log 2>&1")),
                ),
            ),
            dialog?.activity,
        )
    }

    @Test
    fun `a finished job alone keeps the plain confirm`() {
        val known = knownArchiveActivity(turnRunning = false, jobs = listOf(job("bash-1", "npm test", finishedAt = 2_000L)))
        assertNull(archiveStopDialogFor("s", known))
    }

    @Test
    fun `only running subagents count`() {
        val known = knownArchiveActivity(
            turnRunning = false,
            subagents = listOf(child("c1", "running", "Review the diff"), child("c2", "idle", "Old helper")),
        )
        assertEquals(
            listOf(SessionActivity(kind = "subagent", items = listOf(SessionActivityItem("c1", "Review the diff")))),
            known,
        )
    }

    @Test
    fun `a turn with jobs lists the turn first and then the jobs`() {
        val known = knownArchiveActivity(
            turnRunning = true,
            jobs = listOf(job("bash-1", "npm test"), job("bash-2", "tail -f app.log")),
        )
        assertEquals(listOf("turn", "job"), known.map { it.kind })
        assertEquals(listOf("npm test", "tail -f app.log"), known[1].items.map { it.label })
    }

    // ------------------------------------------------------------------ what the host answered
    @Test
    fun `a plain archive of an idle session archives`() {
        assertEquals(ArchiveOutcome.Archived, archiveOutcomeOf("s", stopActivity = false, result = archived))
    }

    @Test
    fun `a refused plain archive carries the host's list of running work`() {
        val outcome = archiveOutcomeOf(
            "s",
            stopActivity = false,
            result = sessionActive(
                """{"sessionId":"s","activity":[{"kind":"turn"},{"kind":"job","items":[{"id":"bash-1","label":"npm test"}]}]}""",
            ),
        )
        outcome as ArchiveOutcome.Busy
        assertEquals("s", outcome.sessionId)
        assertEquals(listOf("turn", "job"), outcome.activity.map { it.kind })
        assertEquals("npm test", outcome.activity[1].items.single().label)
    }

    @Test
    fun `a refusal with kinds only keeps the kinds`() {
        val outcome = archiveOutcomeOf(
            "s",
            stopActivity = false,
            result = sessionActive("""{"sessionId":"s","activity":[{"kind":"subagent","items":[{"id":"c1"}]}]}"""),
        ) as ArchiveOutcome.Busy
        assertEquals(listOf("subagent"), outcome.activity.map { it.kind })
        assertNull(outcome.activity.single().items.single().label)
    }

    @Test
    fun `a refusal with unreadable details is still a busy session`() {
        val outcome = archiveOutcomeOf("s", stopActivity = false, result = sessionActive("""{"unexpected":true}"""))
        assertEquals(ArchiveOutcome.Busy("s", emptyList()), outcome)
    }

    @Test
    fun `stop and archive on a session that turned out idle still archives`() {
        // The app thought work was running; by the time the person confirmed it had finished. The
        // host answers the stopping request with a normal archive, not an error.
        assertEquals(ArchiveOutcome.Archived, archiveOutcomeOf("s", stopActivity = true, result = archived))
    }

    @Test
    fun `a refusal to a stopping request is a failure, not a second dialog`() {
        val outcome = archiveOutcomeOf("s", stopActivity = true, result = sessionActive("""{"sessionId":"s","activity":[]}"""))
        assertEquals(ArchiveOutcome.Failed, outcome)
    }

    @Test
    fun `any other error is a failure`() {
        val outcome = archiveOutcomeOf(
            "s",
            stopActivity = false,
            result = RpcResult.Err(RpcError("session/not-found", "gone", JsonPrimitive("x"))),
        )
        assertEquals(ArchiveOutcome.Failed, outcome)
    }
}
