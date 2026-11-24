package com.labteto.dshmobile.core.wire

import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.Reader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// These projections are consumed by Android list rows and session API clients. Other projections
// can contain large snapshots that are used by the desktop, but not by these mobile surfaces.
private val listProjectionKeys = setOf(
    "title",
    "agentPreset",
    "subagent",
    "userQuestions",
    "inbox",
    "sessionListMetadata",
)
private val listRowKeys = setOf(
    "sessionId",
    "updatedAt",
    "running",
    "blank",
    "parentSessionId",
    "origin",
    "cwd",
    "agentPreset",
    "formatStatus",
)

/** Skip unused projection values on the wire, before they become strings or JSON nodes. */
internal fun compactSessionList(input: Reader): String = JsonReader(input).use { reader ->
    reader.strictness = Strictness.STRICT
    val result = reader.objectFields { name ->
        when (name) {
            "type", "rpcId" -> value()
            "result" -> objectFields { field ->
                when (field) {
                    "ok", "error" -> value()
                    "value" -> objectFields { key ->
                        if (key == "items") rows() else null
                    }
                    else -> null
                }
            }
            else -> null
        }
    }
    if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw IOException("Unexpected data after session list")
    }
    result.toString()
}

private fun JsonReader.rows(): JsonArray {
    val rows = mutableListOf<JsonElement>()
    beginArray()
    while (hasNext()) {
        rows += objectFields { key ->
            when {
                key in listRowKeys -> value()
                key == "projections" -> projections()
                else -> null
            }
        }
    }
    endArray()
    return JsonArray(rows)
}

private fun JsonReader.projections(): JsonElement {
    if (peek() == JsonToken.NULL) {
        nextNull()
        return JsonNull
    }
    return objectFields { field ->
        when (field) {
            "asOfSeq", "kind" -> value()
            "values" -> objectFields { projection ->
                if (projection in listProjectionKeys) value() else null
            }
            else -> null
        }
    }
}

private inline fun JsonReader.objectFields(
    read: JsonReader.(String) -> JsonElement?,
): JsonObject {
    val fields = linkedMapOf<String, JsonElement>()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        val value = read(name)
        if (value == null) skipValue() else fields[name] = value
    }
    endObject()
    return JsonObject(fields)
}

private fun JsonReader.value(): JsonElement =
    kotlinx.serialization.json.Json.parseToJsonElement(JsonParser.parseReader(this).toString())
