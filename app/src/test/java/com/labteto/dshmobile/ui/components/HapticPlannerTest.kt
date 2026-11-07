package com.labteto.dshmobile.ui.components

import android.view.HapticFeedbackConstants
import com.labteto.dshmobile.ui.theme.HapticStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The choice of effect for every kind of phone, against made-up capability models. The Android
 * half (HapticEngine) can only be checked on a device; everything it plays comes from here.
 */
class HapticPlannerTest {

    private val allPrimitives = HapticPrimitive.entries.toSet()

    /** A modern phone: a rich motor that supports everything. */
    private val rich = HapticCaps(sdk = 35, hasVibrator = true, hasAmplitudeControl = true, primitives = allPrimitives)

    private val api30Primitives = setOf(
        HapticPrimitive.Click, HapticPrimitive.Tick, HapticPrimitive.QuickRise,
        HapticPrimitive.QuickFall, HapticPrimitive.SlowRise,
    )

    private fun plan(
        event: HapticEvent,
        caps: HapticCaps = rich,
        strength: HapticStrength = HapticStrength.Normal,
        weight: Float = 1f,
    ) = HapticPlanner.plan(event, caps, strength, weight)

    // ---- Tier selection -----------------------------------------------------------------

    @Test
    fun `a phone with no vibrator is silent on every path`() {
        val none = rich.copy(hasVibrator = false)
        assertEquals(HapticTier.Silent, HapticPlanner.tier(none))
        HapticEvent.entries.forEach { assertEquals(HapticPlan.Silent, plan(it, none)) }
    }

    @Test
    fun `touch feedback off in the system plays nothing`() {
        val off = rich.copy(touchFeedbackOn = false)
        HapticEvent.entries.forEach { assertEquals(HapticPlan.Silent, plan(it, off)) }
    }

    @Test
    fun `primitives win when the phone supports click and tick`() {
        assertEquals(HapticTier.Primitives, HapticPlanner.tier(rich))
        assertEquals(HapticTier.Primitives, HapticPlanner.tier(rich.copy(sdk = 30, primitives = api30Primitives)))
    }

    @Test
    fun `without click and tick the phone drops to predefined effects`() {
        val noClick = rich.copy(primitives = allPrimitives - HapticPrimitive.Click)
        val noTick = rich.copy(primitives = allPrimitives - HapticPrimitive.Tick)
        assertEquals(HapticTier.Predefined, HapticPlanner.tier(noClick))
        assertEquals(HapticTier.Predefined, HapticPlanner.tier(noTick))
        assertEquals(HapticTier.Predefined, HapticPlanner.tier(rich.copy(primitives = emptySet())))
    }

    @Test
    fun `api 29 has predefined effects but no primitives`() {
        val api29 = rich.copy(sdk = 29, primitives = allPrimitives)
        // Even a primitive set the phone claims is ignored below API 30.
        assertEquals(HapticTier.Predefined, HapticPlanner.tier(api29))
    }

    @Test
    fun `api 26 to 28 use a one shot only with amplitude control`() {
        val withAmp = HapticCaps(sdk = 28, hasVibrator = true, hasAmplitudeControl = true)
        val without = withAmp.copy(hasAmplitudeControl = false)
        assertEquals(HapticTier.OneShot, HapticPlanner.tier(withAmp))
        assertEquals(HapticTier.ViewConstants, HapticPlanner.tier(without))
    }

    @Test
    fun `a failed direct call falls back to the view constants`() {
        assertEquals(HapticTier.ViewConstants, HapticPlanner.tier(rich.copy(directOk = false)))
        assertTrue(plan(HapticEvent.Tap, rich.copy(directOk = false)) is HapticPlan.ViewConstant)
    }

    // ---- Tier 1: primitives -------------------------------------------------------------

    @Test
    fun `a composition only ever holds primitives the phone supports`() {
        // A phone that has the two basics and nothing else: every effect must still resolve to
        // those, because one unsupported primitive makes the whole composition play nothing.
        val basics = rich.copy(primitives = setOf(HapticPrimitive.Click, HapticPrimitive.Tick))
        HapticEvent.entries.forEach { event ->
            val composition = plan(event, basics) as HapticPlan.Composition
            assertTrue(composition.steps.isNotEmpty())
            composition.steps.forEach { step -> assertTrue("$event uses ${step.primitive}", basics.supports(step.primitive)) }
        }
    }

    @Test
    fun `thud and low tick are not used below api 31 even if reported`() {
        val claims = rich.copy(sdk = 30, primitives = allPrimitives)
        HapticEvent.entries.forEach { event ->
            val composition = plan(event, claims) as HapticPlan.Composition
            composition.steps.forEach {
                assertNotEquals(HapticPrimitive.Thud, it.primitive)
                assertNotEquals(HapticPrimitive.LowTick, it.primitive)
            }
        }
    }

