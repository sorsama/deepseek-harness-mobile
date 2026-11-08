@file:SuppressLint("InlinedApi")

package com.labteto.dshmobile.ui.components

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import com.labteto.dshmobile.ui.theme.HapticStrength
import kotlin.math.roundToInt

/*
 * Haptics, part one: deciding what to play.
 *
 * Everything here is plain Kotlin with no Android calls, so the choice of effect for every phone
 * can be unit-tested with a made-up capability model. The Android half is HapticEngine, which
 * asks the phone what it can do, hands the answer to [HapticPlanner.plan] and plays the result.
 *
 * The four ways a phone can play something, best first (see [HapticTier]):
 *  1. Primitives composition, API 30+, only when the phone says it supports the primitives.
 *     Crisp, scalable, several in a row. A composition containing one unsupported primitive plays
 *     nothing at all, so a primitive the phone lacks is swapped for the nearest one it has.
 *  2. Predefined VibrationEffect (tick, click, heavy click, double click), API 29+. The phone
 *     tunes these, and anything it has not tuned falls back to a platform waveform.
 *  3. A single 8 to 16 ms one-shot, only on a vibrator with amplitude control. Without that,
 *     any non-zero amplitude plays at full power, which is the buzz the Android haptics
 *     principles tell apps to avoid.
 *  4. View.performHapticFeedback with the action constants. Follows the system touch feedback
 *     setting and cannot be scaled.
 *
 * Android 16's VibrationEffect.Builder with Preset is the documented first choice on the newest
 * releases, but it needs a compile SDK newer than the one this app builds against, so that tier
 * is not implemented. On those phones the primitives tier runs instead.
 */

// [HapticEvent], the vocabulary this file plans for, lives with the shared UI (Haptics.kt in :sharedUi).

/** The vibration primitives of API 30+. Spin is left out: nothing in the app needs it. */
enum class HapticPrimitive(val minSdk: Int) {
    Click(30),
    Tick(30),
    QuickRise(30),
    QuickFall(30),
    SlowRise(30),
    LowTick(31),
    Thud(31),
}

/** The predefined effects of API 29+. */
enum class HapticPreset { Tick, Click, HeavyClick, DoubleClick }

/** How a phone will play the app's haptics, best first. */
enum class HapticTier {
    Primitives,
    Predefined,
    OneShot,
    ViewConstants,

    /** No vibration motor: nothing is played. */
    Silent,
}

/**
 * What one phone can do, as the engine found out.
 *
 * @param primitives the primitives the phone reports as supported. Empty before API 30.
 * @param touchFeedbackOn the system "Touch feedback" setting. Off means the user does not want
 * these buzzes, so nothing is played.
 * @param directOk false once a direct vibrator call has thrown: the rest of the run falls back to
 * the View constants rather than trying again.
 */
data class HapticCaps(
    val sdk: Int,
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val primitives: Set<HapticPrimitive> = emptySet(),
    val touchFeedbackOn: Boolean = true,
    val directOk: Boolean = true,
) {
    fun supports(primitive: HapticPrimitive): Boolean = sdk >= primitive.minSdk && primitive in primitives
}

/** What to play: the outcome of [HapticPlanner.plan]. */
sealed interface HapticPlan {
    /** Play nothing. */
    data object Silent : HapticPlan

    /** Primitives, in order. [Step.delayMs] is the gap after the previous one ends. */
    data class Composition(val steps: List<Step>) : HapticPlan {
        data class Step(val primitive: HapticPrimitive, val scale: Float, val delayMs: Int)
    }

    data class Predefined(val preset: HapticPreset) : HapticPlan

    data class OneShot(val millis: Long, val amplitude: Int) : HapticPlan

    data class ViewConstant(val constant: Int) : HapticPlan
}

object HapticPlanner {
    /** No effect is played quieter than this: below it a phone's actuator often does nothing at all. */
    const val MIN_SCALE = 0.15f

