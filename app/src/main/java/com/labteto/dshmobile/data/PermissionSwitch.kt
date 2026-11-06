package com.labteto.dshmobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How long, after the harness acknowledges `/permission`, we wait for its projection to move. */
internal const val PERMISSION_SETTLE_MS = 4_000L

/** The most a switch may stay pending when the command itself never answers. */
internal const val PERMISSION_IN_FLIGHT_MS = 15_000L

/** How a permission switch ended. Every attempt ends in exactly one of these. */
sealed interface PermissionSwitchResult {
    /** The picked preset was already in force, so no command was sent. */
    data object AlreadyActive : PermissionSwitchResult

    /** The harness's own projection now reports the preset that was asked for. */
    data class Applied(val value: String) : PermissionSwitchResult

    /**
     * The command was accepted but the harness reports a different value — it normalised the id,
     * or reports `custom`. The chip shows [actual], because that is what is in force.
     */
    data class Differs(val requested: String, val actual: String) : PermissionSwitchResult

    /** The command was sent but the projection never moved; the caller says the harness didn't confirm. */
    data class Unconfirmed(val requested: String) : PermissionSwitchResult

    /** The command was refused or never reached the harness. */
    data class Failed(val outcome: CommandOutcome) : PermissionSwitchResult

    /** The session changed, the connection dropped, or a newer pick replaced this one. */
    data object Abandoned : PermissionSwitchResult
}

/**
 * The in-flight state of a permission-preset switch: what the chip shows while the harness catches
 * up, and the rule for when it stops showing it.
 *
 * The harness never answers a `/permission` with the new value — the answer is the `permissions`
 * projection moving. The first version cleared the optimistic value only when that projection
 * emitted a value *equal to the pick*, which is a StateFlow and so never re-emits an unchanged
 * value. Anything that kept the projection from landing on exactly the pick left the chip disabled
 * under a skeleton for good: picking the preset already in force, a host that normalises the id or
 * reports `custom`, a frame that never arrives, a caller whose scope was cancelled mid-command.
 *
 * Every path here ends in [PermissionSwitchResult]:
 *
 * - the preset in force is picked again → [PermissionSwitchResult.AlreadyActive], nothing sent;
 * - the command fails → [PermissionSwitchResult.Failed];
 * - the projection reports the pick → [PermissionSwitchResult.Applied], even before the command's
 *   own answer lands;
 * - the projection reports anything else after the command is acknowledged →
 *   [PermissionSwitchResult.Differs], and the chip shows the harness's real value;
 * - nothing moves within [settleMs] of the acknowledgement (or [inFlightMs] of the pick, when the
 *   command never answers) → [PermissionSwitchResult.Unconfirmed];
 * - the session changes or the connection drops → [PermissionSwitchResult.Abandoned].
 *
 * The command runs on [scope], not on the caller's: a screen that leaves composition mid-switch
 * only stops waiting, it does not strand the pending state.
 */
internal class PermissionSwitch(
    private val scope: CoroutineScope,
    private val settleMs: Long = PERMISSION_SETTLE_MS,
    private val inFlightMs: Long = PERMISSION_IN_FLIGHT_MS,
) {
    private val _pending = MutableStateFlow<String?>(null)

    /** The preset a switch is in flight for, or null when nothing is waiting on the harness. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val lock = Any()
    private var attempt: Attempt? = null

    private class Attempt(val target: String, val baseline: String?) {
        val result = CompletableDeferred<PermissionSwitchResult>()
        var acknowledged = false

        /** A value other than the baseline seen before the command was acknowledged. */
        var movedTo: String? = null
        var watchdog: Job? = null
    }

    /**
     * Switch to [target]. [current] is the preset the projection reports right now; [run] sends
     * the command. Returns when the attempt has ended, whichever way it ended.
     */
    suspend fun switchTo(
        target: String,
        current: String?,
        run: suspend () -> CommandOutcome,
    ): PermissionSwitchResult {
        val started = synchronized(lock) {
            // With a switch already in flight the chip is showing *its* target, so picking the
            // value the projection still reports is a real request to go back, not a no-op.
            if (attempt == null && target == current) return PermissionSwitchResult.AlreadyActive
            attempt?.let { finish(it, PermissionSwitchResult.Abandoned) }
            Attempt(target, current).also { fresh ->
                attempt = fresh
                _pending.value = target
                fresh.watchdog = scope.launch {
                    delay(inFlightMs)
                    expire(fresh)
                }
            }
        }
        scope.launch {
            val outcome = try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CommandOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            acknowledge(started, outcome)
        }
        return started.result.await()
    }

    /** Feed the `permissions` projection's current value, on every emission. */
    fun observe(currentValue: String?) {
        synchronized(lock) {
            val a = attempt ?: return
            when {
                currentValue == null -> Unit
                currentValue == a.target -> finish(a, PermissionSwitchResult.Applied(a.target))
                currentValue == a.baseline -> a.movedTo = null
                a.acknowledged -> finish(a, PermissionSwitchResult.Differs(a.target, currentValue))
                else -> a.movedTo = currentValue
            }
        }
    }

    /** The open session changed or the connection dropped: nothing in flight means anything now. */
    fun reset() {
        synchronized(lock) { attempt?.let { finish(it, PermissionSwitchResult.Abandoned) } }
    }

    private fun acknowledge(a: Attempt, outcome: CommandOutcome) {
        synchronized(lock) {
            if (attempt !== a) return
            if (outcome !is CommandOutcome.Ok) {
                finish(a, PermissionSwitchResult.Failed(outcome))
                return
            }
            a.acknowledged = true
            val moved = a.movedTo
            if (moved != null) {
                // The projection moved while the command was still in flight, to something that
                // is not the pick. The command is answered now, so that value is the verdict.
                finish(a, PermissionSwitchResult.Differs(a.target, moved))
                return
            }
            a.watchdog?.cancel()
            a.watchdog = scope.launch {
                delay(settleMs)
                expire(a)
            }
        }
    }

    private fun expire(a: Attempt) {
        synchronized(lock) {
            if (attempt === a) finish(a, PermissionSwitchResult.Unconfirmed(a.target))
        }
    }

    private fun finish(a: Attempt, result: PermissionSwitchResult) {
        if (attempt === a) {
            attempt = null
            _pending.value = null
        }
        a.watchdog?.cancel()
        a.result.complete(result)
    }
}
