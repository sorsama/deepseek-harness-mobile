package com.labteto.dshmobile.ui.screens.main

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The countdown's digits on Android, which come from `NumberFormat` (moved from `QuestionPresentationTest`). */
class CountdownNumberTest {
    @Test fun `formatted quantity uses the display locale numbering system`() {
        assertEquals("24", countdownNumber(24, Locale.ENGLISH))
        assertEquals("٢٤", countdownNumber(24, Locale.forLanguageTag("ar-u-nu-arab")))
        assertEquals("২৪", countdownNumber(24, Locale.forLanguageTag("bn-u-nu-beng")))
    }
}