    /** On a tier that cannot scale, a tick lighter than this is skipped instead of played full. */
    const val MIN_UNSCALED_WEIGHT = 0.5f

    /** The longest single one-shot, per the guidance that a key click lasts 10 to 20 ms. */
    const val MAX_ONE_SHOT_MS = 20L

    /** Which way this phone will play, ignoring the user's settings. */
    fun tier(caps: HapticCaps): HapticTier = when {
        !caps.hasVibrator -> HapticTier.Silent
        caps.directOk && caps.supports(HapticPrimitive.Click) && caps.supports(HapticPrimitive.Tick) ->
            HapticTier.Primitives
        caps.directOk && caps.sdk >= 29 -> HapticTier.Predefined
        caps.directOk && caps.sdk >= 26 && caps.hasAmplitudeControl -> HapticTier.OneShot
        else -> HapticTier.ViewConstants
    }

    /**
     * The effect for [event] on this phone at this [strength].
     *
     * @param weight 0 to 1, how much of a full-strength effect to play. The tick stream lowers it
     * for fast movement. Tiers that cannot scale skip an effect lighter than [MIN_UNSCALED_WEIGHT].
     */
    fun plan(event: HapticEvent, caps: HapticCaps, strength: HapticStrength, weight: Float = 1f): HapticPlan {
        if (!caps.hasVibrator || !caps.touchFeedbackOn || weight <= 0f) return HapticPlan.Silent
        val w = weight.coerceAtMost(1f)
        return when (tier(caps)) {
            HapticTier.Primitives -> composition(event, caps, strength, w)
            HapticTier.Predefined ->
                if (w < MIN_UNSCALED_WEIGHT) HapticPlan.Silent else HapticPlan.Predefined(preset(event, strength))
            HapticTier.OneShot -> oneShot(event, strength, w)
            HapticTier.ViewConstants ->
                if (w < MIN_UNSCALED_WEIGHT) HapticPlan.Silent else HapticPlan.ViewConstant(viewConstant(event, strength, caps.sdk))
            HapticTier.Silent -> HapticPlan.Silent
        }
    }

    // ---- Tier 1: primitives -------------------------------------------------------------

    private class Beat(val primitive: HapticPrimitive, val scale: Float, val delayMs: Int = 0)

    /**
     * Scales are for Normal strength and kept at or under about 0.7 for everything but the
     * strongest accents, so Strong (x1.4) still has room to be stronger. Frequent effects are the
     * lightest, the way Android's principles ask: a tick under a dragging finger is far lighter
     * than the click of a decision.
     */
    private fun recipe(event: HapticEvent): List<Beat> = when (event) {
        HapticEvent.Tap -> listOf(Beat(HapticPrimitive.Click, 0.55f))
        HapticEvent.ToggleOn -> listOf(Beat(HapticPrimitive.Tick, 0.45f), Beat(HapticPrimitive.Click, 0.7f, 25))
        HapticEvent.ToggleOff -> listOf(Beat(HapticPrimitive.Click, 0.5f), Beat(HapticPrimitive.QuickFall, 0.3f, 10))
        HapticEvent.Selection -> listOf(Beat(HapticPrimitive.Tick, 0.45f))
        HapticEvent.Confirm -> listOf(Beat(HapticPrimitive.Tick, 0.5f), Beat(HapticPrimitive.Click, 0.85f, 35))
        HapticEvent.Reject -> listOf(Beat(HapticPrimitive.Thud, 0.85f), Beat(HapticPrimitive.Thud, 0.5f, 60))
        HapticEvent.Attention -> listOf(Beat(HapticPrimitive.SlowRise, 0.45f), Beat(HapticPrimitive.Click, 1f, 30))
        HapticEvent.LongPress -> listOf(Beat(HapticPrimitive.Thud, 0.8f))
        HapticEvent.Tick -> listOf(Beat(HapticPrimitive.LowTick, 0.4f))
        HapticEvent.DragStart -> listOf(Beat(HapticPrimitive.QuickRise, 0.4f), Beat(HapticPrimitive.Click, 0.7f))
        HapticEvent.DragCross -> listOf(Beat(HapticPrimitive.Tick, 0.4f))
        HapticEvent.DragDrop -> listOf(Beat(HapticPrimitive.Click, 0.8f))
    }

