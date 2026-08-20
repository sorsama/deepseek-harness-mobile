package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Folds raw [SessionEventEnvelope]s into a [ConversationSnapshot].
 * Mirrors the harness web client's conversation assembly at the level a
 * mobile renderer needs:
 *
 *  - assistant streaming: `assistant/chunk` deltas merge per block index;
 *    `block-end` carries the assembled block; `assistant/message` is the
 *    commit point (and authoritative when no chunks were seen).
 *  - interruption: harness 0.1.0-rc.8 marks a cancelled turn's finalized prefix
 *    on the `assistant/message` itself, and that wins; a `turn/end` whose reason
 *    kind is `interrupted`/`aborted`/`error` marks the turn's assistant node
 *    otherwise, which is all an rc.7 host gives.
 *  - unknown event types become [OtherNode] (merge-extensible contract).
 *
 * The fold is pure: same events → same snapshot.
 */
class EventFold(private val sessionId: String) {

    fun fold(events: List<SessionEventEnvelope>): ConversationSnapshot {
        val state = FoldState(sessionId)
        events.forEach { state.apply(it) }
        return state.snapshot()
    }

    /**
     * Incremental folding with the snapshot as the accumulator.
     * Streaming callers keep the previous snapshot and re-render on change.
     */
    class Incremental(initial: ConversationSnapshot, private val sessionId: String) {
        private val state = FoldState(sessionId)
        private var folded: Long = initial.lastSeq.coerceAtLeast(-1)

        init {
            // Seed the fold from the snapshot's nodes (rebuild buffers from scratch).
            state.blank = initial.blank
            state.nodes.addAll(initial.nodes)
            state.running = initial.running
            state.hasMore = initial.hasMore
        }

        fun apply(event: SessionEventEnvelope): ConversationSnapshot? {
            if (event.seq <= folded) return null // duplicate or already-folded
            state.apply(event)
            folded = event.seq
            return state.snapshot()
        }

        fun snapshot(): ConversationSnapshot = state.snapshot()
    }
}

private class FoldState(private val sessionId: String) {
    val nodes = mutableListOf<ChatNode>()
    var blank = true
    var running = false
    var hasMore = false
    var lastSeq = -1L
    var gap = false

    /** Open assistant per (turn, step), built from chunk deltas. */
    private data class OpenAssistant(
        val turn: Int,
        val step: Int,
        val blocks: MutableList<MutableChatBlockAccumulator> = mutableListOf(),
    )

    private class MutableChatBlockAccumulator {
        var kind: String = "unknown"
        var text = StringBuilder()
        var toolCallId: String? = null
        var toolName: String? = null
        var arguments = StringBuilder()
        var raw: JsonElement? = null
    }

    private val openByKey = linkedMapOf<String, OpenAssistant>()

    fun snapshot(): ConversationSnapshot = ConversationSnapshot(
        sessionId = sessionId,
        nodes = nodes.toList(),
        running = running,
        blank = blank,
        hasMore = hasMore,
        lastSeq = lastSeq,
        gap = gap,
    )

