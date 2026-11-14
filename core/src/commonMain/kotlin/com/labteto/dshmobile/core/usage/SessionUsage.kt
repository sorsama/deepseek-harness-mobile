package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.RetryNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.wire.dto.ModelSelection
import com.labteto.dshmobile.core.wire.dto.ModelSelectionProjection
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything the usage card can say about a session without asking a host anything: the model it
 * runs on, what it has spent, and whether the provider just refused it.
 */

/** Why a provider turned a request away. */
enum class LimitKind { QUOTA, RATE_LIMIT }

/** A refusal still in force, and the provider route it came from when the failure named one. */
data class LimitHit(val kind: LimitKind, val provider: String? = null)

/** Provider-neutral failure codes (`llm/src/error.ts`): the account ran out, or it is going too fast. */
fun limitKindOf(code: String?): LimitKind? = when (code) {
    "QUOTA", "ACCOUNT_QUOTA" -> LimitKind.QUOTA
    "RATE_LIMIT" -> LimitKind.RATE_LIMIT
    else -> null
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * The limit the latest outcome of this transcript ran into, or null when it did not hit one.
 *
 * It reads the end of the log: a turn that ended in an `error` carrying one of the limit codes, or a
 * model retry scheduled because of one. Anything the agent did after that (new prompt, a
 * message that came back, a tool call, the next turn) means the request got through, so the line goes
 * away by itself without a clock. `llm/retry-started` is skipped, so the line holds steady while the
 * harness backs off and tries again.
 */
fun limitHit(nodes: List<ChatNode>): LimitHit? {
    for (i in nodes.indices.reversed()) {
        when (val node = nodes[i]) {
            is TurnEndNode -> {
                if (node.reasonKind != "error") return null
                val kind = limitKindOf(node.failureCode) ?: return null
                val retry = nodes.subList(0, i).lastOrNull { it is RetryNode && it.kind == "llm/retry" && (it.data as? JsonObject)?.get("turn")?.text()?.toIntOrNull() == node.turn }
                return LimitHit(kind, ((retry as? RetryNode)?.data as? JsonObject)?.get("provider").text())
            }
            is RetryNode -> {
                if (node.kind != "llm/retry") continue
                val data = node.data as? JsonObject ?: return null
                val kind = limitKindOf(((data["failure"] as? JsonObject)?.get("code")).text()) ?: return null
                return LimitHit(kind, data["provider"].text())
            }
            is AssistantMessageNode -> if (!node.streaming) return null
            is TurnStartNode, is UserMessageNode, is ToolCallNode, is ToolResultNode -> return null
            else -> Unit
        }
    }
    return null
}

/** Whether a limit belongs on the card for [provider]; a failure that named no route is the session's. */
fun LimitHit?.appliesTo(provider: String?): LimitHit? = this?.takeIf { it.provider == null || provider == null || it.provider == provider }

/**
 * Token totals folded from the loaded `assistant/message` usage, for a host that composes no
 * `tokenUsage` projection. It only sees the loaded window of a long session, so the projection wins
 * whenever there is one; null when no loaded message reported usage at all.
 */
fun tokensFromNodes(nodes: List<ChatNode>): TokenUsageView? {
    var seen = false
    var uncached = 0L; var output = 0L; var read = 0L; var write = 0L
    for (node in nodes) {
        if (node !is AssistantMessageNode || node.streaming) continue
        val usage = node.usage as? JsonObject ?: continue
        seen = true
        uncached += usage["inputTokens"].text()?.toLongOrNull() ?: 0
        output += usage["outputTokens"].text()?.toLongOrNull() ?: 0
        read += usage["cacheReadTokens"].text()?.toLongOrNull() ?: 0
        write += usage["cacheWriteTokens"].text()?.toLongOrNull() ?: 0
    }
    return if (seen) TokenUsageView(uncached, output, read, write) else null
}

/**
 * The selection a quota lookup is about. `models.current` prefers a queued next choice while a turn
 * is still running on the last used one, so while running the last used selection is the truth.
 */
fun quotaSelectionOf(selection: ModelSelectionProjection?, running: Boolean): ModelSelection? =
    selection?.let { if (running) it.lastUsed else (it.next ?: it.lastUsed) }

/**
 * `Provider · Model` for the card: the host catalog's display names when it lists the route, the
 * raw route ids otherwise. Either half may be missing; both missing is null.
 */
fun routeLabel(models: SessionModelsValue?, selection: ModelSelection?): String? {
    selection ?: return null
    val group = models?.groups?.firstOrNull { it.id == selection.provider }
    val provider = group?.name?.takeIf { it.isNotBlank() } ?: selection.provider.takeIf { it.isNotBlank() }
    val model = group?.models?.firstOrNull { it.id == selection.model }?.name?.takeIf { it.isNotBlank() } ?: selection.model.takeIf { it.isNotBlank() }
    return listOfNotNull(provider, model).joinToString(" · ").ifEmpty { null }
}
