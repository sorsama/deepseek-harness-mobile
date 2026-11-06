package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DiffHunk
import com.labteto.dshmobile.ui.components.ReadLine
import com.labteto.dshmobile.ui.components.SearchFile
import com.labteto.dshmobile.ui.components.SearchMatch
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.WebCardKind
import com.labteto.dshmobile.ui.components.WebSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool cards, derived here from the raw call and result.
 *
 * Through harness 0.1.1 the host computed these: it parsed the arguments, consulted its tool
 * registry, and sent a finished render intent alongside each event. Harness 0.1.2 stopped — the
 * session journal now carries only raw, persistable events — so the derivation moved to the
 * client, and this is that derivation.
 *
 * It is a port of the host's own card models (`packages/client/ui-tool/src/client/tool/models`),
 * kept deliberately close to them: the same tool names, the same argument validation, and the
 * same rule that anything not recognised in full falls through to the generic card rather than
 * being half-rendered. Validation is not defensive padding here — a card that guesses at a shape
 * it does not actually understand is worse than the generic one, because the generic card at
 * least shows the arguments as they were.
 *
 * The durable `meta` a tool attaches to its result is what most of these read. It survived the
 * change specifically so a client could do this.
 *
 * One difference from the host's version: it skips any call with a `parentCallId` (a child
 * dispatch inside code mode), which this app's fold does not model, so there is no such guard.
 */
internal fun buildToolCardView(
    call: ToolCallNode,
    result: ToolResultNode?,
    running: Boolean,
    cwd: String? = null,
    home: String? = null,
): ToolCardView {
    val args = parsedArguments(call)
    if (args != null) {
        terminalCard(call, args, result, running, cwd)?.let { return it }
        diffCard(call, args, result)?.let { return it }
        readCard(args, result, cwd, home)?.let { return it }
        searchCard(call, args, result)?.let { return it }
        webCard(call, args, result)?.let { return it }
    }
    return ToolCardView.GenericCard(title = call.name, rawInput = call.arguments)
}

