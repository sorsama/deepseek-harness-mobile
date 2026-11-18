package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Timing, records and totals derived from a session journal.
 *
 * Event shapes are the real ones: `step/start`, `assistant/message` (with `usage`, `stream` and
 * `message.source`), `tool/call`, `tool/result`, `turn/start` and `turn/end` as recorded in
 * `apps/cli/tests/profiles/headless/tests/expected/headless-profile/session.expected.jsonl`
 * (harness 0.2.1-rc.2), and `approval/asked` / `approval/decided` as appended by
 * `packages/interaction/user-approval/src/index.ts`. Those snapshots normalise every time to 0, so
 * the times below are synthesised to make each case's arithmetic checkable by hand; the one test that
 * keeps them at 0 is the test of what happens when a log has none.
 */
class TrajectoryTimingTest {

    private fun ev(type: String, seq: Long, time: Long, json: String) =
        SessionEventEnvelope(type, seq, time, WireJson.parseToJsonElement(json))

    private fun turnStart(seq: Long, time: Long, turn: Int = 1) = ev("turn/start", seq, time, """{"turn":$turn}""")
    private fun turnEnd(seq: Long, time: Long, turn: Int = 1) =
        ev("turn/end", seq, time, """{"turn":$turn,"reason":{"kind":"completed"}}""")
    private fun stepStart(seq: Long, time: Long, step: Int, turn: Int = 1) =
        ev("step/start", seq, time, """{"turn":$turn,"step":$step}""")
    private fun stepEnd(seq: Long, time: Long, step: Int, turn: Int = 1) =
        ev("step/end", seq, time, """{"turn":$turn,"step":$step}""")

    private fun message(
        seq: Long,
        time: Long,
        step: Int,
        content: String,
        usage: String? = null,
        stream: String? = null,
        turn: Int = 1,
        interrupted: Boolean = false,
    ) = ev(
        "assistant/message", seq, time,
        """{"turn":$turn,"step":$step,"message":{"role":"assistant","content":$content,
            "source":{"kind":"model","provider":"cli-mock","model":"cli-mock"},"id":"m$seq"}
            ${usage?.let { ""","usage":$it""" } ?: ""}
            ${stream?.let { ""","stream":$it""" } ?: ""}
            ${if (interrupted) ""","interrupted":true""" else ""}}""",
    )

    private fun toolCall(seq: Long, time: Long, callId: String, step: Int = 1, turn: Int = 1, name: String = "bash") =
        ev("tool/call", seq, time, """{"turn":$turn,"step":$step,"callId":"$callId","name":"$name","arguments":"{\"command\":\"ls\"}"}""")

    private fun toolResult(seq: Long, time: Long, callId: String, step: Int = 1, turn: Int = 1, isError: Boolean = false) =
        ev(
            "tool/result", seq, time,
            """{"turn":$turn,"step":$step,"message":{"role":"tool","source":{"kind":"tool","callId":"$callId"},
                "toolCallId":"$callId","content":[{"type":"text","text":"done $callId"}],"isError":$isError,"id":"r$seq"}}""",
        )

    private fun asked(seq: Long, time: Long, id: String, callId: String?) = ev(
        "approval/asked", seq, time,
        """{"id":"$id","toolName":"bash"${callId?.let { ""","callId":"$it"""" } ?: ""}}""",
    )

    private fun decided(seq: Long, time: Long, id: String) =
        ev("approval/decided", seq, time, """{"id":"$id","outcome":"allowed-once"}""")

    private val reasoningThenTool =
        """[{"type":"reasoning","text":"Inspecting the task before the tool call."},
            {"type":"tool-call","id":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}]"""
    private val textOnly = """[{"type":"text","text":"All done."}]"""

    /** The first token of step 1 arrives 600 ms after the step starts. */
    private val stepOneStream =
        """[{"type":"chunk","time":1000600,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
            {"type":"reasoning-chunks","time0":1000650,"index":0,"dt":[],"texts":["Inspecting"]},
            {"type":"chunk","time":1001900,"chunk":{"type":"finish","reason":{"kind":"tool-calls"}}}]"""

