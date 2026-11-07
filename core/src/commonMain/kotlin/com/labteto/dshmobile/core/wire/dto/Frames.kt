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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shapes carried by the host's live state, ported from `packages/api/session-controller`
 * and `packages/interaction/user-questions` (v0.1.2-alpha.1).
 *
 * This file used to hold the two downlink frame unions as well. Harness 0.1.2 deleted both
 * sockets they belonged to: session queue, jobs and projections now arrive as
 * [SessionControlFrame] items on `session/control`, the registry frames as
 * [WorkspaceFollowFrame] items on `workspace/follow`, and approvals and questions as
 * agent-scoped waterfalls on `$events`. What remains here is the payload vocabulary those
 * frames carry, which did not change.
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

    /**
     * A lifecycle state this build does not know.
     *
     * The host's status union is closed today, but a new member would otherwise fail the whole
     * [JobView] and take the rest of the session's job list with it — one unrecognised word
     * costing every other row. `coerceInputValues` lands here instead, because the field below
     * defaults to it.
     */
    UNKNOWN,
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
    /** Current lifecycle state; an unrecognised one reads as [JobStatus.UNKNOWN]. */
    @SerialName("status") val status: JobStatus = JobStatus.UNKNOWN,
    /** Kind-specific status detail ('exit code: 3'), present once the producer supplied one. */
    @SerialName("detail") val detail: String? = null,
    /** Harness 0.1.7: the producer's live progress line (`3/10`), cleared at settlement. */
    @SerialName("progress") val progress: String? = null,
    /** Harness 0.1.7: the owning session; absent for an unowned job every session can see. */
    @SerialName("owner") val owner: String? = null,
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

/**
 * A caller-declared presentation intent; tagged so further intents can be added.
 *
 * Harness 0.2.2 grew a second tag, `approval` (produced by the experimental CUA plugin), and the
 * upstream union is explicitly open: a UI that does not know a tag renders the generic option list
 * and the answer encoding is identical either way — an intent changes presentation, never the
 * protocol. A closed decode would instead throw on the whole [AskUserQuestionItem], and both the
 * `$events` waterfall and the durable `userQuestions` projection then drop the question card
 * entirely, leaving the turn blocked until timeout. So any unrecognised `kind` lands in [Other]
 * with its complete object preserved; [PlanReview] stays the only tag this build interprets.
 */
@Serializable(with = AskUserQuestionIntentSerializer::class)
sealed class AskUserQuestionIntent {
    /** The wire discriminant. */
    abstract val kind: String

    /** A plan submitted for review: `detail` is the plan markdown, the decision approves or declines it. */
    @Serializable
    data class PlanReview(
        /** The option label that approves the plan; every other option declines it. */
        @SerialName("approve") val approve: String,
    ) : AskUserQuestionIntent() {
        override val kind: String get() = "plan-review"
    }

    /**
     * An intent of a `kind` this build does not model — `approval` today, any future tag after it —
     * preserved verbatim so the surrounding question keeps its id, wording and options and stays
     * answerable through the generic flow. Never constructed for [PlanReview]'s tag.
     */
    data class Other(
        override val kind: String,
        val raw: JsonElement,
    ) : AskUserQuestionIntent()
}

/** Custom `kind`-dispatching serializer for [AskUserQuestionIntent]; an unknown kind is kept, not failed. */
object AskUserQuestionIntentSerializer : KSerializer<AskUserQuestionIntent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AskUserQuestionIntent") {
        element("kind", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: AskUserQuestionIntent) {
        val json: JsonElement = when (value) {
            is AskUserQuestionIntent.PlanReview ->
                encodeToJsonElement(AskUserQuestionIntent.PlanReview.serializer(), value)
                    .withKind(value.kind)
            is AskUserQuestionIntent.Other -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): AskUserQuestionIntent {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: ""
        return when (kind) {
            "plan-review" -> decodeFromJsonElement(AskUserQuestionIntent.PlanReview.serializer(), json)
            else -> AskUserQuestionIntent.Other(kind, json)
        }
    }
}

/** The concrete serializer omits the computed [AskUserQuestionIntent.kind]; restore it for the wire. */
private fun JsonElement.withKind(kind: String): JsonElement = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        putAll(this@withKind.jsonObject)
        put("kind", JsonPrimitive(kind))
    },
)

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
