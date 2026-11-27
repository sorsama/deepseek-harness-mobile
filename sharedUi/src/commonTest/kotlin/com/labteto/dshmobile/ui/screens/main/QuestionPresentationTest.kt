package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.QuestionCardState
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class QuestionPresentationTest {
    private val card = QuestionCardState("s", "c", emptyList(), remainingMs = 24_000, ready = true)

    @Test fun `countdown rounds up for plural selection and safely clamps extremes`() {
        assertEquals(0, countdownSeconds(-1))
        assertEquals(0, countdownSeconds(0))
        assertEquals(1, countdownSeconds(1))
        assertEquals(1, countdownSeconds(1_000))
        assertEquals(2, countdownSeconds(1_001))
        assertEquals(24, countdownSeconds(23_001))
        assertEquals(Int.MAX_VALUE, countdownSeconds(Long.MAX_VALUE))
    }

    @Test fun `formatted quantity uses the display locale numbering system`() {
        assertEquals("24", countdownNumber(24, Locale.ENGLISH))
        assertEquals("٢٤", countdownNumber(24, Locale.forLanguageTag("ar-u-nu-arab")))
        assertEquals("২৪", countdownNumber(24, Locale.forLanguageTag("bn-u-nu-beng")))
    }

    @Test fun `focus pauses countdown and take time gives persistent held feedback`() {
        assertEquals(QuestionWaitStatus.Countdown, questionWaitStatus(card))
        assertEquals(QuestionWaitStatus.Paused, questionWaitStatus(card.copy(focused = true)))
        assertEquals(QuestionWaitStatus.Held, questionWaitStatus(card.copy(focused = true, held = true)))
        assertEquals(QuestionWaitStatus.Held, questionWaitStatus(card.copy(held = true)))
    }

    @Test fun `continued queued and settled override stale timer and hold flags`() {
        val held = card.copy(held = true, focused = true)
        assertEquals(QuestionWaitStatus.Continued, questionWaitStatus(held.copy(state = "continued")))
        assertEquals(QuestionWaitStatus.Queued, questionWaitStatus(held.copy(state = "continued", queued = true)))
        assertEquals(QuestionWaitStatus.Settled, questionWaitStatus(held.copy(state = "settled", queued = true)))
        assertEquals(QuestionWaitStatus.Waiting, questionWaitStatus(card.copy(remainingMs = null, ready = false)))
    }
}
