@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// ============================================================================================
// Shared LLM vocabulary (source: packages/llm/llm/src/types.ts and message.ts)
// ============================================================================================

/** Provider-neutral content block; merge-extensible by `type`. Unknown blocks are preserved. */
@Serializable(with = ContentBlockSerializer::class)
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        @SerialName("text") val text: String,
    ) : ContentBlock()

    /** Reasoning / thinking content, distinct from visible text. */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        @SerialName("text") val text: String,
    ) : ContentBlock()

    /** A durable raster image reference, valid in user or assistant content. */
    @Serializable
    @SerialName("image")
    data class Image(
        @SerialName("attachment") val attachment: ImageAttachmentRef,
    ) : ContentBlock()

    /** A tool invocation requested by the model. */
    @Serializable
    @SerialName("tool-call")
    data class ToolCall(
        /** Provider-issued call id; correlates with the matching tool result. */
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        /** Raw JSON string as produced by the model. */
        @SerialName("arguments") val arguments: String,
    ) : ContentBlock()

    /** The result of a tool invocation, sent back to the model. */
    @Serializable
    @SerialName("tool-result")
    data class ToolResult(
        @SerialName("toolCallId") val toolCallId: String,
        @SerialName("content") val content: List<ContentBlock> = emptyList(),
        @SerialName("isError") val isError: Boolean? = null,
    ) : ContentBlock()
}

/** A content block of an unknown `type`, preserved verbatim. */
data class UnknownContentBlock(
    val type: String,
    val raw: JsonElement,
) : ContentBlock()

/** Custom `type`-dispatching serializer for [ContentBlock]; unknown blocks pass through raw. */
object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentBlock") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        val json: JsonElement = when (value) {
            is ContentBlock.Text -> encodeWithType(ContentBlock.Text.serializer(), value)
            is ContentBlock.Reasoning -> encodeWithType(ContentBlock.Reasoning.serializer(), value)
            is ContentBlock.Image -> encodeWithType(ContentBlock.Image.serializer(), value)
            is ContentBlock.ToolCall -> encodeWithType(ContentBlock.ToolCall.serializer(), value)
            is ContentBlock.ToolResult -> encodeWithType(ContentBlock.ToolResult.serializer(), value)
            is UnknownContentBlock -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): ContentBlock {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "text" -> decodeFromJsonElement(ContentBlock.Text.serializer(), json)
            "reasoning" -> decodeFromJsonElement(ContentBlock.Reasoning.serializer(), json)
            "image" -> decodeFromJsonElement(ContentBlock.Image.serializer(), json)
            "tool-call" -> decodeFromJsonElement(ContentBlock.ToolCall.serializer(), json)
            "tool-result" -> decodeFromJsonElement(ContentBlock.ToolResult.serializer(), json)
            else -> UnknownContentBlock(type, json)
        }
    }
}

