package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackProblemTest {

    @Test
    fun `a version conflict is named as one`() {
        assertEquals(FeedbackProblem.Conflict, feedbackProblem("version-conflict"))
    }

    @Test
    fun `any other code, or none, is a plain failure and never raw text`() {
        assertEquals(FeedbackProblem.Generic, feedbackProblem(null))
        assertEquals(FeedbackProblem.Generic, feedbackProblem(""))
        assertEquals(FeedbackProblem.Generic, feedbackProblem("unknown"))
        assertEquals(FeedbackProblem.Generic, feedbackProblem("message-not-found"))
    }

    @Test
    fun `a thrown failure maps to what it means to the reader`() {
        assertEquals(FeedbackProblem.Offline, feedbackProblemOf(FeedbackOfflineException()))
        assertEquals(FeedbackProblem.Conflict, feedbackProblemOf(FeedbackRejectedException("version-conflict")))
        assertEquals(FeedbackProblem.Generic, feedbackProblemOf(FeedbackRejectedException("")))
        assertEquals(FeedbackProblem.Generic, feedbackProblemOf(RuntimeException("Unable to resolve host")))
        assertEquals(FeedbackProblem.Generic, feedbackProblemOf(java.io.IOException()))
    }
}
