package com.labteto.dshmobile.core.permission

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The code a shell tool fails with when its session's mode needs a sandbox the host cannot give. */
const val SANDBOX_UNAVAILABLE = "SANDBOX_UNAVAILABLE"

/**
 * Presets that run commands without the harness's sandbox, so a session on one of them cannot be
 * refused for want of it: upstream's Full access, and the built-in runtime's Ask before commands.
 */
val PRESETS_WITHOUT_SANDBOX = setOf("danger-full-access", "ask-commands")

/**
 * Whether the chat should explain that the last request's command was refused for want of a
 * sandbox, and the session is not on a preset that would have run it. The notice goes when the
 * person sends the next message, or when the session is switched to such a preset.
 *
 * The runtime's README ("What the app has to do", point 5) documents the two shapes the refusal
 * takes with harness 0.2.2-rc.1, and this reads both:
 *  - a runner was picked but could not start the command: the result carries
 *    `error.code` [SANDBOX_UNAVAILABLE]. That counts on any harness;
 *  - no runner is usable at all (what a phone meets): `tool-bash` re-throws the job's failure as a
 *    plain `Error`, so the result has no `error` at all, only the harness's sentence. On the
 *    built-in harness ([hostWithoutSandbox]) an error result of a shell tool that starts with that
 *    documented sentence counts too ([isSandboxRefusalText]). Elsewhere the words are not trusted.
 *
 * Earlier (#111) the built-in harness counted every failed shell call in a session on a sandboxed
 * preset, because the shape without a code had not been written down. The README now names it, so
 * that guess is gone: a command that started and then failed is not reported as a refusal.
 *
 * @param currentPreset the session's preset now (the `permissions` projection's `currentValue`).
 * @param hostWithoutSandbox the harness runs where no kernel sandbox can work (the phone's own).
 */
fun sandboxRefusalShown(nodes: List<ChatNode>, currentPreset: String?, hostWithoutSandbox: Boolean = false): Boolean {
    if (currentPreset in PRESETS_WITHOUT_SANDBOX) return false
    for (i in nodes.indices.reversed()) {
        when (val node = nodes[i]) {
            is UserMessageNode -> return false
            is ToolResultNode -> if (isSandboxRefusal(nodes, node, hostWithoutSandbox)) return true
            else -> Unit
        }
    }
    return false
}

private fun isSandboxRefusal(nodes: List<ChatNode>, node: ToolResultNode, hostWithoutSandbox: Boolean): Boolean {
    if (node.errorCode == SANDBOX_UNAVAILABLE) return true
    if (!hostWithoutSandbox || !node.isError || node.errorCode != null) return false
    return shellCallOf(nodes, node.callId) && isSandboxRefusalText(resultText(node))
}

/**
 * The harness's sentence for a command refused because no sandbox is usable, as the runtime's README
 * quotes it: `Error: sandbox mode "<mode>" is requested but no sandbox backend is usable on this
 * host; ...`. The mode is any of the sandboxed ones, so only the parts around it are compared.
 */
fun isSandboxRefusalText(text: String): Boolean {
    val body = text.trimStart().removePrefix("Error: ")
    return body.startsWith(SANDBOX_REFUSAL_PREFIX) && body.contains(SANDBOX_REFUSAL_MIDDLE)
}

internal const val SANDBOX_REFUSAL_PREFIX = "sandbox mode \""
internal const val SANDBOX_REFUSAL_MIDDLE = "is requested but no sandbox backend is usable on this host"

private fun shellCallOf(nodes: List<ChatNode>, callId: String): Boolean =
    nodes.any { it is ToolCallNode && it.callId == callId && it.name in ApprovalPrompt.COMMAND_TOOLS }

/** Whether this is the built-in runtime's denial of a subagent's gated call ([PermissionDenial.SubagentCannotAsk]). */
fun isSubagentCommandDenial(node: ToolResultNode): Boolean = permissionDenialOf(node) == PermissionDenial.SubagentCannotAsk

