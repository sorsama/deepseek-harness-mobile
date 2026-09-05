@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.labteto.dshmobile.core.wire.dto

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session journal wire types, ported from `packages/api/session-controller/src/types.ts`
 * (v0.1.3-alpha.1).
 *
 * Harness 0.1.2 split reading a transcript into a live `session/follow` stream and a
 * `session/page` unary read, and the two are not independent: a page must be pinned to the follow
 * generation's opening cursor. See [SessionPageRequest].
 *
 * Harness 0.1.3 changed what those two carry. Session format v2 keeps one durable settlement per
 * model attempt instead of one event per token, so the packed `chunks` history record that 0.1.2
 * introduced is gone along with the `assistant/chunk` events it packed. Live deltas are no longer
 * durable at all: a follower that wants them opts in with [SessionFollowRequest.assistantStream]
 * and receives them as [SessionFollowFrame.AssistantStream] frames, which are process-local
 * presentation and never replayed from the log.
 */

/**
 * Durable identity selecting an ordinary session or one direct subagent child.
 *
 * One address protocol now covers both, which is why `subagents/history` no longer exists. A
 * subagent address names the parent as well as the child because a cold host read verifies
 * durable ownership rather than authorizing access from the child id alone.
 */
@Serializable(with = SessionAddressSerializer::class)
sealed class SessionAddress {
    /** The wire discriminant. */
    abstract val kind: String

    /** An ordinary session. */
    @Serializable
    data class Session(
        @SerialName("kind") override val kind: String = "session",
        @SerialName("sessionId") val sessionId: String,
    ) : SessionAddress()

    /** One direct subagent child, addressed through the parent whose authority is claimed. */
    @Serializable
    data class Subagent(
        @SerialName("kind") override val kind: String = "subagent",
        @SerialName("parentSessionId") val parentSessionId: String,
        @SerialName("childSessionId") val childSessionId: String,
        /** 'one-shot' | 'continuable'. */
        @SerialName("mode") val mode: String,
    ) : SessionAddress()
}

/** Custom `kind`-dispatching serializer for [SessionAddress]. */
object SessionAddressSerializer : KSerializer<SessionAddress> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionAddress") {
        element("kind", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: SessionAddress) {
        val json = when (value) {
            is SessionAddress.Session -> encodeToJsonElement(SessionAddress.Session.serializer(), value)
            is SessionAddress.Subagent -> encodeToJsonElement(SessionAddress.Subagent.serializer(), value)
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionAddress {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: "") {
            "session" -> decodeFromJsonElement(SessionAddress.Session.serializer(), json)
            "subagent" -> decodeFromJsonElement(SessionAddress.Subagent.serializer(), json)
            else -> throw IllegalArgumentException("unknown session address kind \"$kind\"")
        }
    }
}

/**
 * One session event in wire form.
 *
 * `data` stays opaque here; the fold layer owns event-kind recognition, and an unknown `type`
 * has always been something this client passes through rather than fails on.
 */
@Serializable
data class SessionWireEvent(
    @SerialName("type") val type: String,
    @SerialName("seq") val seq: Int,
    @SerialName("time") val time: Long,
    @SerialName("data") val data: JsonElement = JsonObject(emptyMap()),
    /**
     * Marks an event a reader may skip when it does not recognise `type`. Absent means required.
     * This client renders unknown events as passthrough rows either way, so the marker is carried
     * rather than acted on.
     */
    @SerialName("ignorable") val ignorable: Boolean? = null,
    /** Present when this event cites earlier ones as sources; carries their sequence numbers. */
    @SerialName("sourceEventSeqs") val sourceEventSeqs: List<Int>? = null,
    /** Surface-mutation intent: `"append"` or `{op: "replace", start, end}`. */
    @SerialName("surfaceOp") val surfaceOp: JsonElement? = null,
)

/**
 * One history record.
 *
 * Since harness 0.1.3 there is exactly one record class: an ordinary event. The packed `chunks`
 * run of 0.1.2 is gone because format v2 has nothing left to pack — the per-token deltas it
 * compressed are no longer durable events; each model attempt is one settlement event that embeds
 * its own compact stream. The sealed shape is kept so a future record class still decodes as an
 * event rather than dropping and opening a sequence gap.
 */
