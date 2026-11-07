@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Gateway logical-stream mux and Remote Event wire vocabulary, ported from
 * `packages/api/gateway/src/stream-protocol.ts` (v0.1.2-alpha.1).
 *
 * Harness 0.1.2 replaced the two downlink-only sockets (`/api/events.mux`, `/api/events.host`)
 * with one bidirectional WebSocket at [REMOTE_STREAM_MUX_PATH]. The client opens named *logical*
 * streams over it; the host answers each with items, an error, or an end. Unlike the sockets it
 * replaces, this one is written to — see [RemoteStreamClientMessage].
 *
 * There is no envelope here. These messages are the complete WebSocket text payload, not the
 * payload slot of a `server-request`, which no longer exists.
 */

/** Exact WebSocket route carrying every Typert Remote stream. */
const val REMOTE_STREAM_MUX_PATH: String = "/api/remote.mux"

/**
 * Gateway-internal logical stream carrying application-selected host events.
 *
 * This is the connection's liveness source: its opening [RemoteEventFrame.Ready] frame is what
 * makes a generation ready, and its end terminates one. It is opened unconditionally, not on
 * demand, so connection health does not depend on whether any screen is listening.
 */
const val REMOTE_EVENT_STREAM_ENDPOINT: String = "\$events"

/** Gateway-internal unary endpoint returning one client Remote Event outcome. */
const val REMOTE_EVENT_RESULT_ENDPOINT: String = "\$events/result"

// ============================================================================================
// Logical stream carrier
// ============================================================================================

/** One logical stream request sent to the host. Serialized as the whole WebSocket text message. */
@Serializable(with = RemoteStreamClientMessageSerializer::class)
sealed class RemoteStreamClientMessage {
    /** The wire message type. */
    abstract val type: String

    /**
     * Open one logical stream. [payload] is the standard Remote payload — `{"args": {…}}` — the
     * same shape a unary call posts, because a stream endpoint takes the same named arguments.
     */
    @Serializable
    data class Open(
        @SerialName("type") override val type: String = "open",
        @SerialName("streamId") val streamId: String,
        @SerialName("endpoint") val endpoint: String,
        @SerialName("payload") val payload: JsonElement,
    ) : RemoteStreamClientMessage()

    /** Cancel one open logical stream. The host answers with an `end` or an `error`. */
    @Serializable
    data class Cancel(
        @SerialName("type") override val type: String = "cancel",
        @SerialName("streamId") val streamId: String,
    ) : RemoteStreamClientMessage()

    /**
     * One uplink item on a duplex stream (harness 0.2.1-alpha.3, `terminal/attach`). Items sent
     * after `open` wait in the host's per-stream inbox, which is bounded: overflowing it fails the
     * stream with `gateway/uplink-overflow`, so a sender has to budget against the window the
     * endpoint reports.
     */
    @Serializable
    data class Item(
        @SerialName("type") override val type: String = "item",
        @SerialName("streamId") val streamId: String,
        @SerialName("value") val value: JsonElement,
    ) : RemoteStreamClientMessage()

    /** Half-close the uplink. The downlink stays open until the host ends it. */
    @Serializable
    data class End(
        @SerialName("type") override val type: String = "end",
        @SerialName("streamId") val streamId: String,
    ) : RemoteStreamClientMessage()
}

/** Encodes [RemoteStreamClientMessage] to its flat wire object. */
object RemoteStreamClientMessageSerializer : KSerializer<RemoteStreamClientMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RemoteStreamClientMessage") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: RemoteStreamClientMessage) {
        val json = when (value) {
            is RemoteStreamClientMessage.Open ->
                encodeToJsonElement(RemoteStreamClientMessage.Open.serializer(), value)
            is RemoteStreamClientMessage.Cancel ->
                encodeToJsonElement(RemoteStreamClientMessage.Cancel.serializer(), value)
            is RemoteStreamClientMessage.Item ->
                encodeToJsonElement(RemoteStreamClientMessage.Item.serializer(), value)
            is RemoteStreamClientMessage.End ->
                encodeToJsonElement(RemoteStreamClientMessage.End.serializer(), value)
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): RemoteStreamClientMessage {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "open" -> decodeFromJsonElement(RemoteStreamClientMessage.Open.serializer(), json)
            "cancel" -> decodeFromJsonElement(RemoteStreamClientMessage.Cancel.serializer(), json)
            "item" -> decodeFromJsonElement(RemoteStreamClientMessage.Item.serializer(), json)
            "end" -> decodeFromJsonElement(RemoteStreamClientMessage.End.serializer(), json)
            else -> throw IllegalArgumentException("unknown remote-stream client message \"$type\"")
        }
    }
}

