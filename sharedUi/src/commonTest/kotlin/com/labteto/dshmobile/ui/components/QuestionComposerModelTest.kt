package com.labteto.dshmobile.ui.components

import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the host checks, restated here so a change to the composer cannot quietly break them.
 *
 * `matchesQuestions` (`packages/host/apiproxy/src/api-proxy.ts`) refuses the *whole* batch on any
 * one violation, and a refusal is not a retry: the wait stays pending and the `ask_user_question`
 * call that opened it never unblocks. The free-text case is the one that motivated most of this —
 * `custom` used to be written beside the answer list rather than on the answer, and the host's
 * schema strips a key it does not recognise instead of objecting, so the answer went out, came
 * back accepted, and reached the model with the typed text simply gone.
 */
class QuestionComposerModelTest {

    private fun question(
        id: String,
        multiSelect: Boolean? = null,
        vararg options: String,
    ) = AskUserQuestionItem(
        id = id,
        question = "Which one?",
        options = if (options.isEmpty()) null else options.map { AskUserQuestionOption(it) },
        multiSelect = multiSelect,
    )

    // ---- the encoder ------------------------------------------------------

    @Test
    fun `a free-text answer rides its own question, not the batch`() {
        val questions = listOf(question("a", options = arrayOf("Alpha")), question("b"))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha")), QuestionDraft(custom = "my own"))
        val answer = encodeAnswers(questions, drafts)
        assertNull(answer.answers[0].custom)
        assertEquals("my own", answer.answers[1].custom)
    }

    @Test
    fun `a blank custom answer is omitted rather than sent empty`() {
        val answer = encodeAnswers(listOf(question("a")), listOf(QuestionDraft(custom = "   ")))
        assertNull(answer.answers[0].custom)
    }

    @Test
    fun `a single-select question sends free text or a selection, never both`() {
        val questions = listOf(question("a", options = arrayOf("Alpha", "Beta")))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha"), custom = "neither"))
        val answer = encodeAnswers(questions, drafts)
        assertEquals(emptyList<String>(), answer.answers[0].selected)
        assertEquals("neither", answer.answers[0].custom)
    }

    @Test
    fun `a multi-select question may send both a selection and free text`() {
        val questions = listOf(question("a", multiSelect = true, options = arrayOf("Alpha", "Beta")))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha", "Beta"), custom = "and this"))
        val answer = encodeAnswers(questions, drafts)
        assertEquals(listOf("Alpha", "Beta"), answer.answers[0].selected)
        assertEquals("and this", answer.answers[0].custom)
    }

    @Test
    fun `a skipped question answers with an empty selection and no free text`() {
        val questions = listOf(question("a", options = arrayOf("Alpha")))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha"), custom = "x", skipped = true))
        val answer = encodeAnswers(questions, drafts)
        assertEquals(emptyList<String>(), answer.answers[0].selected)
        assertNull(answer.answers[0].custom)
    }

    @Test
    fun `the batch answers every question in the order it was asked`() {
        val questions = listOf(question("first"), question("second"), question("third"))
        val answer = encodeAnswers(questions, listOf(QuestionDraft(custom = "only the first")))
        assertEquals(listOf("first", "second", "third"), answer.answers.map { it.id })
    }

    @Test
    fun `a selection the question never offered is dropped`() {
        val questions = listOf(question("a", multiSelect = true, options = arrayOf("Alpha")))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha", "Ghost")))
        assertEquals(listOf("Alpha"), encodeAnswers(questions, drafts).answers[0].selected)
    }

    @Test
    fun `a repeated selection is sent once`() {
        val questions = listOf(question("a", multiSelect = true, options = arrayOf("Alpha")))
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha", "Alpha")))
        assertEquals(listOf("Alpha"), encodeAnswers(questions, drafts).answers[0].selected)
    }

    @Test
    fun `a question with no options carries no selection`() {
        val drafts = listOf(QuestionDraft(selected = listOf("Alpha"), custom = "typed"))
        assertEquals(emptyList<String>(), encodeAnswers(listOf(question("a")), drafts).answers[0].selected)
    }

    // ---- the draft reducers -----------------------------------------------

