package com.labteto.dshmobile.core.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A tool result as a person reads it.
 *
 * A tool answers with content blocks (`[{ "type": "text", "text": "..." }]`), and some of them
 * wrap that once more: an MCP style `{ "content": [...] }`, an `{ "output": ... }` envelope, or a
 * `text` that is itself the JSON of another block list. None of those wrappers is something a reader
 * wants to expand, so [text] is what is left once they are peeled away.
 */
data class ToolResultReading(
    /**
     * The text to show and to copy by default. Prose lifted out of its wrappers where the result is
     * text, and the result pretty-printed where it is a genuine structure. Empty when the result
     * carries nothing to read, an image-only result for one.
     */
    val text: String,
    /** True when some part of [text] is a pretty-printed structure rather than the tool's own prose. */
    val structured: Boolean,
    /**
     * The original result as pretty-printed JSON, for the explicit "raw" action; null when [text]
     * already is that (a bare string, or a structure shown as it is), so no action is offered that
     * would show the same thing twice.
     */
    val rawJson: String?,
)

/**
 * Read [content], the body of a `tool/result`, for people; null when there is no body at all.
 *
 * - A block list shows its text blocks, joined by newlines, as the tool wrote them. Image blocks are
 *   left out (the transcript draws them); any other block shape is pretty-printed, like the host's
 *   own web client does.
 * - A `text` that is itself a block list or an envelope, as a JSON string or as a nested value, is
 *   unwrapped to the text inside, up to a few levels deep.
 * - An envelope (`content`, `output` or `text` beside only bookkeeping keys such as `isError` or
 *   `structuredContent`) shows what it carries. An object with other fields is a real result: it is
 *   pretty-printed whole, so nothing it says is dropped.
 * - A string that merely looks like JSON stays as written. Only the shapes above are peeled off.
 */
fun readToolResult(content: JsonElement?): ToolResultReading? {
    if (content == null || content is JsonNull) return null
    val part = read(content, 0)
    val text = part?.text.orEmpty()
    val raw = if (content is JsonPrimitive && content.isString) content.content else PRETTY.encodeToString(JsonElement.serializer(), content)
    return ToolResultReading(text = text, structured = part?.structured ?: false, rawJson = raw.takeIf { it != text })
}

/**
 * [text] with a wrapper peeled off when it is exactly one: a JSON string holding a block list or an
 * envelope becomes the text inside it. Anything else comes back unchanged.
 */
fun unwrapToolText(text: String): String = peel(text, 0)

private class Part(val text: String, val structured: Boolean)

@OptIn(ExperimentalSerializationApi::class)
private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * How deep the reader follows nesting before it shows what is left as it is. One wrapper layer (a
 * block list, its text block, the string inside) costs three steps, so this is about eight layers.
 */
private const val MAX_DEPTH = 24

/** Keys an envelope may carry beside its payload without them being part of the answer. */
private val BOOKKEEPING = setOf(
    "type", "isError", "is_error", "structuredContent", "_meta", "meta", "annotations", "toolCallId", "callId",
)

/** The payload keys of an envelope, in the order they win when more than one is present. */
private val PAYLOAD_KEYS = listOf("content", "output", "text")

private fun read(element: JsonElement, depth: Int): Part? = when (element) {
    is JsonNull -> null
    is JsonPrimitive ->
        if (element.isString) Part(peel(element.content, depth), false) else Part(element.content, false)
    is JsonArray -> readArray(element, depth)
    is JsonObject -> readObject(element, depth)
}

private fun readArray(array: JsonArray, depth: Int): Part? {
    if (array.isEmpty()) return null
    // A list of anything but blocks (numbers, plain records) is data, not content: show it as data.
    if (!array.all { isBlock(it) }) return structure(array)
    if (depth >= MAX_DEPTH) return structure(array)
    val parts = array.mapNotNull { read(it, depth + 1) }
    if (parts.isEmpty()) return null
    return Part(parts.joinToString("\n") { it.text }, parts.any { it.structured })
}

private fun isBlock(element: JsonElement): Boolean = when (element) {
    is JsonPrimitive -> element.isString
    is JsonObject -> (element["type"] as? JsonPrimitive)?.isString == true
    else -> false
}

private fun readObject(obj: JsonObject, depth: Int): Part? {
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
    if (depth >= MAX_DEPTH) return structure(obj)
    if (type == "image") return null
    if (type == "text" && obj.containsKey("text")) {
        // The payload of a text block is usually a string, but a double-wrapped result nests
        // another block under it; read whatever is there.
        return read(obj.getValue("text"), depth + 1)
    }
    envelopePayload(obj)?.let { payload -> read(payload, depth + 1)?.let { return it } }
    return structure(obj)
}

/** The value an envelope carries, or null when [obj] has fields that make it a result of its own. */
private fun envelopePayload(obj: JsonObject): JsonElement? {
    val key = PAYLOAD_KEYS.firstOrNull { obj.containsKey(it) } ?: return null
    if (obj.keys.any { it != key && it !in BOOKKEEPING }) return null
    return obj.getValue(key)
}

private fun structure(element: JsonElement): Part =
    Part(PRETTY.encodeToString(JsonElement.serializer(), element), true)

private fun peel(text: String, depth: Int): String {
    if (depth >= MAX_DEPTH) return text
    val trimmed = text.trim()
    if (trimmed.length < 2) return text
    val first = trimmed.first()
    val last = trimmed.last()
    if (!((first == '[' && last == ']') || (first == '{' && last == '}'))) return text
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return text
    // A list of plain strings or numbers is a tool printing JSON, not a wrapper around its answer.
    if (parsed is JsonArray && parsed.none { it is JsonObject }) return text
    val inner = read(parsed, depth + 1) ?: return text
    return if (inner.structured) text else inner.text
}
