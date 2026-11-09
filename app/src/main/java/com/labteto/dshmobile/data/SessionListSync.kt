package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The rules that keep the sessions drawer in step with sessions changed somewhere else: the
 * desktop harness, `dsh` on the computer, another phone.
 *
 * A session created elsewhere reaches this client as `api-session/added` with `blank: true`, and
 * the drawer hides blank rows. Nothing but its first message makes it visible, and that message
 * arrives for a session nobody here is following, as a status flip, an activity stamp and a
 * `sessionListMetadata` projection frame. The store used to read all three only for the open
 * session, so the row stayed hidden until a reconnect re-read the list. The same blind spot hid a
 * rename. These are the small pure pieces the store uses to close it, kept apart so they can be
 * tested without a connection.
 */

/**
 * What one read of the session list found, for the caller that asked for it.
 *
 * The store reports a failure through [SessionStore.connectionError], which the chat screen draws
 * as a banner *behind* the drawer. A pull-to-refresh happens in the drawer, over that banner, so
 * the gesture hands the outcome back as well and the drawer can say it where the pull happened.
 */
sealed interface SessionListRefresh {
    /** The host answered; the mirror now holds its list. */
    data object Done : SessionListRefresh

    /**
     * The read did not happen. [reason] is the host's own message when it gave one, and null when
     * there was nothing to ask: no connection, or no harness selected.
     */
    data class Failed(val reason: String? = null) : SessionListRefresh
}

/**
 * Decides whether a background list read should run, so the fallback refresh cannot hammer the
 * host.
 *
 * At most one read is in flight, and a read that was not forced waits [minIntervalMs] after the
 * previous one began. Reads that are refused are simply dropped: the caller asks again on its next
 * trigger, and a read that begins later sees everything the dropped one would have.
 */
internal class RefreshThrottle(
    private val minIntervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastBeganAt: Long? = null
    private var inFlight = false

    /** True when the caller may read now; it must then call [end] when the read finishes. */
    @Synchronized
    fun begin(force: Boolean = false): Boolean {
        if (inFlight) return false
        val t = now()
        val last = lastBeganAt
        if (!force && last != null && t - last in 0 until minIntervalMs) return false
        inFlight = true
        lastBeganAt = t
        return true
    }

    @Synchronized
    fun end() {
        inFlight = false
    }
}

/**
 * Whether a `sessionListMetadata` projection value says its session has had a prompt.
 *
 * Null when the value is absent or does not carry a boolean `blank`, so a frame this client cannot
 * read changes nothing. Only `false` is ever acted on: a session does not become blank again.
 */
internal fun listMetadataBlank(value: JsonElement?): Boolean? =
    (value as? JsonObject)?.get("blank")?.jsonPrimitive?.booleanOrNull

/** A session title from a `title` projection value: the bare string, or `{title}`. */
internal fun titleOf(value: JsonElement?): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> (value["title"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

/**
 * The title a list row should show after a list read.
 *
 * A sequenced read is the host's current word, so it replaces what we held: that is how a rename
 * made elsewhere reaches this client. A cached read is the stored hint the host warns can be
 * stale, and an absent title says nothing, so both defer to what we already know.
 */
internal fun listedTitle(held: String?, listed: String?, listedIsCurrent: Boolean): String? =
    if (listed != null && listedIsCurrent) listed else held ?: listed

/**
 * Whether a list row should be hidden as blank.
 *
 * A session this client has seen run, receive a prompt or report a prompt in its metadata is never
 * blank again, even if a later row says so: list reads and `session-added` summaries can be built
 * from a hint older than what the control stream already told us, and re-hiding a conversation
 * that was just shown is worse than showing it a moment early.
 */
internal fun effectiveBlank(listedBlank: Boolean, engaged: Boolean): Boolean = listedBlank && !engaged

/** [row] after the session is known to have had a prompt: visible in the drawer. */
internal fun SessionRow.engaged(): SessionRow = if (blank) copy(blank = false) else this
