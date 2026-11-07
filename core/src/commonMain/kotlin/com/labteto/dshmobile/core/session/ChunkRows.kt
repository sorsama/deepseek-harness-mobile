package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Expansion of packed assistant-delta runs back into ordinary session events.
 *
 * This is back-compatibility with harness 0.1.2 and nothing else. That version sent history pages
 * and follow snapshots as `SessionHistoryRecord`s where a run of consecutive same-block assistant
 * deltas was packed into one `chunks` record instead of one event per token; 0.1.3 removed the
 * packing along with the durable deltas themselves, so a current host never produces a row this
 * touches. The packing is lossless — every fragment and every inter-event time gap is retained —
 * and this mirrors the host's own `expandRow`, so the fold above sees exactly the events it would
 * have seen from a 0.1.1 host.
 *
 * Reading the packed form matters because *not* reading it is worse than not supporting it. A
 * `chunks` row read as a single event collapses the whole run to its first `seq` and leaves a hole
 * at `seq0+1 .. seq0+N-1`; [EventFold] derives its `gap` flag from journal continuity alone, so
 * that hole raises a permanent "Reconnecting…" banner over a connection that is streaming
 * perfectly well.
 *
 * Expanding rather than folding the run directly is a deliberate trade. Folding is what the
 * format is *for*: upstream measured one 416,756-event tail as 696 records, and expanding gives
 * back all 416,756 objects. But every consumer above this point — the fold, the tool-view index,
 * the conversation assembler — already understands scalar events and nothing else, and a packed
 * run is only ever produced by history, never by the live tail, so a folding path would have to
 * be written and kept correct twice. Expansion buys correctness now; if a long transcript proves
 * slow on a real device, this is the one function to replace.
 *
 * Rows are validated before expanding and a malformed one is dropped whole rather than
 * half-expanded: a partial run would open the very sequence gap this exists to avoid.
 */
object ChunkRows {

    /** The three packed row tags, in their wire (`chunkrow/`-prefixed) form. */
    private const val TEXT = "chunkrow/text-chunks"
    private const val REASONING = "chunkrow/reasoning-chunks"
    private const val TOOL_CALL = "chunkrow/tool-call-chunks"

    /** Whether [type] is a packed run tag rather than an ordinary event type. */
    fun isPackedRun(type: String): Boolean = type == TEXT || type == REASONING || type == TOOL_CALL

    /**
     * Expand one history record into the session events it stands for.
     *
     * An ordinary record yields its single event. A packed run yields one `assistant/chunk` event
     * per member, with `seq` counting up from the row's own `seq` and `time` accumulated from the
     * row's `dt` gaps — a gap may be negative, because the host's wall clock can step backwards
     * between two events and the format records what happened rather than what is tidy.
     */
    fun expand(record: SessionHistoryRecord): List<SessionWireEvent> {
        val event = record.event
        if (record !is SessionHistoryRecord.Chunks || !isPackedRun(event.type)) return listOf(event)
        return expandRun(event)
    }

    /** Expand a list of records in order, flattening packed runs in place. */
    fun expandAll(records: List<SessionHistoryRecord>): List<SessionWireEvent> {
        val out = ArrayList<SessionWireEvent>(records.size)
        for (record in records) out.addAll(expand(record))
        return out
    }

    /**
     * The inclusive sequence span one record covers.
     *
     * An ordinary event covers `[seq, seq]`; a run covers `[seq, seq + memberCount - 1]`. Callers
     * that check page continuity need this before expanding, because a run's own `seq` says
     * nothing about where the next record starts.
     */
    fun sequenceSpan(record: SessionHistoryRecord): IntRange {
        val seq = record.event.seq
        if (record !is SessionHistoryRecord.Chunks) return seq..seq
        val count = memberCount(record.event)
        return if (count <= 0) seq..seq else seq..(seq + count - 1)
    }

    private fun memberCount(event: SessionWireEvent): Int {
        val data = event.data as? JsonObject ?: return 0
        val members = if (event.type == TOOL_CALL) data["args"] else data["texts"]
        return (members as? JsonArray)?.size ?: 0
    }

    private fun expandRun(event: SessionWireEvent): List<SessionWireEvent> {
        val data = event.data as? JsonObject ?: return emptyList()
        val members = (if (event.type == TOOL_CALL) data["args"] else data["texts"]) as? JsonArray
            ?: return emptyList()
        val turn = data["turn"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        val step = data["step"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        val index = data["index"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        // One shorter than the member list by construction; a row that disagrees is malformed.
        val gaps = (data["dt"] as? JsonArray).orEmpty()
        if (members.isNotEmpty() && gaps.size != members.size - 1) return emptyList()

        val toolCallId = data["id"]?.jsonPrimitive?.contentOrNull
        if (event.type == TOOL_CALL && toolCallId == null) return emptyList()
        // Present only when every member carried the same name; a mixed run never packs, so its
        // absence is meaningful rather than a lookup failure.
        val toolName = data["name"]?.jsonPrimitive?.contentOrNull

        val out = ArrayList<SessionWireEvent>(members.size)
        var time = event.time
        for (k in members.indices) {
            if (k > 0) time += gaps[k - 1].jsonPrimitive.longOrNull
                ?: gaps[k - 1].jsonPrimitive.doubleOrNull?.toLong()
                ?: return emptyList()
            val member = members[k].jsonPrimitive.contentOrNull ?: return emptyList()
            val chunk = when (event.type) {
                TEXT -> buildJsonObject {
                    put("type", JsonPrimitive("text-delta"))
                    put("index", JsonPrimitive(index))
                    put("text", JsonPrimitive(member))
                }
                REASONING -> buildJsonObject {
                    put("type", JsonPrimitive("reasoning-delta"))
                    put("index", JsonPrimitive(index))
                    put("text", JsonPrimitive(member))
                }
                else -> buildJsonObject {
                    put("type", JsonPrimitive("tool-call-delta"))
                    put("index", JsonPrimitive(index))
                    put("id", JsonPrimitive(toolCallId))
                    if (toolName != null) put("name", JsonPrimitive(toolName))
                    put("argumentsDelta", JsonPrimitive(member))
                }
            }
            out.add(
                SessionWireEvent(
                    type = "assistant/chunk",
                    seq = event.seq + k,
                    time = time,
                    data = buildJsonObject {
                        put("turn", JsonPrimitive(turn))
                        put("step", JsonPrimitive(step))
                        put("chunk", chunk)
                    },
                ),
            )
        }
        return out
    }
}

/** An absent JSON array reads as empty, so a row missing its gaps is not a crash. */
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
