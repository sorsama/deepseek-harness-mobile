package com.labteto.dshmobile.ui.screens.main.preview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal enum class NotebookCellKind { Markdown, Code, Raw }

internal sealed interface NotebookOutput {
    /** Printed text; [error] marks a traceback and [stderr] the error stream. */
    data class Text(val text: String, val error: Boolean = false, val stderr: Boolean = false) : NotebookOutput

    /** A base64 picture ([mime] is `image/png` or `image/jpeg`); decoded only when it is drawn. */
    data class Image(val mime: String, val base64: String) : NotebookOutput

    /** An output the app does not draw (HTML, widgets, LaTeX); [mime] says what it was. */
    data class Unsupported(val mime: String) : NotebookOutput
}

internal data class NotebookCell(
    val kind: NotebookCellKind,
    val source: String,
    val executionCount: Int?,
    val outputs: List<NotebookOutput>,
)

internal data class NotebookDoc(
    /** The highlighter key for code cells (`py`, `js`, `r`...), when the notebook names its kernel language. */
    val languageKey: String?,
    /** The kernel language as the notebook spells it, for the label on code cells. */
    val languageName: String?,
    val cells: List<NotebookCell>,
)

internal sealed interface NotebookParse {
    data class Valid(val doc: NotebookDoc) : NotebookParse
    data class Invalid(val reason: String) : NotebookParse
}

private const val MAX_OUTPUT_CHARS = 20_000
private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/** [text] without terminal colour codes, which notebook tracebacks are full of. */
internal fun stripAnsi(text: String): String = ANSI.replace(text, "")

private val LANGUAGE_KEYS = mapOf(
    "python" to "py", "python3" to "py", "javascript" to "js", "typescript" to "ts", "r" to "r",
    "julia" to "jl", "scala" to "scala", "ruby" to "rb", "bash" to "sh", "shell" to "sh", "sql" to "sql",
    "kotlin" to "kt", "java" to "java", "c++" to "cpp", "c" to "c", "go" to "go", "rust" to "rs",
    "haskell" to "hs", "lua" to "lua", "php" to "php", "perl" to "pl", "swift" to "swift",
)

/** A notebook field that is a string or a list of lines, as one string. */
private fun JsonElement?.joined(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    else -> ""
}

private fun capped(text: String): String =
    if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n…" else text

private fun outputOf(element: JsonElement): List<NotebookOutput> {
    val output = element as? JsonObject ?: return emptyList()
    return when (output["output_type"]?.joined()) {
        "stream" -> listOf(
            NotebookOutput.Text(capped(stripAnsi(output["text"].joined())), stderr = output["name"].joined() == "stderr"),
        )
        "error" -> {
            val trace = (output["traceback"] as? JsonArray)?.joinToString("\n") { it.joined() }.orEmpty()
            val text = trace.ifBlank { output["ename"].joined() + ": " + output["evalue"].joined() }
            listOf(NotebookOutput.Text(capped(stripAnsi(text)), error = true))
        }
        "execute_result", "display_data" -> {
            val data = output["data"] as? JsonObject ?: return emptyList()
            val picture = listOf("image/png", "image/jpeg").firstNotNullOfOrNull { mime ->
                data[mime]?.joined()?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }?.let { mime to it }
            }
            when {
                picture != null -> listOf(NotebookOutput.Image(picture.first, picture.second))
                data["text/plain"] != null -> listOf(NotebookOutput.Text(capped(stripAnsi(data["text/plain"].joined()))))
                data.isEmpty() -> emptyList()
                else -> listOf(NotebookOutput.Unsupported(data.keys.first()))
            }
        }
        else -> emptyList()
    }
}

/** Reads a Jupyter notebook (nbformat 4). */
internal fun parseNotebook(text: String): NotebookParse {
    val element = try {
        Json.parseToJsonElement(text.removePrefix(BYTE_ORDER_MARK))
    } catch (e: Exception) {
        return NotebookParse.Invalid(e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160))
    }
    val root = element as? JsonObject ?: return NotebookParse.Invalid("")
    val cells = root["cells"] as? JsonArray ?: return NotebookParse.Invalid("")
    val metadata = root["metadata"] as? JsonObject
    val languageName = ((metadata?.get("language_info") as? JsonObject)?.get("name")
        ?: (metadata?.get("kernelspec") as? JsonObject)?.get("language"))?.joined()?.takeIf { it.isNotBlank() }
    val parsed = cells.mapNotNull { element ->
        val cell = element as? JsonObject ?: return@mapNotNull null
        val kind = when (cell["cell_type"]?.joined()) {
            "markdown" -> NotebookCellKind.Markdown
            "code" -> NotebookCellKind.Code
            else -> NotebookCellKind.Raw
        }
        NotebookCell(
            kind = kind,
            source = cell["source"].joined(),
            executionCount = (cell["execution_count"] as? JsonPrimitive)?.intOrNull,
            outputs = (cell["outputs"] as? JsonArray)?.flatMap { outputOf(it) }.orEmpty(),
        )
    }
    return NotebookParse.Valid(
        NotebookDoc(languageName?.lowercase()?.let { LANGUAGE_KEYS[it] ?: it }, languageName, parsed),
    )
}