    fun apply(event: SessionEventEnvelope) {
        if (event.seq > lastSeq + 1 && lastSeq >= 0 && !gap) gap = true
        lastSeq = maxOf(lastSeq, event.seq)
        val data = event.data
        when (event.type) {
            "turn/start" -> {
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                nodes.add(TurnStartNode(event.seq, turn))
                running = true
            }

            "turn/end" -> {
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                val reason = data.jsonObject["reason"]?.jsonObject
                val kind = reason?.get("kind")?.jsonPrimitive?.contentOrNull ?: "completed"
                nodes.add(TurnEndNode(event.seq, turn, kind, reason?.get("reason")))
                if (kind == "interrupted" || kind == "aborted" || kind == "error") {
                    markInterrupted(turn)
                }
                running = false
            }

            "user/message" -> {
                blank = false
                val messageId = data.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                val sourceKind = (data.jsonObject["source"] as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull
                nodes.add(UserMessageNode(event.seq, messageId, parseBlocks(data.jsonObject["content"]), sourceKind))
            }

            "assistant/chunk" -> {
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                val step = data.jsonObject["step"]?.jsonPrimitive?.intOrNull ?: 0
                val chunk = data.jsonObject["chunk"]?.jsonObject ?: return
                mergeChunk(turn, step, chunk)
            }

            "assistant/message" -> {
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                val step = data.jsonObject["step"]?.jsonPrimitive?.intOrNull ?: 0
                val message = data.jsonObject["message"]?.jsonObject
                val messageId = message?.get("id")?.jsonPrimitive?.contentOrNull
                val usage = data.jsonObject["usage"]
                // Harness 0.1.0-rc.8 finalises a cancelled turn's delivered prefix as an ordinary
                // `assistant/message` carrying this marker, which is authoritative. Before rc.8
                // the host appended nothing at all and the prefix was simply lost, so the absence
                // of the key is not "not interrupted" — see markInterrupted.
                val interrupted = data.jsonObject["interrupted"]?.jsonPrimitive?.booleanOrNull ?: false
                val key = key(turn, step)
                val open = openByKey[key]
                val blocks = when {
                    open != null && open.blocks.isNotEmpty() -> commit(open)
                    else -> parseBlocks(message?.get("content"))
                }
                openByKey.remove(key)
                if (nodes.none { it is AssistantMessageNode && it.seq == event.seq }) {
                    nodes.add(
                        AssistantMessageNode(event.seq, messageId, turn, step, blocks, usage, interrupted),
                    )
                }
            }

            "tool/call" -> {
                val callId = data.jsonObject["callId"]?.jsonPrimitive?.contentOrNull ?: ""
                val name = data.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val args = data.jsonObject["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                val step = data.jsonObject["step"]?.jsonPrimitive?.intOrNull ?: 0
                nodes.add(ToolCallNode(event.seq, callId, name, args, turn, step))
            }

            "tool/result" -> {
                val message = data.jsonObject["message"]?.jsonObject
                val content = message?.get("content")
                val toolCallId = (content as? JsonArray)?.firstOrNull()?.jsonObject?.get("toolCallId")
                    ?.jsonPrimitive?.contentOrNull
                val isError = (content as? JsonArray)?.firstOrNull()?.jsonObject?.get("isError")
                    ?.jsonPrimitive?.booleanOrNull ?: false
                val turn = data.jsonObject["turn"]?.jsonPrimitive?.intOrNull ?: 0
                val step = data.jsonObject["step"]?.jsonPrimitive?.intOrNull ?: 0
                nodes.add(ToolResultNode(event.seq, toolCallId.orEmpty(), content, isError, turn, step, data.jsonObject["meta"]))
            }

            "todo/write" -> nodes.add(TodoNode(event.seq, data.jsonObject["todos"] ?: data))

            "goal/change" -> nodes.add(GoalNode(event.seq, data))

            "plan/mode" -> nodes.add(PlanModeNode(event.seq, data.jsonObject["active"]?.jsonPrimitive?.booleanOrNull ?: false))

            "compaction/start", "compaction/end", "compaction/prune" -> nodes.add(CompactionNode(event.seq, event.type, data))
            "compaction/summary" -> nodes.add(CompactionNode(event.seq, event.type, data))

            "llm/retry", "llm/retry-started" -> nodes.add(RetryNode(event.seq, event.type, data))

            "command/run", "command/done" -> nodes.add(CommandNode(event.seq, event.type, data))

            "session/title" -> {
                val title = data.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: return
                nodes.add(TitleNode(event.seq, title))
            }

            "tool-workflow/run-start", "tool-workflow/run-end", "tool-workflow/agent-start", "tool-workflow/agent-end" ->
                nodes.add(WorkflowNode(event.seq, event.type, data))

            "subagent/descriptor" -> nodes.add(SubagentNode(event.seq, data))

            "request/header", "request/context", "session/end-seed", "approval/asked", "approval/decided",
            "approval/policy", "permission/preset", "sandbox/mode", "schedule/change", "feedback/record",
            "hook/invoked", "hook/result", "agent-preset/selected", "agent/inbox/spliced",
            "tool/code-dispatch", "tool/code-dispatch-start", "web/deepseek-search-llm-request",
            "session/title-llm-request",
            -> {
                // Log-only metadata: not chat-renderable; deliberately skipped.
            }

            else -> nodes.add(OtherNode(event.seq, event.type, data))
        }
    }

    private fun key(turn: Int, step: Int) = "$turn.$step"

    private fun mergeChunk(turn: Int, step: Int, chunk: JsonObject) {
        val type = chunk["type"]?.jsonPrimitive?.contentOrNull ?: return
        // Usage, finish, and future metadata chunks do not address a content block. Creating an
        // accumulator for them would leave a phantom `unknown` block that can hide the complete
        // content carried by a following non-streamed assistant/message event.
        if (type !in BLOCK_CHUNK_TYPES) return
        val index = chunk["index"]?.jsonPrimitive?.intOrNull ?: 0
        val open = openByKey.getOrPut(key(turn, step)) { OpenAssistant(turn, step) }
        while (open.blocks.size <= index) open.blocks.add(MutableChatBlockAccumulator())
        val acc = open.blocks[index]
        when (type) {
            "block-start" -> acc.kind = chunk["blockType"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            "text-delta" -> acc.text.append(chunk["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "reasoning-delta" -> {
                acc.kind = "reasoning"
                acc.text.append(chunk["text"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            "tool-call-delta" -> {
                acc.kind = "tool-call"
                acc.toolCallId = chunk["id"]?.jsonPrimitive?.contentOrNull
                acc.toolName = chunk["name"]?.jsonPrimitive?.contentOrNull
                acc.arguments.append(chunk["argumentsDelta"]?.jsonPrimitive?.contentOrNull ?: "")
            }
            "block-end" -> acc.raw = chunk["block"]
            else -> Unit
        }
    }

    private companion object {
        val BLOCK_CHUNK_TYPES = setOf(
            "block-start",
            "text-delta",
            "reasoning-delta",
            "tool-call-delta",
            "block-end",
        )
    }

    private fun commit(open: OpenAssistant): List<ChatBlock> = open.blocks.map { acc ->
        val assembled = acc.raw?.jsonObject
        when {
            acc.kind == "text" || acc.kind == "reasoning" -> ChatBlock(
                kind = acc.kind,
                text = assembled?.get("text")?.jsonPrimitive?.contentOrNull ?: acc.text.toString(),
                raw = acc.raw,
            )
            acc.kind == "tool-call" -> ChatBlock(
                kind = "tool-call",
                toolCallId = assembled?.get("id")?.jsonPrimitive?.contentOrNull ?: acc.toolCallId,
                toolName = assembled?.get("name")?.jsonPrimitive?.contentOrNull ?: acc.toolName,
                argumentsJson = assembled?.get("arguments")?.jsonPrimitive?.contentOrNull ?: acc.arguments.toString(),
                raw = acc.raw,
            )
            else -> ChatBlock(
                kind = assembled?.get("type")?.jsonPrimitive?.contentOrNull ?: acc.kind,
                text = assembled?.get("text")?.jsonPrimitive?.contentOrNull,
                toolCallId = assembled?.get("id")?.jsonPrimitive?.contentOrNull ?: acc.toolCallId,
                toolName = assembled?.get("name")?.jsonPrimitive?.contentOrNull ?: acc.toolName,
                argumentsJson = assembled?.get("arguments")?.jsonPrimitive?.contentOrNull ?: acc.arguments.toString(),
                raw = acc.raw,
            )
        }
    }

    private fun parseBlocks(content: JsonElement?): List<ChatBlock> {
        // A build that sends `content` as a bare string rather than a block array would otherwise
        // fold to no blocks at all, and the message would vanish from the transcript instead of
        // rendering. Leniency here is the same contract the rest of the fold keeps.
        if (content is JsonPrimitive && content.isString) {
            val text = content.contentOrNull.orEmpty()
            return if (text.isBlank()) emptyList() else listOf(ChatBlock("text", text = text, raw = content))
        }
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            when (type) {
                "text" -> ChatBlock("text", text = obj["text"]?.jsonPrimitive?.contentOrNull, raw = element)
                "reasoning" -> ChatBlock("reasoning", text = obj["text"]?.jsonPrimitive?.contentOrNull, raw = element)
                "tool-call" -> ChatBlock(
                    kind = "tool-call",
                    toolCallId = obj["id"]?.jsonPrimitive?.contentOrNull,
                    toolName = obj["name"]?.jsonPrimitive?.contentOrNull,
                    argumentsJson = obj["arguments"]?.jsonPrimitive?.contentOrNull,
                    raw = element,
                )
                "tool-result" -> ChatBlock(
                    kind = "tool-result",
                    toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull,
                    isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
                    raw = element,
                )
                "image" -> ChatBlock("image", raw = element)
                else -> ChatBlock("unknown", raw = element)
            }
        }
    }

    /**
     * Mark the turn's assistant answer as cut short, inferred from how the turn ended.
     *
     * This is the pre-rc.8 source of the fact and stays the only one an rc.7 host offers. When
     * rc.8 marked the message itself, that marker is authoritative and this adds nothing, so it
     * stands aside rather than re-marking. It does not — and from a pure fold cannot — fix the
     * case where a turn is cancelled between steps: no message exists for the cancelled step, so
     * the search lands on the previous, complete one. That was equally true before rc.8.
     */
    private fun markInterrupted(turn: Int) {
        if (nodes.any { it is AssistantMessageNode && it.turn == turn && it.interrupted }) return
        val index = nodes.indexOfLast { it is AssistantMessageNode && it.turn == turn }
        if (index >= 0) {
            val node = nodes[index] as AssistantMessageNode
            nodes[index] = node.copy(interrupted = true)
        }
    }
}