@Serializable(with = SessionHistoryRecordSerializer::class)
sealed class SessionHistoryRecord {
    /** The wire discriminant; `event` is the only value a 0.1.3 host sends. */
    abstract val type: String

    /** The inner event. */
    abstract val event: SessionWireEvent

    /** One ordinary logical event. */
    @Serializable
    data class Event(
        @SerialName("type") override val type: String = "event",
        @SerialName("event") override val event: SessionWireEvent,
    ) : SessionHistoryRecord()
}

/** Custom serializer for [SessionHistoryRecord]; every record class reads as an event. */
object SessionHistoryRecordSerializer : KSerializer<SessionHistoryRecord> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionHistoryRecord") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: SessionHistoryRecord) {
        val json = when (value) {
            is SessionHistoryRecord.Event ->
                encodeToJsonElement(SessionHistoryRecord.Event.serializer(), value)
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionHistoryRecord {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        // An unrecognised record class is read as an ordinary event rather than dropped: the
        // outer discriminator only names the class, and the inner value is shaped the same either
        // way. Dropping it would open a sequence gap and send the journal into a repair it cannot
        // resolve.
        return decodeFromJsonElement(SessionHistoryRecord.Event.serializer(), json)
    }
}

/**
 * Named arguments of `session/page`.
 *
 * [throughSeq] is mandatory and comes from the matching `session/follow` generation's opening
 * cursor: it fixes the read at the same log cut, which is what lets a page and the live tail be
 * joined without a gap. `-1` denotes an empty log. [beforeSeq] selects an *older* page before
 * that cut and cannot stand in for the cursor.
 */
@Serializable
data class SessionPageRequest(
    @SerialName("address") val address: SessionAddress,
    @SerialName("throughSeq") val throughSeq: Int,
    /** Absent for the tail page, which must end exactly at [throughSeq]. */
    @SerialName("beforeSeq") val beforeSeq: Int? = null,
    /** Caps user/assistant message count without dropping tools or state between them. */
    @SerialName("maxMessages") val maxMessages: Int? = null,
)

/** One contiguous backwards page of a session log. */
@Serializable
data class SessionPage(
    @SerialName("records") val records: List<SessionHistoryRecord> = emptyList(),
    @SerialName("hasMore") val hasMore: Boolean = false,
)

/**
 * Named arguments of the `session/follow` stream.
 *
 * [assistantStream] opts this follower into the process-local assistant frames — the only way
 * to see a reply while it is being written. It is declared as `true | undefined` upstream, so a
 * follower that does not want them omits the key rather than sending `false`; WireJson drops the
 * null.
 */
@Serializable
data class SessionFollowRequest(
    @SerialName("address") val address: SessionAddress,
    @SerialName("maxMessages") val maxMessages: Int? = null,
    @SerialName("assistantStream") val assistantStream: Boolean? = null,
)

// ============================================================================================
// Assistant stream (harness 0.1.3)
// ============================================================================================

/**
 * One active model attempt as a reconnect opening snapshot describes it.
 *
 * [stream] is the compact record list the attempt had accumulated at the opening revision —
 * the same encoding an `assistant/message` settlement embeds — and [nextIndex] is the dense
 * position the next live [SessionAssistantStreamFrame.Chunk] will carry. A follower expands the
 * stream (`core/session/AssistantStream.kt`) to rebuild the partial reply it missed.
 */
@Serializable
data class SessionAssistantStreamAttempt(
    @SerialName("attemptId") val attemptId: String,
    /** Last durable session seq observed when this attempt started; `-1` before any event. */
    @SerialName("startedAfterSeq") val startedAfterSeq: Int,
    @SerialName("turn") val turn: Int,
    @SerialName("step") val step: Int,
    @SerialName("nextIndex") val nextIndex: Int,
    @SerialName("stream") val stream: JsonArray = JsonArray(emptyList()),
)

/** Complete process-local assistant state at one follow opening. */
@Serializable
data class SessionAssistantStreamBaseline(
    @SerialName("revision") val revision: Int = 0,
    @SerialName("activeAttempt") val activeAttempt: SessionAssistantStreamAttempt? = null,
)

