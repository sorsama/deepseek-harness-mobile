package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.*

private val surfaceTypes = setOf("system/message", "user/message", "developer/message", "assistant/message", "tool/result")
private fun SessionEventEnvelope.replacement(): JsonObject? = (surfaceIntent as? JsonObject)
    ?: surfaceOp?.takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
fun SessionEventEnvelope.isSurfaceReplacement(): Boolean = type in surfaceTypes && replacement()?.get("op")?.let { (it as? JsonPrimitive)?.contentOrNull } == "replace"

/** Model-visible order. Replacement copies are not new human transcript messages upstream. */
fun effectiveSurfaceEvents(events: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
    val surface = mutableListOf<SessionEventEnvelope>()
    for (event in events) {
        if (event.type == "image/offload") {
            val targets = (event.data as? JsonObject)?.get("targets") as? JsonArray
            targets?.forEach { raw ->
                val target = raw as? JsonObject ?: return@forEach
                val seq = (target["seq"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                val indexes = (target["imageIndexes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet() ?: return@forEach
                val at = surface.indexOfFirst { it.seq == seq && it.type in setOf("user/message", "tool/result") }
                if (at >= 0) surface[at] = offloadedCopy(surface[at], indexes)
            }
            continue
        }
        if (event.type !in surfaceTypes) continue
        val op = event.replacement()
        if (!event.isSurfaceReplacement()) { surface.add(event); continue }
        val start = (op?.get("startSeq") as? JsonPrimitive)?.longOrNull ?: continue
        val end = (op?.get("endSeq") as? JsonPrimitive)?.longOrNull ?: continue
        val first = surface.indexOfFirst { it.seq == start }
        val last = surface.indexOfFirst { it.seq == end }
        if (first >= 0 && last >= first) {
            repeat(last - first + 1) { surface.removeAt(first) }
            surface.add(first, event)
        } else {
            // A paged window may begin inside the replacement's range. The omitted prefix is
            // unknown; replace only known members and retain the decision for the next page fold.
            val known = surface.indexOfFirst { it.seq in start..end }
            surface.removeAll { it.seq in start..end }
            surface.add(if (known < 0) 0 else known.coerceAtMost(surface.size), event)
        }
    }
    return surface
}

private fun offloadedCopy(event: SessionEventEnvelope, indexes: Set<Int>): SessionEventEnvelope {
    var imageIndex = 0
    fun visit(blocks: JsonArray): JsonArray = JsonArray(blocks.map { raw ->
        val block = raw as? JsonObject ?: return@map raw
        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
            "image" -> if (imageIndex++ in indexes) JsonObject(block + ("offloaded" to JsonPrimitive(true))) else block
            "tool-result" -> (block["content"] as? JsonArray)?.let { JsonObject(block + ("content" to visit(it))) } ?: block
            else -> block
        }
    })
    val data = event.data as? JsonObject ?: return event
    val message = if (event.type == "user/message") data else data["message"] as? JsonObject ?: return event
    val content = message["content"] as? JsonArray ?: return event
    val projected = JsonObject(message + ("content" to visit(content)))
    return event.copy(data = if (event.type == "user/message") projected else JsonObject(data + ("message" to projected)))
}
