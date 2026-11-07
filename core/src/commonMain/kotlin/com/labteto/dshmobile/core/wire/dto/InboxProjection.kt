package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The pending-queue view, rebuilt from the `inbox` projection.
 *
 * Through harness 0.1.6-alpha.1 the host computed this for us: `session/control` carried a
 * `queues` map in its baseline and a `{"type":"queue"}` frame per change, each item already
 * carrying its `placement`. Current master deletes both — `SessionControlBaseline` is
 * `{jobs, projections}` now, and `SessionQueuedItem` is gone — and publishes the raw agent inbox
 * as an ordinary projection instead:
 *
 * ```json
 * {"next-turn": [<UserMessage>, …], "next-step": [<UserMessage>, …]}
 * ```
 *
 * So the placement the host used to hand over has to be derived here, by exactly the rule the
 * deleted host code used: everything waiting for the next turn is `queued`, and everything waiting
 * for the next step is `steering` when a person wrote it and `context` when something else did.
 * Getting that wrong would not fail loudly — it would put a person's steering message in the
 * wrong section of the dock — so it is one function with one test rather than a branch inside the
 * store.
 *
 * A member that will not decode is dropped rather than failing the batch: one malformed pending
 * message should cost that row, not the whole dock.
 */
object InboxProjection {

    private const val NEXT_TURN = "next-turn"
    private const val NEXT_STEP = "next-step"

    /** Placement for a message the agent will take at its next turn boundary. */
    const val QUEUED = "queued"

    /** Placement for a person's message the agent will take at its next step. */
    const val STEERING = "steering"

    /** Placement for a non-human message the agent will take at its next step. */
    const val CONTEXT = "context"

    /**
     * Rebuild the pending queue from one `inbox` projection value.
     *
     * @param value the projection value, as it arrives on `session/control` or in a
     *   `session/follow` snapshot's `projections.values`.
     * @return pending items in inbox order — next-turn first, then next-step — or an empty list
     *   when the value is absent or not the expected shape.
     */
    fun itemsFrom(value: JsonElement?): List<QueuedInboxItem> {
        val root = value as? JsonObject ?: return emptyList()
        return listFrom(root[NEXT_TURN]) { QUEUED } + listFrom(root[NEXT_STEP], ::stepPlacement)
    }

    private fun listFrom(
        element: JsonElement?,
        placement: (JsonObject) -> String,
    ): List<QueuedInboxItem> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { member ->
            val message = member as? JsonObject ?: return@mapNotNull null
            val decoded = runCatching {
                WireJson.decodeFromJsonElement(MessageData.serializer(), message)
            }.getOrNull() ?: return@mapNotNull null
            QueuedInboxItem(id = decoded.id, placement = placement(message), message = decoded)
        }
    }

    /**
     * A next-step message is steering only when a person wrote it.
     *
     * The host tags a browser-submitted prompt `{"kind":"user"}` — or `"user-rpc"`-shaped, which
     * still spells `kind` as `user` — and everything a tool or plugin injected with some other
     * kind. Anything unrecognised reads as context, which is the conservative half: presenting a
     * plugin's injected note as if the person had typed it is the worse mistake.
     */
    private fun stepPlacement(message: JsonObject): String {
        val kind = (message["source"] as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull
        return if (kind == "user") STEERING else CONTEXT
    }
}