/**
 * How one attempt ended, as the terminal [SessionAssistantStreamFrame.End] reports it.
 *
 * [Committed] names the durable settlement the attempt became — an `assistant/message` when it
 * produced a surface message, an `assistant/attempt` when it did not — and its seq, which is the
 * client's cue that the transient rows are now redundant. [Abandoned] means no durable event will
 * follow at all.
 */
@Serializable(with = AssistantStreamOutcomeSerializer::class)
sealed class AssistantStreamOutcome {
    /** The wire discriminant. */
    abstract val kind: String

    @Serializable
    data class Committed(
        @SerialName("kind") override val kind: String = "committed",
        /** 'assistant/message' | 'assistant/attempt'. */
        @SerialName("eventType") val eventType: String,
        @SerialName("seq") val seq: Int,
    ) : AssistantStreamOutcome()

    @Serializable
    data class Abandoned(
        @SerialName("kind") override val kind: String = "abandoned",
    ) : AssistantStreamOutcome()

    /** An outcome of an unknown `kind`, preserved verbatim. */
    data class Unknown(
        override val kind: String,
        val raw: JsonElement,
    ) : AssistantStreamOutcome()
}

/** Custom `kind`-dispatching serializer for [AssistantStreamOutcome]. */
object AssistantStreamOutcomeSerializer : KSerializer<AssistantStreamOutcome> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AssistantStreamOutcome") {
        element("kind", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: AssistantStreamOutcome) {
        val json: JsonElement = when (value) {
            is AssistantStreamOutcome.Committed ->
                encodeToJsonElement(AssistantStreamOutcome.Committed.serializer(), value)
            is AssistantStreamOutcome.Abandoned ->
                encodeToJsonElement(AssistantStreamOutcome.Abandoned.serializer(), value)
            is AssistantStreamOutcome.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): AssistantStreamOutcome {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: "") {
            "committed" -> decodeFromJsonElement(AssistantStreamOutcome.Committed.serializer(), json)
            "abandoned" -> decodeFromJsonElement(AssistantStreamOutcome.Abandoned.serializer(), json)
            else -> AssistantStreamOutcome.Unknown(kind, json)
        }
    }
}

/**
 * One process-local assistant frame, delivered on `session/follow` to a follower that opted in.
 *
 * Frames are dense: every one carries the host's monotonically increasing [revision], a chunk
 * carries its position in the attempt, and the terminal frame carries the count it closes. A
 * follower that sees a hole cannot know what it missed and drops the attempt's transient rows;
 * the durable settlement arrives on the ordinary event path regardless, so nothing is lost —
 * only the live preview of it.
 */
@Serializable(with = SessionAssistantStreamFrameSerializer::class)
sealed class SessionAssistantStreamFrame {
    /** The wire discriminant. */
    abstract val type: String

    /** Identity of the attempt, unique within one agent lifecycle. */
    abstract val attemptId: String

    /** Host-side frame counter; `1` opens a fresh agent lifecycle. */
    abstract val revision: Int

    /** A model attempt began. */
    @Serializable
    data class Start(
        @SerialName("type") override val type: String = "start",
        @SerialName("attemptId") override val attemptId: String,
        @SerialName("revision") override val revision: Int,
        /** Last durable seq when the attempt started; a settlement at or below it is not this attempt's. */
        @SerialName("startedAfterSeq") val startedAfterSeq: Int,
        @SerialName("turn") val turn: Int,
        @SerialName("step") val step: Int,
    ) : SessionAssistantStreamFrame()

    /** One raw model chunk, in the same shape `assistant/chunk` used to carry. */
    @Serializable
    data class Chunk(
        @SerialName("type") override val type: String = "chunk",
        @SerialName("attemptId") override val attemptId: String,
        @SerialName("revision") override val revision: Int,
        /** Dense position within the attempt, from 0. */
        @SerialName("index") val index: Int,
        @SerialName("time") val time: Long,
        @SerialName("chunk") val chunk: JsonElement,
    ) : SessionAssistantStreamFrame()

