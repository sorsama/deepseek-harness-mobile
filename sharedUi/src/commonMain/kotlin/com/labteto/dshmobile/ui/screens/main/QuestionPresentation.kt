package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.data.QuestionCardState

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

/**
 * The countdown's seconds as the reader's language writes a number: on Android `NumberFormat.getIntegerInstance` in the
 * configuration's first locale (the reader's own digits, grouped), as the countdown has always been written there;
 * Western digits on iOS, like every count there ([com.labteto.dshmobile.ui.components.localizedCount]).
 */
@Composable
internal expect fun countdownNumber(seconds: Int): String