/**
 * One logical stream frame sent by the host.
 *
 * Unlike the frame unions this replaces, an unrecognised `type` is *not* passed through: the
 * carrier's three cases are closed, and a fourth would mean this client cannot account for the
 * stream's state. Domain payloads still degrade on shape — that tolerance belongs one layer up,
 * in whatever decodes [Item.value].
 */
@Serializable(with = RemoteStreamServerMessageSerializer::class)
sealed class RemoteStreamServerMessage {
    /** The wire message type. */
    abstract val type: String

    /** The logical stream this frame belongs to. */
    abstract val streamId: String

    /** One stream item. A void item omits `value` entirely. */
    @Serializable
    data class Item(
        @SerialName("type") override val type: String = "item",
        @SerialName("streamId") override val streamId: String,
        @SerialName("value") val value: JsonElement? = null,
    ) : RemoteStreamServerMessage()

    /** The stream failed. Terminal for this logical stream; other streams are unaffected. */
    @Serializable
    data class Error(
        @SerialName("type") override val type: String = "error",
        @SerialName("streamId") override val streamId: String,
        @SerialName("error") val error: RpcError,
    ) : RemoteStreamServerMessage()

    /** The stream completed normally. */
    @Serializable
    data class End(
        @SerialName("type") override val type: String = "end",
        @SerialName("streamId") override val streamId: String,
    ) : RemoteStreamServerMessage()
}

/** Custom `type`-dispatching serializer for [RemoteStreamServerMessage]. */
object RemoteStreamServerMessageSerializer : KSerializer<RemoteStreamServerMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RemoteStreamServerMessage") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: RemoteStreamServerMessage) {
        val json = when (value) {
            is RemoteStreamServerMessage.Item ->
                encodeToJsonElement(RemoteStreamServerMessage.Item.serializer(), value)
            is RemoteStreamServerMessage.Error ->
                encodeToJsonElement(RemoteStreamServerMessage.Error.serializer(), value)
            is RemoteStreamServerMessage.End ->
                encodeToJsonElement(RemoteStreamServerMessage.End.serializer(), value)
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): RemoteStreamServerMessage {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "item" -> decodeFromJsonElement(RemoteStreamServerMessage.Item.serializer(), json)
            "error" -> decodeFromJsonElement(RemoteStreamServerMessage.Error.serializer(), json)
            "end" -> decodeFromJsonElement(RemoteStreamServerMessage.End.serializer(), json)
            else -> throw IllegalArgumentException("unknown remote-stream server message \"$type\"")
        }
    }
}

// ============================================================================================
// Remote Event plane ($events)
// ============================================================================================

/** Stable host facts published with every established event generation. */
@Serializable
data class RemoteEventHostInfo(
    /** The host account's home directory, used only to abbreviate displayed paths. */
    @SerialName("home") val home: String,
)

/**
 * One item of the `$events` logical stream.
 *
 * [Ready] must be first; anything else opening the stream is a protocol failure that ends the
 * generation. An unknown `type` becomes [Unknown] rather than a failure: the allowlist upstream
 * grows, and a notification this build has never heard of should cost that one frame.
 */
@Serializable(with = RemoteEventFrameSerializer::class)
sealed class RemoteEventFrame {
    /** The wire frame type. */
    abstract val type: String

    /**
     * The opening frame. Proves the host installed its incremental listeners before answering,
     * so no baseline read can race them.
     *
     * [clientId] binds every later [REMOTE_EVENT_RESULT_ENDPOINT] reply to this generation; a
     * reply carrying a retired one is refused, which is why it is retained rather than logged.
     */
    @Serializable
    data class Ready(
        @SerialName("type") override val type: String = "ready",
        @SerialName("clientId") val clientId: String,
        @SerialName("host") val host: RemoteEventHostInfo,
    ) : RemoteEventFrame()

