package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the record inspector shows for one record, read from the journal when the inspector opens.
 *
 * Kept apart from [TrajectoryRecord] on purpose: a record is derived for every step and tool call of a
 * session and must stay small, while the text of an input or an output can be megabytes. Callers run
 * this off the main thread and only for the record the person opened.
 *
 * The official inspector shows Input, Output, Thinking and the request's tool catalogue
 * (`TrajectoryTable.tsx`). A model step has no input of its own here: what the model was sent is the
 * conversation so far, which the chat already shows, so a step offers its reply, its reasoning and
 * the tools it asked for instead.
 */
data class RecordDetail(
    /** A tool call's arguments, pretty-printed when they are JSON. Null for a model step. */
    val input: String?,
    /** A model step's reply text, or a tool call's result text. Null when there is none to show. */
    val output: String?,
    /** A model step's reasoning text. */
    val thinking: String?,
    /** The names of the tools a model step asked for. */
    val requestedTools: List<String>,
)

/** Arguments longer than this are shown as recorded, not re-printed. */
private const val MAX_PRETTY_CHARS = 200_000

private val PRETTY = Json { prettyPrint = true }

/** The inspector's text for [record], read from [journal]. */
fun readRecordDetail(record: TrajectoryRecord, journal: List<SessionEventEnvelope>): RecordDetail = when (record) {
    is ModelStepRecord -> readModelStep(record, journal)
    is ToolRecord -> readToolCall(record, journal)
}

private fun readModelStep(record: ModelStepRecord, journal: List<SessionEventEnvelope>): RecordDetail {
    val message = record.eventSeqs
        .mapNotNull { eventAt(journal, it) }
        .lastOrNull { it.type == "assistant/message" }
    val content = ((message?.data as? JsonObject)?.get("message") as? JsonObject)?.get("content") as? JsonArray
    val blocks = content.orEmpty().mapNotNull { it as? JsonObject }
    fun texts(type: String) = blocks
        .filter { it.str("type") == type }
        .mapNotNull { it.str("text") }
        .filter { it.isNotEmpty() }
    return RecordDetail(
        input = null,
        output = texts("text").takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        thinking = texts("reasoning").takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        requestedTools = blocks.filter { it.str("type") == "tool-call" }.mapNotNull { it.str("name") },
    )
}

private fun readToolCall(record: ToolRecord, journal: List<SessionEventEnvelope>): RecordDetail {
    val events = record.eventSeqs.mapNotNull { eventAt(journal, it) }
    val call = events.firstOrNull { it.type == "tool/call" }
    val result = events.lastOrNull { it.type == "tool/result" }
    val arguments = ((call?.data as? JsonObject)?.get("arguments") as? JsonPrimitive)?.contentOrNull
    val body = toolResultBody((result?.data as? JsonObject)?.get("message") as? JsonObject)
    return RecordDetail(
        input = arguments?.let(::prettyArguments),
        output = readToolResult(body)?.text?.takeIf { it.isNotBlank() },
        thinking = null,
        requestedTools = emptyList(),
    )
}

/** Arguments as the model wrote them, indented when they are a JSON object or array. */
internal fun prettyArguments(raw: String): String {
    if (raw.length > MAX_PRETTY_CHARS) return raw
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
    return runCatching { PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }
        .getOrDefault(raw)
}

/** A tool result's body: its own content, unwrapped from session format v3's `tool-result` block. */
private fun toolResultBody(message: JsonObject?): JsonElement? {
    val content = message?.get("content")
    val wrapper = ((content as? JsonArray)?.singleOrNull() as? JsonObject)?.takeIf { it.str("type") == "tool-result" }
    return wrapper?.get("content") ?: content
}

/**
 * The raw journal events behind [record] as pretty JSON, for the explicit "Show raw JSON" action: the
 * power-user view of exactly what the harness logged, never the default one.
 */
fun rawRecordJson(record: TrajectoryRecord, journal: List<SessionEventEnvelope>): String {
    val events = record.eventSeqs.mapNotNull { eventAt(journal, it) }
    val array = JsonArray(
        events.map { event ->
            buildJsonObject {
                put("seq", event.seq)
                put("type", event.type)
                put("time", event.time)
                put("data", event.data)
                event.surfaceOp?.let { put("surfaceOp", it) }
                event.sourceEventSeqs?.let { seqs -> put("sourceEventSeqs", JsonArray(seqs.map(::JsonPrimitive))) }
            }
        },
    )
    return PRETTY.encodeToString(JsonElement.serializer(), array)
}

/** The event with [seq]: a binary search over the seq-ordered journal, with a scan as the fallback. */
internal fun eventAt(journal: List<SessionEventEnvelope>, seq: Long): SessionEventEnvelope? {
    var low = 0
    var high = journal.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val candidate = journal[mid].seq
        when {
            candidate < seq -> low = mid + 1
            candidate > seq -> high = mid - 1
            else -> return journal[mid]
        }
    }
    return journal.firstOrNull { it.seq == seq }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
