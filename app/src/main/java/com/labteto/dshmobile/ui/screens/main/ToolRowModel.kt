package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.vector.ImageVector
import com.labteto.dshmobile.ui.components.FeatherIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How a tool call is labelled in the transcript — the port of the harness web client's
 * `tool-call-model.ts`.
 *
 * A row reads `Read · app\build.gradle.kts`: a verb drawn from the tool's *variant* rather than its
 * raw name, then the one argument that identifies what it acted on, relativised against the
 * session's working directory. Deriving it here (instead of printing whatever title the tool's
 * presenter happened to emit) is what keeps the verb column consistent and the paths short.
 *
 * The verb labels are deliberately not string resources: they are the harness's own tool
 * vocabulary, which it leaves untranslated in every locale, and a translated verb here would stop
 * matching the desktop UI it mirrors.
 */
internal enum class ToolRowVariant(val title: String) {
    Search("Search"),
    Read("Read"),
    Bash("Bash"),
    Write("Write"),
    Edit("Edit"),
    Code("Code"),
    Other("Tool call"),
}

/** Tool name -> variant. Unlisted tools fall to [ToolRowVariant.Other]. */
private val TOOL_VARIANTS: Map<String, ToolRowVariant> = mapOf(
    "bash" to ToolRowVariant.Bash,
    "pwsh" to ToolRowVariant.Bash,
    "read" to ToolRowVariant.Read,
    "web_fetch" to ToolRowVariant.Read,
    "web_search" to ToolRowVariant.Search,
    "grep" to ToolRowVariant.Search,
    "glob" to ToolRowVariant.Search,
    "write" to ToolRowVariant.Write,
    "edit" to ToolRowVariant.Edit,
    "run_code" to ToolRowVariant.Code,
    "cordis_package_inspect" to ToolRowVariant.Read,
    "cordis_runtime_inspect" to ToolRowVariant.Read,
    "cordis_run" to ToolRowVariant.Other,
    "cordis_stop" to ToolRowVariant.Other,
    "cordis_undefine" to ToolRowVariant.Other,
)

/**
 * Which argument identifies the call, per variant, in preference order. The first key present in
 * the arguments object wins.
 */
private val SUMMARY_KEYS: Map<ToolRowVariant, List<String>> = mapOf(
    ToolRowVariant.Bash to listOf("description", "command"),
    ToolRowVariant.Read to listOf("path", "file_path", "url"),
    // `queries` first: harness 0.1.0-rc.8 turned `web_search` into a 1-4 query array, and an
    // rc.7 host still sends the singular `query` that grep and glob use anyway.
    ToolRowVariant.Search to listOf("queries", "query", "pattern", "url"),
    ToolRowVariant.Write to listOf("path", "file_path"),
    ToolRowVariant.Edit to listOf("path", "file_path"),
    ToolRowVariant.Code to listOf("description"),
    // Any other tool: the first argument that reads as a subject. The title already names the tool.
    ToolRowVariant.Other to listOf("description", "title", "name", "objective", "file_path", "path", "url", "query", "prompt"),
)

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** The header a tool row shows: a verb plus the argument that identifies the call. */
internal data class ToolRowModel(
    val variant: ToolRowVariant,
    val title: String,
    val summary: String?,
    /** Set when the summary names a file the host could open. */
    val filePath: String?,
)

internal fun classifyTool(toolName: String): ToolRowVariant =
    TOOL_VARIANTS[toolName.lowercase()] ?: ToolRowVariant.Other

/**
 * The row's leading glyph, keyed on the same variant as the verb.
 *
 * Deriving it here rather than from the card's presenter shape is what makes the icon column agree
 * with the verb column: a `web_fetch` reads `Read` and gets the page glyph, not whatever card the
 * host chose to render its result in.
 */
internal fun ToolRowVariant.featherIcon(): ImageVector = when (this) {
    ToolRowVariant.Search -> FeatherIcons.Search
    ToolRowVariant.Read -> FeatherIcons.FileText
    ToolRowVariant.Bash -> FeatherIcons.Terminal
    ToolRowVariant.Write -> FeatherIcons.FilePlus
    ToolRowVariant.Edit -> FeatherIcons.Edit3
    ToolRowVariant.Code -> FeatherIcons.Code
    ToolRowVariant.Other -> FeatherIcons.Tool
}

/**
 * Build the row header for one call.
 *
 * [viewTitle] is the tool presenter's own title when it supplied one — it knows details the generic
 * table cannot, such as a shell being PowerShell rather than bash — and takes precedence over the
 * variant verb. An unrecognised tool with no presenter title keeps its raw name in the summary so
 * the row is still identifiable.
 */