/** The call's arguments as an object, or null when they are absent or not a JSON object. */
private fun parsedArguments(call: ToolCallNode): JsonObject? =
    runCatching { WireJson.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()

/** The single text block a first-party result carries, or null for any other content layout. */
private fun singleResultText(result: ToolResultNode): String? {
    val content = result.content as? JsonArray ?: return null
    val only = content.singleOrNull() as? JsonObject ?: return null
    if (only["type"]?.jsonPrimitive?.contentOrNull != "text") return null
    return only["text"]?.jsonPrimitive?.contentOrNull
}

/** Every text block joined: a capped search's full-result locator, and what "Copy output" copies. */
internal fun flattenContent(result: ToolResultNode): String? {
    val content = result.content as? JsonArray ?: return null
    val text = content
        .mapNotNull { it as? JsonObject }
        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
        .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("\n")
    return text.ifEmpty { null }
}

private fun str(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.contentOrNull
private fun int(o: JsonObject, key: String): Int? = o[key]?.jsonPrimitive?.intOrNull
private fun bool(o: JsonObject, key: String): Boolean? = o[key]?.jsonPrimitive?.booleanOrNull
private fun meta(result: ToolResultNode?): JsonObject? = result?.meta as? JsonObject

// ============================================================================================
// Terminal
// ============================================================================================

private class ShellCall(
    val command: String,
    val description: String?,
    val workdir: String?,
    /** A persistent shell reports resets and partial output, so its result has no one exit status. */
    val persistent: Boolean,
    val background: Boolean,
)

private fun shellCall(name: String, args: JsonObject): ShellCall? {
    if (name != "bash" && name != "pwsh") return null
    val command = str(args, "command")?.takeIf { it.isNotBlank() } ?: return null
    if (args.containsKey("workdir") && str(args, "workdir") == null) return null
    val background = bool(args, "background") ?: bool(args, "run_in_background")
    if (!args.containsKey("description")) {
        // The shipped bash and pwsh schemas require `description`; the persistent-shell providers
        // omit it, and their parameter roots stay open — so its absence is the discriminator.
        return ShellCall(command, null, null, persistent = true, background = false)
    }
    val description = str(args, "description")?.takeIf { it.isNotBlank() } ?: return null
    return ShellCall(command, description, str(args, "workdir"), persistent = false, background = background == true)
}

private class TerminalSendCall(val text: String, val sessionId: String, val background: Boolean)

private fun terminalSendCall(name: String, args: JsonObject): TerminalSendCall? {
    if (name != "terminal_send") return null
    val sessionId = str(args, "sessionId")?.takeIf { it.isNotEmpty() } ?: return null
    val text = str(args, "text") ?: return null
    val background = bool(args, "run_in_background") ?: bool(args, "background")
    return TerminalSendCall(text, sessionId, background == true)
}

/**
 * Split a trailing exit-code or signal marker off rendered shell output.
 *
 * The markers are the host shell renderer's own literals. Absent either, the command exited 0 —
 * the renderer only appends a marker for a non-zero exit or a signal.
 */
private class ExitStatus(val output: String, val exitCode: Int?, val signal: String?)

private val SIGNAL_MARKER = Regex("\\n\\[killed by signal: ([^\\]\\n]+)]$")
private val EXIT_MARKER = Regex("\\n\\[exit code: (\\d+)]$")

private fun parseExitStatus(text: String): ExitStatus {
    SIGNAL_MARKER.find(text)?.let { match ->
        return ExitStatus(text.substring(0, match.range.first), null, match.groupValues[1])
    }
    EXIT_MARKER.find(text)?.let { match ->
        return ExitStatus(text.substring(0, match.range.first), match.groupValues[1].toIntOrNull(), null)
    }
    return ExitStatus(text, 0, null)
}

private fun terminalCard(
    call: ToolCallNode,
    args: JsonObject,
    result: ToolResultNode?,
    running: Boolean,
    cwd: String?,
): ToolCardView? {
    val shell = shellCall(call.name, args)
    val send = if (shell == null) terminalSendCall(call.name, args) else null
    if (shell == null && send == null) return null
    // A background call has no output to show inline; it becomes a job instead.
    if (shell?.background == true || send?.background == true) return null

    val command = shell?.command ?: send?.text.orEmpty()
    val description = shell?.description
    val workdir = resolveTerminalCwd(shell?.workdir, cwd)

    if (result == null) {
        return ToolCardView.TerminalCard(
            title = command,
            description = description,
            cwd = workdir,
            running = true,
        )
    }
    // A persistent shell's result stays generic: it can report a reset or partial output, and
    // inventing one process exit status for it would be a claim this client cannot support.
    if (result.isError || shell?.persistent == true) return null
    val output = singleResultText(result) ?: return null
    val status = if (send != null) ExitStatus(output, null, null) else parseExitStatus(output)
    return ToolCardView.TerminalCard(
        title = command,
        description = description,
        cwd = workdir,
        output = status.output,
        exitCode = status.exitCode,
        signal = status.signal,
        running = false,
    )
}

/**
 * The directory a command actually ran in, for the prompt label.
 *
 * An absolute workdir is used as authored, a relative one joins under the session workspace, and
 * an omitted one is the workspace itself. Separators are preserved because the value is only
 * displayed — a Windows path keeps its backslashes.
 */
private fun resolveTerminalCwd(workdir: String?, sessionCwd: String?): String? {
    if (workdir.isNullOrEmpty()) return sessionCwd
    if (sessionCwd.isNullOrEmpty()) return normalizeSegments(workdir)
    if (isAbsolutePath(workdir)) return normalizeSegments(workdir)
    val separator = if (sessionCwd.contains('\\') && !sessionCwd.contains('/')) "\\" else "/"
    return normalizeSegments(sessionCwd.trimEnd('/', '\\') + separator + workdir)
}

private fun isAbsolutePath(path: String): Boolean =
    path.startsWith("/") || path.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(path)

/**
 * Collapse `.` and `..` so the label names the directory the command ran in.
 *
 * The shell resolves its workdir before running, so a joined `/w/app/..` has to display as `/w`
 * rather than as the literal it was assembled from. A `..` that would climb past the root is
 * dropped, which is what a filesystem does with it.
 */
private fun normalizeSegments(path: String): String {
    if (!Regex("(?:^|[/\\\\])\\.\\.?(?:[/\\\\]|$)").containsMatchIn(path)) return path
    val backslashed = path.contains('\\') && !path.contains('/')
    val separator = if (backslashed) "\\" else "/"
    val rooted = path.startsWith("/") || path.startsWith("\\")
    val drive = Regex("^[A-Za-z]:").find(path)?.value.orEmpty()
    val body = collapse(path.substring(drive.length), rooted || drive.isNotEmpty(), separator)
    val leading = if (rooted) separator else ""
    return if (drive.isEmpty()) "$leading$body" else "$drive${if (rooted) leading else separator}$body"
}

private fun collapse(body: String, rooted: Boolean, separator: String): String {
    val kept = ArrayList<String>()
    for (segment in body.split('/', '\\')) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." ->
                if (kept.isNotEmpty() && kept.last() != "..") {
                    kept.removeAt(kept.lastIndex)
                } else if (!rooted) {
                    // Without a root a `..` stays meaningful against a cwd this cannot see.
                    kept.add(segment)
                }
            else -> kept.add(segment)
        }
    }
    return kept.joinToString(separator)
}

