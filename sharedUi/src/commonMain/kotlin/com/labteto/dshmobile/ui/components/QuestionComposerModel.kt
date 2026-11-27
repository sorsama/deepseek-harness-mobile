package com.labteto.dshmobile.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswerItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption

/**
 * The question composer's answer logic, kept out of the Compose layer so it can be tested — a port
 * of the harness web client's `QuestionFlow`
 * (`packages/client/ui-user-questions/src/client/QuestionComposer.tsx`) and its `planReviewOf`
 * (`.../src/client/contract/slots.ts`).
 *
 * The encoding is not a matter of taste. The host validates an answer batch against the exact
 * request it resolves (`matchesQuestions`, `packages/host/apiproxy/src/api-proxy.ts`) and refuses
 * the *whole* batch on any single violation — and a refusal is not a retry: the receipt says
 * `bad-response`, the wait stays pending, and the `ask_user_question` call that opened it never
 * unblocks. So the rules below are the mirror of that function, and the tests pin them one by one.
 */

/** What the user has picked or typed for one question, before it is encoded for the wire. */
@kotlinx.serialization.Serializable
internal data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    /** Deliberately passed over: still answered, with an empty selection. */
    val skipped: Boolean = false,
)

/** An option label split into what is shown and whether the asker marked it recommended. */
internal data class OptionLabel(val display: String, val recommended: Boolean)

/**
 * Unicode whitespace, spelled out because Java's `\s` is ASCII-only and JavaScript's is not.
 *
 * The harness matches with `\s`, which there covers U+3000 and its relatives, so a label written
 * with an ideographic space before the marker is a recommendation in the web client. Transcribing
 * the regex literally would leave the marker showing raw in exactly the locale that writes it that
 * way.
 */
private const val UNICODE_SPACE =
    "[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"

/**
 * A trailing recommendation marker, in either language and either width of parenthesis. The
 * `ask_user_question` tool tells the model to write one — "If you recommend one, put it first and
 * append (Recommended) to that label" — so it arrives on ordinary traffic and has to be read as a
 * marker rather than shown as part of the choice.
 */
private val RECOMMENDED_SUFFIX = Regex(
    "$UNICODE_SPACE*(?:\\((?:recommended|推荐)\\)|（(?:recommended|推荐)）)$UNICODE_SPACE*$",
    RegexOption.IGNORE_CASE,
)

/**
 * Split the recommendation marker off a label for display.
 *
 * The split is presentation only: the label that goes back on the wire is the one the asker wrote,
 * because the host checks every selection against its own option labels and a stripped one is not
 * among them.
 */
internal fun parseRecommendedLabel(label: String): OptionLabel {
    val stripped = RECOMMENDED_SUFFIX.replace(label, "")
    return if (stripped == label) OptionLabel(label, false) else OptionLabel(stripped, true)
}

/** Whether the user gave this question anything at all. Drives the continue button. */
internal fun QuestionDraft.answered(): Boolean = selected.isNotEmpty() || custom.isNotBlank()

/** Answered, or deliberately skipped. Drives the batch submit. */
internal fun QuestionDraft.completed(): Boolean = answered() || skipped

/**
 * Pick or unpick an option.
 *
 * Multi-select toggles and leaves any free text alone. Single-select replaces, and clears the free
 * text with it: on such a question the two are alternatives, not additions, and an answer carrying
 * both is refused.
 */
internal fun QuestionDraft.choose(label: String, multiSelect: Boolean): QuestionDraft =
    if (multiSelect) {
        copy(
            selected = if (label in selected) selected - label else selected + label,
            skipped = false,
        )
    } else {
        QuestionDraft(selected = listOf(label), custom = "", skipped = false)
    }

/** Type into the free-form field; on a single-select question that supersedes the choice. */
internal fun QuestionDraft.withCustom(text: String, multiSelect: Boolean): QuestionDraft = copy(
    selected = if (multiSelect) selected else emptyList(),
    custom = text,
    skipped = false,
)