    /**
     * One turn, two steps, one tool call that waited two seconds for approval.
     *
     * ```
     * 1_000_000 turn/start
     * 1_000_050 step 1 starts          model, waiting for the first token
     * 1_000_650 first token            model, generating
     * 1_002_000 step 1 message         other (100 ms)
     * 1_002_100 tool/call              tool
     * 1_002_200 approval/asked         approval wait
     * 1_004_200 approval/decided       tool
     * 1_005_000 tool/result            other (100 ms)
     * 1_005_100 step 2 starts          model, first token unknown
     * 1_006_100 step 2 message         other (200 ms)
     * 1_006_300 turn/end
     * ```
     */
    private val recorded = listOf(
        turnStart(10, 1_000_000),
        stepStart(11, 1_000_050, 1),
        message(
            15, 1_002_000, 1, reasoningThenTool,
            usage = """{"inputTokens":11,"outputTokens":3,"cacheReadTokens":2}""",
            stream = stepOneStream,
        ),
        toolCall(16, 1_002_100, "c1"),
        asked(17, 1_002_200, "a1", "c1"),
        decided(18, 1_004_200, "a1"),
        toolResult(19, 1_005_000, "c1"),
        stepEnd(20, 1_005_010, 1),
        stepStart(21, 1_005_100, 2),
        message(25, 1_006_100, 2, textOnly, usage = """{"inputTokens":7,"outputTokens":5,"reasoningTokens":1}"""),
        stepEnd(26, 1_006_110, 2),
        turnEnd(27, 1_006_300),
    )

    private fun sliceMs(slices: List<TimeSlice>, kind: TimeKind): Long? = slices.firstOrNull { it.kind == kind }?.ms

    @Test
    fun aRecordedTurnIsAccountedForMomentByMoment() {
        val turn = deriveTrajectoryTimeline(recorded).turns.single()
        assertEquals(false, turn.open)
        assertEquals(6_300L, turn.wallMs)
        assertEquals(600L, sliceMs(turn.slices, TimeKind.FIRST_TOKEN_WAIT))
        assertEquals(1_350L, sliceMs(turn.slices, TimeKind.GENERATING))
        assertEquals(1_000L, sliceMs(turn.slices, TimeKind.MODEL))
        assertEquals(2_000L, sliceMs(turn.slices, TimeKind.APPROVAL_WAIT))
        assertEquals(900L, sliceMs(turn.slices, TimeKind.TOOLS))
        assertEquals(450L, sliceMs(turn.slices, TimeKind.OTHER))
    }

    @Test
    fun theSlicesAddUpToTheTurnsWallTime() {
        val turn = deriveTrajectoryTimeline(recorded).turns.single()
        assertEquals(turn.wallMs, turn.slices.sumOf { it.ms })
    }

    @Test
    fun aModelStepSplitsIntoTimeToFirstTokenAndGeneration() {
        val step = deriveTrajectoryTimeline(recorded).turns.single().records
            .filterIsInstance<ModelStepRecord>().first()
        assertEquals(600L, step.firstTokenWaitMs)
        assertEquals(1_350L, step.generationMs)
        assertEquals(1_950L, step.durationMs)
        assertEquals("cli-mock", step.model)
        assertEquals("cli-mock", step.provider)
        assertEquals(1_000_650L, step.firstTokenMs)
        // 3 output tokens over 1.35 s is 2.2 a second, which rounds to 2.
        assertEquals(2L, step.outputTokensPerSecond)
    }

    @Test
    fun aStepWhoseFirstTokenWasNotRecordedHasNoSplitAndNoRate() {
        val step = deriveTrajectoryTimeline(recorded).turns.single().records
            .filterIsInstance<ModelStepRecord>().last()
        assertEquals(1_000L, step.durationMs)
        assertNull(step.firstTokenWaitMs)
        assertNull(step.generationMs)
        assertNull(step.outputTokensPerSecond)
    }

    @Test
    fun aToolCallKeepsItsOwnDurationAndItsApprovalWait() {
        val tool = deriveTrajectoryTimeline(recorded).turns.single().records.filterIsInstance<ToolRecord>().single()
        assertEquals("c1", tool.callId)
        assertEquals("bash", tool.name)
        assertEquals(2_900L, tool.durationMs)
        assertEquals(2_000L, tool.approvalWaitMs)
        assertTrue(tool.finished)
        assertEquals(false, tool.isError)
        // The call, its approval question and its answer, and its result, in journal order.
        assertEquals(listOf(16L, 17L, 18L, 19L), tool.eventSeqs)
    }

