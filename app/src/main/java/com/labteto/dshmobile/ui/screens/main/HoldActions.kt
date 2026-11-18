package com.labteto.dshmobile.ui.screens.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.readToolResult
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.LocalFileOpener
import com.labteto.dshmobile.ui.components.rememberCopier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Which part of a tool call a hold can copy. */
internal enum class ToolCopyKind { Command, Path, Link, Query, Output }

/** One thing a tool call's hold menu offers to copy, with the exact text it copies. */
internal data class ToolCopy(val kind: ToolCopyKind, val text: String)

/**
 * What can be copied out of one tool call, in the order the hold menu lists it.
 *
 * Read from the arguments themselves, not from the row: the row shows a shell call's description
 * rather than its command and cuts every path down to the working directory, and the reason to copy
 * either is to paste it somewhere that needs the real thing. Queries keep one per line, so a
 * multi-query web search pastes as a list. [output] is the result's text, when it has any.
 */
internal fun toolCopies(argumentsJson: String?, output: String?): List<ToolCopy> {
    val arguments = argumentsJson
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { com.labteto.dshmobile.core.wire.WireJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    fun string(key: String): String? =
        (arguments?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    val queries = (arguments?.get("queries") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.takeIf(String::isNotBlank) }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n")
    return listOfNotNull(
        string("command")?.let { ToolCopy(ToolCopyKind.Command, it) },
        (string("path") ?: string("file_path"))?.let { ToolCopy(ToolCopyKind.Path, it) },
        string("url")?.let { ToolCopy(ToolCopyKind.Link, it) },
        (queries ?: string("query") ?: string("pattern"))?.let { ToolCopy(ToolCopyKind.Query, it) },
        output?.takeIf { it.isNotBlank() }?.let { ToolCopy(ToolCopyKind.Output, it) },
    )
}

/**
 * The text a settled call returned, for "Copy output": the same text the output view shows, with
 * the protocol wrappers around it peeled off, else its failure prose.
 */
internal fun toolOutputText(result: ToolResultNode?): String? =
    result?.let {
        readToolResult(it.content)?.text?.takeIf(String::isNotBlank) ?: toolFailureMessage(it.content)
    }

/**
 * A tool call's hold menu: copy what it ran or touched, copy what came back, and for a file call,
 * open the file or mention it in the next message.
 */
@Composable
internal fun ToolHoldMenu(
    call: ToolCallNode,
    result: ToolResultNode?,
    filePath: String?,
    context: ChatNodeContext,
    /** Opens the call's raw protocol data ([toolRawJson]); null offers no such action. */
    onShowRaw: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val copy = rememberCopier()
    val open = LocalFileOpener.current
    // Once per call and result: the output text of a call can be megabytes, and reading it is not
    // something to repeat each time the menu recomposes.
    val copies = remember(call.arguments, result) { toolCopies(call.arguments, toolOutputText(result)) }
    val actions = buildList {
        copies.forEach { item ->
            val label = when (item.kind) {
                ToolCopyKind.Command -> R.string.hold_copy_command
                ToolCopyKind.Path -> R.string.hold_copy_path
                ToolCopyKind.Link -> R.string.details_copy_link
                ToolCopyKind.Query -> R.string.hold_copy_query
                ToolCopyKind.Output -> R.string.hold_copy_output
            }
            add(HoldAction(stringResource(label), Icons.Outlined.ContentCopy) { copy(item.text) })
        }
        if (filePath != null) {
            add(HoldAction(stringResource(R.string.hold_open_file), Icons.Outlined.FolderOpen) { open(filePath) })
            context.onMention?.let { mention ->
                add(
                    HoldAction(stringResource(R.string.hold_mention), Icons.Outlined.AlternateEmail) {
                        mention(mentionPath(filePath, context.cwd), false)
                    },
                )
            }
        }
        // The protocol view, for whoever is debugging: what the call was sent and what came back,
        // as the harness recorded it. It used to sit beside every output and under every card; the
        // transcript itself now shows only what the tool did.
        if (call.arguments.isNotBlank()) {
            add(HoldAction(stringResource(R.string.hold_copy_arguments), Icons.Outlined.ContentCopy) { copy(call.arguments) })
        }
        onShowRaw?.let { show ->
            add(HoldAction(stringResource(R.string.tool_output_show_raw), Icons.Outlined.Code) { show() })
        }
    }
    // What the menu is about: the command or path, else the tool in words (`pwsh` is "PowerShell",
    // `list_agents` is "List agents"), never the wire name.
    val preview = copies.firstOrNull { it.kind != ToolCopyKind.Output }?.text
        ?: toolRowModel(call.name, call.arguments, context.cwd).title
    HoldMenu(onDismiss = onDismiss, actions = actions, preview = technicalDisplay(preview), monospacePreview = true)
}

/**
 * Everything the harness recorded about one call, as pretty JSON: its name and arguments, and once
 * it settled, the result body, the durable `meta` the cards are built from, and the error facts.
 * This is where the raw metadata that used to sit under every card is still one hold away.
 */
internal fun toolRawJson(call: ToolCallNode, result: ToolResultNode?): String {
    val json = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(call.name))
        put("callId", kotlinx.serialization.json.JsonPrimitive(call.callId))
        val args = runCatching { com.labteto.dshmobile.core.wire.WireJson.parseToJsonElement(call.arguments) }.getOrNull()
        put("arguments", args ?: kotlinx.serialization.json.JsonPrimitive(call.arguments))
        if (result != null) {
            put("isError", kotlinx.serialization.json.JsonPrimitive(result.isError))
            result.content?.let { put("content", it) }
            result.meta?.let { put("meta", it) }
            if (result.errorCode != null || result.errorName != null || result.errorReason != null) {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    result.errorName?.let { put("name", kotlinx.serialization.json.JsonPrimitive(it)) }
                    result.errorCode?.let { put("code", kotlinx.serialization.json.JsonPrimitive(it)) }
                    result.errorReason?.let { put("reason", kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }
    }
    return RAW_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), json)
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val RAW_JSON = kotlinx.serialization.json.Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * A path as an `@` mention wants it: relative to the session's working directory when it is inside
 * it, since that is how the composer's own reference picker writes them, and as it is otherwise.
 */
internal fun mentionPath(path: String, cwd: String?): String = relativizeToCwd(path, cwd)

/** The hold menu of a file named somewhere outside a tool card: copy its path, or mention it. */
@Composable
internal fun FileHoldMenu(
    path: String,
    folder: Boolean,
    onMention: ((String, Boolean) -> Unit)?,
    onDismiss: () -> Unit,
    /** The path to copy, when it differs from the one mentioned (an absolute one, say). */
    copyPath: String = path,
) {
    val copy = rememberCopier()
    HoldMenu(
        onDismiss = onDismiss,
        preview = technicalDisplay(copyPath),
        monospacePreview = true,
        actions = buildList {
            add(HoldAction(stringResource(R.string.hold_copy_path), Icons.Outlined.ContentCopy) { copy(copyPath) })
            onMention?.let { mention ->
                add(HoldAction(stringResource(R.string.hold_mention), Icons.Outlined.AlternateEmail) { mention(path, folder) })
            }
        },
    )
}

/** The hold menu of a prompt listed somewhere other than its bubble: the history sheet. */
@Composable
internal fun PromptHoldMenu(text: String, onEdit: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val copy = rememberCopier()
    HoldMenu(
        onDismiss = onDismiss,
        preview = text,
        actions = buildList {
            add(HoldAction(stringResource(R.string.common_copy), Icons.Outlined.ContentCopy) { copy(text) })
            onEdit?.let { edit ->
                add(
                    HoldAction(
                        stringResource(R.string.hold_edit_as_new),
                        Icons.Outlined.Edit,
                        detail = stringResource(R.string.hold_edit_as_new_detail),
                    ) { edit(text) },
                )
            }
        },
    )
}
