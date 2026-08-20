@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.RpcError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Downlink stream frame unions, ported exactly from `packages/host/apiproxy/src/api/events.ts`
 * (v0.1.0-rc.8). A frame is the payload slot of a downstream ServerRequest; dispatch on `type`.
 * Unknown frame kinds fall back to [UnknownMuxFrame] / [UnknownHostFrame] preserving the raw JSON.
 */

/** One pending inbox occurrence in the authoritative `session/queue` snapshot. */
@Serializable
data class QueuedInboxItem(
    /** Message identity used by inbox mutations. */
    @SerialName("id") val id: String,
    /** Agent-resolved FIFO placement ('queued' | 'steering' | 'context'). */
    @SerialName("placement") val placement: String,
    /** Complete pending message; it is not durable until the Agent claims it. */
    @SerialName("message") val message: MessageData,
)

/** Current lifecycle state of one background job. */
@Serializable
enum class JobStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("stopping")
    STOPPING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("killed")
    KILLED,

    @SerialName("failed")
    FAILED,
}

/** One background job as the client sees it. */
@Serializable
data class JobView(
    /** Registry-issued `<kind>-N` identity, stable for the task's whole life. */
    @SerialName("id") val id: String,
    /** Producer kind (`bash`, `pwsh`, `pty-send`, `subagent`, …). */
    @SerialName("kind") val kind: String,
    /** Producer-supplied one-line label: the command, or the delegation description. */
    @SerialName("label") val label: String,
    /** Current lifecycle state. */
    @SerialName("status") val status: JobStatus,
    /** Kind-specific status detail ('exit code: 3'), present once the producer supplied one. */
    @SerialName("detail") val detail: String? = null,
    /** Epoch ms when the task was registered. */
    @SerialName("startedAt") val startedAt: Long,
    /** Epoch ms when the task settled; absent while live. */
    @SerialName("finishedAt") val finishedAt: Long? = null,
)

/** One selectable answer offered to the user. */
@Serializable
data class AskUserQuestionOption(
    @SerialName("label") val label: String,
    /** Optional extra context rendered by capable UIs. */
    @SerialName("description") val description: String? = null,
)

/** A caller-declared presentation intent; tagged so further intents can be added. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class AskUserQuestionIntent {
    /** A plan submitted for review: `detail` is the plan markdown, the decision approves or declines it. */
    @Serializable
    @SerialName("plan-review")
    data class PlanReview(
        /** The option label that approves the plan; every other option declines it. */
        @SerialName("approve") val approve: String,
    ) : AskUserQuestionIntent()
}

/** One question in a user-questions request. */
@Serializable
data class AskUserQuestionItem(
    /** Stable caller-provided question id, echoed in the answer. */
    @SerialName("id") val id: String,
    /** The question to display. */
    @SerialName("question") val question: String,
    /** Optional supporting detail rendered with the question. */
    @SerialName("detail") val detail: String? = null,
    /** Optional short heading/group label. */
    @SerialName("header") val header: String? = null,
    /** Optional choices the UI can render as a menu. */
    @SerialName("options") val options: List<AskUserQuestionOption>? = null,
    /** Whether more than one option may be selected. Defaults to single-select. */
    @SerialName("multiSelect") val multiSelect: Boolean? = null,
    /** Optional presentation intent for capable UIs. */
    @SerialName("intent") val intent: AskUserQuestionIntent? = null,
)

/**
 * Answer to one question. `custom` rides the *item*, not the batch beside it — the harness
 * schema (`packages/host/apiproxy/src/api/questions.schema.ts`) puts it here, and a key placed
 * anywhere else is stripped by its zod parse without a word, so the answer arrives empty.
 *
 * The codec suppresses explicit nulls, so an absent `custom` is omitted rather than sent as
 * `null` — which is what `matchesQuestions` treats as "no free text" (it rejects a *blank* one).
 */
@Serializable
data class AskUserQuestionAnswerItem(
    /** The answered question's id, echoed back. */
    @SerialName("id") val id: String,
    /** Selected option labels, verbatim — including any `(Recommended)` suffix. */
    @SerialName("selected") val selected: List<String> = emptyList(),
    /** Free-text "Other" answer; absent when the user typed none. */
    @SerialName("custom") val custom: String? = null,
)

