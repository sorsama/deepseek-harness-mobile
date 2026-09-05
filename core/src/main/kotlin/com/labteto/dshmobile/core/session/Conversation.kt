package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonElement

/**
 * Wire-shaped session event (envelope fields per the harness
 * `SessionEvent` contract; `data` stays raw for lenient, merge-extensible
 * handling — typed DTOs parse it on demand).
 */
data class SessionEventEnvelope(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement,
    val surfaceOp: String? = null,
)

/** One content block of an assistant/user message (chat renderer shape). */
data class ChatBlock(
    val kind: String, // text | reasoning | image | file | tool-call | tool-result | unknown
    val text: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val argumentsJson: String? = null,
    val isError: Boolean = false,
    val raw: JsonElement? = null,
)

/** A chat-renderable node, in seq order. */
sealed interface ChatNode {
    val seq: Long
}

data class TurnStartNode(override val seq: Long, val turn: Int) : ChatNode
data class TurnEndNode(override val seq: Long, val turn: Int, val reasonKind: String, val reasonDetail: JsonElement? = null) : ChatNode

data class UserMessageNode(
    override val seq: Long,
    val messageId: String?,
    val blocks: List<ChatBlock>,
    val sourceKind: String?,
) : ChatNode {
    val previewText: String
        get() = blocks.firstOrNull { it.kind == "text" }?.text?.take(120) ?: ""
}

data class AssistantMessageNode(
    override val seq: Long,
    val messageId: String?,
    val turn: Int?,
    val step: Int?,
    val blocks: List<ChatBlock>,
    val usage: JsonElement? = null,
    val interrupted: Boolean = false,
    /**
     * A provisional message assembled from the attempt being written, not a durable event.
     * Its `seq` is minted past the durable cursor and is not stable across folds.
     */
    val streaming: Boolean = false,
) : ChatNode {
    val plainText: String
        get() = blocks.filter { it.kind == "text" }.joinToString("") { it.text.orEmpty() }
}

data class ToolCallNode(
    override val seq: Long,
    val callId: String,
    val name: String,
    val arguments: String,
    val turn: Int,
    val step: Int,
) : ChatNode

data class ToolResultNode(
    override val seq: Long,
    val callId: String,
    val content: JsonElement?,
    val isError: Boolean,
    val turn: Int,
    val step: Int,
    val meta: JsonElement? = null,
) : ChatNode

data class TodoNode(override val seq: Long, val todos: JsonElement) : ChatNode
data class GoalNode(override val seq: Long, val data: JsonElement) : ChatNode
data class PlanModeNode(override val seq: Long, val active: Boolean) : ChatNode
data class CompactionNode(override val seq: Long, val kind: String, val data: JsonElement) : ChatNode
data class RetryNode(override val seq: Long, val kind: String, val data: JsonElement) : ChatNode
data class TurnErrorNode(override val seq: Long, val message: String, val code: String?) : ChatNode
data class CommandNode(override val seq: Long, val kind: String, val data: JsonElement) : ChatNode
data class TitleNode(override val seq: Long, val title: String) : ChatNode
data class WorkflowNode(override val seq: Long, val kind: String, val data: JsonElement) : ChatNode
data class SubagentNode(override val seq: Long, val data: JsonElement) : ChatNode
data class OtherNode(override val seq: Long, val type: String, val data: JsonElement) : ChatNode

/** One pending queue item (from the session/queue frame snapshot). */
data class QueueItem(
    val id: String,
    val placement: String, // queued | steering | context
    val previewText: String,
    val content: JsonElement,
)

/**
 * The folded, chat-renderable view of one session. Rebuilt incrementally
 * from [EventFold]; queue/projections are merged in by the session store.
 */
data class ConversationSnapshot(
    val sessionId: String,
    val nodes: List<ChatNode> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val projections: Map<String, JsonElement> = emptyMap(),
    val running: Boolean = false,
    val blank: Boolean = true,
    val hasMore: Boolean = false,
    val lastSeq: Long = -1,
    val gap: Boolean = false,
) {
    val turns: Int get() = nodes.count { it is TurnStartNode }
}
