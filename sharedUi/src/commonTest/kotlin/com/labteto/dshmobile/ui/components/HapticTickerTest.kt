package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/** The Detent-style rules for a tick stream: throttle, speed, settle. */
class TickGovernorTest {

    /** Crossings every [gapMs] for [count] crossings from [start]; the weights the governor returned. */
    private fun run(governor: TickGovernor, start: Long, gapMs: Long, count: Int): List<Float> =
        (0 until count).map { governor.onCross(start + it * gapMs) }

    @Test
    fun `the first crossing always plays at full weight`() {
        assertEquals(1f, TickGovernor().onCross(1_000), 0f)
    }

    @Test
    fun `a crossing inside the minimum gap is skipped`() {
        val governor = TickGovernor(minGapMs = 35)
        assertEquals(1f, governor.onCross(0), 0f)
        assertEquals(0f, governor.onCross(10), 0f)
        assertEquals(0f, governor.onCross(34), 0f)
        assertTrue(governor.onCross(70) > 0f)
    }

    @Test
    fun `the gap is measured from the last tick that played`() {
        val governor = TickGovernor(minGapMs = 35)
        governor.onCross(0)
        governor.onCross(20) // skipped
        // 40 ms after the tick that played, though only 20 ms after the skipped crossing.
        assertTrue(governor.onCross(40) > 0f)
    }

    @Test
    fun `slow movement plays every tick at full weight`() {
        val weights = run(TickGovernor(), start = 0, gapMs = 120, count = 10)
        assertTrue(weights.all { it == 1f })
    }

    @Test
    fun `ticks get lighter as the movement speeds up`() {
        // About 17 crossings a second: between full and thinning.
        val weights = run(TickGovernor(), start = 0, gapMs = 60, count = 12)
        val last = weights.last()
        assertTrue("last $last", last < 1f && last > 0.4f)
        assertTrue(weights.zipWithNext().all { (a, b) -> b <= a + 1e-4f })
    }

    @Test
    fun `very fast movement drops every other tick and plays the rest light`() {
        // 40 ms apart is 25 a second: past the thinning threshold, and just over the minimum gap.
        val weights = run(TickGovernor(), start = 0, gapMs = 40, count = 14)
        val settled = weights.drop(6)
        assertTrue("some skipped: $settled", settled.any { it == 0f })
        assertTrue("some played: $settled", settled.any { it > 0f })
        settled.filter { it > 0f }.forEach { assertEquals(0.4f, it, 1e-4f) }
        // Every other one is dropped, so about half of them play.
        assertEquals(settled.size / 2, settled.count { it > 0f })
    }

    @Test
    fun `the played rate never exceeds the minimum gap`() {
        val governor = TickGovernor(minGapMs = 35)
        val played = (0 until 200).count { governor.onCross(it * 5L) > 0f }
        // 1000 ms of crossings every 5 ms: at most one tick per 35 ms.
        assertTrue("played $played", played <= 1000 / 35 + 1)
    }

    @Test
    fun `a pause starts speed again from scratch`() {
        val governor = TickGovernor()
        run(governor, start = 0, gapMs = 40, count = 10) // fast
        assertEquals(1f, governor.onCross(10_000), 0f)
        // The next one after the pause is the first of a new movement, so it is not fast either.
        assertEquals(1f, governor.onCross(10_120), 0f)
    }

    @Test
    fun `settle always plays but not twice in a row`() {
        val governor = TickGovernor(settleDebounceMs = 80)
        assertTrue(governor.onSettle(1_000))
        assertFalse(governor.onSettle(1_030))
        assertTrue(governor.onSettle(1_100))
    }

    @Test
    fun `settle plays even right after a crossing tick`() {
        val governor = TickGovernor()
        governor.onCross(500)
        assertTrue(governor.onSettle(505))
    }

    @Test
    fun `settle ends the movement so the next crossing is a fresh start`() {
        val governor = TickGovernor()
        run(governor, start = 0, gapMs = 40, count = 10)
        governor.onSettle(400)
        assertEquals(1f, governor.onCross(600), 0f)
    }

    @Test
    fun `reset forgets everything`() {
        val governor = TickGovernor()
        governor.onCross(0)
        governor.onSettle(10)
        governor.reset()
        assertEquals(1f, governor.onCross(5), 0f)
        assertTrue(governor.onSettle(20))
    }
}

class HapticTickerTest {

    private class Recorder {
        val played = mutableListOf<Pair<HapticEvent, Float>>()
        var now = 0L
        val ticker = HapticTicker(play = { event, weight -> played += event to weight }, clock = { now })
    }

    @Test
    fun `cross plays a drag tick and settle plays a drop`() {
        val r = Recorder()
        r.ticker.cross()
        r.now = 100
        r.ticker.settle()
        assertEquals(listOf(HapticEvent.DragCross to 1f, HapticEvent.DragDrop to 1f), r.played)
    }

    @Test
    fun `one remembered ticker throttles across calls`() {
        val r = Recorder()
        repeat(6) {
            r.now = it * 5L
            r.ticker.cross()
        }
        assertEquals(1, r.played.size)
    }

    @Test
    fun `a ticker made again each time would never throttle`() {
        // The mistake the shared ticker exists to prevent: a fresh governor forgets its last tick.
        var plays = 0
        repeat(6) {
            val fresh = HapticTicker(play = { _, _ -> plays++ }, clock = { it * 5L })
            fresh.cross()
        }
        assertEquals(6, plays)
    }

    @Test
    fun `reset lets the next crossing play at once`() {
        val r = Recorder()
        r.ticker.cross()
        r.now = 5
        r.ticker.reset()
        r.ticker.cross()
        assertEquals(2, r.played.size)
    }
}

class DetentCounterTest {
    @Test
    fun `the first value is only a starting point`() {
        assertEquals(0, DetentCounter(10f).crossed(37f))
    }

    @Test
    fun `staying inside one cell crosses nothing`() {
        val counter = DetentCounter(10f)
        counter.crossed(31f)
        assertEquals(0, counter.crossed(38f))
        assertEquals(0, counter.crossed(30f))
    }

    @Test
    fun `moving up and down counts each detent passed`() {
        val counter = DetentCounter(10f)
        counter.crossed(5f)
        assertEquals(1, counter.crossed(12f))
        assertEquals(3, counter.crossed(41f))
        assertEquals(2, counter.crossed(22f))
    }

    @Test
    fun `negative values work`() {
        val counter = DetentCounter(10f)
        counter.crossed(5f)
        assertEquals(1, counter.crossed(-3f))
    }

    @Test
    fun `reset starts a new gesture`() {
        val counter = DetentCounter(10f)
        counter.crossed(5f)
        counter.reset()
        assertEquals(0, counter.crossed(95f))
    }
}
