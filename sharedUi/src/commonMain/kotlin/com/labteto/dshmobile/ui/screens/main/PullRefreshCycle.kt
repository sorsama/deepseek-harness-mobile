package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.labteto.dshmobile.data.SessionListRefresh
import com.labteto.dshmobile.ui.components.PullRefreshPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One pull-to-refresh, from the release under the thumb to the moment the indicator has slid away.
 *
 * The class owns the timing, not the pixels: the drawer collects [phase] to drive the indicator and
 * supplies the read and the haptics as callbacks, so the part that is easy to get wrong — a fast
 * host making a refresh look like a flicker, a double pull starting two reads, a failed read
 * vanishing without a word — is testable on the JVM with virtual time.
 *
 * The minimum spinner time is not decoration. A local harness answers the session list in a few
 * milliseconds, and a spinner that appears and is replaced by a tick inside one screen refresh
 * reads as a glitch rather than as work done. The result hold is the same idea for the end: the
 * tick or the cross has to be legible before the pill leaves.
 */
internal class PullRefreshCycle(
    private val scope: CoroutineScope,
    private val read: suspend () -> SessionListRefresh,
    private val onDone: () -> Unit = {},
    private val onFailed: (String?) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val minimumSpinnerMs: Long = MIN_SPINNER_MS,
    private val resultHoldMs: Long = RESULT_HOLD_MS,
) {
    private val _phase = MutableStateFlow(PullRefreshPhase.Idle)

    /** What the indicator should draw right now. */
    val phase: StateFlow<PullRefreshPhase> = _phase.asStateFlow()

    /**
     * Starts a refresh unless one is still running.
     *
     * The phase is claimed before the coroutine is launched, so two quick releases — or a pull
     * landing at the same moment as the overflow menu's Refresh — only ever read once.
     */
    fun start() {
        if (_phase.value != PullRefreshPhase.Idle) return
        _phase.value = PullRefreshPhase.Refreshing
        scope.launch {
            val beganAt = now()
            val result = try {
                read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                SessionListRefresh.Failed(failure.message)
            }
            val spent = now() - beganAt
            if (spent < minimumSpinnerMs) delay(minimumSpinnerMs - spent)
            _phase.value = when (result) {
                SessionListRefresh.Done -> PullRefreshPhase.Done
                is SessionListRefresh.Failed -> PullRefreshPhase.Failed
            }
            when (result) {
                SessionListRefresh.Done -> onDone()
                is SessionListRefresh.Failed -> onFailed(result.reason)
            }
            delay(resultHoldMs)
            _phase.value = PullRefreshPhase.Idle
        }
    }

    private companion object {
        /** Long enough to read as work, short enough never to feel like waiting. */
        const val MIN_SPINNER_MS = 650L

        /** How long the check (or the cross) stays up before the pill springs away. */
        const val RESULT_HOLD_MS = 620L
    }
}

/**
 * A [PullRefreshCycle] whose callbacks follow the latest recomposition.
 *
 * The cycle is remembered for the drawer's lifetime so an in-flight read survives a recomposition
 * (a rename arriving, a filter being tapped), while the closure it calls still sees the current
 * toast and haptics.
 */
@Composable
internal fun rememberPullRefreshCycle(
    scope: CoroutineScope,
    read: suspend () -> SessionListRefresh,
    onDone: () -> Unit,
    onFailed: (reason: String?) -> Unit,
): PullRefreshCycle {
    val currentRead by rememberUpdatedState(read)
    val currentDone by rememberUpdatedState(onDone)
    val currentFailed by rememberUpdatedState(onFailed)
    return remember(scope) {
        PullRefreshCycle(
            scope = scope,
            read = { currentRead() },
            onDone = { currentDone() },
            onFailed = { reason -> currentFailed(reason) },
        )
    }
}