    @Test
    fun tokensAreSummedAndAFieldNoStepReportedStaysAbsent() {
        val turn = deriveTrajectoryTimeline(recorded).turns.single()
        val usage = assertNotNull(turn.usage)
        assertEquals(18L, usage.inputTokens)
        assertEquals(8L, usage.outputTokens)
        assertEquals(2L, usage.cacheReadTokens)
        assertEquals(1L, usage.reasoningTokens)
        // No reply reported a cache write, so it is not 0.
        assertNull(usage.cacheWriteTokens)
    }

    @Test
    fun sessionTotalsAddTheTurnsUp() {
        val second = listOf(
            turnStart(30, 2_000_000, 2),
            stepStart(31, 2_000_100, 1, 2),
            message(32, 2_000_900, 1, textOnly, usage = """{"inputTokens":4,"outputTokens":2}""", turn = 2),
            turnEnd(33, 2_001_000, 2),
        )
        val timeline = deriveTrajectoryTimeline(recorded + second)
        val totals = timeline.totals
        assertEquals(2, totals.turns)
        assertEquals(7_300L, totals.wallMs)
        assertEquals(3, totals.modelSteps)
        assertEquals(1, totals.toolCalls)
        assertEquals(22L, totals.usage?.inputTokens)
        assertEquals(10L, totals.usage?.outputTokens)
        assertEquals(totals.wallMs, totals.slices.sumOf { it.ms })
        assertEquals(false, totals.hasOpenTurn)
    }

    @Test
    fun toolCallsThatOverlapAreNotCountedTwice() {
        val events = listOf(
            turnStart(1, 1_000),
            toolCall(2, 1_100, "a"),
            toolCall(3, 1_300, "b"),
            toolResult(4, 1_600, "a"),
            toolResult(5, 1_900, "b"),
            turnEnd(6, 2_000),
        )
        val turn = deriveTrajectoryTimeline(events).turns.single()
        // The two calls together cover 1_100..1_900, not 500 + 600.
        assertEquals(800L, sliceMs(turn.slices, TimeKind.TOOLS))
        assertEquals(200L, sliceMs(turn.slices, TimeKind.OTHER))
        assertEquals(1_000L, turn.wallMs)
        assertEquals(turn.wallMs, turn.slices.sumOf { it.ms })
        assertEquals(2, turn.toolCalls)
    }

    @Test
    fun aTurnThatHasNotEndedShowsNoTotalAndInventsNoOtherTime() {
        val events = listOf(
            turnStart(1, 1_000),
            stepStart(2, 1_200, 1),
            message(3, 1_700, 1, textOnly),
            stepStart(4, 1_800, 2),
        )
        val timeline = deriveTrajectoryTimeline(events)
        val turn = timeline.turns.single()
        assertEquals(true, turn.open)
        assertNull(turn.wallMs)
        assertEquals(listOf(TimeKind.MODEL), turn.slices.map { it.kind })
        assertEquals(500L, turn.slices.single().ms)
        assertEquals(true, timeline.totals.hasOpenTurn)
        assertNull(timeline.totals.wallMs)
    }

    @Test
    fun aLogWithoutTimesYieldsNoDurationsButKeepsTheTokens() {
        // The harness's own snapshots carry time 0 everywhere; that means "not recorded".
        val events = recorded.map { it.copy(time = 0) }
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertNull(turn.wallMs)
        assertTrue(turn.slices.isEmpty())
        turn.records.forEach { assertNull(it.durationMs) }
        assertEquals(18L, turn.usage?.inputTokens)
    }

