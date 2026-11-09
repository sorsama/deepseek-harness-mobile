package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionListRefresh
import com.labteto.dshmobile.ui.components.PullRefreshPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRefreshCycleTest {
    private fun TestScope.cycle(
        read: suspend () -> SessionListRefresh = { SessionListRefresh.Done },
        onDone: () -> Unit = {},
        onFailed: (String?) -> Unit = {},
    ) = PullRefreshCycle(
        // Not backgroundScope: its delays never advance virtual time on their own, and this cycle
        // *is* the thing under test. As a child of the test, runTest drives it to completion.
        scope = this,
        read = read,
        onDone = onDone,
        onFailed = onFailed,
        now = { testScheduler.currentTime },
    )

    @Test
    fun `a second pull while a read is running does not read again`() = runTest {
        var reads = 0
        val cycle = cycle(read = {
            reads++
            delay(200)
            SessionListRefresh.Done
        })

        cycle.start()
        cycle.start()
        cycle.start()
        runCurrent()

        assertEquals(1, reads)
        advanceUntilIdle()
        assertEquals(PullRefreshPhase.Idle, cycle.phase.value)
    }

    @Test
    fun `a fast read keeps the spinner up long enough to see`() = runTest {
        val cycle = cycle()
        cycle.start()
        runCurrent()

        assertEquals(PullRefreshPhase.Refreshing, cycle.phase.value)
        testScheduler.advanceTimeBy(300)
        assertEquals(PullRefreshPhase.Refreshing, cycle.phase.value)
        testScheduler.advanceTimeBy(400)
        assertEquals(PullRefreshPhase.Done, cycle.phase.value)
        testScheduler.advanceTimeBy(700)
        assertEquals(PullRefreshPhase.Idle, cycle.phase.value)
    }

    @Test
    fun `a slow read is not padded`() = runTest {
        val cycle = cycle(read = {
            delay(900)
            SessionListRefresh.Done
        })
        cycle.start()

        testScheduler.advanceTimeBy(850)
        assertEquals(PullRefreshPhase.Refreshing, cycle.phase.value)
        testScheduler.advanceTimeBy(100)
        assertEquals(PullRefreshPhase.Done, cycle.phase.value)
        advanceUntilIdle()
        assertEquals(PullRefreshPhase.Idle, cycle.phase.value)
    }

    @Test
    fun `a failed read shows the cross and hands the reason back`() = runTest {
        var done = false
        var reason: String? = null
        val cycle = cycle(
            read = { SessionListRefresh.Failed("Session is busy") },
            onDone = { done = true },
            onFailed = { reason = it },
        )
        cycle.start()
        testScheduler.advanceTimeBy(700)

        assertEquals(PullRefreshPhase.Failed, cycle.phase.value)
        assertEquals("Session is busy", reason)
        assertFalse("a failed read is not a success", done)
        testScheduler.advanceTimeBy(700)
        assertEquals(PullRefreshPhase.Idle, cycle.phase.value)
    }

    @Test
    fun `a read with no connection has no reason to show`() = runTest {
        var called = false
        var reason: String? = "unset"
        val cycle = cycle(
            read = { SessionListRefresh.Failed() },
            onFailed = {
                called = true
                reason = it
            },
        )
        cycle.start()
        advanceUntilIdle()

        assertTrue(called)
        assertNull(reason)
    }

    @Test
    fun `a read that throws is a failure, not a crash`() = runTest {
        var reason: String? = null
        val cycle = cycle(
            read = { error("socket closed") },
            onFailed = { reason = it },
        )
        cycle.start()
        testScheduler.advanceTimeBy(700)

        assertEquals(PullRefreshPhase.Failed, cycle.phase.value)
        assertEquals("socket closed", reason)
        advanceUntilIdle()
    }

    @Test
    fun `the cycle can be pulled again once it has settled`() = runTest {
        var reads = 0
        val cycle = cycle(read = {
            reads++
            SessionListRefresh.Done
        })

        cycle.start()
        advanceUntilIdle()
        cycle.start()
        advanceUntilIdle()

        assertEquals(2, reads)
    }
}
