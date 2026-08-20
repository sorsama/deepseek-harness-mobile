package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Goals-domain DTOs, ported from `packages/host/apiproxy/src/api/goals.schema.ts` and
 * `packages/goal/goal/src/types.ts` (v0.1.0-rc.8). Mutations only: the read side is the
 * 'goal' session projection; every non-clear mutation acknowledges with the new CAS ref.
 */

/** Durable continuation phase. */
@Serializable
enum class GoalPhase {
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
    @SerialName("phase") val phase: GoalPhase,
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
