package com.labteto.dshmobile.core.permission

/**
 * What an `approval/request` asks, read for a phone screen.
 *
 * The built-in harness's "Ask before commands" gate (the runtime's `permission.mjs`) words its
 * request as `<tool>: <what>` in `reason`, where `<what>` is the command, the program, or the path
 * of a file outside the workspace, and puts a question plus the same text in `displayReason.en`.
 * Shown as it comes, that reads "Reason: bash: rm -rf build" in body text. Split, the panel can ask
 * its own localized question and show the command whole, in monospace.
 *
 * Every other request (a desktop harness's sandbox escalation, an Auto review denial) keeps the
 * harness's own wording, in the reader's language where the harness sent one.
 */
data class ApprovalPrompt(
    val kind: Kind,
    /** The tool the request is about. */
    val toolName: String,
    /** The command, program or path, exactly as the harness sent it; null for [Kind.Plain]. */
    val detail: String?,
    /** The harness cut [detail] short (it keeps the first [HARNESS_DETAIL_LIMIT] characters). */
    val shortened: Boolean,
    /** For [Kind.Plain]: the harness's own text, already in the reader's language where it had one. */
    val text: String?,
) {
    enum class Kind {
        /** A shell command or a program that can start processes: `bash`, `run_code`, the terminal tools. */
        Command,

        /** `write` or `edit` on a file outside the session's workspace. */
        FileChange,

        /** Another gated tool (a `plugin_manager` change): its name and arguments. */
        Tool,

        /** Not in the runtime gate's format: the harness's text as it is. */
        Plain,
    }

    companion object {
        /** The runtime gate keeps this many characters of a command and then adds `...`. */
        const val HARNESS_DETAIL_LIMIT = 2000

        /** Tools whose detail is something that runs. Matches the runtime gate's own list. */
        val COMMAND_TOOLS = setOf("bash", "run_code", "terminal_open", "terminal_send")

        /** Tools whose detail is a file path. */
        val FILE_TOOLS = setOf("write", "edit")

        /** Other tools the runtime gate asks about. */
        val OTHER_GATED_TOOLS = setOf("plugin_manager")
    }
}

/**
 * Read one request.
 *
 * @param toolName the request's `toolName`.
 * @param reason the request's raw `reason` (never the localized one: the format is in the raw text).
 * @param localizedReason what the harness wants a reader in this language to see, for [ApprovalPrompt.Kind.Plain].
 */
fun approvalPromptOf(toolName: String, reason: String?, localizedReason: String? = reason): ApprovalPrompt {
    val prefix = "$toolName: "
    val detail = reason?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() }
    val kind = when {
        detail == null -> ApprovalPrompt.Kind.Plain
        toolName in ApprovalPrompt.COMMAND_TOOLS -> ApprovalPrompt.Kind.Command
        toolName in ApprovalPrompt.FILE_TOOLS -> ApprovalPrompt.Kind.FileChange
        toolName in ApprovalPrompt.OTHER_GATED_TOOLS -> ApprovalPrompt.Kind.Tool
        else -> ApprovalPrompt.Kind.Plain
    }
    if (kind == ApprovalPrompt.Kind.Plain) {
        return ApprovalPrompt(kind, toolName, detail = null, shortened = false, text = localizedReason?.takeIf { it.isNotBlank() } ?: reason)
    }
    val shortened = detail!!.length == ApprovalPrompt.HARNESS_DETAIL_LIMIT + 3 && detail.endsWith("...")
    return ApprovalPrompt(kind, toolName, detail = detail, shortened = shortened, text = null)
}