/** Raw streaming protocol emitted by adapters; merge-extensible by `type`. */
@Serializable(with = StreamChunkSerializer::class)
sealed class StreamChunk {
    @Serializable
    @SerialName("block-start")
    data class BlockStart(
        @SerialName("index") val index: Int,
        @SerialName("blockType") val blockType: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("text-delta")
    data class TextDelta(
        @SerialName("index") val index: Int,
        @SerialName("text") val text: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning-delta")
    data class ReasoningDelta(
        @SerialName("index") val index: Int,
        @SerialName("text") val text: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool-call-delta")
    data class ToolCallDelta(
        @SerialName("index") val index: Int,
        @SerialName("id") val id: String,
        @SerialName("name") val name: String? = null,
        @SerialName("argumentsDelta") val argumentsDelta: String = "",
    ) : StreamChunk()

    @Serializable
    @SerialName("block-end")
    data class BlockEnd(
        @SerialName("index") val index: Int,
        @SerialName("block") val block: ContentBlock,
    ) : StreamChunk()

    @Serializable
    @SerialName("usage")
    data class Usage(
        @SerialName("usage") val usage: TokenUsage,
    ) : StreamChunk()

    @Serializable
    @SerialName("finish")
    data class Finish(
        @SerialName("reason") val reason: FinishReason,
        /** Adapter-private lossless-JSON state for replaying a successful response. */
        @SerialName("replayState") val replayState: JsonElement? = null,
    ) : StreamChunk()
}

/** A stream chunk of an unknown `type`, preserved verbatim. */
data class UnknownStreamChunk(
    val type: String,
    val raw: JsonElement,
) : StreamChunk()

/** Custom `type`-dispatching serializer for [StreamChunk]; unknown chunks pass through raw. */
object StreamChunkSerializer : KSerializer<StreamChunk> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("StreamChunk") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: StreamChunk) {
        val json: JsonElement = when (value) {
            is StreamChunk.BlockStart -> encodeWithType(StreamChunk.BlockStart.serializer(), value)
            is StreamChunk.TextDelta -> encodeWithType(StreamChunk.TextDelta.serializer(), value)
            is StreamChunk.ReasoningDelta -> encodeWithType(StreamChunk.ReasoningDelta.serializer(), value)
            is StreamChunk.ToolCallDelta -> encodeWithType(StreamChunk.ToolCallDelta.serializer(), value)
            is StreamChunk.BlockEnd -> encodeWithType(StreamChunk.BlockEnd.serializer(), value)
            is StreamChunk.Usage -> encodeWithType(StreamChunk.Usage.serializer(), value)
            is StreamChunk.Finish -> encodeWithType(StreamChunk.Finish.serializer(), value)
            is UnknownStreamChunk -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): StreamChunk {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "block-start" -> decodeFromJsonElement(StreamChunk.BlockStart.serializer(), json)
            "text-delta" -> decodeFromJsonElement(StreamChunk.TextDelta.serializer(), json)
            "reasoning-delta" -> decodeFromJsonElement(StreamChunk.ReasoningDelta.serializer(), json)
            "tool-call-delta" -> decodeFromJsonElement(StreamChunk.ToolCallDelta.serializer(), json)
            "block-end" -> decodeFromJsonElement(StreamChunk.BlockEnd.serializer(), json)
            "usage" -> decodeFromJsonElement(StreamChunk.Usage.serializer(), json)
            "finish" -> decodeFromJsonElement(StreamChunk.Finish.serializer(), json)
            else -> UnknownStreamChunk(type, json)
        }
    }
}

/**
 * The concrete serializers above do not emit the sealed class discriminator on their own.
 * Re-add it whenever a typed wire value is converted back to JSON; the transcript fold consumes
 * that JSON and otherwise sees every known block/chunk as an empty `unknown` value.
 */
private fun <T> encodeWithType(serializer: SerializationStrategy<T>, value: T): JsonElement =
    encodeToJsonElement(serializer, value).withType(serializer.descriptor.serialName)

private fun JsonElement.withType(type: String): JsonElement = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        putAll(this@withType.jsonObject)
        put("type", JsonPrimitive(type))
    },
)

/** Serializable provider or transport failure facts; policy decides whether they are retryable. */
@Serializable
data class LlmFailure(
    /** Human-readable provider or transport failure. */
    @SerialName("message") val message: String,
    /** Stable provider-neutral machine-routing code. */
    @SerialName("code") val code: String,
    /** HTTP status returned by the provider, when available. */
    @SerialName("status") val status: Int? = null,
    /** Provider-requested delay in milliseconds, when valid and available. */
    @SerialName("providerRetryAfterMs") val providerRetryAfterMs: Long? = null,
    /** Opaque provider-issued request identifier for diagnostics. */
    @SerialName("requestId") val requestId: String? = null,
)

/** Token accounting for one model call (cache fields are optional; counts are disjoint). */
@Serializable
data class TokenUsage(
    @SerialName("inputTokens") val inputTokens: Int,
    @SerialName("outputTokens") val outputTokens: Int,
    @SerialName("cacheReadTokens") val cacheReadTokens: Int? = null,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Int? = null,
    @SerialName("reasoningTokens") val reasoningTokens: Int? = null,
)

/** Why a model response stopped; merge-extensible by `kind`. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class FinishReason {
    @Serializable
    @SerialName("stop")
    class Stop : FinishReason()

    @Serializable
    @SerialName("tool-calls")
    class ToolCalls : FinishReason()

    @Serializable
    @SerialName("max-tokens")
    class MaxTokens : FinishReason()

    @Serializable
    @SerialName("aborted")
    data class Aborted(
        @SerialName("failure") val failure: LlmFailure,
    ) : FinishReason()

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("failure") val failure: LlmFailure,
    ) : FinishReason()
}

/** Where a message (or injected content) came from; merge-extensible by `kind`. */
@Serializable
data class MessageSource(
    /** 'user' | 'plugin' | 'model' | 'tool' (or a plugin-merged kind). */
    @SerialName("kind") val kind: String,
    /** Present when `kind` is 'plugin'. */
    @SerialName("plugin") val plugin: String? = null,
    /** Present when `kind` is 'model' (the provider route that produced the message). */
    @SerialName("provider") val provider: String? = null,
    /** Present when `kind` is 'model' (the provider model id). */
    @SerialName("model") val model: String? = null,
    /** Adapter-private replay state for model-produced messages. */
    @SerialName("replayState") val replayState: JsonElement? = null,
    /** Present when `kind` is 'tool' (the correlated call id). */
    @SerialName("callId") val callId: String? = null,
    /** Present on the user-rpc provenance the host folds into a prompt's user/message. */
    @SerialName("rpcId") val rpcId: String? = null,
    /** Host-validated browser zone recorded on the exact user message. */
    @SerialName("clientTimeZone") val clientTimeZone: String? = null,
    /** Producer-declared context form ('instructions' | 'catalog' | 'snapshot' | 'notice' | ...). */
    @SerialName("form") val form: String? = null,
    /** Named contributions of a 'snapshot'-form context. */
    @SerialName("sections") val sections: List<ContextSnapshotSection>? = null,
    /** One-line account of a 'notice'-form context. */
    @SerialName("summary") val summary: String? = null,
)