    /**
     * One host notification. Never replayed after a disconnect — state that must survive one
     * needs a query, cursor, or opening baseline instead.
     */
    @Serializable
    data class Emit(
        @SerialName("type") override val type: String = "emit",
        @SerialName("event") val event: String,
        @SerialName("args") val args: List<JsonElement> = emptyList(),
    ) : RemoteEventFrame()

    /**
     * One agent-scoped request awaiting this client's answer.
     *
     * [request] carries the event's own fields with `agent` and `signal` removed — the agent is
     * hoisted to [agentId], and the cancellation lifetime becomes a later [Cancel] frame.
     */
    @Serializable
    data class Waterfall(
        @SerialName("type") override val type: String = "waterfall",
        @SerialName("event") val event: String,
        @SerialName("eventId") val eventId: String,
        @SerialName("agentId") val agentId: String,
        @SerialName("request") val request: JsonObject,
    ) : RemoteEventFrame()

    /**
     * A previously delivered [Waterfall] is withdrawn: another client answered first, or the
     * host's own caller cancelled. The UI must retire the prompt without answering it.
     */
    @Serializable
    data class Cancel(
        @SerialName("type") override val type: String = "cancel",
        @SerialName("eventId") val eventId: String,
    ) : RemoteEventFrame()

    /** A frame of an unknown `type`, preserved verbatim. */
    data class Unknown(
        override val type: String,
        val raw: JsonElement,
    ) : RemoteEventFrame()
}

/** Custom `type`-dispatching serializer for [RemoteEventFrame]; unknown kinds pass through raw. */
object RemoteEventFrameSerializer : KSerializer<RemoteEventFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RemoteEventFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: RemoteEventFrame) {
        val json: JsonElement = when (value) {
            is RemoteEventFrame.Ready -> encodeToJsonElement(RemoteEventFrame.Ready.serializer(), value)
            is RemoteEventFrame.Emit -> encodeToJsonElement(RemoteEventFrame.Emit.serializer(), value)
            is RemoteEventFrame.Waterfall -> encodeToJsonElement(RemoteEventFrame.Waterfall.serializer(), value)
            is RemoteEventFrame.Cancel -> encodeToJsonElement(RemoteEventFrame.Cancel.serializer(), value)
            is RemoteEventFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): RemoteEventFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "ready" -> decodeFromJsonElement(RemoteEventFrame.Ready.serializer(), json)
            "emit" -> decodeFromJsonElement(RemoteEventFrame.Emit.serializer(), json)
            "waterfall" -> decodeFromJsonElement(RemoteEventFrame.Waterfall.serializer(), json)
            "cancel" -> decodeFromJsonElement(RemoteEventFrame.Cancel.serializer(), json)
            else -> RemoteEventFrame.Unknown(type, json)
        }
    }
}

/** Error fields retained when this client rejects a host waterfall. */
@Serializable
data class RemoteEventRejection(
    @SerialName("name") val name: String,
    @SerialName("message") val message: String,
    @SerialName("code") val code: String? = null,
    @SerialName("details") val details: JsonElement? = null,
)

/**
 * This client's answer to one delivered waterfall.
 *
 * The three kinds are distinct decisions, not degrees of the same one: [Next] declines to answer
 * and lets the host's own chain continue, [Result] claims the request, and [Rejected] fails it.
 * A dismissed question is [Rejected] — answering every item with an empty selection is a valid
 * *answer* the model reads as "no preference".
 */
@Serializable(with = RemoteEventOutcomeSerializer::class)
sealed class RemoteEventOutcome {
    /** The outcome discriminant. */
    abstract val kind: String

    /** Delegate to the host's later listeners. */
    @Serializable
    data class Next(
        @SerialName("kind") override val kind: String = "next",
    ) : RemoteEventOutcome()

    /** Claim the request with a value. A void result omits `value`. */
    @Serializable
    data class Result(
        @SerialName("kind") override val kind: String = "result",
        @SerialName("value") val value: JsonElement? = null,
    ) : RemoteEventOutcome()

