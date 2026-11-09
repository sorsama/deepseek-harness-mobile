package com.labteto.dshmobile.ui.components

import kotlin.time.TimeSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.floor

/*
 * Haptics, part three: ticks for a continuous gesture.
 *
 * The model is a detent wheel. One tick per detent crossed, and when the movement stops a firmer
 * one, like a physical wheel catching on its last notch. Doing that well takes three rules, which
 * [TickGovernor] holds and which are unit-tested:
 *  - a minimum gap between ticks, so a fast swipe over many detents is a fast roll and not one
 *    long buzz;
 *  - lighter ticks as the movement speeds up, and every other tick dropped once it is very fast,
 *    so speed feels like speed instead of a wall of identical pulses;
 *  - the throttle's memory lives as long as the gesture, in one remembered [HapticTicker]. A
 *    ticker made again on every recomposition would forget its last tick each time and never
 *    throttle anything.
 */

/**
 * Decides which crossings play, and how hard. Pure arithmetic over a clock the caller supplies.
 *
 * @param minGapMs the least time between two played ticks.
 * @param idleResetMs a pause this long starts a new movement: speed is measured from scratch.
 * @param fullUntilPerSec up to this many crossings a second, every tick plays at full weight.
 * @param thinFromPerSec from this many a second, every other tick is dropped as well.
 * @param lightestWeight the weight of a tick at [thinFromPerSec] and beyond.
 * @param settleDebounceMs the least time between two settles.
 */
class TickGovernor(
    private val minGapMs: Long = 35,
    private val idleResetMs: Long = 350,
    private val fullUntilPerSec: Float = 12f,
    private val thinFromPerSec: Float = 24f,
    private val lightestWeight: Float = 0.4f,
    private val settleDebounceMs: Long = 80,
) {
    private var lastCrossAt = NEVER
    private var lastPlayedAt = NEVER
    private var lastSettleAt = NEVER
    private var averageGapMs = Float.NaN
    private var thinParity = 0

    /**
     * One detent was crossed at [nowMs]. Returns the weight to play it at, 0 to 1, or 0 to skip.
     * Speed is measured over every crossing, played or not, because it is the finger's speed.
     */
    fun onCross(nowMs: Long): Float {
        val sinceCross = if (lastCrossAt == NEVER) Long.MAX_VALUE else nowMs - lastCrossAt
        lastCrossAt = nowMs
        if (sinceCross > idleResetMs) {
            averageGapMs = Float.NaN
            thinParity = 0
        } else {
            val gap = sinceCross.toFloat().coerceAtLeast(1f)
            averageGapMs = if (averageGapMs.isNaN()) gap else 0.5f * averageGapMs + 0.5f * gap
        }
        if (lastPlayedAt != NEVER && nowMs - lastPlayedAt < minGapMs) return 0f

        val perSec = if (averageGapMs.isNaN()) 0f else 1000f / averageGapMs
        if (perSec >= thinFromPerSec) {
            thinParity++
            if (thinParity % 2 == 0) return 0f
        }
        lastPlayedAt = nowMs
        return when {
            perSec <= fullUntilPerSec -> 1f
            perSec >= thinFromPerSec -> lightestWeight
            else -> 1f - (1f - lightestWeight) * (perSec - fullUntilPerSec) / (thinFromPerSec - fullUntilPerSec)
        }
    }

    /**
     * The movement stopped or the finger lifted at [nowMs]. True if the firmer settle tick should
     * play. It always does, except right after another settle, and it ends the movement: the next
     * crossing starts speed from scratch.
     */
    fun onSettle(nowMs: Long): Boolean {
        val play = lastSettleAt == NEVER || nowMs - lastSettleAt >= settleDebounceMs
        if (play) lastSettleAt = nowMs
        averageGapMs = Float.NaN
        thinParity = 0
        lastCrossAt = NEVER
        return play
    }

    /** A new gesture starts: forget everything. */
    fun reset() {
        lastCrossAt = NEVER
        lastPlayedAt = NEVER
        lastSettleAt = NEVER
        averageGapMs = Float.NaN
        thinParity = 0
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * The tick stream of one gesture: call [cross] for every detent the movement passes and [settle]
 * when it ends. Get one from [rememberHapticTicker], or from [DsHaptics.ticker] where a class
 * owns the gesture.
 */
class HapticTicker internal constructor(
    private val play: (HapticEvent, Float) -> Unit,
    private val clock: () -> Long = ::uptimeMillis,
    private val governor: TickGovernor = TickGovernor(),
) {
    /** The movement passed one detent. */
    fun cross() {
        val weight = governor.onCross(clock())
        if (weight > 0f) play(HapticEvent.DragCross, weight)
    }

    /** The movement ended on a detent: a firmer tick. */
    fun settle() {
        if (governor.onSettle(clock())) play(HapticEvent.DragDrop, 1f)
    }

    /** A new gesture starts. */
    fun reset() = governor.reset()
}

/**
 * Counts the detents a moving value passes: one every [step] along the number line, so
 * `DetentCounter(10f)` is a detent at 0, 10, 20 and so on. The first value only sets the
 * starting point.
 */
class DetentCounter(private val step: Float) {
    private var lastIndex = Int.MIN_VALUE

    /** How many detents lie between the previous value and [value]; 0 while it stays in one cell. */
    fun crossed(value: Float): Int {
        val index = floor(value / step).toInt()
        val passed = if (lastIndex == Int.MIN_VALUE) 0 else kotlin.math.abs(index - lastIndex)
        lastIndex = index
        return passed
    }

    /** Forget the position: the next value is a starting point again. */
    fun reset() {
        lastIndex = Int.MIN_VALUE
    }
}

/** A tick stream for one gesture, kept for as long as this composition lives. */
@Composable
fun rememberHapticTicker(): HapticTicker {
    val haptics = rememberDsHaptics()
    return remember(haptics) { haptics.ticker() }
}

/** Milliseconds on a monotonic clock, from the first time anything asks. Only differences matter. */
private fun uptimeMillis(): Long = TickClockOrigin.elapsedNow().inWholeMilliseconds

private val TickClockOrigin = TimeSource.Monotonic.markNow()
