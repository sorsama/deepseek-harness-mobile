package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode

/**
 * Why a turn could not reach a model at all, as far as the harness says so in a code.
 *
 * Only structured failure codes count. The harness's message text is never matched: it is written
 * for the person who reads the log, it changes between releases, and a guess from it would send
 * people to the key screen for a fault that has nothing to do with keys.
 */
enum class ModelSetupProblem {
    /** No key is stored for the provider the turn used (`MISSING_CREDENTIAL`). */
    MissingKey,

    /** A key is stored, but it is not in a usable form (`INVALID_CREDENTIAL`). */
    InvalidKey,

    /** The DeepSeek account route needs a sign-in (`ACCOUNT_SIGN_IN_REQUIRED`). */
    SignIn,

    /** The session names a provider route no adapter serves (`NO_ADAPTER`). */
    NoProvider,
}

/**
 * The problem a provider-neutral failure code names, or null when it names none.
 *
 * Codes from `packages/llm/llm/src/error.ts` and the adapters (`llm-deepseek-api-key`,
 * `llm-deepseek-account`, `llm-pi-ai`) at harness 0.2.2-rc.1.
 */
fun modelSetupProblemOf(code: String?): ModelSetupProblem? = when (code) {
    "MISSING_CREDENTIAL" -> ModelSetupProblem.MissingKey
    "INVALID_CREDENTIAL" -> ModelSetupProblem.InvalidKey
    "ACCOUNT_SIGN_IN_REQUIRED" -> ModelSetupProblem.SignIn
    "NO_ADAPTER" -> ModelSetupProblem.NoProvider
    else -> null
}

/**
 * The setup problem the latest outcome of this transcript ran into, or null.
 *
 * Read from the end, like [limitHit]: a turn that ended in an `error` with one of the codes above.
 * Anything the agent did after it (a new prompt, a reply, a tool call, the next turn) means a model
 * answered since, so the notice goes away by itself once the key is in place and a turn gets through.
 */
fun modelSetupProblem(nodes: List<ChatNode>): ModelSetupProblem? {
    for (i in nodes.indices.reversed()) {
        when (val node = nodes[i]) {
            is TurnEndNode -> return if (node.reasonKind == "error") modelSetupProblemOf(node.failureCode) else null
            is AssistantMessageNode -> if (!node.streaming) return null
            is TurnStartNode, is UserMessageNode, is ToolCallNode, is ToolResultNode -> return null
            else -> Unit
        }
    }
    return null
}