/** One named contribution to a `snapshot`-form context, in assembly order. */
@Serializable
data class ContextSnapshotSection(
    @SerialName("name") val name: String,
    @SerialName("text") val text: String,
)

/** One immutable message representation shared by delivery, durable history, and model requests. */
@Serializable
data class MessageData(
    /** Stable identity preserved across every representation boundary. */
    @SerialName("id") val id: String,
    /** Provider-neutral conversation role ('system' | 'user' | 'assistant'). */
    @SerialName("role") val role: String,
    /** Exact model-facing blocks. */
    @SerialName("content") val content: List<ContentBlock> = emptyList(),
    /** Required source fields supplied by the producer. */
    @SerialName("source") val source: MessageSource,
)

/** One entry in an agent's todo list — the unit of the `todo/write` whole-list snapshot. */
@Serializable
data class TodoItem(
    /** What this task is — a short imperative line shown in the UI. */
    @SerialName("content") val content: String,
    /** Lifecycle state ('pending' | 'in_progress' | 'completed'). */
    @SerialName("status") val status: String,
)

/** The conversation's call configuration (provider, model, reasoning effort, sampling scalars). */
@Serializable
data class LlmCallConfig(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("maxTokens") val maxTokens: Int? = null,
    @SerialName("stop") val stop: List<String>? = null,
)

/** Effective config fields supplied by exact-model adapter resolution rather than the caller. */
@Serializable
data class LlmCallConfigAdapterDefaults(
    @SerialName("reasoningEffort") val reasoningEffort: Boolean? = null,
    @SerialName("maxTokens") val maxTokens: Boolean? = null,
)

/** JSON-schema description of a tool, as sent to the model. */
@Serializable
data class ToolSchema(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    /** JSON Schema object for the arguments. */
    @SerialName("parameters") val parameters: JsonElement,
)

/** Logged request state outside derived history (the `request/header` payload). */
@Serializable
data class EpochHeader(
    @SerialName("config") val config: LlmCallConfig,
    @SerialName("adapterDefaults") val adapterDefaults: LlmCallConfigAdapterDefaults? = null,
    /** Rendered system prompt text; absent for a system-less request. */
    @SerialName("system") val system: String? = null,
    /** Assembled tool schemas; absent for a tool-less request. */
    @SerialName("tools") val tools: List<ToolSchema>? = null,
)

/** Why a `request/header` snapshot was appended ('initial' | 'resume' | 'change'). */
@Serializable
enum class RequestHeaderReason {
    @SerialName("initial")
    INITIAL,

    @SerialName("resume")
    RESUME,

    @SerialName("change")
    CHANGE,
}

/** A 1-based inclusive seq span (used by compaction records). */
@Serializable
data class SeqRange(
    @SerialName("start") val start: Int,
    @SerialName("end") val end: Int,
)

// ============================================================================================
// Session event payloads (sources: core/session types.ts, goal, plan-mode, tool-workflow,
// schedule, compaction, subagent/descriptor, command-feedback, user-approval)
// ============================================================================================

/** `turn/start` payload. */
@Serializable
data class TurnStartData(
    @SerialName("turn") val turn: Int,
)

/** Why a turn ended; merge-extensible by `kind`. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class TurnEndReason {
    @Serializable
    @SerialName("completed")
    class Completed : TurnEndReason()

    @Serializable
    @SerialName("aborted")
    data class Aborted(
        @SerialName("reason") val reason: AgentCancelCause,
    ) : TurnEndReason()

    @Serializable
    @SerialName("blocked")
    class Blocked : TurnEndReason()

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("error") val error: LlmFailure,
    ) : TurnEndReason()

    @Serializable
    @SerialName("max-tokens")
    class MaxTokens : TurnEndReason()

    @Serializable
    @SerialName("interrupted")
    class Interrupted : TurnEndReason()
}

/** Why an active agent driver was cancelled (or the durable 'legacy' record). */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class AgentCancelCause {
    @Serializable
    @SerialName("user")
    class User : AgentCancelCause()

    @Serializable
    @SerialName("parent")
    class Parent : AgentCancelCause()

    @Serializable
    @SerialName("hook")
    data class Hook(
        @SerialName("reason") val reason: String,
    ) : AgentCancelCause()

    @Serializable
    @SerialName("disposed")
    class Disposed : AgentCancelCause()

    /** Durable record whose coarse original carried no cause. */
    @Serializable
    @SerialName("legacy")
    class Legacy : AgentCancelCause()
}