    @Test
    fun `choosing on a single-select question replaces the choice and clears the free text`() {
        val draft = QuestionDraft(selected = listOf("Alpha"), custom = "typed")
        val next = draft.choose("Beta", multiSelect = false)
        assertEquals(listOf("Beta"), next.selected)
        assertEquals("", next.custom)
    }

    @Test
    fun `choosing on a multi-select question toggles that one label`() {
        val draft = QuestionDraft(selected = listOf("Alpha"))
        assertEquals(listOf("Alpha", "Beta"), draft.choose("Beta", multiSelect = true).selected)
        assertEquals(emptyList<String>(), draft.choose("Alpha", multiSelect = true).selected)
    }

    @Test
    fun `typing clears a single-select selection but keeps a multi-select one`() {
        val draft = QuestionDraft(selected = listOf("Alpha"))
        assertEquals(emptyList<String>(), draft.withCustom("typed", multiSelect = false).selected)
        assertEquals(listOf("Alpha"), draft.withCustom("typed", multiSelect = true).selected)
    }

    @Test
    fun `a skipped question counts as complete but not as answered`() {
        val draft = QuestionDraft(skipped = true)
        assertFalse(draft.answered())
        assertTrue(draft.completed())
    }

    @Test
    fun `whitespace alone is not an answer`() {
        assertFalse(QuestionDraft(custom = "  \n ").answered())
    }

    @Test
    fun `submit lands on the first question that is neither answered nor skipped`() {
        val drafts = listOf(
            QuestionDraft(selected = listOf("Alpha")),
            QuestionDraft(skipped = true),
            QuestionDraft(),
            QuestionDraft(),
        )
        assertEquals(2, firstIncomplete(drafts))
    }

    @Test
    fun `a complete batch reports no incomplete question`() {
        assertEquals(-1, firstIncomplete(listOf(QuestionDraft(custom = "yes"), QuestionDraft(skipped = true))))
    }

    @Test
    fun `a single-select pick advances, except on the last question`() {
        assertEquals(1, advanceFrom(0, count = 3))
        assertEquals(2, advanceFrom(2, count = 3))
    }

    // ---- the recommendation marker ----------------------------------------

    @Test
    fun `the recommended marker is stripped for display in both scripts`() {
        assertEquals(OptionLabel("Use OAuth", true), parseRecommendedLabel("Use OAuth (Recommended)"))
        assertEquals(OptionLabel("稳妥方案", true), parseRecommendedLabel("稳妥方案（推荐）"))
    }

    @Test
    fun `the marker is matched whatever case it was written in`() {
        assertEquals(OptionLabel("Use OAuth", true), parseRecommendedLabel("Use OAuth (recommended)"))
        assertEquals(OptionLabel("Use OAuth", true), parseRecommendedLabel("Use OAuth (RECOMMENDED)"))
    }

    @Test
    fun `an ideographic space before the marker is still a marker`() {
        // Java's `\s` stops at ASCII where JavaScript's does not, so a literal transcription of the
        // harness regex would leave this one showing the marker raw.
        assertEquals(OptionLabel("稳妥方案", true), parseRecommendedLabel("稳妥方案　（推荐）"))
    }

    @Test
    fun `a label that merely mentions the word is left alone`() {
        val label = "Recommended by the docs"
        assertEquals(OptionLabel(label, false), parseRecommendedLabel(label))
    }

    @Test
    fun `the wire keeps the label the asker wrote, marker and all`() {
        val questions = listOf(question("a", options = arrayOf("Use OAuth (Recommended)")))
        val drafts = listOf(QuestionDraft(selected = listOf("Use OAuth (Recommended)")))
        assertEquals(
            listOf("Use OAuth (Recommended)"),
            encodeAnswers(questions, drafts).answers[0].selected,
        )
    }

    // ---- the height cap ---------------------------------------------------

    @Test
    fun `the card yields most of a short column back to the transcript`() {
        assertEquals(240f, questionCardMaxHeight(400.dp).value, 0.01f)
    }

    @Test
    fun `the card stops growing well before it fills a tall column`() {
        assertEquals(360f, questionCardMaxHeight(1200.dp).value, 0.01f)
    }
}
