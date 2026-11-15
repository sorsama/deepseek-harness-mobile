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
 * The refusal is read from the code, never from the message text (which changes between releases):
 * a tool result since the last message the person sent that carries [SANDBOX_UNAVAILABLE].
 *
 * Harness 0.2.2-rc.1 loses that code on its usual path: the web profile runs a foreground `bash`
 * call as a job, and a job whose start failed is re-thrown as a plain error with the message only
 * (`tool-bash`, `waitOnJob`). So where the host is known to have no sandbox at all
 * ([hostWithoutSandbox]: the built-in harness, where Android allows none), a failed shell call in a
 * session on a sandboxed preset counts as that refusal: on such a host no command can run under
 * those presets, so the call cannot have failed any other way.
 *
 * @param currentPreset the session's preset now (the `permissions` projection's `currentValue`).
 * @param hostWithoutSandbox the harness runs where no kernel sandbox can work (the phone's own).
 */
fun sandboxRefusalShown(nodes: List<ChatNode>, currentPreset: String?, hostWithoutSandbox: Boolean = false): Boolean {
    if (currentPreset in PRESETS_WITHOUT_SANDBOX) return false
    for (i in nodes.indices.reversed()) {
        when (val node = nodes[i]) {
            is UserMessageNode -> return false
            is ToolResultNode -> {
                if (node.errorCode == SANDBOX_UNAVAILABLE) return true
                if (hostWithoutSandbox && node.isError && shellCallOf(nodes, node.callId)) return true
            }
            else -> Unit
        }
    }
    return false
}

private fun shellCallOf(nodes: List<ChatNode>, callId: String): Boolean =
    nodes.any { it is ToolCallNode && it.callId == callId && it.name in ApprovalPrompt.COMMAND_TOOLS }

/**
 * The built-in runtime's denial of a subagent's gated call in an "Ask before commands" session.
 *
 * A subagent cannot ask anyone, so the runtime's gate refuses its commands and says why in English
 * (`permission.mjs`, `denied(...)`), without a structured code. The marker is that fixed sentence of
 * our own runtime, not wording of the harness's; when the runtime adds a code for it, match that.
 */
fun isSubagentCommandDenial(node: ToolResultNode): Boolean {
    if (!node.isError) return false
    return resultText(node).contains(SUBAGENT_DENIAL_MARKER)
}

internal const val SUBAGENT_DENIAL_MARKER = "this is a subagent, and the session that started it asks before commands"

private fun resultText(node: ToolResultNode): String = when (val content = node.content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString("\n") { block ->
        ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    else -> ""
}
