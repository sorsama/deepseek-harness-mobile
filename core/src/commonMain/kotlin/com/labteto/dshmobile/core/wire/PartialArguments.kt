package com.labteto.dshmobile.core.wire

import kotlinx.serialization.json.*

/** Display-only string fields from a streamed top-level object. Never used as executable arguments. */
fun partialArgumentStrings(raw: String): JsonObject {
    val text = raw.take(65_536)
    val fields = mutableMapOf<String, JsonElement>()
    var index = 0
    var depth = 0
    fun string(): String? {
        val start = index++
        var escaped = false
        while (index < text.length) {
            val c = text[index++]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') return runCatching { WireJson.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content }.getOrNull()
        }
        var tail = text.substring(start)
        // A stream can end inside an escape. Drop only the unfinished suffix.
        repeat(7) {
            runCatching { WireJson.parseToJsonElement(tail + '"').jsonPrimitive.content }.getOrNull()?.let { return it }
            if (tail.length > 1) tail = tail.dropLast(1)
        }
        return null
    }
    while (index < text.length) {
        when (text[index]) {
            '{', '[' -> { depth++; index++ }
            '}', ']' -> { depth--; index++ }
            '"' -> {
                val key = string()
                while (index < text.length && text[index].isWhitespace()) index++
                if (depth == 1 && key != null && text.getOrNull(index) == ':') {
                    index++
                    while (index < text.length && text[index].isWhitespace()) index++
                    if (text.getOrNull(index) == '"') string()?.let { fields[key] = JsonPrimitive(it) }
                }
            }
            else -> index++
        }
    }
    return JsonObject(fields)
}
