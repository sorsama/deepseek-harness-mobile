package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Shapes pinned at tag `dsh-v0.2.1-alpha.1` (`protocol/5badb15.json`), with legacy shapes from
 * `dsh-v0.1.7-rc.2`, decoded by the shipped DTOs and fold.
 *
 * The fixture's commit must equal [DshCore.PROTOCOL_COMMIT], so moving the baseline without
 * re-transcribing the fixture fails here rather than passing on stale shapes.
 */
class PinnedProtocolFixtureTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/protocol/5badb15.json")!!.readText()).jsonObject

    private fun events(): List<SessionEventEnvelope> = fixture.getValue("events").jsonArray.map { raw ->
        val e = raw.jsonObject
        SessionEventEnvelope(e.getValue("type").jsonPrimitive.content, e.getValue("seq").jsonPrimitive.long,
            e.getValue("time").jsonPrimitive.long, e.getValue("data"), surfaceIntent = e["surfaceOp"],
            sourceEventSeqs = (e["sourceEventSeqs"] as? JsonArray)?.map { it.jsonPrimitive.int },
            ignorable = e["ignorable"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun theFixtureRetainsItsHistoricalCommit() {
        assertEquals("5badb15009ae1756c3afe0ae0cef1faafc290ccc", fixture.getValue("upstreamCommit").jsonPrimitive.content)
        assertEquals("0.2.1-alpha.1", fixture.getValue("release").jsonPrimitive.content)
    }

    @Test fun workspaceBaselineCarriesArchiveAndPins() {
        val frame = decodeFromJsonElement(WorkspaceFollowFrameSerializer, fixture.getValue("workspaceBaseline"))
            as WorkspaceFollowFrame.Baseline
        assertEquals(listOf("archived"), frame.archivedSessionIds)
        assertEquals(listOf("newest", "older"), frame.pinnedSessionIds)
        val pinned = decodeFromJsonElement(WorkspaceFollowFrameSerializer, fixture.getValue("workspacePinned"))
        assertEquals(listOf("older"), (pinned as WorkspaceFollowFrame.Pinned).pinnedSessionIds)
    }

    @Test fun aBaselineWithoutPinsMeansTheHostHasNone() {
        val frame = decodeFromJsonElement(WorkspaceFollowFrameSerializer, Json.parseToJsonElement("""
            {"type":"baseline","value":{"items":[],"archivedSessionIds":[]}}
        """)) as WorkspaceFollowFrame.Baseline
        assertNull(frame.pinnedSessionIds)
    }

    @Test fun historyFoldsWithSurfaceProjectionAndV4Shapes() {
        val events = events()
        val snapshot = EventFold("s").fold(events)
        // Model-visible order: the system message replaced in place, and v4's developer message on
        // the surface beside the tool result.
        assertEquals(listOf(0L, 3L, 6L, 7L, 8L), snapshot.effectiveSurface.map { it.seq })
        val projectedImage = snapshot.effectiveSurface.first().data.jsonObject.getValue("content").jsonArray.first().jsonObject
        assertTrue(projectedImage.getValue("offloaded").jsonPrimitive.boolean)
        assertFalse(events.first().data.jsonObject.getValue("content").jsonArray.first().jsonObject.containsKey("offloaded"))
        assertTrue(snapshot.nodes.any { it is OtherNode && it.type == "future/notice" })
        assertEquals(events, snapshot.journal)

        val call = snapshot.nodes.filterIsInstance<ToolCallNode>().single()
        val result = snapshot.nodes.filterIsInstance<ToolResultNode>().single()
        assertEquals(call.callId, result.callId)
        assertTrue(result.isError)

        val tools = snapshot.nodes.filterIsInstance<DeveloperMessageNode>().single()
        assertEquals(listOf("web_search"), tools.addedTools)

        val goal = snapshot.nodes.filterIsInstance<UserMessageNode>().single { it.messageId == "u2" }
        assertTrue("a v4 producer kind is injected context", goal.isInjectedContext)
        assertFalse(snapshot.nodes.filterIsInstance<UserMessageNode>().single { it.messageId == "u1" }.isInjectedContext)

        assertEquals("forked", snapshot.nodes.filterIsInstance<TurnEndNode>().single().reasonKind)
    }

    @Test fun rostersFromEitherHarnessDecode() {
        val current = decodeFromJsonElement(AgentPresetListValue.serializer(), fixture.getValue("roster"))
        assertEquals(listOf("standard", "ptc"), current.presets.map { it.id })
        assertFalse(current.authorable)
        assertTrue(current.modeSelectionEnabled)
        assertEquals(AgentPresetTrust.UNKNOWN, current.presets.first().trust)

        val legacy = decodeFromJsonElement(AgentPresetListValue.serializer(), fixture.getValue("legacyRoster"))
        assertFalse(legacy.modeSelectionEnabled)
        assertTrue(legacy.authorable)
    }

    @Test fun jobRowsDecodeOneAtATime() {
        val rows = jobRowsOf(fixture.getValue("jobRows"))!!
        assertEquals(listOf("bash-1", "bash-2"), rows.map { it.id })
        assertEquals("3/10", rows.first().progress)
        assertEquals("s", rows.first().owner)
        assertEquals(JobStatus.RUNNING, rows.first().status)
        assertEquals("an unknown status costs the field, not the row", JobStatus.UNKNOWN, rows[1].status)
        assertNull(jobRowsOf(buildJsonObject { put("type", "heartbeat") }))
    }

    @Test fun jobFollowFramesDecodeAndAnUnknownOneIsKept() {
        val frames = fixture.getValue("jobFollow").jsonArray.map { decodeFromJsonElement(JobFollowFrameSerializer, it) }
        val opened = frames[0] as JobFollowFrame.Opened
        assertEquals(4L, opened.from)
        val output = frames[1] as JobFollowFrame.Output
        assertTrue(output.lossy)
        assertTrue(output.chunks.first().gapBefore)
        assertEquals("stderr", output.chunks[1].channel)
        assertEquals(12L, output.next)
        val status = frames[2] as JobFollowFrame.Status
        assertEquals(JobStatus.COMPLETED, status.job.status)
        assertEquals("heartbeat", (frames[3] as JobFollowFrame.Unknown).type)
    }

    @Test fun sessionActiveDetailsNameTheRunningWork() {
        val details = decodeFromJsonElement(
            WorkspaceSessionActiveDetails.serializer(),
            fixture.getValue("sessionActive").jsonObject.getValue("details"),
        )
        assertEquals(listOf("turn", "job", "schedule"), details.activity.map { it.kind })
        assertEquals("npm test", details.activity[1].items.single().label)
    }

    @Test fun approvalReasonPrefersTheReadersLanguage() {
        val approval = decodeFromJsonElement(ApprovalRequestEvent.serializer(), fixture.getValue("approval"))
        assertEquals("自动审查拦截了此命令", approval.reasonFor("zh"))
        assertEquals("自动审查拦截了此命令", approval.reasonFor("zh-CN"))
        assertEquals("Auto review blocked this command", approval.reasonFor("th"))
        assertEquals("Auto review denied tool \"bash\": destructive", approval.copy(displayReason = null).reasonFor("en"))
    }

    @Test fun subagentCatalogBecomesSheetRows() {
        val rows = subagentEntriesFromCatalog(fixture.getValue("subagentCatalog")) { it == "child-1" }!!
        assertEquals("a row without an id is skipped, not fatal", 3, rows.size)
        val research = rows[0] as SubagentListEntry.ChildContinuable
        assertEquals("Research", research.label)
        assertEquals("running", research.activity)
        assertEquals("inactive", (rows[1] as SubagentListEntry.ChildOneShot).activity)
        assertEquals("child-3", (rows[2] as SubagentListEntry.Diagnostic).id)
        assertNull("no projection means no catalog, so the caller can fall back", subagentEntriesFromCatalog(null) { false })
    }

    @Test fun optionalFlagsNestedFailuresAndRecoveryFramesDecode() {
        val catalog = decodeFromJsonElement(PermissionCatalog.serializer(), fixture.getValue("permissionCatalog"))
        assertEquals(listOf("read-only", "auto"), catalog.options.map { it.value })
        val failure = decodeFromJsonElement(MessageFeedbackResult.serializer(MessageFeedbackItem.serializer()), fixture.getValue("feedbackConflict"))
        assertFalse(failure.ok)
        assertEquals("Retained note", failure.error?.current?.note)
        val snapshot = decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalSnapshot"))
        val output = decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalOutput"))
        assertEquals("attachment-2", snapshot.info?.controllerId)
        assertEquals(snapshot.sequence!! + 1, output.sequence)
        assertFalse(decodeFromJsonElement(WorkspaceFileText.serializer(), fixture.getValue("fileRead")).eof)
    }
    @Test fun newQuestionAndAutomationProjectionsDecode() {
        val questions = decodeFromJsonElement(UserQuestionsView.serializer(), fixture.getValue("userQuestions"))
        assertEquals("continued", questions.active.single().state)
        assertEquals("Yes", questions.settled.single().answers.single().selected.single())
        val task = decodeFromJsonElement(AutomationTask.serializer(), fixture.getValue("automation"))
        assertEquals(listOf(1, 5), task.weekdays)
        val legacy = Json.parseToJsonElement(javaClass.getResource("/protocol/4878cda.json")!!.readText()).jsonObject
        assertEquals(fixture.getValue("events"), legacy.getValue("events"))
    }
}