/**
 * Why the built-in runtime's "Ask before commands" gate did not run a call, in the reader's terms.
 * The runtime's README lists the codes ("Denial codes"); the rare ones share [Other].
 */
enum class PermissionDenial {
    /** `APPROVAL_NOT_AVAILABLE_TO_SUBAGENT`: a helper agent cannot ask anyone. */
    SubagentCannotAsk,

    /** `APPROVAL_REJECTED_BY_USER`. */
    Rejected,

    /** `APPROVAL_CANCELLED`: the question was withdrawn while the turn went on. */
    Cancelled,

    /** `APPROVAL_UNAVAILABLE`: nobody was there to answer. */
    NobodyToAsk,

    /** `APPROVAL_TEXT_TOO_LARGE`: the text was over the runtime's limit, so nothing was asked. */
    TextTooLarge,

    /** Any other code of the gate (unreadable arguments, the gate's own failure, and the rest). */
    Other,

    /**
     * `AUTO_REVIEW_DENIED` from `AutoReviewDeniedError`: the harness's Auto review refused the call,
     * so it never ran. The reviewer's reason, when recorded, is [ToolResultNode.errorReason].
     */
    AutoReviewRejected,
}

/** `error.code` the harness records when Auto review refuses a call. */
const val AUTO_REVIEW_DENIED = "AUTO_REVIEW_DENIED"

/** `error.name` beside [AUTO_REVIEW_DENIED]; the web client matches both, and so does this. */
const val AUTO_REVIEW_DENIED_ERROR_NAME = "AutoReviewDeniedError"

/** `error.name` on every denial of the runtime's gate (permissions version 2 and later). */
const val PERMISSION_DENIED_ERROR_NAME = "DshMobilePermissionDenied"

/** The runtime's limit for one approval's text, in bytes (`runtime.permissions.approvalTextLimitBytes`). */
const val APPROVAL_TEXT_LIMIT_BYTES = 262_144

/**
 * Why the runtime's gate refused this call, or null when it did not.
 *
 * Read from `error.code` (`runtime.permissions.version` 2 and later), never from the sentence the
 * model reads, which the README says may be reworded. A runtime with version 1 gave no code; for
 * it, and only for the subagent case the app explains, the gate's own fixed sentence is the
 * fallback ([SUBAGENT_DENIAL_MARKER]). A result with a code from somewhere else is not a denial.
 */
fun permissionDenialOf(node: ToolResultNode): PermissionDenial? {
    if (!node.isError) return null
    when (node.errorCode) {
        "APPROVAL_NOT_AVAILABLE_TO_SUBAGENT" -> return PermissionDenial.SubagentCannotAsk
        "APPROVAL_REJECTED_BY_USER" -> return PermissionDenial.Rejected
        "APPROVAL_CANCELLED" -> return PermissionDenial.Cancelled
        "APPROVAL_UNAVAILABLE" -> return PermissionDenial.NobodyToAsk
        "APPROVAL_TEXT_TOO_LARGE" -> return PermissionDenial.TextTooLarge
        // The harness's own reviewer, not this runtime's gate (web client: auto-review-denial.ts).
        AUTO_REVIEW_DENIED ->
            return if (node.errorName == AUTO_REVIEW_DENIED_ERROR_NAME) PermissionDenial.AutoReviewRejected else null
        null -> Unit
        else -> return if (node.errorName == PERMISSION_DENIED_ERROR_NAME) PermissionDenial.Other else null
    }
    // Version 1: no code at all. Our own runtime's fixed sentence, for the one case worth explaining.
    return if (resultText(node).contains(SUBAGENT_DENIAL_MARKER)) PermissionDenial.SubagentCannotAsk else null
}

internal const val SUBAGENT_DENIAL_MARKER = "this is a subagent, and the session that started it asks before commands"

private fun resultText(node: ToolResultNode): String = when (val content = node.content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString("\n") { block ->
        ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    else -> ""
}