    /** What to play instead of a primitive the phone lacks, nearest first, with a scale adjustment. */
    private fun substitutes(primitive: HapticPrimitive): List<Pair<HapticPrimitive, Float>> = when (primitive) {
        HapticPrimitive.Click -> listOf(HapticPrimitive.Tick to 1f)
        HapticPrimitive.Tick -> listOf(HapticPrimitive.Click to 0.7f)
        HapticPrimitive.LowTick -> listOf(HapticPrimitive.Tick to 0.9f, HapticPrimitive.Click to 0.6f)
        HapticPrimitive.Thud -> listOf(HapticPrimitive.Click to 1f, HapticPrimitive.Tick to 1f)
        HapticPrimitive.QuickRise -> listOf(HapticPrimitive.Click to 0.9f, HapticPrimitive.Tick to 1f)
        HapticPrimitive.QuickFall -> listOf(HapticPrimitive.Tick to 1f, HapticPrimitive.Click to 0.6f)
        HapticPrimitive.SlowRise ->
            listOf(HapticPrimitive.QuickRise to 1f, HapticPrimitive.Click to 0.9f, HapticPrimitive.Tick to 1f)
    }

    private fun composition(event: HapticEvent, caps: HapticCaps, strength: HapticStrength, weight: Float): HapticPlan {
        val steps = recipe(event).map { beat ->
            // Only primitives the phone reported are ever put in a composition: one unsupported
            // primitive would make the whole effect play nothing.
            val (primitive, adjust) = if (caps.supports(beat.primitive)) {
                beat.primitive to 1f
            } else {
                substitutes(beat.primitive).firstOrNull { caps.supports(it.first) }
                    ?: return HapticPlan.Silent
            }
            HapticPlan.Composition.Step(
                primitive = primitive,
                scale = scaled(beat.scale * adjust * strength.scale * weight),
                delayMs = beat.delayMs,
            )
        }
        return HapticPlan.Composition(steps)
    }

    private fun scaled(raw: Float): Float = ((raw.coerceIn(MIN_SCALE, 1f)) * 100f).roundToInt() / 100f

    // ---- Tier 2: predefined effects -----------------------------------------------------

    /** Lightest to strongest, and which rung is Normal. Strength moves one rung either way. */
    private class Ladder(val rungs: List<HapticPreset>, val normal: Int)

    private val tickClick = listOf(HapticPreset.Tick, HapticPreset.Click)
    private val clickHeavy = listOf(HapticPreset.Click, HapticPreset.HeavyClick)
    private val tickClickHeavy = listOf(HapticPreset.Tick, HapticPreset.Click, HapticPreset.HeavyClick)

    private fun ladder(event: HapticEvent): Ladder = when (event) {
        HapticEvent.Tap -> Ladder(tickClickHeavy, 1)
        HapticEvent.ToggleOn -> Ladder(tickClickHeavy, 1)
        HapticEvent.ToggleOff -> Ladder(tickClick, 0)
        HapticEvent.Selection -> Ladder(tickClick, 0)
        HapticEvent.Confirm -> Ladder(listOf(HapticPreset.Click, HapticPreset.DoubleClick), 1)
        HapticEvent.Reject -> Ladder(clickHeavy, 1)
        HapticEvent.Attention -> Ladder(listOf(HapticPreset.Click, HapticPreset.DoubleClick, HapticPreset.HeavyClick), 1)
        HapticEvent.LongPress -> Ladder(clickHeavy, 1)
        HapticEvent.Tick -> Ladder(tickClick, 0)
        HapticEvent.DragStart -> Ladder(clickHeavy, 0)
        HapticEvent.DragCross -> Ladder(tickClick, 0)
        HapticEvent.DragDrop -> Ladder(clickHeavy, 0)
    }