/**
 * The human's answer to a whole `question/requested` batch.
 *
 * One item per question, in request order: the host checks the count and the id at each index
 * (`matchesQuestions`, `packages/host/apiproxy/src/api-proxy.ts`) and refuses the response
 * outright if either differs, leaving the tool blocked. A question the user skipped is still
 * answered — with an empty selection.
 */
@Serializable
data class AskUserQuestionAnswer(
    @SerialName("answers") val answers: List<AskUserQuestionAnswerItem> = emptyList(),
)

/**
 * The error a client sends to dismiss a question request rather than answer it.
 *
 * The proxy accepts an `ok:false` client-response for a question only when the code is exactly
 * `cancelled`; anything else comes back as `bad-response` and the wait stays open. `details` is
 * required by the schema, and [RpcError] defaults it to `{}` — which reaches the wire because the
 * codec encodes defaults.
 */
val QUESTION_CANCELLED: RpcError = RpcError("cancelled", "the user closed this question request")

// ============================================================================================
// Mux stream frames
// ============================================================================================

/** All-session aggregated mux stream frames (payload slot of a mux-stream ServerRequest). */
@Serializable(with = MuxFrameSerializer::class)
sealed class MuxFrame {
    /** The wire frame type. */
    abstract val type: String

    @Serializable
    @SerialName("session/event")
    data class SessionEventFrame(
        @SerialName("type") override val type: String = "session/event",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("event") val event: SessionEvent,
        @SerialName("view") val view: ToolEventView? = null,
    ) : MuxFrame()

    @Serializable
    @SerialName("session/subscribed")
    data class SessionSubscribed(
        @SerialName("type") override val type: String = "session/subscribed",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("lastSeq") val lastSeq: Int,
    ) : MuxFrame()

    @Serializable
    @SerialName("approval/requested")
    data class ApprovalRequested(
        @SerialName("type") override val type: String = "approval/requested",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("approvalId") val approvalId: String,
        @SerialName("toolName") val toolName: String,
        @SerialName("callId") val callId: String? = null,
        @SerialName("reason") val reason: String? = null,
    ) : MuxFrame()

    @Serializable
    @SerialName("approval/resolved")
    data class ApprovalResolved(
        @SerialName("type") override val type: String = "approval/resolved",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("approvalId") val approvalId: String,
        /** 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'. */
        @SerialName("outcome") val outcome: String,
    ) : MuxFrame()

    @Serializable
    @SerialName("question/requested")
    data class QuestionRequested(
        @SerialName("type") override val type: String = "question/requested",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("questions") val questions: List<AskUserQuestionItem> = emptyList(),
    ) : MuxFrame()

    @Serializable
    @SerialName("question/resolved")
    data class QuestionResolved(
        @SerialName("type") override val type: String = "question/resolved",
        @SerialName("sessionId") val sessionId: String,
        /** The rpcId of the answered question/requested frame, echoed. */
        @SerialName("questionRpcId") val questionRpcId: String,
        /** 'answered' | 'cancelled'. */
        @SerialName("outcome") val outcome: String,
    ) : MuxFrame()

    /** Complete transient inbox state after every enqueue, mutation, claim, or discard. */
    @Serializable
    @SerialName("session/queue")
    data class SessionQueue(
        @SerialName("type") override val type: String = "session/queue",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("items") val items: List<QueuedInboxItem> = emptyList(),
    ) : MuxFrame()

    /** Complete set of background jobs this session can see. */
    @Serializable
    @SerialName("session/jobs")
    data class SessionJobs(
        @SerialName("type") override val type: String = "session/jobs",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("jobs") val jobs: List<JobView> = emptyList(),
    ) : MuxFrame()

    /** One projection unit's finished value changed (live push state, never logged). */
    @Serializable
    @SerialName("session/projection")
    data class SessionProjection(
        @SerialName("type") override val type: String = "session/projection",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("key") val key: String,
        /** The unit's schema-validated view output (opaque to the carrier). */
        @SerialName("value") val value: JsonElement,
        /** The unit's watermark at emission. */
        @SerialName("seq") val seq: Int,
    ) : MuxFrame()

    /** Terminates the stream's generation; the client must reconnect. */
    @Serializable
    @SerialName("stream/error")
    data class StreamError(
        @SerialName("type") override val type: String = "stream/error",
        @SerialName("error") val error: RpcError,
    ) : MuxFrame()
}

/** A mux frame of an unknown `type`, preserved verbatim. */
data class UnknownMuxFrame(
    override val type: String,
    val raw: JsonElement,
) : MuxFrame()

