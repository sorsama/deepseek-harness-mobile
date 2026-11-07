package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Goals-domain DTOs, ported from `packages/host/apiproxy/src/api/goals.schema.ts` and
 * `packages/goal/goal/src/types.ts` (v0.1.1-rc.2). Mutations only: the read side is the
 * 'goal' session projection; every non-clear mutation acknowledges with the new CAS ref.
 */

/** Durable continuation phase. */
@Serializable
enum class GoalPhase {
    /** A phase this build does not know; shown without a verdict rather than failing the goal. */
    UNKNOWN,

    @SerialName("active")
    ACTIVE,

    @SerialName("paused")
    PAUSED,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("complete")
    COMPLETE,
}

/** Compare-and-set identity for one exact goal revision. */
@Serializable
data class GoalRef(
    /** Stable goal identity. */
    @SerialName("id") val id: String,
    /** Positive revision; every durable mutation increments it. */
    @SerialName("revision") val revision: Int,
)

/** Machine-routable and human-readable explanation for a blocked goal. */
@Serializable
data class GoalBlockReason(
    /** Stable lower-kebab-case classification chosen by the blocking policy. */
    @SerialName("code") val code: String,
    /** Non-empty explanation shown to humans and models. */
    @SerialName("message") val message: String,
)

/** Full durable state written by every non-clear goal mutation. */
@Serializable
data class GoalSnapshot(
    @SerialName("id") val id: String,
    @SerialName("revision") val revision: Int,
    /** Human-requested completion objective. */
    @SerialName("objective") val objective: String,
    /** Durable lifecycle phase. */
    @SerialName("phase") val phase: GoalPhase = GoalPhase.UNKNOWN,
    /** Present exactly while `phase` is `blocked`. */
    @SerialName("blockedReason") val blockedReason: GoalBlockReason? = null,
    /** Total admitted goal-round cap. */
    @SerialName("maxGoalRounds") val maxGoalRounds: Int,
)

/** The `{ ref }` acknowledgement value of every non-clear goal mutation. */
@Serializable
data class GoalRefValue(
    @SerialName("ref") val ref: GoalRef,
)

// ---- goal.* request payloads ----

/** Request payload of `goal.create`. */
@Serializable
data class GoalCreateRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("objective") val objective: String,
    @SerialName("maxGoalRounds") val maxGoalRounds: Int? = null,
)

/** Request payload of `goal.edit` (at least one of objective / maxGoalRounds). */
@Serializable
data class GoalEditRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("ref") val ref: GoalRef,
    @SerialName("objective") val objective: String? = null,
    @SerialName("maxGoalRounds") val maxGoalRounds: Int? = null,
)

/** Request payload of `goal.pause`. */
@Serializable
data class GoalPauseRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("ref") val ref: GoalRef,
)

/** Request payload of `goal.resume`. */
@Serializable
data class GoalResumeRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("ref") val ref: GoalRef,
)

/** Request payload of `goal.complete`. */
@Serializable
data class GoalCompleteRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("ref") val ref: GoalRef,
)

/** Request payload of `goal.clear`. */
@Serializable
data class GoalClearRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("ref") val ref: GoalRef,
)

// ---- goal.* response values ----

/** Value of `goal.create`. */
@Serializable
data class GoalCreateValue(
    @SerialName("ref") val ref: GoalRef,
)

/** Value of `goal.edit`. */
@Serializable
data class GoalEditValue(
    @SerialName("ref") val ref: GoalRef,
)

/** Value of `goal.pause`. */
@Serializable
data class GoalPauseValue(
    @SerialName("ref") val ref: GoalRef,
)

/** Value of `goal.resume`. */
@Serializable
data class GoalResumeValue(
    @SerialName("ref") val ref: GoalRef,
)

/** Value of `goal.complete`. */
@Serializable
data class GoalCompleteValue(
    @SerialName("ref") val ref: GoalRef,
)

/** Value of `goal.clear`. */
@Serializable
data class GoalClearValue(
    @SerialName("cleared") val cleared: Boolean = true,
)

/**
 * Value of every goal mutation except `clear`.
 *
 * 0.1.1 acknowledged a mutation with a bare `{ ref }`; 0.1.2 answers the updated view, so the
 * caller no longer has to re-read a projection to show the result of its own edit.
 *
 * Extends the durable snapshot with replay counters and process-local activation. `activation`
 * is never persisted — a goal reloaded from a cold log has none.
 */
@Serializable
data class GoalView(
    @SerialName("id") val id: String,
    @SerialName("revision") val revision: Int,
    @SerialName("objective") val objective: String,
    @SerialName("phase") val phase: GoalPhase = GoalPhase.UNKNOWN,
    @SerialName("blockedReason") val blockedReason: GoalBlockReason? = null,
    @SerialName("maxGoalRounds") val maxGoalRounds: Int,
    /** Highest admitted round number for this goal. */
    @SerialName("roundsStarted") val roundsStarted: Int = 0,
    /** Epoch milliseconds of the create mutation. */
    @SerialName("createdAt") val createdAt: Long = 0,
    /** Epoch milliseconds of the latest mutation. */
    @SerialName("updatedAt") val updatedAt: Long = 0,
    /** Process-local continuation eligibility; opaque here and never persisted. */
    @SerialName("activation") val activation: JsonElement? = null,
)
