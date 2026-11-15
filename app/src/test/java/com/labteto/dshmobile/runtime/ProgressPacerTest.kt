package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressPacerTest {
    private var now = 0L
    private val pacer = ProgressPacer(minIntervalMs = 250, nowMs = { now })

    @Test
    fun `the first event of a step is always let through`() {
        assertTrue(pacer.admit("download:base"))
    }

    @Test
    fun `events inside the interval are dropped and the first one after it is let through`() {
        assertTrue(pacer.admit("download:base"))
        now = 100
        assertFalse(pacer.admit("download:base"))
        now = 249
        assertFalse(pacer.admit("download:base"))
        now = 250
        assertTrue(pacer.admit("download:base"))
        // The interval counts from the last event let through, not from the last one seen.
        now = 400
        assertFalse(pacer.admit("download:base"))
        now = 500
        assertTrue(pacer.admit("download:base"))
    }

    @Test
    fun `a new step is let through at once whatever the interval`() {
        assertTrue(pacer.admit("download:base"))
        now = 1
        assertTrue(pacer.admit("download:harness"))
        now = 2
        assertTrue(pacer.admit("unpack:harness"))
        now = 3
        assertFalse(pacer.admit("unpack:harness"))
    }

    @Test
    fun `the last event of a step is always let through`() {
        assertTrue(pacer.admit("unpack:base"))
        now = 1
        assertFalse(pacer.admit("unpack:base"))
        now = 2
        assertTrue(pacer.admit("unpack:base", last = true))
    }

    @Test
    fun `a flood of events produces a bounded number of states`() {
        // 10,000 events over 10 seconds, one every millisecond: the pace is four a second.
        var let = 0
        for (t in 0 until 10_000) {
            now = t.toLong()
            if (pacer.admit("download:base")) let++
        }
        assertEquals(40, let)
    }

    @Test
    fun `reset lets the next event through`() {
        assertTrue(pacer.admit("download:base"))
        now = 10
        assertFalse(pacer.admit("download:base"))
        pacer.reset()
        assertTrue(pacer.admit("download:base"))
    }
}