    /** The attempt ended. [index] is the number of chunk frames this marker closes. */
    @Serializable
    data class End(
        @SerialName("type") override val type: String = "end",
        @SerialName("attemptId") override val attemptId: String,
        @SerialName("revision") override val revision: Int,
        @SerialName("index") val index: Int,
        @SerialName("outcome") val outcome: AssistantStreamOutcome,
    ) : SessionAssistantStreamFrame()

    /** A frame of an unknown `type`, preserved verbatim. */
    data class Unknown(
        override val type: String,
        override val attemptId: String,
        override val revision: Int,
        val raw: JsonElement,
    ) : SessionAssistantStreamFrame()
}

/** Custom `type`-dispatching serializer for [SessionAssistantStreamFrame]. */
object SessionAssistantStreamFrameSerializer : KSerializer<SessionAssistantStreamFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionAssistantStreamFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: SessionAssistantStreamFrame) {
        val json: JsonElement = when (value) {
            is SessionAssistantStreamFrame.Start ->
                encodeToJsonElement(SessionAssistantStreamFrame.Start.serializer(), value)
            is SessionAssistantStreamFrame.Chunk ->
                encodeToJsonElement(SessionAssistantStreamFrame.Chunk.serializer(), value)
            is SessionAssistantStreamFrame.End ->
                encodeToJsonElement(SessionAssistantStreamFrame.End.serializer(), value)
            is SessionAssistantStreamFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionAssistantStreamFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "start" -> decodeFromJsonElement(SessionAssistantStreamFrame.Start.serializer(), json)
            "chunk" -> decodeFromJsonElement(SessionAssistantStreamFrame.Chunk.serializer(), json)
            "end" -> decodeFromJsonElement(SessionAssistantStreamFrame.End.serializer(), json)
            else -> SessionAssistantStreamFrame.Unknown(
                type = type,
                attemptId = json["attemptId"]?.jsonPrimitive?.contentOrNull ?: "",
                revision = json["revision"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                raw = json,
            )
        }
    }
}

/**
 * One frame of the `session/follow` stream.
 *
 * Every generation opens with exactly one [Snapshot] — including after a reconnect, which sends
 * a complete replacement rather than a delta. There is no `afterSeq`: the protocol has no way to
 * resume mid-stream, and the client repairs by paging instead. A follower that opted in also
 * receives [AssistantStream] frames, interleaved with durable [Entry] frames in arrival order.
 */
@Serializable(with = SessionFollowFrameSerializer::class)
sealed class SessionFollowFrame {
    /** The complete opening window. */
    @Serializable
    data class Snapshot(
        @SerialName("type") val type: String = "snapshot",
        /** The host's `SessionWireHeader`; read leniently, since nothing here depends on it. */
        @SerialName("header") val header: JsonElement = JsonObject(emptyMap()),
        /** The log cut this generation opened at; pass it as `throughSeq` when paging. */
        @SerialName("cursor") val cursor: Int,
        @SerialName("records") val records: List<SessionHistoryRecord> = emptyList(),
        @SerialName("hasMore") val hasMore: Boolean = false,
        /** Projection baseline no later than [cursor]; merge by watermark against live updates. */
        @SerialName("projections") val projections: JsonObject = JsonObject(emptyMap()),
        /**
         * Present exactly when the follower opted in. The host promises it on every opted-in
         * snapshot, so its absence there means the host predates the feature.
         */
        @SerialName("assistantStream") val assistantStream: SessionAssistantStreamBaseline? = null,
    ) : SessionFollowFrame()

    /** One durable event appended after the opening cursor. */
    data class Entry(val record: SessionHistoryRecord) : SessionFollowFrame()

    /** One process-local assistant frame; never a durable event. */
    data class AssistantStream(val frame: SessionAssistantStreamFrame) : SessionFollowFrame()
}

/**
 * Custom serializer for [SessionFollowFrame].
 *
 * The union is not uniformly tagged: the opening frame carries `type: "snapshot"`, an assistant
 * frame carries `type: "assistant-stream"` around its own `frame`, and every other item is a
 * bare history record whose own `type` names the record class. Discriminating on the two named
 * kinds specifically — rather than assuming a closed tag set — is what keeps a future record
 * class from being mistaken for an opening frame.
 */