/** `turn/end` payload. */
@Serializable
data class TurnEndData(
    @SerialName("turn") val turn: Int,
    @SerialName("reason") val reason: TurnEndReason,
)

/** `step/start` payload. */
@Serializable
data class StepStartData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
)

/** `step/end` payload. */
@Serializable
data class StepEndData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
)

/** `assistant/chunk` payload — raw stream chunk with token-level replay fidelity. */
@Serializable
data class AssistantChunkData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("chunk") val chunk: StreamChunk,
)

/** `assistant/message` payload — assembled assistant message plus optional token accounting. */
@Serializable
data class AssistantMessageData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("message") val message: MessageData,
    /** Present when the adapter reported token accounting. */
    @SerialName("usage") val usage: TokenUsage? = null,
)

/** `tool/call` payload — the model requested one tool invocation. */
@Serializable
data class ToolCallData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("callId") val callId: String,
    @SerialName("name") val name: String,
    /** Raw JSON string exactly as the model produced it (unparsed). */
    @SerialName("arguments") val arguments: String,
)

/** Internal failure identity of a completed tool call. */
@Serializable
data class ToolErrorRef(
    @SerialName("name") val name: String,
    @SerialName("code") val code: String,
)

/** `tool/result` payload. */
@Serializable
data class ToolResultData(
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("message") val message: MessageData,
    @SerialName("error") val error: ToolErrorRef? = null,
    /** Tool-private presentation payload; opaque to the core. */
    @SerialName("meta") val meta: JsonElement? = null,
)

/** `todo/write` payload — whole-list snapshot; latest write wins on replay. */
@Serializable
data class TodoWriteData(
    @SerialName("todos") val todos: List<TodoItem> = emptyList(),
)

/** `request/header` payload — full header for the next request. */
@Serializable
data class RequestHeaderData(
    @SerialName("header") val header: EpochHeader,
    @SerialName("reason") val reason: RequestHeaderReason,
)

/** `request/context` payload — route metadata for the next request. */
@Serializable
data class RequestContextData(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    /** Maximum combined request and response context in tokens, when advertised. */
    @SerialName("contextWindow") val contextWindow: Int? = null,
)

/** `session/end-seed` payload — an empty marker; position and `time` carry the meaning. */
@Serializable
class SessionEndSeedData

/** `goal/change` payload — complete post-mutation state or clear tombstone. */
@Serializable
data class GoalChangeData(
    @SerialName("kind") val kind: String = "goal/change",
    @SerialName("version") val version: Int = 1,
    /** 'create' | 'edit' | 'pause' | 'resume' | 'complete' | 'block' | 'clear'. */
    @SerialName("operation") val operation: String,
    /** Present for non-clear operations: the full durable goal snapshot. */
    @SerialName("goal") val goal: GoalSnapshot? = null,
    @SerialName("roundsStarted") val roundsStarted: Int? = null,
    @SerialName("createdAt") val createdAt: Long? = null,
    @SerialName("updatedAt") val updatedAt: Long? = null,
    /** Present for the clear tombstone. */
    @SerialName("cleared") val cleared: GoalRef? = null,
    @SerialName("clearedAt") val clearedAt: Long? = null,
)

/** `plan/mode` payload — whether plan mode is in force from this point on. */
@Serializable
data class PlanModeData(
    @SerialName("active") val active: Boolean,
)

/** `tool-workflow/run-start` payload — opens one durable top-level workflow record. */
@Serializable
data class ToolWorkflowRunStartData(
    @SerialName("runId") val runId: String,
    @SerialName("name") val name: String,
)

/** `tool-workflow/agent-start` payload — records one published workflow member. */
@Serializable
data class ToolWorkflowAgentStartData(
    @SerialName("runId") val runId: String,
    @SerialName("seq") val seq: Int,
    @SerialName("label") val label: String,
    @SerialName("phase") val phase: String? = null,
    @SerialName("childId") val childId: String,
)

/** `tool-workflow/agent-end` payload — records one member settlement. */
@Serializable
data class ToolWorkflowAgentEndData(
    @SerialName("runId") val runId: String,
    @SerialName("seq") val seq: Int,
    /** 'completed' | 'failed' | 'cancelled'. */
    @SerialName("outcome") val outcome: String,
)

/** `tool-workflow/run-end` payload — closes one workflow record after cleanup. */
@Serializable
data class ToolWorkflowRunEndData(
    @SerialName("runId") val runId: String,
    /** 'completed' | 'cancelled' | 'error'. */
    @SerialName("stopReason") val stopReason: String,
)

