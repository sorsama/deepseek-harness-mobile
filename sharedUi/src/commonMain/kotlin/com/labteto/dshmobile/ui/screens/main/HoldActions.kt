package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import org.jetbrains.compose.resources.stringResource
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_copy
import com.labteto.dshmobile.shared.resources.details_copy_link
import com.labteto.dshmobile.shared.resources.hold_copy_arguments
import com.labteto.dshmobile.shared.resources.hold_copy_command
import com.labteto.dshmobile.shared.resources.hold_copy_output
import com.labteto.dshmobile.shared.resources.hold_copy_path
import com.labteto.dshmobile.shared.resources.hold_copy_query
import com.labteto.dshmobile.shared.resources.hold_edit_as_new
import com.labteto.dshmobile.shared.resources.hold_edit_as_new_detail
import com.labteto.dshmobile.shared.resources.hold_mention
import com.labteto.dshmobile.shared.resources.hold_open_file
import com.labteto.dshmobile.shared.resources.tool_output_show_raw
import com.labteto.dshmobile.ui.components.DsIcons

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
                ToolCopyKind.Command -> Res.string.hold_copy_command
                ToolCopyKind.Path -> Res.string.hold_copy_path
                ToolCopyKind.Link -> Res.string.details_copy_link
                ToolCopyKind.Query -> Res.string.hold_copy_query
                ToolCopyKind.Output -> Res.string.hold_copy_output
            }
            add(HoldAction(stringResource(label), DsIcons.ContentCopy) { copy(item.text) })
        }
        if (filePath != null) {
            add(HoldAction(stringResource(Res.string.hold_open_file), DsIcons.FolderOpen) { open(filePath) })
            context.onMention?.let { mention ->
                add(
                    HoldAction(stringResource(Res.string.hold_mention), DsIcons.AlternateEmail) {
                        mention(mentionPath(filePath, context.cwd), false)
                    },
                )
            }
        }
        // The protocol view, for whoever is debugging: what the call was sent and what came back,
        // as the harness recorded it. It used to sit beside every output and under every card; the
        // transcript itself now shows only what the tool did.
        if (call.arguments.isNotBlank()) {
            add(HoldAction(stringResource(Res.string.hold_copy_arguments), DsIcons.ContentCopy) { copy(call.arguments) })
        }
        onShowRaw?.let { show ->
            add(HoldAction(stringResource(Res.string.tool_output_show_raw), DsIcons.Code) { show() })
        }
    }
    // What the menu is about: the command or path, else the tool in words (`pwsh` is "PowerShell",
    // `list_agents` is "List agents"), never the wire name.
    val preview = copies.firstOrNull { it.kind != ToolCopyKind.Output }?.text
        ?: toolRowModel(call.name, call.arguments, context.cwd).title
    HoldMenu(onDismiss = onDismiss, actions = actions, preview = technicalDisplay(preview), monospacePreview = true)
}

/**
 * A path as an `@` mention wants it: relative to the session's working directory when it is inside
 * it, since that is how the composer's own reference picker writes them, and as it is otherwise.
 */
internal fun mentionPath(path: String, cwd: String?): String = relativizeToCwd(path, cwd)

/** The hold menu of a file named somewhere outside a tool card: copy its path, or mention it. */
@Composable
fun FileHoldMenu(
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
            add(HoldAction(stringResource(Res.string.hold_copy_path), DsIcons.ContentCopy) { copy(copyPath) })
            onMention?.let { mention ->
                add(HoldAction(stringResource(Res.string.hold_mention), DsIcons.AlternateEmail) { mention(path, folder) })
            }
        },
    )
}

/** The hold menu of a prompt listed somewhere other than its bubble: the history sheet. */
@Composable
fun PromptHoldMenu(text: String, onEdit: ((String) -> Unit)?, onDismiss: () -> Unit) {
    val copy = rememberCopier()
    HoldMenu(
        onDismiss = onDismiss,
        preview = text,
        actions = buildList {
            add(HoldAction(stringResource(Res.string.common_copy), DsIcons.ContentCopy) { copy(text) })
            onEdit?.let { edit ->
                add(
                    HoldAction(
                        stringResource(Res.string.hold_edit_as_new),
                        DsIcons.Edit,
                        detail = stringResource(Res.string.hold_edit_as_new_detail),
                    ) { edit(text) },
                )
            }
        },
    )
}