    @Test
    fun `an unsupported thud becomes a click so reject still lands`() {
        val noThud = rich.copy(primitives = allPrimitives - HapticPrimitive.Thud)
        val reject = plan(HapticEvent.Reject, noThud) as HapticPlan.Composition
        assertEquals(listOf(HapticPrimitive.Click, HapticPrimitive.Click), reject.steps.map { it.primitive })
    }

    @Test
    fun `a supported thud is used for reject and long press`() {
        val reject = plan(HapticEvent.Reject) as HapticPlan.Composition
        val hold = plan(HapticEvent.LongPress) as HapticPlan.Composition
        assertEquals(listOf(HapticPrimitive.Thud, HapticPrimitive.Thud), reject.steps.map { it.primitive })
        assertEquals(listOf(HapticPrimitive.Thud), hold.steps.map { it.primitive })
    }

    @Test
    fun `scales stay between the floor and one at every strength`() {
        HapticStrength.entries.forEach { strength ->
            HapticEvent.entries.forEach { event ->
                (plan(event, rich, strength) as HapticPlan.Composition).steps.forEach {
                    assertTrue("$event $strength ${it.scale}", it.scale >= HapticPlanner.MIN_SCALE && it.scale <= 1f)
                }
            }
        }
    }

    @Test
    fun `light is lighter than normal and strong is stronger, for every event`() {
        HapticEvent.entries.forEach { event ->
            fun peak(strength: HapticStrength) =
                (plan(event, rich, strength) as HapticPlan.Composition).steps.maxOf { it.scale }
            assertTrue("$event light < normal", peak(HapticStrength.Light) < peak(HapticStrength.Normal))
            // Attention already peaks at a full click, so Strong can only match it there.
            assertTrue("$event strong >= normal", peak(HapticStrength.Strong) >= peak(HapticStrength.Normal))
        }
    }

    @Test
    fun `frequent effects are lighter than decisions`() {
        fun peak(event: HapticEvent) = (plan(event) as HapticPlan.Composition).steps.maxOf { it.scale }
        assertTrue(peak(HapticEvent.DragCross) < peak(HapticEvent.Tap))
        assertTrue(peak(HapticEvent.Selection) < peak(HapticEvent.Confirm))
        assertTrue(peak(HapticEvent.Tick) < peak(HapticEvent.DragDrop))
        assertTrue(peak(HapticEvent.Tap) < peak(HapticEvent.Attention))
    }

    @Test
    fun `the weight scales a composition`() {
        fun scale(weight: Float) = (plan(HapticEvent.DragCross, weight = weight) as HapticPlan.Composition).steps.single().scale
        assertTrue(scale(0.5f) < scale(1f))
        assertEquals(scale(1f), scale(1.7f), 0f)
    }

    @Test
    fun `toggle on and toggle off are different effects`() {
        assertNotEquals(plan(HapticEvent.ToggleOn), plan(HapticEvent.ToggleOff))
    }

    @Test
    fun `drop is firmer than a crossing tick`() {
        val cross = (plan(HapticEvent.DragCross) as HapticPlan.Composition).steps.single()
        val drop = (plan(HapticEvent.DragDrop) as HapticPlan.Composition).steps.single()
        assertTrue(drop.scale > cross.scale)
    }

    @Test
    fun `a zero weight plays nothing`() {
        assertEquals(HapticPlan.Silent, plan(HapticEvent.DragCross, weight = 0f))
    }

    // ---- Tier 2: predefined -------------------------------------------------------------

    private val predefined = HapticCaps(sdk = 29, hasVibrator = true, hasAmplitudeControl = false)

    @Test
    fun `predefined effects step one rung with the strength`() {
        fun preset(event: HapticEvent, strength: HapticStrength) =
            (plan(event, predefined, strength) as HapticPlan.Predefined).preset
        assertEquals(HapticPreset.Tick, preset(HapticEvent.Tap, HapticStrength.Light))
        assertEquals(HapticPreset.Click, preset(HapticEvent.Tap, HapticStrength.Normal))
        assertEquals(HapticPreset.HeavyClick, preset(HapticEvent.Tap, HapticStrength.Strong))
        assertEquals(HapticPreset.Tick, preset(HapticEvent.DragCross, HapticStrength.Normal))
        assertEquals(HapticPreset.Click, preset(HapticEvent.DragCross, HapticStrength.Strong))
        assertEquals(HapticPreset.DoubleClick, preset(HapticEvent.Confirm, HapticStrength.Normal))
    }

    @Test
    fun `every event has a predefined effect`() {
        HapticStrength.entries.forEach { strength ->
            HapticEvent.entries.forEach { assertTrue(plan(it, predefined, strength) is HapticPlan.Predefined) }
        }
    }