    @Test
    fun aStreamWhoseTimesAreZeroHasNoFirstToken() {
        // The same stream as the harness snapshot: every time normalised to 0.
        val zeroStream =
            """[{"type":"chunk","time":0,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
                {"type":"reasoning-chunks","time0":0,"index":0,"dt":[],"texts":["Inspecting the task before the tool call."]},
                {"type":"chunk","time":0,"chunk":{"type":"usage","usage":{"inputTokens":11,"outputTokens":3,"cacheReadTokens":2}}}]"""
        val events = listOf(
            turnStart(1, 1_000),
            stepStart(2, 1_100, 1),
            message(3, 1_500, 1, reasoningThenTool, stream = zeroStream),
            turnEnd(4, 1_600),
        )
        val step = deriveTrajectoryTimeline(events).turns.single().records.filterIsInstance<ModelStepRecord>().single()
        assertNull(step.firstTokenMs)
        assertNull(step.firstTokenWaitMs)
        // The step itself still has a duration, and with no split it is one model slice.
        assertEquals(400L, step.durationMs)
        assertEquals(400L, sliceMs(deriveTrajectoryTimeline(events).turns.single().slices, TimeKind.MODEL))
    }

    @Test
    fun aReplyThatReportedNoUsageHasNoUsageRatherThanZero() {
        val events = listOf(
            turnStart(1, 1_000),
            stepStart(2, 1_100, 1),
            message(3, 1_500, 1, textOnly),
            turnEnd(4, 1_600),
        )
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertNull(turn.usage)
        assertNull(turn.records.filterIsInstance<ModelStepRecord>().single().usage)
        assertNull(deriveTrajectoryTimeline(events).totals.usage)
    }

    @Test
    fun theFirstTokenOfARetriedAttemptCountsForTheStep() {
        // `assistant/attempt` is the failed first try; the final message's own stream starts later.
        val attempt = ev(
            "assistant/attempt", 3, 1_300,
            """{"turn":1,"step":1,"stream":[{"type":"text-chunks","time0":1250,"index":0,"dt":[],"texts":["a"]},
                {"type":"chunk","time":1290,"chunk":{"type":"finish","reason":{"kind":"error","failure":{"message":"x","code":"RATE_LIMIT","status":429}}}}]}""",
        )
        val events = listOf(
            turnStart(1, 1_000),
            stepStart(2, 1_100, 1),
            attempt,
            message(
                4, 2_000, 1, textOnly,
                stream = """[{"type":"text-chunks","time0":1800,"index":0,"dt":[],"texts":["All done."]}]""",
            ),
            turnEnd(5, 2_100),
        )
        val step = deriveTrajectoryTimeline(events).turns.single().records.filterIsInstance<ModelStepRecord>().single()
        assertEquals(1_250L, step.firstTokenMs)
        assertEquals(150L, step.firstTokenWaitMs)
        // The attempt event belongs to the step's raw view too.
        assertEquals(listOf(2L, 3L, 4L), step.eventSeqs)
    }

    @Test
    fun anApprovalThatNamesNoCallStillCountsInTheTurn() {
        val events = listOf(
            turnStart(1, 1_000),
            asked(2, 1_100, "x", null),
            decided(3, 1_400, "x"),
            turnEnd(4, 1_500),
        )
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertEquals(300L, sliceMs(turn.slices, TimeKind.APPROVAL_WAIT))
        assertTrue(turn.records.isEmpty())
    }

    @Test
    fun aClockThatStepsBackwardsGivesNoDurationAndNoNegativeSlice() {
        val events = listOf(
            turnStart(1, 1_000),
            toolCall(2, 1_500, "a"),
            toolResult(3, 1_200, "a"),
            turnEnd(4, 1_600),
        )
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertNull(turn.records.single().durationMs)
        assertTrue(turn.slices.none { it.kind == TimeKind.TOOLS })
        assertEquals(turn.wallMs, turn.slices.sumOf { it.ms })
    }

    @Test
    fun aToolThatNeverReturnedIsRunningAndHasNoDuration() {
        val events = listOf(turnStart(1, 1_000), toolCall(2, 1_100, "a"))
        val tool = deriveTrajectoryTimeline(events).turns.single().records.single()
        assertIs<ToolRecord>(tool)
        assertEquals(false, tool.finished)
        assertNull(tool.durationMs)
    }

    @Test
    fun aFailedToolIsMarkedAndCounted() {
        val events = listOf(
            turnStart(1, 1_000),
            toolCall(2, 1_100, "a"),
            toolResult(3, 1_200, "a", isError = true),
            turnEnd(4, 1_300),
        )
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertEquals(1, turn.failedToolCalls)
        assertTrue(turn.records.single().failed)
    }