    private fun preset(event: HapticEvent, strength: HapticStrength): HapticPreset {
        val ladder = ladder(event)
        val shift = when (strength) {
            HapticStrength.Light -> -1
            HapticStrength.Normal -> 0
            HapticStrength.Strong -> 1
        }
        return ladder.rungs[(ladder.normal + shift).coerceIn(0, ladder.rungs.lastIndex)]
    }

    // ---- Tier 3: one short one-shot, amplitude control only -----------------------------

    private fun oneShot(event: HapticEvent, strength: HapticStrength, weight: Float): HapticPlan {
        val ladder = ladder(event)
        val (millis, amplitude) = when (ladder.rungs[ladder.normal]) {
            HapticPreset.Tick -> 8L to 70
            HapticPreset.Click -> 12L to 120
            HapticPreset.HeavyClick -> 16L to 190
            HapticPreset.DoubleClick -> 16L to 150
        }
        return HapticPlan.OneShot(
            millis = millis.coerceAtMost(MAX_ONE_SHOT_MS),
            amplitude = (amplitude * strength.scale * weight).roundToInt().coerceIn(1, 255),
        )
    }

    // ---- Tier 4: the action constants ---------------------------------------------------

    /** The first option whose API level the phone has. The last of each list is always available. */
    private fun pick(sdk: Int, vararg options: Pair<Int, Int>): Int =
        (options.firstOrNull { sdk >= it.first } ?: options.last()).second

    private fun viewConstant(event: HapticEvent, strength: HapticStrength, sdk: Int): Int {
        val light = strength == HapticStrength.Light
        val strong = strength == HapticStrength.Strong
        val clockTick = 21 to HapticFeedbackConstants.CLOCK_TICK
        val virtualKey = 5 to HapticFeedbackConstants.VIRTUAL_KEY
        val longPress = 1 to HapticFeedbackConstants.LONG_PRESS
        return when (event) {
            HapticEvent.Tap -> pick(sdk, if (light) clockTick else if (strong) longPress else virtualKey, virtualKey)
            HapticEvent.ToggleOn -> if (light) {
                pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_TICK, clockTick)
            } else {
                pick(sdk, 34 to HapticFeedbackConstants.TOGGLE_ON, 30 to HapticFeedbackConstants.CONFIRM, virtualKey)
            }
            HapticEvent.ToggleOff -> if (light) {
                pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_TICK, clockTick)
            } else {
                pick(sdk, 34 to HapticFeedbackConstants.TOGGLE_OFF, virtualKey)
            }
            HapticEvent.Selection -> when {
                light -> pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, clockTick)
                strong -> pick(sdk, virtualKey)
                else -> pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_TICK, clockTick)
            }
            HapticEvent.Confirm -> pick(sdk, 30 to HapticFeedbackConstants.CONFIRM, virtualKey)
            HapticEvent.Reject -> pick(sdk, 30 to HapticFeedbackConstants.REJECT, virtualKey)
            HapticEvent.Attention -> pick(sdk, if (light) 30 to HapticFeedbackConstants.CONFIRM else longPress, longPress)
            HapticEvent.LongPress -> pick(sdk, longPress)
            HapticEvent.Tick -> when {
                light -> pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, clockTick)
                else -> pick(sdk, clockTick, virtualKey)
            }
            HapticEvent.DragStart -> pick(sdk, 34 to HapticFeedbackConstants.DRAG_START, longPress)
            HapticEvent.DragCross -> if (strong) {
                pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_TICK, clockTick)
            } else {
                pick(sdk, 34 to HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, clockTick)
            }
            HapticEvent.DragDrop -> pick(sdk, 30 to HapticFeedbackConstants.GESTURE_END, virtualKey)
        }
    }
}