// ============================================================================================
// Diff
// ============================================================================================

private class IntendedDiff(val tool: String, val hunk: DiffHunk)

/** The diff a file-mutation call *asks* for, derived from its arguments alone. */
private fun intendedDiff(call: ToolCallNode, args: JsonObject): IntendedDiff? {
    if (call.name == "str_replace_editor") {
        val path = str(args, "path")?.takeIf { it.isNotBlank() } ?: return null
        return when (str(args, "command")) {
            "create" -> IntendedDiff(
                "str_replace_editor",
                DiffHunk(path, null, str(args, "file_text").orEmpty()),
            )
            "str_replace" -> IntendedDiff(
                "str_replace_editor",
                DiffHunk(path, str(args, "old_str"), str(args, "new_str").orEmpty()),
            )
            else -> null
        }
    }
    val path = str(args, "file_path")?.takeIf { it.isNotBlank() } ?: return null
    if (call.name == "write") {
        val content = str(args, "content") ?: return null
        return IntendedDiff("write", DiffHunk(path, null, content))
    }
    if (call.name != "edit") return null
    val oldText = str(args, "old_string") ?: return null
    val newText = str(args, "new_string") ?: return null
    return IntendedDiff("edit", DiffHunk(path, oldText.ifEmpty { null }, newText))
}

/** The hunks the tool reports it actually applied, from its result metadata. */
private fun appliedDiffs(result: ToolResultNode): List<DiffHunk>? {
    val diffs = meta(result)?.get("diffs") as? JsonArray ?: return null
    if (diffs.isEmpty()) return emptyList()
    val out = ArrayList<DiffHunk>(diffs.size)
    for (hunk in diffs) {
        val o = hunk as? JsonObject ?: return null
        val path = str(o, "path") ?: return null
        val newText = str(o, "newText") ?: return null
        out.add(DiffHunk(path, str(o, "oldText"), newText))
    }
    return out
}

private fun diffCard(call: ToolCallNode, args: JsonObject, result: ToolResultNode?): ToolCardView? {
    val intended = intendedDiff(call, args) ?: return null
    // Still running: show the change the call asked for.
    if (result == null) return ToolCardView.DiffCard(diffs = listOf(intended.hunk))
    // `str_replace_editor` reports nothing about what it applied, so a settled one goes generic.
    if (intended.tool == "str_replace_editor" || result.isError) return null
    val applied = appliedDiffs(result)
    if (applied.isNullOrEmpty()) {
        // A successful write with no reported hunks is an identical overwrite; its whole-file
        // diff is still the truthful picture. An edit that reports nothing is not.
        return if (intended.tool == "write") ToolCardView.DiffCard(diffs = listOf(intended.hunk)) else null
    }
    return ToolCardView.DiffCard(diffs = applied)
}

