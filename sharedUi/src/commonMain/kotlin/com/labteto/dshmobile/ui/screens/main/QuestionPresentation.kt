package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.QuestionCardState
import java.text.NumberFormat
import java.util.Locale

internal enum class QuestionWaitStatus { Countdown, Paused, Held, Continued, Queued, Settled, Waiting }

internal fun questionWaitStatus(card: QuestionCardState): QuestionWaitStatus = when {
    card.state == "settled" -> QuestionWaitStatus.Settled
    card.queued -> QuestionWaitStatus.Queued
    card.state == "continued" -> QuestionWaitStatus.Continued
    card.held -> QuestionWaitStatus.Held
    card.focused && card.remainingMs != null -> QuestionWaitStatus.Paused
    card.remainingMs != null -> QuestionWaitStatus.Countdown
    else -> QuestionWaitStatus.Waiting
}

/** Ceiling seconds without overflow; Android quantity selectors take an Int. */
internal fun countdownSeconds(milliseconds: Long): Int {
    val nonNegative = milliseconds.coerceAtLeast(0)
    return (nonNegative / 1000 + if (nonNegative % 1000 > 0) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun countdownNumber(seconds: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(seconds)
