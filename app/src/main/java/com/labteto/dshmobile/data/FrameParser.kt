package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.MessageData
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionEventSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure, unit-testable decode helpers shared by [SessionStore] and the notification observer.
 *
 * Every function is lenient: malformed payloads yield `null` (or an empty field) instead of
 * throwing, because stream frames are merge-extensible and may drift.
 */

/**
 * Convert one wire event from a journal record into the raw-envelope shape the fold consumes.
 *
 * The journal's event form is already flat — type, seq, time, raw `data` — so unlike
 * [sessionEventToEnvelope] this needs no round-trip through a typed DTO. `surfaceOp` is
 * stringified for the envelope exactly as the typed path does it.
 *
 * This is the path every history page, follow snapshot and live entry takes, so anything the fold
 * needs from the envelope has to be copied here. `ignorable` is: without it the fold cannot tell
 * a harness plugin record (the translator's request and result) from an event it must show.
 */
fun wireEventToEnvelope(event: SessionWireEvent): SessionEventEnvelope = SessionEventEnvelope(
    type = event.type,
    seq = event.seq.toLong(),
    time = event.time,
    data = event.data,
    surfaceOp = event.surfaceOp?.let { raw ->
        (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    },
    ignorable = event.ignorable,
)

/**
 * Convert a typed [SessionEvent] into the raw-envelope shape the fold consumes. `data` is the
 * raw JSON of the event's payload (re-derived from the serializer so it stays a [JsonElement]
 * exactly as the fold expects); `surfaceOp` is stringified for the envelope.
 */
fun sessionEventToEnvelope(event: SessionEvent): SessionEventEnvelope {
    val json = encodeToJsonElement(SessionEventSerializer, event).jsonObject
    val data = json["data"] ?: JsonObject(emptyMap())
    val surfaceOp = json["surfaceOp"]?.let { raw ->
        (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    }
    return SessionEventEnvelope(
        type = event.type,
        seq = event.seq.toLong(),
        time = event.time,
        data = data,
        surfaceOp = surfaceOp,
        surfaceIntent = event.surfaceOp,
        sourceEventSeqs = event.sourceEventSeqs,
        ignorable = event.ignorable,
    )
}

/** Decode a raw `session/event` event object into a [SessionEventEnvelope], or null on drift. */
fun parseSessionEventEnvelope(eventJson: JsonElement): SessionEventEnvelope? =
    runCatching { sessionEventToEnvelope(decodeFromJsonElement(SessionEventSerializer, eventJson)) }
        .getOrNull()

/** Convert one authoritative queue snapshot item into the renderer-facing [QueueItem]. */
fun queuedInboxItemToQueueItem(item: QueuedInboxItem): QueueItem {
    val preview = item.message.content
        .filterIsInstance<ContentBlock.Text>()
        .firstOrNull()
        ?.text
        ?.take(120)
        .orEmpty()
    val content = encodeToJsonElement(MessageData.serializer(), item.message)
    return QueueItem(
        id = item.id,
        placement = item.placement,
        previewText = preview,
        content = content,
    )
}