    /** Fail the request. The host restores name, code and details on its side. */
    @Serializable
    data class Rejected(
        @SerialName("kind") override val kind: String = "rejected",
        @SerialName("error") val error: RemoteEventRejection,
    ) : RemoteEventOutcome()
}

/** Encodes [RemoteEventOutcome] to its flat wire object. */
object RemoteEventOutcomeSerializer : KSerializer<RemoteEventOutcome> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RemoteEventOutcome") {
        element("kind", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: RemoteEventOutcome) {
        val json = when (value) {
            is RemoteEventOutcome.Next -> encodeToJsonElement(RemoteEventOutcome.Next.serializer(), value)
            is RemoteEventOutcome.Result -> encodeToJsonElement(RemoteEventOutcome.Result.serializer(), value)
            is RemoteEventOutcome.Rejected -> encodeToJsonElement(RemoteEventOutcome.Rejected.serializer(), value)
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): RemoteEventOutcome {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: "") {
            "next" -> decodeFromJsonElement(RemoteEventOutcome.Next.serializer(), json)
            "result" -> decodeFromJsonElement(RemoteEventOutcome.Result.serializer(), json)
            "rejected" -> decodeFromJsonElement(RemoteEventOutcome.Rejected.serializer(), json)
            else -> throw IllegalArgumentException("unknown remote-event outcome \"$kind\"")
        }
    }
}

/** The `$events/result` request body: one outcome bound to a generation and a pending event. */
@Serializable
data class RemoteEventResult(
    @SerialName("clientId") val clientId: String,
    @SerialName("eventId") val eventId: String,
    @SerialName("outcome") val outcome: RemoteEventOutcome,
)

// ============================================================================================
// The two waterfalls this client answers
// ============================================================================================

/** Remote Event name of the approval waterfall. */
const val APPROVAL_REQUEST_EVENT: String = "approval/request"

/** Remote Event name of the user-question waterfall. */
const val USER_QUESTIONS_REQUEST_EVENT: String = "user-questions/request"

/**
 * The `approval/request` waterfall body, after the gateway strips `agent` and `signal`.
 *
 * There is no approval id: the pending request is identified by the frame's `eventId`, which is
 * also what a [RemoteEventFrame.Cancel] names.
 */
@Serializable
data class ApprovalRequestEvent(
    /** Tool whose operation requires a decision. */
    @SerialName("toolName") val toolName: String,
    /** Exact tool call being decided, when the asker had one. */
    @SerialName("callId") val callId: String? = null,
    /** Human-readable reason supplied by the asker. */
    @SerialName("reason") val reason: String? = null,
    /**
     * Harness 0.1.7: the reason localized for display, keyed by language (`en` always present).
     * Presentation only; the host never persists it. Set by sandbox escalation and Auto review.
     */
    @SerialName("displayReason") val displayReason: Map<String, String>? = null,
) {
    /**
     * The reason to show a person reading in [language] (an ISO 639 code such as `zh`): the
     * localized text in that language, else its English, else the raw [reason].
     */
    fun reasonFor(language: String): String? {
        val localized = displayReason.orEmpty()
        return localized[language]?.takeIf { it.isNotBlank() }
            ?: localized[language.substringBefore('-')]?.takeIf { it.isNotBlank() }
            ?: localized["en"]?.takeIf { it.isNotBlank() }
            ?: reason
    }
}

/** The `user-questions/request` waterfall body, after the gateway strips `agent` and `signal`. */
@Serializable
data class AskUserQuestionRequestEvent(
    @SerialName("questions") val questions: List<AskUserQuestionItem> = emptyList(),
    val wait: QuestionWait? = null,
)

/**
 * Closed approval outcomes. `unavailable` is the host's own fail-closed answer rather than
 * something this client sends; it is named here because the vocabulary is one set.
 */
object ApprovalOutcome {
    /** A one-shot grant. */
    const val ALLOWED_ONCE: String = "allowed-once"

    /** An explicit refusal. */
    const val REJECTED: String = "rejected"

    /** The request was withdrawn before it was decided. */
    const val CANCELLED: String = "cancelled"

    /** No answerer was reachable; callers fail closed on this. */
    const val UNAVAILABLE: String = "unavailable"
}
