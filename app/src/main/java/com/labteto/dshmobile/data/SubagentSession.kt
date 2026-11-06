package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.SessionAddress
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Durable identity of one subagent session, as that child's own log records it.
 *
 * Harness 0.2.2 refuses a plain session address for any session whose header says
 * `origin: "subagent"` (`subagent Sessions require their durable parent address`). A child's
 * transcript therefore has to be asked for through its creator: [parentSessionId] is the
 * *immediate* parent, because that is the edge the host's address validation compares — a
 * grandchild names its own parent, not the root of the tree.
 */
data class SubagentIdentity(
    val parentSessionId: String,
    /** `one-shot` or `continuable`; [UNKNOWN_SUBAGENT_MODE] before the child's descriptor arrives. */
    val mode: String,
    /** Durable creation label, when the child's descriptor carries one. */
    val label: String? = null,
) {
    /**
     * Whether the host accepts user messages for this child. A one-shot child is terminal, and an
     * unknown mode cannot be sent to either: the delivery endpoint requires an exact
     * `continuable` address, so the composer stays read-only until the descriptor lands.
     */
    val continuable: Boolean get() = mode == SUBAGENT_CONTINUABLE_MODE
}

/** The `origin` a session header carries when the session was spawned as a subagent. */
internal const val SUBAGENT_ORIGIN: String = "subagent"

/** The `mode` an address carries before the child's own `subagent` projection is known. */
internal const val UNKNOWN_SUBAGENT_MODE: String = "unknown"

/** The only mode `subagents/prompt` accepts. */
internal const val SUBAGENT_CONTINUABLE_MODE: String = "continuable"

/**
 * The host refuses a child prompt when the child's exact direct parent Agent is not live in its
 * process. That is a fact about the child's tree, not about this connection.
 */
internal const val SUBAGENT_PARENT_UNAVAILABLE: String = "subagent/parent-unavailable"

/** An attachment on a child prompt was refused (bad media type, model without image input). */
internal const val SUBAGENT_ATTACHMENT_INVALID: String = "subagent/attachment-invalid"

/** Projection key carrying a child session's own identity descriptor (`{mode,label,seq}`). */
internal const val SUBAGENT_IDENTITY_PROJECTION: String = "subagent"

/**
 * Read a session list row's subagent identity.
 *
 * Two sources decide this and they are deliberately separate: `origin` and `parentSessionId` come
 * from the row's header, and the mode and label from its `subagent` projection — which may be
 * absent on a cold row or for a host that has not published it yet. An absent descriptor still
 * yields an identity (mode [UNKNOWN_SUBAGENT_MODE]) as long as the header says the session is a
 * child: the follow address is then valid, and the opening snapshot's own projection supplies the
 * real mode for the composer. A fork has a `parentSessionId` too, so `origin` alone decides.
 */
internal fun subagentIdentityOf(
    origin: String?,
    parentSessionId: String?,
    projection: JsonElement?,
): SubagentIdentity? {
    if (origin != SUBAGENT_ORIGIN) return null
    val parent = parentSessionId?.takeIf { it.isNotBlank() } ?: return null
    val descriptor = projection as? JsonObject
    val projectedMode = (descriptor?.get("mode") as? JsonPrimitive)?.contentOrNull
    val mode = when (projectedMode) {
        SUBAGENT_CONTINUABLE_MODE, "one-shot" -> projectedMode
        else -> UNKNOWN_SUBAGENT_MODE
    }
    val label = (descriptor?.get("label") as? JsonPrimitive)?.contentOrNull
    return SubagentIdentity(parentSessionId = parent, mode = mode, label = label)
}

/**
 * The durable address naming one session's log on the wire.
 *
 * A subagent log is only readable through its parent address; an ordinary session must keep the
 * plain address, because a host that predates the combined address protocol would not know it.
 */
internal fun sessionAddressFor(sessionId: String, identity: SubagentIdentity?): SessionAddress =
    identity?.let {
        SessionAddress.Subagent(
            parentSessionId = it.parentSessionId,
            childSessionId = sessionId,
            mode = it.mode,
        )
    } ?: SessionAddress.Session(sessionId = sessionId)

/**
 * The child identity one session list row describes, if it describes a child at all.
 *
 * A row whose `subagent` projection never arrived still tells us the session is a child from its
 * header, and [subagentIdentityOf] then yields the `unknown`-mode address the host accepts. A fork
 * has a `parentSessionId` too, so lineage alone never makes an ordinary row a child.
 */
internal fun openSubagentFor(row: SessionRow?): SubagentIdentity? =
    row?.let { it.subagent ?: subagentIdentityOf(it.origin, it.parentSessionId, null) }

/**
 * What one composer send should do for the session it targets.
 *
 * Kept as its own decision so the read-only rule is one testable place: the host refuses
 * `session/prompt` on a child and `subagents/prompt` only takes a continuable one.
 */
internal sealed interface PromptTarget {
    /** An ordinary session: `session/prompt` as always. */
    data object Ordinary : PromptTarget

    /** A continuable child: deliver through `subagents/prompt`. */
    data class Child(val identity: SubagentIdentity) : PromptTarget

    /** A child this client cannot message: the UI offers no composer. */
    data class ReadOnly(val identity: SubagentIdentity) : PromptTarget
}

/** Decide how a send to the open session has to travel. */
internal fun promptTargetFor(identity: SubagentIdentity?): PromptTarget = when {
    identity == null -> PromptTarget.Ordinary
    identity.continuable -> PromptTarget.Child(identity)
    else -> PromptTarget.ReadOnly(identity)
}