/** `schedule/change` payload — strict version-1 durable Schedule mutation union. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("operation")
sealed class ScheduleChangeData {
    @Serializable
    @SerialName("create")
    data class Create(
        @SerialName("version") val version: Int = 1,
        @SerialName("schedule") val schedule: ScheduleRecord,
    ) : ScheduleChangeData()

    @Serializable
    @SerialName("delete")
    data class Delete(
        @SerialName("version") val version: Int = 1,
        @SerialName("id") val id: String,
    ) : ScheduleChangeData()

    @Serializable
    @SerialName("dispatch")
    data class Dispatch(
        @SerialName("version") val version: Int = 1,
        @SerialName("id") val id: String,
        /** Wall-clock decision time for fixed-rate decisions. */
        @SerialName("acceptedAt") val acceptedAt: String? = null,
    ) : ScheduleChangeData()
}

/** The v1 durable reminder record union. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class ScheduleRecord {
    /** A delayed one-shot reminder. */
    @Serializable
    @SerialName("after")
    data class After(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("afterSeconds") val afterSeconds: Int,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()

    /** An absolute one-shot reminder. */
    @Serializable
    @SerialName("at")
    data class At(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()

    /** A fixed-rate recurring reminder. */
    @Serializable
    @SerialName("every")
    data class Every(
        @SerialName("id") val id: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("everySeconds") val everySeconds: Int,
        @SerialName("scheduledAt") val scheduledAt: String,
    ) : ScheduleRecord()
}

/** `compaction/start` payload — marks the start of a compaction (holds the lock). */
@Serializable
data class CompactionStartData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    /** Open-turn number, or null for a standalone manual transaction between turns. */
    @SerialName("turn") val turn: Int? = null,
)

/** `compaction/summary` payload — completed summary, its inputs, and its model call facts. */
@Serializable
data class CompactionSummaryData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    @SerialName("summary") val summary: List<ContentBlock> = emptyList(),
    @SerialName("shadowedRange") val shadowedRange: SeqRange,
    @SerialName("shadowedSeqs") val shadowedSeqs: List<Int> = emptyList(),
    @SerialName("shadowedTokenCount") val shadowedTokenCount: Int,
    /** The provider route that wrote the summary. */
    @SerialName("provider") val provider: String,
    /** The model that wrote the summary. */
    @SerialName("model") val model: String,
    @SerialName("maxTokens") val maxTokens: Int? = null,
    /** Provider-reported token usage for the summarization request, when emitted. */
    @SerialName("usage") val usage: TokenUsage? = null,
    /** Complete provider output before the backend's safe summary projection. */
    @SerialName("rawOutput") val rawOutput: List<ContentBlock>? = null,
    /** True when the summary identifies exactly one call through the context's LLM seam. */
    @SerialName("llmStreamCall") val llmStreamCall: Boolean? = null,
)

/** `compaction/end` payload — releases the lock; `error` records an unsuccessful attempt. */
@Serializable
data class CompactionEndData(
    @SerialName("compactionId") val compactionId: String,
    @SerialName("sourceCommandId") val sourceCommandId: String? = null,
    @SerialName("turn") val turn: Int? = null,
    @SerialName("error") val error: String? = null,
)

/** `compaction/prune` payload — shadow price of one model-free prune replacement. */
@Serializable
data class CompactionPruneData(
    @SerialName("shadowedRange") val shadowedRange: SeqRange,
    @SerialName("shadowedSeqs") val shadowedSeqs: List<Int> = emptyList(),
    @SerialName("shadowedTokenCount") val shadowedTokenCount: Int,
)

/** Tool scoping reapplied on a continuable child resume. */
@Serializable
data class ToolRestriction(
    @SerialName("allow") val allow: List<String>? = null,
    @SerialName("deny") val deny: List<String>? = null,
)

/** `subagent/descriptor` payload — durable child identity and lifecycle mode. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("mode")
sealed class SubagentDescriptorData {
    /** A session-backed subagent that cannot be cold-resumed after its run. */
    @Serializable
    @SerialName("one-shot")
    data class OneShot(
        /** Descriptor format version. */
        @SerialName("version") val version: Int,
        /** The `ctx.subagents` provider name that established the child. */
        @SerialName("provider") val provider: String,
        @SerialName("label") val label: String? = null,
    ) : SubagentDescriptorData()

    /** A session-backed subagent whose declared composition supports cold resume. */
    @Serializable
    @SerialName("continuable")
    data class Continuable(
        @SerialName("version") val version: Int,
        @SerialName("provider") val provider: String,
        @SerialName("label") val label: String,
        @SerialName("agentProvider") val agentProvider: String? = null,
        @SerialName("agentModel") val agentModel: String? = null,
        @SerialName("persona") val persona: String? = null,
        @SerialName("toolFilter") val toolFilter: ToolRestriction? = null,
    ) : SubagentDescriptorData()
}

/** `feedback/record` payload — one recorded human remark about this session. */
@Serializable
data class FeedbackRecordData(
    @SerialName("text") val text: String,
)

/** `approval/asked` payload — an approval question was put to the answerer chain. */
@Serializable
data class ApprovalAskedData(
    @SerialName("id") val id: String,
    @SerialName("toolName") val toolName: String,
    @SerialName("callId") val callId: String? = null,
    @SerialName("reason") val reason: String? = null,
)