    @Test
    fun anInterruptedReplyIsMarkedFailed() {
        val events = listOf(
            turnStart(1, 1_000),
            stepStart(2, 1_100, 1),
            message(3, 1_500, 1, textOnly, interrupted = true),
        )
        assertTrue(deriveTrajectoryTimeline(events).turns.single().records.single().failed)
    }

    @Test
    fun recordsKeepAStableKeyAndJournalOrder() {
        val records = deriveTrajectoryTimeline(recorded).turns.single().records
        assertEquals(listOf("m:1:1", "t:c1", "m:1:2"), records.map { it.key })
        // Prepending older history must not change a key: nothing in it depends on position.
        val older = listOf(turnStart(1, 10, 0), stepStart(2, 20, 1, 0), turnEnd(3, 30, 0))
        val again = deriveTrajectoryTimeline(older + recorded).turns.flatMap { it.records }.map { it.key }
        assertEquals(listOf("m:0:1") + records.map { it.key }, again)
    }

    @Test
    fun aTurnWhoseStartIsNotLoadedMeasuresFromItsFirstEvent() {
        // History is paged in from the end: the `turn/start` may be in the part not loaded yet.
        val events = recorded.filterNot { it.type == "turn/start" }
        val turn = deriveTrajectoryTimeline(events).turns.single()
        assertEquals(6_250L, turn.wallMs)
        assertEquals(turn.wallMs, turn.slices.sumOf { it.ms })
    }

    @Test
    fun aLongSessionKeepsEveryTurnsSlicesEqualToItsWallTime() {
        // 400 turns of one model step and ten parallel tool calls each: about 9,200 events.
        val events = ArrayList<SessionEventEnvelope>()
        var seq = 1L
        for (turn in 1..400) {
            val base = turn * 100_000L
            events += turnStart(seq++, base, turn)
            events += stepStart(seq++, base + 100, 1, turn)
            events += message(seq++, base + 900, 1, textOnly, usage = """{"inputTokens":10,"outputTokens":2}""", turn = turn)
            for (call in 0 until 10) events += toolCall(seq++, base + 1_000 + call * 10L, "c$turn-$call", turn = turn)
            for (call in 0 until 10) events += toolResult(seq++, base + 2_000 + call * 10L, "c$turn-$call", turn = turn)
            events += turnEnd(seq++, base + 3_000, turn)
        }
        val timeline = deriveTrajectoryTimeline(events)
        assertEquals(400, timeline.turns.size)
        assertEquals(4_000, timeline.totals.toolCalls)
        assertEquals(4_000L, timeline.totals.usage?.inputTokens)
        timeline.turns.forEach { assertEquals(it.wallMs, it.slices.sumOf { slice -> slice.ms }) }
        assertEquals(timeline.totals.wallMs, timeline.totals.slices.sumOf { it.ms })
        assertEquals(4_400, timeline.recordIndex().size)
    }

    @Test
    fun anEmptyJournalIsAnEmptyTimeline() {
        val timeline = deriveTrajectoryTimeline(emptyList())
        assertTrue(timeline.turns.isEmpty())
        assertNull(timeline.totals.wallMs)
        assertNull(timeline.totals.usage)
    }

    // ---- first token, from the host's own test vectors -----------------------------------------

    private fun textRun(time0: Long, dt: List<Long>, texts: List<String>) = WireJson.parseToJsonElement(
        """{"type":"text-chunks","time0":$time0,"index":0,"dt":${dt},"texts":${texts.map { "\"$it\"" }}}""",
    )

    private fun toolRun(time0: Long, dt: List<Long>, args: List<String>, name: String? = null) = WireJson.parseToJsonElement(
        """{"type":"tool-call-chunks","time0":$time0,"index":0,"dt":${dt},"id":"call-1",
            ${name?.let { """"name":"$it",""" } ?: ""}"args":${args.map { "\"$it\"" }}}""",
    )

    private fun streamOf(vararg records: kotlinx.serialization.json.JsonElement) =
        kotlinx.serialization.json.JsonArray(records.toList())