/** Custom `type`-dispatching serializer for [MuxFrame]; unknown kinds pass through raw. */
object MuxFrameSerializer : KSerializer<MuxFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MuxFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: MuxFrame) {
        val json: JsonElement = when (value) {
            is MuxFrame.SessionEventFrame -> encodeToJsonElement(MuxFrame.SessionEventFrame.serializer(), value)
            is MuxFrame.SessionSubscribed -> encodeToJsonElement(MuxFrame.SessionSubscribed.serializer(), value)
            is MuxFrame.ApprovalRequested -> encodeToJsonElement(MuxFrame.ApprovalRequested.serializer(), value)
            is MuxFrame.ApprovalResolved -> encodeToJsonElement(MuxFrame.ApprovalResolved.serializer(), value)
            is MuxFrame.QuestionRequested -> encodeToJsonElement(MuxFrame.QuestionRequested.serializer(), value)
            is MuxFrame.QuestionResolved -> encodeToJsonElement(MuxFrame.QuestionResolved.serializer(), value)
            is MuxFrame.SessionQueue -> encodeToJsonElement(MuxFrame.SessionQueue.serializer(), value)
            is MuxFrame.SessionJobs -> encodeToJsonElement(MuxFrame.SessionJobs.serializer(), value)
            is MuxFrame.SessionProjection -> encodeToJsonElement(MuxFrame.SessionProjection.serializer(), value)
            is MuxFrame.StreamError -> encodeToJsonElement(MuxFrame.StreamError.serializer(), value)
            is UnknownMuxFrame -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): MuxFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "session/event" -> decodeFromJsonElement(MuxFrame.SessionEventFrame.serializer(), json)
            "session/subscribed" -> decodeFromJsonElement(MuxFrame.SessionSubscribed.serializer(), json)
            "approval/requested" -> decodeFromJsonElement(MuxFrame.ApprovalRequested.serializer(), json)
            "approval/resolved" -> decodeFromJsonElement(MuxFrame.ApprovalResolved.serializer(), json)
            "question/requested" -> decodeFromJsonElement(MuxFrame.QuestionRequested.serializer(), json)
            "question/resolved" -> decodeFromJsonElement(MuxFrame.QuestionResolved.serializer(), json)
            "session/queue" -> decodeFromJsonElement(MuxFrame.SessionQueue.serializer(), json)
            "session/jobs" -> decodeFromJsonElement(MuxFrame.SessionJobs.serializer(), json)
            "session/projection" -> decodeFromJsonElement(MuxFrame.SessionProjection.serializer(), json)
            "stream/error" -> decodeFromJsonElement(MuxFrame.StreamError.serializer(), json)
            else -> UnknownMuxFrame(type, json)
        }
    }
}

// ============================================================================================
// Host stream frames
// ============================================================================================

/** Host-level info stream frames (payload slot of a host-stream ServerRequest). */
@Serializable(with = HostFrameSerializer::class)
sealed class HostFrame {
    /** The wire frame type. */
    abstract val type: String

    @Serializable
    @SerialName("host/session-added")
    data class SessionAdded(
        @SerialName("type") override val type: String = "host/session-added",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("blank") val blank: Boolean,
        @SerialName("parentSessionId") val parentSessionId: String? = null,
        @SerialName("origin") val origin: String? = null,
        @SerialName("cwd") val cwd: String? = null,
        @SerialName("agentPreset") val agentPreset: String? = null,
    ) : HostFrame()

    @Serializable
    @SerialName("host/session-removed")
    data class SessionRemoved(
        @SerialName("type") override val type: String = "host/session-removed",
        @SerialName("sessionId") val sessionId: String,
    ) : HostFrame()

    @Serializable
    @SerialName("host/session-status")
    data class SessionStatus(
        @SerialName("type") override val type: String = "host/session-status",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("running") val running: Boolean,
    ) : HostFrame()

    /** The only outlet for live failures with no turn position. */
    @Serializable
    @SerialName("host/agent-error")
    data class AgentError(
        @SerialName("type") override val type: String = "host/agent-error",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("message") val message: String,
    ) : HostFrame()

    /** Pushes the full new snapshot after every durable workspace mutation. */
    @Serializable
    @SerialName("host/workspace-changed")
    data class WorkspaceChanged(
        @SerialName("type") override val type: String = "host/workspace-changed",
        @SerialName("workspace") val workspace: WorkspaceView,
    ) : HostFrame()