/** `approval/decided` payload — the outcome of a prior `approval/asked`. */
@Serializable
data class ApprovalDecidedData(
    @SerialName("id") val id: String,
    /** 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'. */
    @SerialName("outcome") val outcome: String,
)

/** `approval/policy` payload — the session's approval policy was switched. */
@Serializable
data class ApprovalPolicyData(
    /** 'ask' | 'never'. */
    @SerialName("policy") val policy: String,
    /** Marks an override seeded into a child at delegation. */
    @SerialName("source") val source: String? = null,
)

// ============================================================================================
// SessionEvent union
// ============================================================================================

/**
 * One immutable entry in the session log. The envelope fields (`type`, `seq`, `time`) are
 * strict; `data` is typed per event kind. Unknown event types fall back to
 * [SessionEvent.Unknown] carrying the raw payload.
 */
@Serializable(with = SessionEventSerializer::class)
sealed class SessionEvent {
    /** The wire event type. */
    abstract val type: String

    /** Monotonic sequence number within the session. */
    abstract val seq: Int

    /** Unix epoch milliseconds. */
    abstract val time: Long

    /**
     * Seq numbers of earlier events this event cites as sources; present only on
     * surface events (`user/message`, `assistant/message`, `tool/result`).
     */
    abstract val sourceEventSeqs: List<Int>?

    /** How this event entered the ordered surface ('append' or a replace op); surface events only. */
    abstract val surfaceOp: JsonElement?

    /** Marks an event a reader may safely skip when it does not recognize `type`. */
    abstract val ignorable: Boolean?