    @Test
    fun `tiers that cannot scale skip a light tick instead of playing it full`() {
        assertEquals(HapticPlan.Silent, plan(HapticEvent.DragCross, predefined, weight = 0.4f))
        assertTrue(plan(HapticEvent.DragCross, predefined, weight = 0.6f) is HapticPlan.Predefined)
    }

    // ---- Tier 3: one-shot ---------------------------------------------------------------

    private val oneShot = HapticCaps(sdk = 27, hasVibrator = true, hasAmplitudeControl = true)

    @Test
    fun `a one shot is short and never buzzy`() {
        HapticStrength.entries.forEach { strength ->
            HapticEvent.entries.forEach { event ->
                val shot = plan(event, oneShot, strength) as HapticPlan.OneShot
                assertTrue("$event ${shot.millis}ms", shot.millis in 1..HapticPlanner.MAX_ONE_SHOT_MS)
                assertTrue("$event amp ${shot.amplitude}", shot.amplitude in 1..255)
            }
        }
    }

    @Test
    fun `a one shot amplitude follows strength and weight`() {
        fun amp(strength: HapticStrength, weight: Float = 1f) =
            (plan(HapticEvent.Tap, oneShot, strength, weight) as HapticPlan.OneShot).amplitude
        assertTrue(amp(HapticStrength.Light) < amp(HapticStrength.Normal))
        assertTrue(amp(HapticStrength.Normal) < amp(HapticStrength.Strong))
        assertTrue(amp(HapticStrength.Normal, 0.5f) < amp(HapticStrength.Normal))
    }

    // ---- Tier 4: view constants ---------------------------------------------------------

    private val legacy = HapticCaps(sdk = 26, hasVibrator = true, hasAmplitudeControl = false)

    /**
     * The constant at [sdk]. Above API 28 the View tier is only reached after a direct call has
     * failed (otherwise the predefined effects play), hence `directOk = false`.
     */
    private fun constant(event: HapticEvent, sdk: Int, strength: HapticStrength = HapticStrength.Normal) =
        (plan(event, legacy.copy(sdk = sdk, directOk = false), strength) as HapticPlan.ViewConstant).constant

    @Test
    fun `view constants never name an api the phone lacks`() {
        // CONFIRM and REJECT are API 30, TOGGLE and the segment ticks API 34.
        val api30Only = setOf(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.REJECT, HapticFeedbackConstants.GESTURE_END)
        val api34Only = setOf(
            HapticFeedbackConstants.TOGGLE_ON, HapticFeedbackConstants.TOGGLE_OFF,
            HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
            HapticFeedbackConstants.DRAG_START,
        )
        HapticStrength.entries.forEach { strength ->
            HapticEvent.entries.forEach { event ->
                (26..29).forEach { sdk ->
                    val c = constant(event, sdk, strength)
                    assertFalse("$event at $sdk uses $c", c in api30Only || c in api34Only)
                }
                (30..33).forEach { sdk ->
                    val c = constant(event, sdk, strength)
                    assertFalse("$event at $sdk uses $c", c in api34Only)
                }
            }
        }
    }

    @Test
    fun `newer phones get the newer constants`() {
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, constant(HapticEvent.ToggleOn, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, constant(HapticEvent.ToggleOff, 34))
        assertEquals(HapticFeedbackConstants.CONFIRM, constant(HapticEvent.Confirm, 30))
        assertEquals(HapticFeedbackConstants.REJECT, constant(HapticEvent.Reject, 30))
        assertEquals(HapticFeedbackConstants.DRAG_START, constant(HapticEvent.DragStart, 34))
        assertEquals(HapticFeedbackConstants.GESTURE_END, constant(HapticEvent.DragDrop, 30))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constant(HapticEvent.Confirm, 26))
    }

    @Test
    fun `tap steps through the constants with the strength`() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, constant(HapticEvent.Tap, 26, HapticStrength.Light))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constant(HapticEvent.Tap, 26, HapticStrength.Normal))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constant(HapticEvent.Tap, 26, HapticStrength.Strong))
    }

    @Test
    fun `the view tier also skips light ticks`() {
        assertEquals(HapticPlan.Silent, plan(HapticEvent.DragCross, legacy, weight = 0.3f))
    }

    @Test
    fun `every event on every tier and strength produces something`() {
        val phones = listOf(rich, rich.copy(sdk = 30, primitives = api30Primitives), predefined, oneShot, legacy)
        phones.forEach { caps ->
            HapticStrength.entries.forEach { strength ->
                HapticEvent.entries.forEach { event ->
                    assertNotEquals("$event on ${HapticPlanner.tier(caps)}", HapticPlan.Silent, plan(event, caps, strength))
                }
            }
        }
    }
}
