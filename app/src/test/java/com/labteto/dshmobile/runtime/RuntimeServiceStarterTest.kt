package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.runtime.RuntimeServiceStarter.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the app asks for the service: which call, how often, and what happens when Android says no. */
class RuntimeServiceStarterTest {
    private class FakePlatform : RuntimeServiceStarter.Platform {
        var visible = true
        var wanted = true

        /** Whether Android accepts each call. */
        var allowService = true
        var allowForeground = true

        val calls = mutableListOf<String>()

        override val appVisible get() = visible
        override fun wanted() = wanted
        override fun startService(): Boolean {
            calls += "startService"
            return allowService
        }

        override fun startForegroundService(): Boolean {
            calls += "startForegroundService"
            return allowForeground
        }
    }

    private val platform = FakePlatform()
    private var now = 0L
    private val starter = RuntimeServiceStarter(platform, nowMs = { now })

    @Test
    fun `with the app visible the service is started without the foreground clock`() {
        assertEquals(Result.STARTED, starter.ensureStarted())
        assertEquals(listOf("startService"), platform.calls)
    }

    @Test
    fun `with nothing visible only startForegroundService is left`() {
        platform.visible = false
        assertEquals(Result.STARTED_FOREGROUND, starter.ensureStarted())
        assertEquals(listOf("startForegroundService"), platform.calls)
    }

    @Test
    fun `a plain start refused by Android falls back to startForegroundService`() {
        platform.allowService = false
        assertEquals(Result.STARTED_FOREGROUND, starter.ensureStarted())
        assertEquals(listOf("startService", "startForegroundService"), platform.calls)
    }

    @Test
    fun `a request made while an earlier one is unanswered is not made`() {
        assertEquals(Result.STARTED, starter.ensureStarted())
        now = 5_000
        assertEquals(Result.PENDING, starter.ensureStarted())
        assertEquals(Result.PENDING, starter.ensureStarted())
        assertEquals(1, platform.calls.size)
    }

    @Test
    fun `an unanswered request does not block the next one for good`() {
        starter.ensureStarted()
        now = RuntimeServiceStarter.PENDING_MS
        assertEquals(Result.STARTED, starter.ensureStarted())
        assertEquals(2, platform.calls.size)
    }

    @Test
    fun `once the service is in the foreground nothing is asked`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        assertTrue(starter.isForeground)
        assertEquals(Result.ALREADY_FOREGROUND, starter.ensureStarted())
        assertEquals(1, platform.calls.size)
    }

    @Test
    fun `after the service stops the next request starts it again`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        starter.onServiceStopped()
        assertFalse(starter.isForeground)
        assertEquals(Result.STARTED, starter.ensureStarted())
        assertEquals(2, platform.calls.size)
    }

    @Test
    fun `a refusal is remembered and the request is made again when the app is visible`() {
        platform.visible = false
        platform.allowForeground = false
        assertEquals(Result.DEFERRED, starter.ensureStarted())

        // Still in the background: asking again is refused again, and still remembered.
        assertEquals(Result.DEFERRED, starter.onAppVisible())

        platform.visible = true
        platform.allowService = true
        platform.calls.clear()
        assertEquals(Result.STARTED, starter.onAppVisible())
        assertEquals(listOf("startService"), platform.calls)
        // Done: becoming visible again does not ask again.
        starter.onServiceForeground()
        assertNull(starter.onAppVisible())
    }

    @Test
    fun `a deferred request is dropped when there is nothing left to cover`() {
        platform.visible = false
        platform.allowForeground = false
        starter.ensureStarted()
        platform.wanted = false
        platform.visible = true
        platform.calls.clear()
        assertNull(starter.onAppVisible())
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `becoming visible with nothing deferred asks for nothing`() {
        assertNull(starter.onAppVisible())
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a service with nothing left to cover says it is going before it asks whether work arrived`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        var foregroundWhenAsked: Boolean? = null
        assertTrue(starter.tryRelease { foregroundWhenAsked = starter.isForeground; false })
        // Work that arrives after the question finds the service gone and asks for a new one.
        assertEquals(false, foregroundWhenAsked)
        platform.calls.clear()
        assertEquals(Result.STARTED, starter.ensureStarted())
        assertEquals(1, platform.calls.size)
    }

    @Test
    fun `a service that is asked to stop while work has arrived stays in the foreground`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        assertFalse(starter.tryRelease { true })
        assertTrue(starter.isForeground)
        platform.calls.clear()
        // The work that arrived asked and was told the service is up: no second request.
        assertEquals(Result.ALREADY_FOREGROUND, starter.ensureStarted())
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a service that Android refused to move to the foreground is asked for again when the app is visible`() {
        starter.ensureStarted()
        starter.onServiceRefused()
        assertFalse(starter.isForeground)
        platform.calls.clear()
        assertEquals(Result.STARTED, starter.onAppVisible())
        assertEquals(1, platform.calls.size)
    }
}