internal fun toolRowModel(
    toolName: String,
    argumentsJson: String?,
    cwd: String?,
    viewTitle: String? = null,
): ToolRowModel {
    val variant = classifyTool(toolName)
    val arguments = parseArguments(argumentsJson)
    val displayArguments = arguments ?: argumentsJson?.let { com.labteto.dshmobile.core.wire.partialArgumentStrings(it) }
    val derived = SUMMARY_KEYS[variant]
        .orEmpty()
        .firstNotNullOfOrNull { key -> displayArguments?.get(key).asSummary()?.takeIf { it.isNotBlank() } }
    val relative = derived?.let { relativizeToCwd(it, cwd) }
    // An unclassified tool with no presenter title used to read "Tool call · list_subagent_models":
    // the tool's code name, which says little to anyone who is not reading the harness's source. Its
    // name in words is the title instead, and the summary is free for what it acted on.
    val unnamed = variant == ToolRowVariant.Other && viewTitle == null && toolName.isNotBlank()
    val title = when {
        !viewTitle.isNullOrBlank() -> viewTitle
        unnamed -> humanizeIdentifier(toolName)
        // The harness's PowerShell tool is not bash, and the row should not say it is.
        toolName.equals("pwsh", ignoreCase = true) -> POWERSHELL_TITLE
        else -> variant.title
    }
    return ToolRowModel(
        variant = variant,
        title = title,
        summary = relative?.let(::rowSummaryText),
        filePath = filePathOf(variant, arguments),
    )
}

/** The PowerShell tool's row title: a product name, the same in every language. */
internal const val POWERSHELL_TITLE = "PowerShell"

/**
 * A background job's producer kind (`bash`, `pwsh`, `pty-send`, `subagent`) as words: the shells by
 * their product names, anything else as [humanizeIdentifier] reads it. The job sheet, the Details
 * panel and the Jobs card all say "PowerShell", never the wire name `pwsh`.
 */
internal fun jobKindLabel(kind: String): String = when (kind.lowercase()) {
    "pwsh" -> POWERSHELL_TITLE
    "bash" -> "Bash"
    else -> humanizeIdentifier(kind)
}

/**
 * Strip the session's working directory from a path so rows read `app\build.gradle.kts` rather than
 * `D:\LabTeto\deepseek-mobile\app\build.gradle.kts`. Handles either separator, because the harness
 * host may be Windows or POSIX regardless of what the phone runs.
 */
internal fun relativizeToCwd(text: String, cwd: String?): String {
    val root = cwd?.takeIf { it.isNotBlank() } ?: return text
    val normalizedRoot = root.trimEnd('\\', '/')
    if (!text.startsWith(normalizedRoot, ignoreCase = true)) return text
    return text.substring(normalizedRoot.length).trimStart('\\', '/').ifBlank { text }
}

/** The last path segment, whichever separator the host uses. */
internal fun basename(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

private fun filePathOf(variant: ToolRowVariant, arguments: JsonObject?): String? = when (variant) {
    ToolRowVariant.Read, ToolRowVariant.Write, ToolRowVariant.Edit ->
        listOf("path", "file_path").firstNotNullOfOrNull { arguments?.get(it).asString() }
    else -> null
}

/**
 * The identifying argument as one line. A plain string is itself; an array of strings is joined
 * the way the harness's own presenter joins `web_search`'s queries, so a multi-query search reads
 * as one row rather than losing its subject entirely.
 */
private fun JsonElement?.asSummary(): String? = when (this) {
    is JsonArray -> mapNotNull { it.asString()?.takeIf(String::isNotBlank) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
    else -> asString()
}

/** Tool arguments arrive as a raw JSON string; a malformed one simply yields no summary. */
private fun parseArguments(argumentsJson: String?): JsonObject? {
    val raw = argumentsJson?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { LENIENT_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/** The most of a summary a one-line row is given to lay out. */
internal const val ROW_SUMMARY_MAX_CHARS = 400

/**
 * A summary cut to what a one-line row can show. The row ellipsizes anyway, but Android shapes the
 * whole string before it cuts it: a 300 KB command handed to the row in full took the app's native
 * heap past 3 GB on a phone emulator (the built-in harness's oversized-command check, found in the
 * integration run). Only the first line is kept, and at most [ROW_SUMMARY_MAX_CHARS] characters of it.
 */
internal fun rowSummaryText(summary: String): String {
    val firstLine = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary
    val cut = if (firstLine.length > ROW_SUMMARY_MAX_CHARS) firstLine.take(ROW_SUMMARY_MAX_CHARS) + "…" else firstLine
    return if (cut.length < summary.length && !cut.endsWith("…")) "$cut…" else cut
}
