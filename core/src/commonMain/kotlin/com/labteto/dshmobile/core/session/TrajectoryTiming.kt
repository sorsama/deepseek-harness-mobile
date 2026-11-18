package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Where the time of a session went, derived from its journal.
 *
 * What the official client shows (harness 0.2.1-rc.2, `packages/client/ui-trajectory`): a three-lane
 * overview of every record's recorded start and duration, in which an assistant span is split into the
 * wait for the first token and the generation after it (`timeline.ts`, `layout.ts`), and an inspector
 * with tokens, duration, input, output and timing (`TrajectoryTable.tsx`). Its facts come from the
 * session events: `step/start` (the step's start), the first token delta of the model stream
 * (`assistantStreamFirstTokenTime`), `assistant/message` (completion, usage, model), `tool/call` and
 * `tool/result` (the tool's start and end).
 *
 * This file keeps the same facts and adds one the web view has no segment for but the journal does
 * carry: `approval/asked` to `approval/decided`, the time a tool spent waiting for a decision.
 *
 * Nothing here is invented. A time of 0 (older logs, or a log whose times were normalised) means "not
 * recorded"; a duration needs both of its ends; a token count that no event reported is absent, never
 * zero. Everything is integer arithmetic, so it behaves the same on Kotlin/Native.
 */

/** What a stretch of a turn's wall-clock time was spent on, in the order a bar draws them. */
enum class TimeKind {
    /** From the start of a model step to its first token. */
    FIRST_TOKEN_WAIT,

    /** From the first token to the end of the model's reply. */
    GENERATING,

    /** A model step whose first token time was not recorded, so it cannot be split. */
    MODEL,

    /** A tool call waiting for an approval decision. */
    APPROVAL_WAIT,

    /** A tool call running. */
    TOOLS,

    /** Time in the turn that no model step, tool or approval accounts for. */
    OTHER,
}

/** [ms] of a turn spent on [kind]. */
data class TimeSlice(val kind: TimeKind, val ms: Long)

/** Token counts one model reply reported. Each field is null when the harness did not report it. */
data class RecordUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    val isEmpty: Boolean
        get() = inputTokens == null && outputTokens == null && cacheReadTokens == null &&
            cacheWriteTokens == null && reasoningTokens == null

    operator fun plus(other: RecordUsage): RecordUsage = RecordUsage(
        inputTokens = add(inputTokens, other.inputTokens),
        outputTokens = add(outputTokens, other.outputTokens),
        cacheReadTokens = add(cacheReadTokens, other.cacheReadTokens),
        cacheWriteTokens = add(cacheWriteTokens, other.cacheWriteTokens),
        reasoningTokens = add(reasoningTokens, other.reasoningTokens),
    )

    private fun add(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else a + b
}

/** One record of the ledger the inspector can open: a model step or a tool call. */
sealed interface TrajectoryRecord {
    /** Stable across refolds and prepended history, so a list can key on it. */
    val key: String
    val turn: Int
    val step: Int

    /** The journal position that orders this record among the others. */
    val seq: Long
    val startMs: Long?
    val endMs: Long?

    /** The `seq` of every journal event this record is made of, for the raw view. */
    val eventSeqs: List<Long>

    /** The model's reply was cut short, or the tool failed. A tool the user stopped is [stopped], not failed. */
    val failed: Boolean

    /** The tool call was stopped (an abort), which is its own outcome and not a failure. */
    val stopped: Boolean get() = false

    /** Needs both ends and a clock that did not step backwards; otherwise null, never 0. */
    val durationMs: Long?
        get() {
            val start = startMs
            val end = endMs
            return if (start != null && end != null && end >= start) end - start else null
        }
}

/** One model reply (a harness "step"). */
data class ModelStepRecord(
    override val turn: Int,
    override val step: Int,
    override val seq: Long,
    override val startMs: Long?,
    override val endMs: Long?,
    override val eventSeqs: List<Long>,
    val firstTokenMs: Long?,
    val provider: String?,
    val model: String?,
    val usage: RecordUsage?,
    val interrupted: Boolean,
    /** False while the reply is still being written, or when it never settled. */
    val settled: Boolean,
) : TrajectoryRecord {
    override val key: String get() = "m:$turn:$step"
    override val failed: Boolean get() = interrupted

    /** Time to first token: step start to first token. */
    val firstTokenWaitMs: Long?
        get() {
            val start = startMs
            val first = firstTokenMs
            return if (start != null && first != null && first >= start) first - start else null
        }

    /** First token to the end of the reply. */
    val generationMs: Long?
        get() {
            val first = firstTokenMs
            val end = endMs
            return if (first != null && end != null && end >= first) end - first else null
        }

    /** Output tokens per second over the generation, rounded to a whole number. */
    val outputTokensPerSecond: Long?
        get() {
            val out = usage?.outputTokens ?: return null
            val generation = generationMs ?: return null
            return if (generation > 0) (out * 1000 + generation / 2) / generation else null
        }
}

/** One tool call, from its `tool/call` to its `tool/result`. */
data class ToolRecord(
    override val turn: Int,
    override val step: Int,
    override val seq: Long,
    override val startMs: Long?,
    override val endMs: Long?,
    override val eventSeqs: List<Long>,
    val callId: String,
    val name: String,
    /** True once a `tool/result` arrived. */
    val finished: Boolean,
    val isError: Boolean,
    val errorCode: String?,
    val errorName: String?,
    /** Time spent waiting for an approval decision; null when no decision was recorded for this call. */
    val approvalWaitMs: Long?,
) : TrajectoryRecord {
    override val key: String get() = "t:${callId.ifEmpty { "seq$seq" }}"
    override val stopped: Boolean get() = isError && isToolAbortError(errorCode, errorName)
    override val failed: Boolean get() = isError && !stopped
}

/** One turn: its records, and where its wall-clock time went. */
data class TurnTiming(
    val turn: Int,
    val records: List<TrajectoryRecord>,
    /** True until the turn's end is in the journal. An open turn shows no total and no "other". */
    val open: Boolean,
    /** Turn start to turn end; null while open, or when either time was not recorded. */
    val wallMs: Long?,
    /** Non-empty slices in [TimeKind] order. For a closed turn with [wallMs] they add up to it. */
    val slices: List<TimeSlice>,
    val modelSteps: Int,
    val toolCalls: Int,
    /** Tool calls that failed; calls the user stopped are counted in [stoppedToolCalls] instead. */
    val failedToolCalls: Int,
    /** The replies' token counts added up; null when no reply of the turn reported any. */
    val usage: RecordUsage?,
    /** Tool calls that were stopped (aborted), which is not a failure. */
    val stoppedToolCalls: Int = 0,
)

/** The whole loaded session added up. */
data class TimelineTotals(
    /** Sum of the closed turns' wall time; null when none has one. */
    val wallMs: Long?,
    val slices: List<TimeSlice>,
    val turns: Int,
    val modelSteps: Int,
    val toolCalls: Int,
    val failedToolCalls: Int,
    val usage: RecordUsage?,
    /** True when the last turn has not ended, so the totals leave it out of the time. */
    val hasOpenTurn: Boolean,
    /** Tool calls that were stopped (aborted); [failedToolCalls] does not include them. */
    val stoppedToolCalls: Int = 0,
)

data class TrajectoryTimeline(
    val turns: List<TurnTiming>,
    val totals: TimelineTotals,
) {
    /** The record with [key], or null. Built once per timeline by callers that look many up. */
    fun recordIndex(): Map<String, TrajectoryRecord> =
        buildMap { turns.forEach { turn -> turn.records.forEach { put(it.key, it) } } }

    companion object {
        val EMPTY = TrajectoryTimeline(
            emptyList(),
            TimelineTotals(null, emptyList(), 0, 0, 0, 0, null, false),
        )
    }
}

/**
 * Fold a session journal into per-turn timing, records and totals. Pure and linear: events are read
 * once, in order, and no event body is parsed beyond the handful of fields named here.
 */
fun deriveTrajectoryTimeline(journal: List<SessionEventEnvelope>): TrajectoryTimeline {
    val turns = LinkedHashMap<Int, TurnBuilder>()
    var openTurn: Int? = null
    val approvals = ArrayList<ApprovalBuilder>()
    // Every tool by its call id, so an approval finds the call it names without scanning the turns.
    val toolsByCall = HashMap<String, Pair<TurnBuilder, ToolBuilder>>()

    fun turnOf(n: Int): TurnBuilder = turns.getOrPut(n) { TurnBuilder(n) }
    fun toolOf(n: Int, callId: String, seq: Long): ToolBuilder {
        val turn = turnOf(n)
        val tool = turn.tool(callId, seq)
        if (callId.isNotEmpty()) toolsByCall[callId] = turn to tool
        return tool
    }

    for (event in journal) {
        val data = event.data as? JsonObject
        val at = event.time.known()
        when (event.type) {
            "turn/start" -> {
                val n = data?.int("turn") ?: continue
                val turn = turnOf(n)
                if (turn.startSeq == null) {
                    turn.startSeq = event.seq
                    turn.startMs = at
                }
                openTurn = n
            }
            "turn/end" -> {
                val n = data?.int("turn") ?: openTurn ?: continue
                val turn = turnOf(n)
                turn.ended = true
                turn.endMs = at
                if (openTurn == n) openTurn = null
            }
            "step/start" -> {
                val n = data?.int("turn") ?: continue
                val step = turnOf(n).step(data.int("step") ?: continue)
                if (step.startSeq == null) {
                    step.startSeq = event.seq
                    step.startMs = at
                }
                turnOf(n).see(at)
            }
            "step/end" -> {
                val n = data?.int("turn") ?: continue
                val step = turnOf(n).step(data.int("step") ?: continue)
                step.stepEndMs = at
                turnOf(n).see(at)
            }
            "assistant/chunk" -> {
                // Durable through harness 0.1.2: each token delta is its own event.
                val n = data?.int("turn") ?: continue
                val step = turnOf(n).step(data.int("step") ?: continue)
                val chunk = data["chunk"] as? JsonObject
                if (chunk != null && at != null && chunk.isTokenDelta()) step.firstToken(at)
            }
            "assistant/attempt" -> {
                // A retried or cancelled attempt still counts toward the step's first token.
                val n = data?.int("turn") ?: continue
                val step = turnOf(n).step(data.int("step") ?: continue)
                AssistantStream.firstTokenTime(data["stream"])?.known()?.let(step::firstToken)
                step.eventSeqs += event.seq
            }
            "assistant/message" -> {
                val n = data?.int("turn") ?: continue
                val step = turnOf(n).step(data.int("step") ?: continue)
                step.messageSeq = event.seq
                step.messageMs = at
                step.eventSeqs += event.seq
                AssistantStream.firstTokenTime(data["stream"])?.known()?.let(step::firstToken)
                val message = data["message"] as? JsonObject
                val source = message?.get("source") as? JsonObject
                step.provider = source?.string("provider")
                step.model = source?.string("model")
                step.usage = (data["usage"] as? JsonObject)?.let(::readUsage)
                step.interrupted = data["interrupted"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
                turnOf(n).see(at)
            }
            "tool/call" -> {
                val n = data?.int("turn") ?: continue
                val callId = data.string("callId").orEmpty()
                val tool = toolOf(n, callId, event.seq)
                tool.callSeq = event.seq
                tool.callMs = at
                tool.step = data.int("step") ?: 0
                tool.name = data.string("name") ?: "tool"
                tool.eventSeqs += event.seq
                turnOf(n).see(at)
            }
            "tool/result" -> {
                val n = data?.int("turn") ?: continue
                val message = data["message"] as? JsonObject
                val callId = toolCallIdOf(message)
                val tool = toolOf(n, callId, event.seq)
                tool.resultSeq = event.seq
                tool.resultMs = at
                if (tool.step == 0) tool.step = data.int("step") ?: 0
                tool.isError = toolIsError(message)
                val error = data["error"] as? JsonObject
                tool.errorCode = error?.string("code")
                tool.errorName = error?.string("name")
                tool.eventSeqs += event.seq
                turnOf(n).see(at)
            }
            "approval/asked" -> {
                val id = data?.string("id") ?: continue
                approvals += ApprovalBuilder(id, data.string("callId"), openTurn, event.seq, at)
            }
            "approval/decided" -> {
                val id = data?.string("id") ?: continue
                approvals.lastOrNull { it.id == id && it.decidedSeq == null }?.let {
                    it.decidedSeq = event.seq
                    it.decidedMs = at
                }
            }
        }
    }

    // An approval belongs to the tool it names; one that names none belongs to the turn it was asked in.
    for (approval in approvals) {
        val owner = approval.callId?.let { toolsByCall[it] }
        val turn = owner?.first ?: approval.turn?.let { turns[it] } ?: continue
        turn.approvals += approval
        owner?.second?.let { tool ->
            tool.eventSeqs += approval.askedSeq
            approval.decidedSeq?.let { tool.eventSeqs += it }
            val wait = approval.waitMs
            if (wait != null) tool.approvalWaitMs = (tool.approvalWaitMs ?: 0L) + wait
        }
    }

    val built = turns.values.sortedBy { it.sortSeq() }.map { it.build() }
    return TrajectoryTimeline(built, totalsOf(built))
}

// ------------------------------------------------------------------------------------------------
// Builders
// ------------------------------------------------------------------------------------------------

private class StepBuilder(val step: Int) {
    var startSeq: Long? = null
    var startMs: Long? = null
    var stepEndMs: Long? = null
    var messageSeq: Long? = null
    var messageMs: Long? = null
    var firstTokenMs: Long? = null
    var provider: String? = null
    var model: String? = null
    var usage: RecordUsage? = null
    var interrupted = false
    val eventSeqs = ArrayList<Long>()

    /** Keeps the earliest token across the settled attempts and the final message of the step. */
    fun firstToken(at: Long) {
        val current = firstTokenMs
        if (current == null || at < current) firstTokenMs = at
    }
}

private class ToolBuilder(val firstSeq: Long, val callId: String) {
    var callSeq: Long? = null
    var callMs: Long? = null
    var resultSeq: Long? = null
    var resultMs: Long? = null
    var step = 0
    var name = "tool"
    var isError = false
    var errorCode: String? = null
    var errorName: String? = null
    var approvalWaitMs: Long? = null
    val eventSeqs = ArrayList<Long>()
}

private class ApprovalBuilder(
    val id: String,
    val callId: String?,
    val turn: Int?,
    val askedSeq: Long,
    val askedMs: Long?,
) {
    var decidedSeq: Long? = null
    var decidedMs: Long? = null

    val waitMs: Long?
        get() {
            val asked = askedMs
            val decided = decidedMs
            return if (asked != null && decided != null && decided >= asked) decided - asked else null
        }
}

private class TurnBuilder(val turn: Int) {
    var startSeq: Long? = null
    var startMs: Long? = null
    var endMs: Long? = null
    var ended = false
    var firstSeenMs: Long? = null
    val steps = LinkedHashMap<Int, StepBuilder>()
    val tools = LinkedHashMap<String, ToolBuilder>()
    val approvals = ArrayList<ApprovalBuilder>()

    fun step(n: Int): StepBuilder = steps.getOrPut(n) { StepBuilder(n) }

    fun tool(callId: String, seq: Long): ToolBuilder {
        // An empty call id cannot pair a result with its call, so each such event stands alone.
        val key = callId.ifEmpty { "seq$seq" }
        return tools.getOrPut(key) { ToolBuilder(seq, callId) }
    }

    fun see(at: Long?) {
        if (at != null && (firstSeenMs == null || at < firstSeenMs!!)) firstSeenMs = at
    }

    fun sortSeq(): Long =
        startSeq ?: steps.values.mapNotNull { it.startSeq ?: it.messageSeq }.minOrNull()
            ?: tools.values.minOfOrNull { it.firstSeq } ?: Long.MAX_VALUE

    fun build(): TurnTiming {
        val stepRecords = steps.values.mapNotNull { step ->
            val seq = step.startSeq ?: step.messageSeq ?: return@mapNotNull null
            val end = step.messageMs ?: step.stepEndMs?.takeIf { step.messageSeq == null }
            ModelStepRecord(
                turn = turn,
                step = step.step,
                seq = seq,
                startMs = step.startMs,
                endMs = end,
                eventSeqs = listOfNotNull(step.startSeq) + step.eventSeqs,
                firstTokenMs = step.firstTokenMs,
                provider = step.provider,
                model = step.model,
                usage = step.usage?.takeUnless { it.isEmpty },
                interrupted = step.interrupted,
                settled = step.messageSeq != null,
            )
        }
        val toolRecords = tools.values.map { tool ->
            ToolRecord(
                turn = turn,
                step = tool.step,
                seq = tool.callSeq ?: tool.resultSeq ?: tool.firstSeq,
                startMs = tool.callMs,
                endMs = tool.resultMs,
                eventSeqs = tool.eventSeqs.sorted(),
                callId = tool.callId,
                name = tool.name,
                finished = tool.resultSeq != null,
                isError = tool.isError,
                errorCode = tool.errorCode,
                errorName = tool.errorName,
                approvalWaitMs = tool.approvalWaitMs,
            )
        }
        val records = (stepRecords + toolRecords).sortedBy { it.seq }

        val closed = ended && endMs != null
        val lo = startMs ?: firstSeenMs
        val hi = endMs
        val spans = ArrayList<Span>()
        stepRecords.forEach { record ->
            val start = record.startMs
            val end = record.endMs
            if (start == null || end == null || end <= start) return@forEach
            val first = record.firstTokenMs
            if (first != null && first in start..end) {
                spans += Span(start, first, TimeKind.FIRST_TOKEN_WAIT)
                spans += Span(first, end, TimeKind.GENERATING)
            } else {
                spans += Span(start, end, TimeKind.MODEL)
            }
        }
        toolRecords.forEach { record ->
            val start = record.startMs
            val end = record.endMs
            if (start != null && end != null && end > start) spans += Span(start, end, TimeKind.TOOLS)
        }
        approvals.forEach { approval ->
            val start = approval.askedMs
            val end = approval.decidedMs
            if (start != null && end != null && end > start) spans += Span(start, end, TimeKind.APPROVAL_WAIT)
        }

        val wall = if (closed && lo != null && hi != null && hi >= lo) hi - lo else null
        val totals = if (wall != null) partition(lo, hi, spans) else partition(null, null, spans)
        val slices = TimeKind.entries.mapNotNull { kind ->
            totals[kind.ordinal].takeIf { it > 0 }?.let { TimeSlice(kind, it) }
        }
        val usages = stepRecords.mapNotNull { it.usage }
        return TurnTiming(
            turn = turn,
            records = records,
            open = !closed,
            wallMs = wall,
            slices = slices,
            modelSteps = stepRecords.count { it.settled },
            toolCalls = toolRecords.size,
            failedToolCalls = toolRecords.count { it.failed },
            usage = usages.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b },
            stoppedToolCalls = toolRecords.count { it.stopped },
        )
    }
}

private class Span(val start: Long, val end: Long, val kind: TimeKind)

private class Edge(val at: Long, val delta: Int, val kind: TimeKind)

/** Highest first: a moment spent waiting for approval is not also counted as the tool running. */
private val PRIORITY = listOf(TimeKind.APPROVAL_WAIT, TimeKind.TOOLS, TimeKind.GENERATING, TimeKind.FIRST_TOKEN_WAIT, TimeKind.MODEL)

/**
 * Give every moment of [from]..[to] to exactly one [TimeKind], so the slices add up to the turn's
 * wall time even when tool calls ran in parallel. With [from] and [to] null (an open turn) only the
 * moments some span covers are counted and no "other" is made up.
 */
private fun partition(from: Long?, to: Long?, spans: List<Span>): LongArray {
    val totals = LongArray(TimeKind.entries.size)
    val edges = ArrayList<Edge>(spans.size * 2)
    for (span in spans) {
        var a = span.start
        var b = span.end
        if (from != null && a < from) a = from
        if (to != null && b > to) b = to
        if (b > a) {
            edges += Edge(a, +1, span.kind)
            edges += Edge(b, -1, span.kind)
        }
    }
    edges.sortBy { it.at }
    val counts = IntArray(TimeKind.entries.size)
    var cursor = from ?: edges.firstOrNull()?.at ?: return totals
    for (edge in edges) {
        if (edge.at > cursor) {
            val kind = PRIORITY.firstOrNull { counts[it.ordinal] > 0 } ?: if (from != null) TimeKind.OTHER else null
            if (kind != null) totals[kind.ordinal] += edge.at - cursor
            cursor = edge.at
        }
        counts[edge.kind.ordinal] += edge.delta
    }
    if (to != null && to > cursor) totals[TimeKind.OTHER.ordinal] += to - cursor
    return totals
}

private fun totalsOf(turns: List<TurnTiming>): TimelineTotals {
    val closed = turns.filter { it.wallMs != null }
    val sums = LongArray(TimeKind.entries.size)
    closed.forEach { turn -> turn.slices.forEach { sums[it.kind.ordinal] += it.ms } }
    val usages = turns.mapNotNull { it.usage }
    return TimelineTotals(
        wallMs = closed.takeIf { it.isNotEmpty() }?.sumOf { it.wallMs ?: 0L },
        slices = TimeKind.entries.mapNotNull { kind -> sums[kind.ordinal].takeIf { it > 0 }?.let { TimeSlice(kind, it) } },
        turns = turns.size,
        modelSteps = turns.sumOf { it.modelSteps },
        toolCalls = turns.sumOf { it.toolCalls },
        failedToolCalls = turns.sumOf { it.failedToolCalls },
        usage = usages.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b },
        hasOpenTurn = turns.any { it.open },
        stoppedToolCalls = turns.sumOf { it.stoppedToolCalls },
    )
}

// ------------------------------------------------------------------------------------------------
// Reading event fields
// ------------------------------------------------------------------------------------------------

/** A recorded time. 0 and below mean the log carries none. */
private fun Long.known(): Long? = if (this > 0L) this else null

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun readUsage(usage: JsonObject): RecordUsage = RecordUsage(
    inputTokens = usage.long("inputTokens"),
    outputTokens = usage.long("outputTokens"),
    cacheReadTokens = usage.long("cacheReadTokens"),
    cacheWriteTokens = usage.long("cacheWriteTokens"),
    reasoningTokens = usage.long("reasoningTokens"),
)

private fun JsonObject.isTokenDelta(): Boolean = when (string("type")) {
    "text-delta", "reasoning-delta" -> string("text")?.isNotEmpty() == true
    "tool-call-delta" -> string("argumentsDelta")?.isNotEmpty() == true || this["name"] != null
    else -> false
}

/** The call a `tool/result` answers, in the session format v3 and v4 spellings. */
private fun toolCallIdOf(message: JsonObject?): String {
    val wrapper = wrapperOf(message)
    return message?.string("toolCallId")
        ?: wrapper?.string("toolCallId")
        ?: (message?.get("source") as? JsonObject)?.string("callId")
        ?: ""
}

private fun toolIsError(message: JsonObject?): Boolean =
    (message?.get("isError") as? JsonPrimitive)?.booleanOrNull
        ?: (wrapperOf(message)?.get("isError") as? JsonPrimitive)?.booleanOrNull
        ?: false

/** Session format v3 nested a tool result's body in one `tool-result` block. */
private fun wrapperOf(message: JsonObject?): JsonObject? {
    val content = message?.get("content") as? JsonArray ?: return null
    return (content.singleOrNull() as? JsonObject)?.takeIf { it.string("type") == "tool-result" }
}

