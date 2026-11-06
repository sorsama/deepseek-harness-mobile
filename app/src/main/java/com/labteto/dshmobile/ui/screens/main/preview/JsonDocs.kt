package com.labteto.dshmobile.ui.screens.main.preview

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of reading a JSON file. */
internal sealed interface JsonParse {
    /**
     * @property root the document; for a JSON Lines file, an array holding one value per line.
     * @property lineDelimited true for `.jsonl` / `.ndjson`.
     */
    data class Valid(val root: JsonElement, val lineDelimited: Boolean) : JsonParse

    /** [reason] is the parser's own short wording; [line] and [column] are 1-based when known. */
    data class Invalid(val reason: String, val line: Int?, val column: Int?) : JsonParse
}

@OptIn(ExperimentalSerializationApi::class)
private val READER = Json {
    // Real-world `.json` files (tsconfig, VS Code settings) carry comments and trailing commas.
    allowComments = true
    allowTrailingComma = true
}

@OptIn(ExperimentalSerializationApi::class)
private val PRETTY = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private val OFFSET = Regex("""offset (\d+)""")

/** 1-based line and column of the character at [offset] in [text]. */
internal fun lineColumnOf(text: String, offset: Int): Pair<Int, Int> {
    val end = offset.coerceIn(0, text.length)
    var line = 1
    var lastBreak = -1
    for (i in 0 until end) if (text[i] == '\n') { line++; lastBreak = i }
    return line to (end - lastBreak)
}

private fun failure(error: Exception, text: String, lineBase: Int = 0): JsonParse.Invalid {
    val message = error.message.orEmpty()
    val first = message.lineSequence().firstOrNull().orEmpty()
    val reason = first.substringAfter(": ", first).trim().take(160)
    val offset = OFFSET.find(first)?.groupValues?.get(1)?.toIntOrNull()
    val position = offset?.let { lineColumnOf(text, it) }
    return JsonParse.Invalid(
        reason = reason,
        line = position?.first?.plus(lineBase),
        column = position?.second,
    )
}

/** Parses [text] as one JSON document, or as one document per line when [lineDelimited]. */
internal fun parseJsonText(text: String, lineDelimited: Boolean): JsonParse {
    val source = text.removePrefix(BYTE_ORDER_MARK)
    if (source.isBlank()) return JsonParse.Invalid("", null, null)
    if (!lineDelimited) {
        return try {
            JsonParse.Valid(READER.parseToJsonElement(source), false)
        } catch (e: Exception) {
            failure(e, source)
        }
    }
    val values = ArrayList<JsonElement>()
    var lineNumber = 0
    for (raw in source.lineSequence()) {
        lineNumber++
        val line = raw.trim()
        if (line.isEmpty()) continue
        try {
            values.add(READER.parseToJsonElement(line))
        } catch (e: Exception) {
            val problem = failure(e, line)
            return JsonParse.Invalid(problem.reason, lineNumber, problem.column)
        }
    }
    return JsonParse.Valid(JsonArray(values), true)
}

/** The document, two-space indented. A JSON Lines file prints each record, a blank line apart. */
internal fun prettyJson(valid: JsonParse.Valid): String {
    val root = valid.root
    return if (valid.lineDelimited && root is JsonArray) {
        root.joinToString("\n\n") { PRETTY.encodeToString(JsonElement.serializer(), it) }
    } else {
        PRETTY.encodeToString(JsonElement.serializer(), root)
    }
}

internal enum class JsonNodeKind { Object, Array, String, Number, Bool, Null, More }

/**
 * One visible line of the collapsible tree.
 *
 * @property id a path that is unique in the document (`$`, `$/items/0`); the key for expansion state.
 * @property label the object key, or the index for an array element; null for the root.
 * @property value a scalar's text (strings quoted and shortened), or `{3}` / `[5]` for a container,
 * or, for [JsonNodeKind.More], how many more rows were left out.
 */
internal data class JsonRow(
    val id: String,
    val depth: Int,
    val label: String?,
    val kind: JsonNodeKind,
    val value: String,
    val childCount: Int,
    val expanded: Boolean,
) {
    val expandable: Boolean get() = (kind == JsonNodeKind.Object || kind == JsonNodeKind.Array) && childCount > 0
}

private const val MAX_TREE_DEPTH = 120
private const val SCALAR_PREVIEW = 300

private fun scalarRow(id: String, depth: Int, label: String?, element: JsonPrimitive): JsonRow {
    val kind = when {
        element is JsonNull -> JsonNodeKind.Null
        element.isString -> JsonNodeKind.String
        element.content == "true" || element.content == "false" -> JsonNodeKind.Bool
        else -> JsonNodeKind.Number
    }
    val text = when (kind) {
        JsonNodeKind.String -> "\"" + element.content.take(SCALAR_PREVIEW) + (if (element.content.length > SCALAR_PREVIEW) "…" else "") + "\""
        else -> element.content
    }
    return JsonRow(id, depth, label, kind, text, 0, false)
}

/**
 * The rows the tree shows right now: the root, and the children of every container whose id is in
 * [expanded], depth first. At most [maxRows] are produced; if the document has more visible rows,
 * the last row is a [JsonNodeKind.More] that says how many were left out.
 */
internal fun flattenJson(root: JsonElement, expanded: Set<String>, maxRows: Int = 4000): List<JsonRow> {
    val rows = ArrayList<JsonRow>()
    var skipped = 0

    fun visit(id: String, depth: Int, label: String?, element: JsonElement) {
        if (rows.size >= maxRows) { skipped++; return }
        when (element) {
            is JsonObject -> {
                val open = id in expanded && depth < MAX_TREE_DEPTH
                rows.add(JsonRow(id, depth, label, JsonNodeKind.Object, "{${element.size}}", element.size, open))
                if (open) element.entries.forEach { (key, child) -> visit("$id/$key", depth + 1, key, child) }
            }
            is JsonArray -> {
                val open = id in expanded && depth < MAX_TREE_DEPTH
                rows.add(JsonRow(id, depth, label, JsonNodeKind.Array, "[${element.size}]", element.size, open))
                if (open) element.forEachIndexed { index, child -> visit("$id/$index", depth + 1, index.toString(), child) }
            }
            is JsonPrimitive -> rows.add(scalarRow(id, depth, label, element))
        }
    }
    visit("$", 0, null, root)
    if (skipped > 0) rows.add(JsonRow("$/…", 0, null, JsonNodeKind.More, skipped.toString(), 0, false))
    return rows
}