// ============================================================================================
// Read
// ============================================================================================

private val READ_BODY = Regex(
    "^<path>[^\\n]*</path>\\n<type>file</type>\\n<content>\\n([\\s\\S]*)\\n</content>$",
)

private fun readCard(
    args: JsonObject,
    result: ToolResultNode?,
    cwd: String?,
    home: String?,
): ToolCardView? {
    if (result == null || result.isError) return null
    val path = str(args, "file_path")?.takeIf { it.isNotBlank() } ?: return null
    val m = meta(result) ?: return null
    val metaPath = str(m, "path") ?: return null
    val offset = int(m, "offset")?.takeIf { it >= 1 } ?: return null
    val totalLines = int(m, "totalLines")?.takeIf { it >= 0 } ?: return null
    val rawLines = m["lines"] as? JsonArray ?: return null
    // The model-facing envelope has to match too: without it this is not the read tool's own
    // result, whatever the metadata happens to look like.
    val text = singleResultText(result) ?: return null
    if (!READ_BODY.containsMatchIn(text)) return null

    val lines = ArrayList<ReadLine>(rawLines.size)
    var previous = offset - 1
    for (raw in rawLines) {
        val o = raw as? JsonObject ?: return null
        val number = int(o, "number") ?: return null
        val lineText = str(o, "text") ?: return null
        // Strictly increasing and within the file: a run that is not is metadata this client
        // cannot trust to render with a line-number gutter.
        if (number < 1 || number <= previous || number > totalLines) return null
        previous = number
        lines.add(ReadLine(number, lineText))
    }
    return ToolCardView.ReadCard(
        label = abbreviateHome(relativizeToCwd(metaPath.ifBlank { path }, cwd), home),
        path = metaPath,
        lines = lines,
        totalLines = totalLines,
        lang = str(m, "lang"),
    )
}

// `relativizeToCwd` is the row model's: the card label and the row header must abbreviate a
// path the same way, or the two lines describing one tool call disagree about which file it
// touched.

/** Display a leftover home-rooted path as `~`. */
private fun abbreviateHome(path: String, home: String?): String {
    if (home.isNullOrEmpty()) return path
    val root = home.trimEnd('/', '\\')
    if (!path.startsWith(root)) return path
    val rest = path.substring(root.length).trimStart('/', '\\')
    return if (rest.isEmpty()) "~" else "~/$rest"
}

// ============================================================================================
// Search
// ============================================================================================

/**
 * Whether a call is a `grep` or `glob` this client will render as a search card.
 *
 * The `include` check is the host's: a negated or comma-separated top-level pattern means the
 * result set is not the simple one this card shows.
 */
private fun validSearchCall(call: ToolCallNode, args: JsonObject): String? {
    if (call.name != "grep" && call.name != "glob") return null
    val pattern = str(args, "pattern") ?: return null
    if (call.name == "grep" && pattern.isEmpty()) return null
    if (call.name == "glob" && pattern.isBlank()) return null
    if (args.containsKey("path") && str(args, "path")?.isNotBlank() != true) return null
    if (call.name == "grep" && args.containsKey("include")) {
        val include = str(args, "include") ?: return null
        if (!validInclude(include)) return null
    }
    return call.name
}

private fun validInclude(include: String): Boolean {
    if (include.isBlank() || include.startsWith("!")) return false
    var depth = 0
    for (c in include) {
        when {
            c == '{' -> depth++
            c == '}' -> depth = maxOf(0, depth - 1)
            c == ',' && depth == 0 -> return false
        }
    }
    return true
}

private fun narrowSearchFiles(value: JsonElement?): List<SearchFile>? {
    val files = value as? JsonArray ?: return null
    val out = ArrayList<SearchFile>(files.size)
    for (file in files) {
        val o = file as? JsonObject ?: return null
        val path = str(o, "path") ?: return null
        val matches = o["matches"] as? JsonArray ?: return null
        val narrowed = ArrayList<SearchMatch>(matches.size)
        for (match in matches) {
            val mo = match as? JsonObject ?: return null
            val lineNumber = int(mo, "lineNumber")?.takeIf { it >= 1 } ?: return null
            val line = str(mo, "line") ?: return null
            narrowed.add(SearchMatch(lineNumber, line))
        }
        out.add(SearchFile(path, narrowed))
    }
    return out
}