object SessionFollowFrameSerializer : KSerializer<SessionFollowFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionFollowFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: SessionFollowFrame) {
        val json: JsonElement = when (value) {
            is SessionFollowFrame.Snapshot ->
                encodeToJsonElement(SessionFollowFrame.Snapshot.serializer(), value)
            is SessionFollowFrame.Entry ->
                encodeToJsonElement(SessionHistoryRecordSerializer, value.record)
            is SessionFollowFrame.AssistantStream -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("assistant-stream"),
                    "frame" to encodeToJsonElement(SessionAssistantStreamFrameSerializer, value.frame),
                ),
            )
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionFollowFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (json["type"]?.jsonPrimitive?.contentOrNull) {
            "snapshot" -> decodeFromJsonElement(SessionFollowFrame.Snapshot.serializer(), json)
            "assistant-stream" -> SessionFollowFrame.AssistantStream(
                decodeFromJsonElement(
                    SessionAssistantStreamFrameSerializer,
                    json["frame"] ?: throw IllegalArgumentException("assistant-stream frame carried no frame"),
                ),
            )
            else -> SessionFollowFrame.Entry(decodeFromJsonElement(SessionHistoryRecordSerializer, json))
        }
    }
}

/**
 * One frame of the host-wide `session/control` stream.
 *
 * One stream serves every live session, so a client can watch transient state without opening a
 * journal per transcript. Each generation emits exactly one [Baseline] first; queue and jobs
 * frames are complete replacement values applied last-wins, never deltas.
 */
@Serializable(with = SessionControlFrameSerializer::class)
sealed class SessionControlFrame {
    /** The wire discriminant. */
    abstract val type: String

    /** The complete opening state for every live session. */
    @Serializable
    data class Baseline(
        @SerialName("type") override val type: String = "baseline",
        @SerialName("value") val value: SessionControlBaseline,
    ) : SessionControlFrame()

    /** The authoritative pending queue for one session. */
    @Serializable
    data class Queue(
        @SerialName("type") override val type: String = "queue",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("items") val items: List<QueuedInboxItem> = emptyList(),
    ) : SessionControlFrame()

    /** The complete background-job set for one session. */
    @Serializable
    data class Jobs(
        @SerialName("type") override val type: String = "jobs",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("jobs") val jobs: List<JobView> = emptyList(),
    ) : SessionControlFrame()

    /** One projection unit's finished value and its durable watermark. */
    @Serializable
    data class Projection(
        @SerialName("type") override val type: String = "projection",
        @SerialName("sessionId") val sessionId: String,
        @SerialName("key") val key: String,
        @SerialName("value") val value: JsonElement,
        @SerialName("seq") val seq: Int,
    ) : SessionControlFrame()

    /** A frame of an unknown `type`, preserved verbatim. */
    data class Unknown(
        override val type: String,
        val raw: JsonElement,
    ) : SessionControlFrame()
}

/** The complete live-control baseline emitted once per control-stream generation. */
@Serializable
data class SessionControlBaseline(
    @SerialName("queues") val queues: Map<String, List<QueuedInboxItem>> = emptyMap(),
    @SerialName("jobs") val jobs: Map<String, List<JobView>> = emptyMap(),
    @SerialName("projections") val projections: Map<String, JsonObject> = emptyMap(),
)

/** Custom `type`-dispatching serializer for [SessionControlFrame]. */
object SessionControlFrameSerializer : KSerializer<SessionControlFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SessionControlFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: SessionControlFrame) {
        val json: JsonElement = when (value) {
            is SessionControlFrame.Baseline ->
                encodeToJsonElement(SessionControlFrame.Baseline.serializer(), value)
            is SessionControlFrame.Queue ->
                encodeToJsonElement(SessionControlFrame.Queue.serializer(), value)
            is SessionControlFrame.Jobs ->
                encodeToJsonElement(SessionControlFrame.Jobs.serializer(), value)
            is SessionControlFrame.Projection ->
                encodeToJsonElement(SessionControlFrame.Projection.serializer(), value)
            is SessionControlFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SessionControlFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "baseline" -> decodeFromJsonElement(SessionControlFrame.Baseline.serializer(), json)
            "queue" -> decodeFromJsonElement(SessionControlFrame.Queue.serializer(), json)
            "jobs" -> decodeFromJsonElement(SessionControlFrame.Jobs.serializer(), json)
            "projection" -> decodeFromJsonElement(SessionControlFrame.Projection.serializer(), json)
            else -> SessionControlFrame.Unknown(type, json)
        }
    }
}