    @Serializable
    @SerialName("host/workspace-removed")
    data class WorkspaceRemoved(
        @SerialName("type") override val type: String = "host/workspace-removed",
        @SerialName("workspaceId") val workspaceId: String,
    ) : HostFrame()

    /** Pushes the complete durable registry order after a reorder. */
    @Serializable
    @SerialName("host/workspace-order-changed")
    data class WorkspaceOrderChanged(
        @SerialName("type") override val type: String = "host/workspace-order-changed",
        @SerialName("workspaceIds") val workspaceIds: List<String> = emptyList(),
    ) : HostFrame()

    /** Pushes the full registry archive set after every durable change. */
    @Serializable
    @SerialName("host/archived-sessions-changed")
    data class ArchivedSessionsChanged(
        @SerialName("type") override val type: String = "host/archived-sessions-changed",
        @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
    ) : HostFrame()

    /** One allowlisted host cordis event forwarded verbatim (no projection, no renaming). */
    @Serializable
    @SerialName("host/remote-event")
    data class RemoteEvent(
        @SerialName("type") override val type: String = "host/remote-event",
        @SerialName("event") val event: String,
        @SerialName("args") val args: List<JsonElement> = emptyList(),
    ) : HostFrame()

    /** Terminates the stream's generation; the client must reconnect. */
    @Serializable
    @SerialName("stream/error")
    data class StreamError(
        @SerialName("type") override val type: String = "stream/error",
        @SerialName("error") val error: RpcError,
    ) : HostFrame()
}

/** A host frame of an unknown `type`, preserved verbatim. */
data class UnknownHostFrame(
    override val type: String,
    val raw: JsonElement,
) : HostFrame()

/** Custom `type`-dispatching serializer for [HostFrame]; unknown kinds pass through raw. */
object HostFrameSerializer : KSerializer<HostFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("HostFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: HostFrame) {
        val json: JsonElement = when (value) {
            is HostFrame.SessionAdded -> encodeToJsonElement(HostFrame.SessionAdded.serializer(), value)
            is HostFrame.SessionRemoved -> encodeToJsonElement(HostFrame.SessionRemoved.serializer(), value)
            is HostFrame.SessionStatus -> encodeToJsonElement(HostFrame.SessionStatus.serializer(), value)
            is HostFrame.AgentError -> encodeToJsonElement(HostFrame.AgentError.serializer(), value)
            is HostFrame.WorkspaceChanged -> encodeToJsonElement(HostFrame.WorkspaceChanged.serializer(), value)
            is HostFrame.WorkspaceRemoved -> encodeToJsonElement(HostFrame.WorkspaceRemoved.serializer(), value)
            is HostFrame.WorkspaceOrderChanged -> encodeToJsonElement(HostFrame.WorkspaceOrderChanged.serializer(), value)
            is HostFrame.ArchivedSessionsChanged -> encodeToJsonElement(HostFrame.ArchivedSessionsChanged.serializer(), value)
            is HostFrame.RemoteEvent -> encodeToJsonElement(HostFrame.RemoteEvent.serializer(), value)
            is HostFrame.StreamError -> encodeToJsonElement(HostFrame.StreamError.serializer(), value)
            is UnknownHostFrame -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): HostFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "host/session-added" -> decodeFromJsonElement(HostFrame.SessionAdded.serializer(), json)
            "host/session-removed" -> decodeFromJsonElement(HostFrame.SessionRemoved.serializer(), json)
            "host/session-status" -> decodeFromJsonElement(HostFrame.SessionStatus.serializer(), json)
            "host/agent-error" -> decodeFromJsonElement(HostFrame.AgentError.serializer(), json)
            "host/workspace-changed" -> decodeFromJsonElement(HostFrame.WorkspaceChanged.serializer(), json)
            "host/workspace-removed" -> decodeFromJsonElement(HostFrame.WorkspaceRemoved.serializer(), json)
            "host/workspace-order-changed" -> decodeFromJsonElement(HostFrame.WorkspaceOrderChanged.serializer(), json)
            "host/archived-sessions-changed" -> decodeFromJsonElement(HostFrame.ArchivedSessionsChanged.serializer(), json)
            "host/remote-event" -> decodeFromJsonElement(HostFrame.RemoteEvent.serializer(), json)
            "stream/error" -> decodeFromJsonElement(HostFrame.StreamError.serializer(), json)
            else -> UnknownHostFrame(type, json)
        }
    }
}