private fun searchCard(call: ToolCallNode, args: JsonObject, result: ToolResultNode?): ToolCardView? {
    if (result == null || result.isError) return null
    val tool = validSearchCall(call, args) ?: return null
    val m = meta(result) ?: return null
    val truncated = bool(m, "truncated") ?: return null
    val total = int(m, "total")?.takeIf { it >= 0 } ?: return null
    if (tool == "grep") {
        if (str(m, "shape") != "matches") return null
        val files = narrowSearchFiles(m["files"]) ?: return null
        return ToolCardView.SearchCard(
            matches = SearchMatches.FileMatches(files),
            truncated = truncated,
            total = total,
        )
    }
    if (str(m, "shape") != "paths") return null
    val paths = (m["paths"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull ?: return null }
        ?: return null
    return ToolCardView.SearchCard(
        matches = SearchMatches.PathList(paths),
        truncated = truncated,
        total = total,
    )
}

// ============================================================================================
// Web
// ============================================================================================

private fun validWebCall(call: ToolCallNode, args: JsonObject): String? = when (call.name) {
    "web_search" -> {
        val queries = args["queries"] as? JsonArray
        if (queries != null && queries.isNotEmpty() &&
            queries.all { it.jsonPrimitive.contentOrNull?.isNotBlank() == true }
        ) {
            call.name
        } else {
            null
        }
    }
    "web_fetch" -> if (str(args, "url")?.isNotBlank() == true) call.name else null
    else -> null
}

private fun webSources(value: JsonElement?): List<WebSource>? {
    val sources = value as? JsonArray ?: return null
    val out = ArrayList<WebSource>(sources.size)
    for (source in sources) {
        val o = source as? JsonObject ?: return null
        val url = str(o, "url") ?: return null
        out.add(WebSource(url, str(o, "title"), str(o, "snippet")))
    }
    return out
}

private fun webCard(call: ToolCallNode, args: JsonObject, result: ToolResultNode?): ToolCardView? {
    if (result == null || result.isError) return null
    val tool = validWebCall(call, args) ?: return null
    val m = meta(result) ?: return null
    if (bool(m, "truncated") == null) return null
    if (tool == "web_search") {
        val sources = webSources(m["sources"]) ?: return null
        return ToolCardView.WebCard(
            kind = WebCardKind.Search(answer = str(m, "answer"), sources = sources),
        )
    }
    val url = str(m, "url") ?: return null
    val statusCode = int(m, "statusCode") ?: return null
    return ToolCardView.WebCard(kind = WebCardKind.Fetch(url, statusCode))
}

/**
 * One content block of a generic card. Images become a real raster: a tool that returns a
 * screenshot delivers it through this path, not through the message path.
 */
@Suppress("unused")
private fun mapContentBlock(block: com.labteto.dshmobile.core.wire.dto.ContentBlock): ContentBlockView =
    when (block) {
        is com.labteto.dshmobile.core.wire.dto.ContentBlock.Text -> ContentBlockView.TextBlock(block.text)
        is com.labteto.dshmobile.core.wire.dto.ContentBlock.Reasoning ->
            ContentBlockView.ReasoningBlock(block.text)
        is com.labteto.dshmobile.core.wire.dto.ContentBlock.Image -> ContentBlockView.ImageBlock(
            attachmentId = block.attachment.attachmentId,
            mediaType = block.attachment.mediaType,
            bytes = block.attachment.bytes,
            width = block.attachment.width,
            height = block.attachment.height,
            name = block.attachment.name,
        )
        is com.labteto.dshmobile.core.wire.dto.ContentBlock.ToolCall ->
            ContentBlockView.TextBlock("↳ ${block.name}")
        is com.labteto.dshmobile.core.wire.dto.ContentBlock.ToolResult ->
            ContentBlockView.TextBlock("↳ result")
        else -> ContentBlockView.TextBlock(block.toString())
    }