/**
 * One frame of the `workspace/follow` stream.
 *
 * The [Order] frame is authoritative and complete: display order is never inferred from the
 * arrival order of upserts, which is what makes the list converge after a reconnect baseline.
 */
@Serializable(with = WorkspaceFollowFrameSerializer::class)
sealed class WorkspaceFollowFrame {
    /** The wire discriminant. */
    abstract val type: String

    /** The complete opening registry state. */
    @Serializable
    data class Baseline(
        @SerialName("type") override val type: String = "baseline",
        @SerialName("workspaces") val workspaces: List<WorkspaceView> = emptyList(),
        @SerialName("workspaceIds") val workspaceIds: List<String> = emptyList(),
        @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
    ) : WorkspaceFollowFrame()

    /** One workspace was added or changed. */
    @Serializable
    data class Upsert(
        @SerialName("type") override val type: String = "upsert",
        @SerialName("workspace") val workspace: WorkspaceView,
    ) : WorkspaceFollowFrame()

    /** One workspace registration was removed. */
    @Serializable
    data class Remove(
        @SerialName("type") override val type: String = "remove",
        @SerialName("workspaceId") val workspaceId: String,
    ) : WorkspaceFollowFrame()

    /** The complete, authoritative registry display order. */
    @Serializable
    data class Order(
        @SerialName("type") override val type: String = "order",
        @SerialName("workspaceIds") val workspaceIds: List<String> = emptyList(),
    ) : WorkspaceFollowFrame()

    /** The complete registry-global archived set. */
    @Serializable
    data class Archived(
        @SerialName("type") override val type: String = "archived",
        @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
    ) : WorkspaceFollowFrame()

    /** A frame of an unknown `type`, preserved verbatim. */
    data class Unknown(
        override val type: String,
        val raw: JsonElement,
    ) : WorkspaceFollowFrame()
}

/** Custom `type`-dispatching serializer for [WorkspaceFollowFrame]. */
object WorkspaceFollowFrameSerializer : KSerializer<WorkspaceFollowFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("WorkspaceFollowFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: WorkspaceFollowFrame) {
        val json: JsonElement = when (value) {
            is WorkspaceFollowFrame.Baseline ->
                encodeToJsonElement(WorkspaceFollowFrame.Baseline.serializer(), value)
            is WorkspaceFollowFrame.Upsert ->
                encodeToJsonElement(WorkspaceFollowFrame.Upsert.serializer(), value)
            is WorkspaceFollowFrame.Remove ->
                encodeToJsonElement(WorkspaceFollowFrame.Remove.serializer(), value)
            is WorkspaceFollowFrame.Order ->
                encodeToJsonElement(WorkspaceFollowFrame.Order.serializer(), value)
            is WorkspaceFollowFrame.Archived ->
                encodeToJsonElement(WorkspaceFollowFrame.Archived.serializer(), value)
            is WorkspaceFollowFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): WorkspaceFollowFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "baseline" -> decodeFromJsonElement(WorkspaceFollowFrame.Baseline.serializer(), json)
            "upsert" -> decodeFromJsonElement(WorkspaceFollowFrame.Upsert.serializer(), json)
            "remove" -> decodeFromJsonElement(WorkspaceFollowFrame.Remove.serializer(), json)
            "order" -> decodeFromJsonElement(WorkspaceFollowFrame.Order.serializer(), json)
            "archived" -> decodeFromJsonElement(WorkspaceFollowFrame.Archived.serializer(), json)
            else -> WorkspaceFollowFrame.Unknown(type, json)
        }
    }
}
