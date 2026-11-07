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
    val surfaceIntent: JsonElement? = null,
    val sourceEventSeqs: List<Int>? = null,
    val ignorable: Boolean? = null,
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

    /**
     * True when this message is harness-injected context rather than something the reader wrote.
     *
     * The harness tags a genuine prompt with `source.kind` of `user`. **Every other kind is
     * injected** — `agent-instructions`, `plugin`, `skill-invocation`, `goal`, `team-message`,
     * `session-reference` and the rest — and rendering those as user bubbles puts a wall of
     * configuration text on the reader's side of the conversation, which reads as though they had
     * typed it.
     *
     * `user-rpc` is deliberately *not* listed: it is a key in the harness's `MessageSourceMap`,
     * not a `source.kind` value, and the variant it names carries `kind: 'user'` itself —
     *
     *     'user-rpc': { kind: 'user'; rpcId: SessionRequestId; clientTimeZone?: string }
     *
     * — so `kind == "user"` already covers both the browser and the RPC path.
     *
     * The test is deliberately a denylist of the *user* kinds rather than an allowlist of injected
     * ones: a kind this build has never heard of is far more likely to be new harness context than
     * a new way for the user to speak.
     */
    val isInjectedContext: Boolean
        get() = sourceKind != null && sourceKind != USER_SOURCE_KIND

    private companion object {
        /** The only `source.kind` value that means the reader typed this. */
        const val USER_SOURCE_KIND = "user"
    }
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
    /**
     * The result body: the tool's own content blocks, or a bare string. Session format v3 nested
     * these inside a `tool-result` wrapper; the fold unwraps it, so this is the body in either
     * format.
     */
    val content: JsonElement?,
    val isError: Boolean,
    val turn: Int,
    val step: Int,
    val meta: JsonElement? = null,
) : ChatNode

/**
 * A `developer/message` (harness 0.1.7): tools made available to, or withdrawn from, the agent
 * partway through a session. Model-visible context rather than conversation.
 */
data class DeveloperMessageNode(
    override val seq: Long,
    val addedTools: List<String>,
    val removedTools: List<String>,
    val sourceKind: String?,
    val data: JsonElement,
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
    val journal: List<SessionEventEnvelope> = emptyList(),
    val effectiveSurface: List<SessionEventEnvelope> = emptyList(),
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
