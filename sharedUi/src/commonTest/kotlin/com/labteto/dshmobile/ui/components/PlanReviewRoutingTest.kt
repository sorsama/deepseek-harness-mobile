package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which card answers a request.
 *
 * The narrowing is what keeps every request answerable. The plan-review card sends one answer, and
 * the host refuses an answer batch shorter than the request it resolves — so claiming a batch of
 * three because one of them declared the intent does not merely render the other two invisibly, it
 * blocks the tool call outright with nothing on screen to say so.
 */
class PlanReviewRoutingTest {

    private fun planQuestion(
        approve: String = "Approve",
        detail: String? = "# The plan",
        multiSelect: Boolean? = null,
        options: List<String> = listOf("Approve", "Refuse"),
        id: String = "plan",
    ) = AskUserQuestionItem(
        id = id,
        question = "Ready to go?",
        detail = detail,
        options = options.map { AskUserQuestionOption(it) },
        multiSelect = multiSelect,
        intent = AskUserQuestionIntent.PlanReview(approve = approve),
    )

    @Test
    fun `a lone plan review naming its own approve label becomes a decision card`() {
        val review = planReviewOf(listOf(planQuestion()))
        assertEquals("plan", review?.id)
        assertEquals("# The plan", review?.plan)
        assertEquals("Approve", review?.approve?.label)
        assertEquals("Refuse", review?.decline?.label)
    }

    @Test
    fun `a batch carrying a plan review alongside other questions stays on the generic flow`() {
        val other = AskUserQuestionItem(id = "other", question = "And this?")
        assertNull(planReviewOf(listOf(planQuestion(), other)))
    }

    @Test
    fun `a plan review whose approve label names no option stays on the generic flow`() {
        assertNull(planReviewOf(listOf(planQuestion(approve = "Ship it"))))
    }

    @Test
    fun `a plan review with no plan body stays on the generic flow`() {
        assertNull(planReviewOf(listOf(planQuestion(detail = null))))
    }

    @Test
    fun `a multi-select plan review stays on the generic flow`() {
        assertNull(planReviewOf(listOf(planQuestion(multiSelect = true))))
    }

    @Test
    fun `a third option is one more than two buttons can express`() {
        assertNull(planReviewOf(listOf(planQuestion(options = listOf("Approve", "Refuse", "Later")))))
    }

    @Test
    fun `an approve-only plan review has no refusal to offer`() {
        val review = planReviewOf(listOf(planQuestion(options = listOf("Approve"))))
        assertEquals("Approve", review?.approve?.label)
        assertNull(review?.decline)
    }

    @Test
    fun `an ordinary question is not a plan review`() {
        assertNull(planReviewOf(listOf(AskUserQuestionItem(id = "a", question = "Which one?"))))
    }

    @Test
    fun `an empty batch is not a plan review`() {
        assertNull(planReviewOf(emptyList()))
    }
}