/** The question a submit should jump back to, or -1 when the batch is complete. */
internal fun firstIncomplete(drafts: List<QuestionDraft>): Int =
    drafts.indexOfFirst { !it.completed() }

/** Where a single-select pick lands next: the following question, or nowhere on the last one. */
internal fun advanceFrom(index: Int, count: Int): Int = if (index < count - 1) index + 1 else index

/**
 * Encode the local drafts as the answer batch the host will accept.
 *
 * Every clause here answers to a rule in `matchesQuestions`:
 *
 * - one answer per question, in the order asked, carrying that question's own id — the host pairs
 *   them positionally, not by id, and refuses a batch of a different length outright;
 * - `selected` de-duplicated and restricted to labels the question actually offered, which means a
 *   question with no options carries no selection at all;
 * - `custom` present only when it is non-blank after trimming, because a blank one is a refusal;
 * - a single-select answer carrying free text sends *no* selection, because the host refuses an
 *   answer that carries both, and refuses more than one label besides;
 * - a skipped question sends an empty selection and no `custom`, which is how the harness's own
 *   composer says the user declined that one.
 */
internal fun encodeAnswers(
    questions: List<AskUserQuestionItem>,
    drafts: List<QuestionDraft>,
): AskUserQuestionAnswer = AskUserQuestionAnswer(
    questions.mapIndexed { index, item ->
        val draft = drafts.getOrElse(index) { QuestionDraft() }
        if (draft.skipped) return@mapIndexed AskUserQuestionAnswerItem(item.id)
        val custom = draft.custom.trim()
        val offered = item.options.orEmpty().mapTo(mutableSetOf()) { it.label }
        val picked = draft.selected.distinct().filter { it in offered }
        AskUserQuestionAnswerItem(
            id = item.id,
            selected = if (custom.isEmpty() || item.multiSelect == true) picked else emptyList(),
            custom = custom.ifEmpty { null },
        )
    },
)

/** A plan submitted for review: everything the decision card renders and answers with. */
internal data class PlanReview(
    val id: String,
    val question: String,
    val plan: String,
    val approve: AskUserQuestionOption,
    /** The option that refuses; absent when the asker offered only the approving one. */
    val decline: AskUserQuestionOption?,
)

/**
 * Narrow a request to the plan-review card, or decline it and leave the generic flow to answer.
 *
 * The card claims a request only when it can send every answer that request allows, and that is
 * not fastidiousness. The card answers one question; the host refuses a batch shorter than the
 * request; so showing it for a two-question batch blocks the tool with no way forward. A batch of
 * more than one, a missing plan body, a multi-select, a third option, or an `approve` naming no
 * real option all stay with the generic flow, where every answer is still reachable — including a
 * request the asker's own service would have rejected, because this client sits downstream of a
 * wire boundary.
 */
internal fun planReviewOf(questions: List<AskUserQuestionItem>): PlanReview? {
    val question = questions.singleOrNull() ?: return null
    val intent = question.intent as? AskUserQuestionIntent.PlanReview ?: return null
    val plan = question.detail ?: return null
    if (question.multiSelect == true) return null
    val options = question.options.orEmpty()
    if (options.size > 2) return null
    val approve = options.firstOrNull { it.label == intent.approve } ?: return null
    return PlanReview(
        id = question.id,
        question = question.question,
        plan = plan,
        approve = approve,
        decline = options.firstOrNull { it.label != intent.approve },
    )
}

/** The harness's `min(60vh, 520px)`, retuned for a column the composer also has to fit in. */
private const val CARD_FRACTION = 0.6f
private val CARD_CEILING = 360.dp

/**
 * The tallest the question card may grow before its body starts scrolling instead.
 *
 * The web card lives in a fixed-height conversation column and replaces the input bar. This one is
 * an ordinary child of the chat column, measured before the composer below it, so an uncapped card
 * does not merely crowd the transcript — it can push the composer off the bottom of the screen.
 */
internal fun questionCardMaxHeight(available: Dp): Dp =
    minOf(available * CARD_FRACTION, CARD_CEILING)