    @Serializable
    @SerialName("turn/start")
    data class TurnStart(
        @SerialName("type") override val type: String = "turn/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TurnStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("turn/end")
    data class TurnEnd(
        @SerialName("type") override val type: String = "turn/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TurnEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("step/start")
    data class StepStart(
        @SerialName("type") override val type: String = "step/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: StepStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("step/end")
    data class StepEnd(
        @SerialName("type") override val type: String = "step/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: StepEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("user/message")
    data class UserMessage(
        @SerialName("type") override val type: String = "user/message",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: MessageData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("assistant/chunk")
    data class AssistantChunk(
        @SerialName("type") override val type: String = "assistant/chunk",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: AssistantChunkData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("assistant/message")
    data class AssistantMessage(
        @SerialName("type") override val type: String = "assistant/message",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: AssistantMessageData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool/call")
    data class ToolCall(
        @SerialName("type") override val type: String = "tool/call",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolCallData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool/result")
    data class ToolResult(
        @SerialName("type") override val type: String = "tool/result",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolResultData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("todo/write")
    data class TodoWrite(
        @SerialName("type") override val type: String = "todo/write",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: TodoWriteData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("request/header")
    data class RequestHeader(
        @SerialName("type") override val type: String = "request/header",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: RequestHeaderData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("request/context")
    data class RequestContext(
        @SerialName("type") override val type: String = "request/context",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: RequestContextData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("session/end-seed")
    data class SessionEndSeed(
        @SerialName("type") override val type: String = "session/end-seed",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: SessionEndSeedData = SessionEndSeedData(),
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("goal/change")
    data class GoalChange(
        @SerialName("type") override val type: String = "goal/change",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: GoalChangeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("plan/mode")
    data class PlanMode(
        @SerialName("type") override val type: String = "plan/mode",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: PlanModeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/run-start")
    data class ToolWorkflowRunStart(
        @SerialName("type") override val type: String = "tool-workflow/run-start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowRunStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/agent-start")
    data class ToolWorkflowAgentStart(
        @SerialName("type") override val type: String = "tool-workflow/agent-start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowAgentStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/agent-end")
    data class ToolWorkflowAgentEnd(
        @SerialName("type") override val type: String = "tool-workflow/agent-end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowAgentEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("tool-workflow/run-end")
    data class ToolWorkflowRunEnd(
        @SerialName("type") override val type: String = "tool-workflow/run-end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ToolWorkflowRunEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("schedule/change")
    data class ScheduleChange(
        @SerialName("type") override val type: String = "schedule/change",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ScheduleChangeData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/start")
    data class CompactionStart(
        @SerialName("type") override val type: String = "compaction/start",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionStartData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/summary")
    data class CompactionSummary(
        @SerialName("type") override val type: String = "compaction/summary",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionSummaryData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/end")
    data class CompactionEnd(
        @SerialName("type") override val type: String = "compaction/end",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionEndData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("compaction/prune")
    data class CompactionPrune(
        @SerialName("type") override val type: String = "compaction/prune",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: CompactionPruneData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("subagent/descriptor")
    data class SubagentDescriptor(
        @SerialName("type") override val type: String = "subagent/descriptor",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: SubagentDescriptorData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("feedback/record")
    data class FeedbackRecord(
        @SerialName("type") override val type: String = "feedback/record",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: FeedbackRecordData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/asked")
    data class ApprovalAsked(
        @SerialName("type") override val type: String = "approval/asked",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalAskedData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/decided")
    data class ApprovalDecided(
        @SerialName("type") override val type: String = "approval/decided",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalDecidedData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()

    @Serializable
    @SerialName("approval/policy")
    data class ApprovalPolicy(
        @SerialName("type") override val type: String = "approval/policy",
        @SerialName("seq") override val seq: Int,
        @SerialName("time") override val time: Long,
        @SerialName("data") val data: ApprovalPolicyData,
        @SerialName("sourceEventSeqs") override val sourceEventSeqs: List<Int>? = null,
        @SerialName("surfaceOp") override val surfaceOp: JsonElement? = null,
        @SerialName("ignorable") override val ignorable: Boolean? = null,
    ) : SessionEvent()
}

/** A session event of an unknown `type`; the complete raw envelope is preserved. */
data class UnknownSessionEvent(
    override val type: String,
    override val seq: Int,
    override val time: Long,
    /** The complete raw envelope JSON (including `type`/`seq`/`time`). */
    val raw: JsonElement,
    override val sourceEventSeqs: List<Int>? = null,
    override val surfaceOp: JsonElement? = null,
    override val ignorable: Boolean? = null,
) : SessionEvent()

/** Custom `type`-dispatching serializer for [SessionEvent]; unknown types pass through raw. */
object SessionEventSerializer : KSerializer<SessionEvent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionEvent") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
        element("seq", buildSerialDescriptor("kotlin.Int", PrimitiveKind.INT))
        element("time", buildSerialDescriptor("kotlin.Long", PrimitiveKind.LONG))
        element("data", buildSerialDescriptor("kotlin.Any", SerialKind.CONTEXTUAL))
    }

    override fun serialize(encoder: Encoder, value: SessionEvent) {
        val json: JsonElement = when (value) {
            is SessionEvent.TurnStart -> encodeToJsonElement(SessionEvent.TurnStart.serializer(), value)
            is SessionEvent.TurnEnd -> encodeToJsonElement(SessionEvent.TurnEnd.serializer(), value)
            is SessionEvent.StepStart -> encodeToJsonElement(SessionEvent.StepStart.serializer(), value)
            is SessionEvent.StepEnd -> encodeToJsonElement(SessionEvent.StepEnd.serializer(), value)
            is SessionEvent.UserMessage -> encodeToJsonElement(SessionEvent.UserMessage.serializer(), value)
            is SessionEvent.AssistantChunk -> encodeToJsonElement(SessionEvent.AssistantChunk.serializer(), value)
            is SessionEvent.AssistantMessage -> encodeToJsonElement(SessionEvent.AssistantMessage.serializer(), value)
            is SessionEvent.ToolCall -> encodeToJsonElement(SessionEvent.ToolCall.serializer(), value)
            is SessionEvent.ToolResult -> encodeToJsonElement(SessionEvent.ToolResult.serializer(), value)
            is SessionEvent.TodoWrite -> encodeToJsonElement(SessionEvent.TodoWrite.serializer(), value)
            is SessionEvent.RequestHeader -> encodeToJsonElement(SessionEvent.RequestHeader.serializer(), value)
            is SessionEvent.RequestContext -> encodeToJsonElement(SessionEvent.RequestContext.serializer(), value)
            is SessionEvent.SessionEndSeed -> encodeToJsonElement(SessionEvent.SessionEndSeed.serializer(), value)
            is SessionEvent.GoalChange -> encodeToJsonElement(SessionEvent.GoalChange.serializer(), value)
            is SessionEvent.PlanMode -> encodeToJsonElement(SessionEvent.PlanMode.serializer(), value)
            is SessionEvent.ToolWorkflowRunStart -> encodeToJsonElement(SessionEvent.ToolWorkflowRunStart.serializer(), value)
            is SessionEvent.ToolWorkflowAgentStart -> encodeToJsonElement(SessionEvent.ToolWorkflowAgentStart.serializer(), value)
            is SessionEvent.ToolWorkflowAgentEnd -> encodeToJsonElement(SessionEvent.ToolWorkflowAgentEnd.serializer(), value)
            is SessionEvent.ToolWorkflowRunEnd -> encodeToJsonElement(SessionEvent.ToolWorkflowRunEnd.serializer(), value)
            is SessionEvent.ScheduleChange -> encodeToJsonElement(SessionEvent.ScheduleChange.serializer(), value)
            is SessionEvent.CompactionStart -> encodeToJsonElement(SessionEvent.CompactionStart.serializer(), value)
            is SessionEvent.CompactionSummary -> encodeToJsonElement(SessionEvent.CompactionSummary.serializer(), value)
            is SessionEvent.CompactionEnd -> encodeToJsonElement(SessionEvent.CompactionEnd.serializer(), value)
            is SessionEvent.CompactionPrune -> encodeToJsonElement(SessionEvent.CompactionPrune.serializer(), value)
            is SessionEvent.SubagentDescriptor -> encodeToJsonElement(SessionEvent.SubagentDescriptor.serializer(), value)
            is SessionEvent.FeedbackRecord -> encodeToJsonElement(SessionEvent.FeedbackRecord.serializer(), value)
            is SessionEvent.ApprovalAsked -> encodeToJsonElement(SessionEvent.ApprovalAsked.serializer(), value)
            is SessionEvent.ApprovalDecided -> encodeToJsonElement(SessionEvent.ApprovalDecided.serializer(), value)
            is SessionEvent.ApprovalPolicy -> encodeToJsonElement(SessionEvent.ApprovalPolicy.serializer(), value)
            is UnknownSessionEvent -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionEvent {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: ""
        val seq = json["seq"]?.jsonPrimitive?.intOrNull ?: 0
        val time = json["time"]?.jsonPrimitive?.longOrNull ?: 0L
        val sourceEventSeqs = json["sourceEventSeqs"]?.let { element ->
            decodeFromJsonElement<List<Int>>(element)
        }
        val surfaceOp = json["surfaceOp"]
        val ignorable = json["ignorable"]?.jsonPrimitive?.booleanOrNull
        return when (type) {
            "turn/start" -> decodeFromJsonElement(SessionEvent.TurnStart.serializer(), json)
            "turn/end" -> decodeFromJsonElement(SessionEvent.TurnEnd.serializer(), json)
            "step/start" -> decodeFromJsonElement(SessionEvent.StepStart.serializer(), json)
            "step/end" -> decodeFromJsonElement(SessionEvent.StepEnd.serializer(), json)
            "user/message" -> decodeFromJsonElement(SessionEvent.UserMessage.serializer(), json)
            "assistant/chunk" -> decodeFromJsonElement(SessionEvent.AssistantChunk.serializer(), json)
            "assistant/message" -> decodeFromJsonElement(SessionEvent.AssistantMessage.serializer(), json)
            "tool/call" -> decodeFromJsonElement(SessionEvent.ToolCall.serializer(), json)
            "tool/result" -> decodeFromJsonElement(SessionEvent.ToolResult.serializer(), json)
            "todo/write" -> decodeFromJsonElement(SessionEvent.TodoWrite.serializer(), json)
            "request/header" -> decodeFromJsonElement(SessionEvent.RequestHeader.serializer(), json)
            "request/context" -> decodeFromJsonElement(SessionEvent.RequestContext.serializer(), json)
            "session/end-seed" -> decodeFromJsonElement(SessionEvent.SessionEndSeed.serializer(), json)
            "goal/change" -> decodeFromJsonElement(SessionEvent.GoalChange.serializer(), json)
            "plan/mode" -> decodeFromJsonElement(SessionEvent.PlanMode.serializer(), json)
            "tool-workflow/run-start" -> decodeFromJsonElement(SessionEvent.ToolWorkflowRunStart.serializer(), json)
            "tool-workflow/agent-start" -> decodeFromJsonElement(SessionEvent.ToolWorkflowAgentStart.serializer(), json)
            "tool-workflow/agent-end" -> decodeFromJsonElement(SessionEvent.ToolWorkflowAgentEnd.serializer(), json)
            "tool-workflow/run-end" -> decodeFromJsonElement(SessionEvent.ToolWorkflowRunEnd.serializer(), json)
            "schedule/change" -> decodeFromJsonElement(SessionEvent.ScheduleChange.serializer(), json)
            "compaction/start" -> decodeFromJsonElement(SessionEvent.CompactionStart.serializer(), json)
            "compaction/summary" -> decodeFromJsonElement(SessionEvent.CompactionSummary.serializer(), json)
            "compaction/end" -> decodeFromJsonElement(SessionEvent.CompactionEnd.serializer(), json)
            "compaction/prune" -> decodeFromJsonElement(SessionEvent.CompactionPrune.serializer(), json)
            "subagent/descriptor" -> decodeFromJsonElement(SessionEvent.SubagentDescriptor.serializer(), json)
            "feedback/record" -> decodeFromJsonElement(SessionEvent.FeedbackRecord.serializer(), json)
            "approval/asked" -> decodeFromJsonElement(SessionEvent.ApprovalAsked.serializer(), json)
            "approval/decided" -> decodeFromJsonElement(SessionEvent.ApprovalDecided.serializer(), json)
            "approval/policy" -> decodeFromJsonElement(SessionEvent.ApprovalPolicy.serializer(), json)
            else -> UnknownSessionEvent(type, seq, time, json, sourceEventSeqs, surfaceOp, ignorable)
        }
    }
}
