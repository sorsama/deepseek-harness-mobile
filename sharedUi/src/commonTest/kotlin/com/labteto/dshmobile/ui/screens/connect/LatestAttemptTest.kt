package com.labteto.dshmobile.ui.screens.connect

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * The connect pre-flight cancels for real: a Cancel that arrives while the probe is out means the
 * connect, the failure and the writes that follow it never happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestAttemptTest {

    @Test
    fun `cancelling stops the tail of the attempt from running`() = runTest {
        val attempt = LatestAttempt()
        val events = mutableListOf<String>()
        attempt.start(this) {
            events += "probe started"
            delay(5_000) // the probe
            ensureActive()
            events += "connected"
        }
        advanceTimeBy(1_000)
        attempt.cancel()
        advanceUntilIdle()
        assertEquals(listOf("probe started"), events)
        assertFalse(attempt.isActive)
    }

    @Test
    fun `an attempt that nobody cancels runs to its end`() = runTest {
        val attempt = LatestAttempt()
        val events = mutableListOf<String>()
        attempt.start(this) {
            delay(5_000)
            ensureActive()
            events += "connected"
        }
        advanceUntilIdle()
        assertEquals(listOf("connected"), events)
    }

    @Test
    fun `starting again stops the earlier attempt`() = runTest {
        val attempt = LatestAttempt()
        val events = mutableListOf<String>()
        attempt.start(this) {
            delay(5_000)
            ensureActive()
            events += "first"
        }
        advanceTimeBy(1_000)
        attempt.start(this) {
            delay(1_000)
            events += "second"
        }
        advanceUntilIdle()
        assertEquals(listOf("second"), events)
    }

    @Test
    fun `cancelling with nothing running is harmless`() {
        val scope = TestScope(StandardTestDispatcher())
        val attempt = LatestAttempt()
        attempt.cancel()
        assertFalse(attempt.isActive)
        attempt.start(scope) { delay(10) }
        assertTrue(attempt.isActive)
        attempt.cancel()
        assertFalse(attempt.isActive)
    }
}