    /**
     * Vectors from `packages/llm/llm/tests/assistant-stream.spec.ts`, "reconstructs the first token
     * member time from time0 and the preceding gaps".
     */
    @Test
    fun firstTokenTimeMatchesTheHostsOwnVectors() {
        assertEquals(1_002L, AssistantStream.firstTokenTime(streamOf(textRun(1_000, listOf(5, -3, 10), listOf("", "", "x", "y")))))
        assertEquals(1_000L, AssistantStream.firstTokenTime(streamOf(textRun(1_000, listOf(5), listOf("a", "b")))))
        assertNull(AssistantStream.firstTokenTime(streamOf(textRun(7, listOf(1, 1), listOf("", "", "")))))
        assertEquals(54L, AssistantStream.firstTokenTime(streamOf(toolRun(50, listOf(2, 2), listOf("", "", "{")))))
        assertNull(AssistantStream.firstTokenTime(streamOf(toolRun(50, listOf(2), listOf("", "")))))
        assertEquals(50L, AssistantStream.firstTokenTime(streamOf(toolRun(50, listOf(2), listOf("", ""), "read"))))
    }

    /** The same spec's "compact stream readers" stream: the first token is the lone space at 103. */
    @Test
    fun firstTokenTimeSkipsBlockMarkersAndFindsTheFirstFragment() {
        val stream = WireJson.parseToJsonElement(
            """[{"type":"chunk","time":100,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
                {"type":"reasoning-chunks","time0":101,"index":0,"dt":[2,2],"texts":["", " ", "think"]},
                {"type":"chunk","time":106,"chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":" think"}}}]""",
        )
        assertEquals(103L, AssistantStream.firstTokenTime(stream))
    }

    @Test
    fun firstTokenTimeIsNullWithoutAStreamOrWithAMalformedOne() {
        assertNull(AssistantStream.firstTokenTime(null))
        assertNull(AssistantStream.firstTokenTime(WireJson.parseToJsonElement("[]")))
        assertNull(AssistantStream.firstTokenTime(WireJson.parseToJsonElement("""[{"type":"nonsense"}]""")))
        assertNull(AssistantStream.firstTokenTime(WireJson.parseToJsonElement("""[{"type":"text-chunks","index":0}]""")))
    }

    // ---- the inspector's text ------------------------------------------------------------------

    @Test
    fun aToolCallsInputIsPrettyAndItsOutputIsTheResultText() {
        val tool = deriveTrajectoryTimeline(recorded).turns.single().records.filterIsInstance<ToolRecord>().single()
        val detail = readRecordDetail(tool, recorded)
        assertEquals("{\n    \"command\": \"ls\"\n}", detail.input)
        assertEquals("done c1", detail.output)
        assertNull(detail.thinking)
    }

    @Test
    fun aModelStepsDetailIsItsReplyItsReasoningAndTheToolsItAskedFor() {
        val step = deriveTrajectoryTimeline(recorded).turns.single().records.filterIsInstance<ModelStepRecord>().first()
        val detail = readRecordDetail(step, recorded)
        assertNull(detail.input)
        assertNull(detail.output)
        assertEquals("Inspecting the task before the tool call.", detail.thinking)
        assertEquals(listOf("bash"), detail.requestedTools)
        val last = deriveTrajectoryTimeline(recorded).turns.single().records.filterIsInstance<ModelStepRecord>().last()
        assertEquals("All done.", readRecordDetail(last, recorded).output)
    }

    @Test
    fun rawJsonHasEveryEventOfTheRecordAndNothingElse() {
        val tool = deriveTrajectoryTimeline(recorded).turns.single().records.filterIsInstance<ToolRecord>().single()
        val raw = rawRecordJson(tool, recorded)
        assertTrue(raw.contains("\"type\": \"tool/call\""))
        assertTrue(raw.contains("\"type\": \"approval/asked\""))
        assertTrue(raw.contains("\"type\": \"approval/decided\""))
        assertTrue(raw.contains("\"type\": \"tool/result\""))
        assertTrue(!raw.contains("assistant/message"))
    }

    @Test
    fun argumentsThatAreNotJsonAreShownAsRecorded() {
        assertEquals("ls -la", prettyArguments("ls -la"))
        assertEquals("{not json", prettyArguments("{not json"))
    }

    @Test
    fun theEventWithASeqIsFoundWhetherOrNotTheJournalIsInOrder() {
        assertEquals(19L, eventAt(recorded, 19)?.seq)
        assertEquals(19L, eventAt(recorded.reversed(), 19)?.seq)
        assertNull(eventAt(recorded, 999))
    }
}
