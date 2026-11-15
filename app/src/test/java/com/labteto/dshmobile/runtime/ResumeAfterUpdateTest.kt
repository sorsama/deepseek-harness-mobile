package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.runtime.ResumeAfterUpdate.Decision
import com.labteto.dshmobile.runtime.ResumeAfterUpdate.Marker
import com.labteto.dshmobile.termux.StartSignIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the built-in harness comes back by itself after an in-app update ([ResumeAfterUpdate]). */
class ResumeAfterUpdateTest {
    private val installed = InstalledRuntime(
        releaseId = "2026.11.15-2",
        sequence = 2,
        harnessVersion = "0.2.2",
        nodeVersion = "24",
        baseId = "base",
        harnessId = "harness",
    )
    private val running = RuntimeState.Running(3090, "0.2.2", 0L, StartSignIn.SIGNED_IN, installed)
    private val stopped = RuntimeState.Stopped(installed)
    private val now = 1_800_000_000_000L
    private val marker = Marker(port = 3090, fromVersionCode = 2200, atEpochMs = now - 60_000)

    // ---- Before the update ----

    @Test
    fun `a running harness is remembered with its own port`() {
        assertEquals(3090, ResumeAfterUpdate.portToResume(running, autoStart = false, lastPort = 3080))
        assertEquals(4000, ResumeAfterUpdate.portToResume(RuntimeState.Starting(4000, installed), autoStart = false, lastPort = null))
    }

    @Test
    fun `a harness the person stopped stays stopped`() {
        assertNull(ResumeAfterUpdate.portToResume(stopped, autoStart = false, lastPort = 3090))
        assertNull(ResumeAfterUpdate.portToResume(RuntimeState.Failed(RuntimeFailure.Network(null), installed), autoStart = false, lastPort = 3090))
    }

    @Test
    fun `set to start with the app it comes back on the last port used`() {
        assertEquals(3090, ResumeAfterUpdate.portToResume(stopped, autoStart = true, lastPort = 3090))
        assertEquals(ResumeAfterUpdate.DEFAULT_PORT, ResumeAfterUpdate.portToResume(stopped, autoStart = true, lastPort = null))
    }

    @Test
    fun `nothing installed means nothing to bring back`() {
        assertNull(ResumeAfterUpdate.portToResume(RuntimeState.NotInstalled, autoStart = true, lastPort = 3090))
        assertNull(ResumeAfterUpdate.portToResume(RuntimeState.Failed(RuntimeFailure.Network(null), null), autoStart = true, lastPort = 3090))
        assertNull(ResumeAfterUpdate.portToResume(RuntimeState.NotSupported(RuntimeUnsupported.BinariesMissing), autoStart = true, lastPort = 3090))
    }

    // ---- After the update ----

    @Test
    fun `no marker means no decision`() {
        assertNull(ResumeAfterUpdate.decide(null, 2201, now, stopped))
    }

    @Test
    fun `after the update a stopped harness is started on the remembered port`() {
        assertEquals(Decision.Start(3090), ResumeAfterUpdate.decide(marker, 2201, now, stopped))
    }

    @Test
    fun `a harness that failed before the update is started too when it is still installed`() {
        val failed = RuntimeState.Failed(RuntimeFailure.Network(null), installed)
        assertEquals(Decision.Start(3090), ResumeAfterUpdate.decide(marker, 2201, now, failed))
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(marker, 2201, now, RuntimeState.Failed(RuntimeFailure.Network(null), null)))
    }

    @Test
    fun `the same build that wrote the marker waits because the update has not landed`() {
        assertEquals(Decision.Wait, ResumeAfterUpdate.decide(marker, 2200, now, stopped))
        assertEquals(Decision.Wait, ResumeAfterUpdate.decide(marker, 2199, now, stopped))
    }

    @Test
    fun `already up is done`() {
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(marker, 2201, now, running))
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(marker, 2201, now, RuntimeState.Starting(3090, installed)))
    }

    @Test
    fun `a busy runtime waits for the next chance`() {
        val updating = RuntimeState.Updating(UpdatePhase.Checking, installed)
        assertEquals(Decision.Wait, ResumeAfterUpdate.decide(marker, 2201, now, updating))
        assertEquals(Decision.Wait, ResumeAfterUpdate.decide(marker, 2201, now, RuntimeState.Stopping(3090, installed)))
    }

    @Test
    fun `a runtime removed in between is not started`() {
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(marker, 2201, now, RuntimeState.NotInstalled))
    }

    @Test
    fun `an old marker expires`() {
        val old = marker.copy(atEpochMs = now - ResumeAfterUpdate.TTL_MS - 1)
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(old, 2201, now, stopped))
        val inTheFuture = marker.copy(atEpochMs = now + 60L * 60 * 1000)
        assertEquals(Decision.Drop, ResumeAfterUpdate.decide(inTheFuture, 2201, now, stopped))
        val slightlyAhead = marker.copy(atEpochMs = now + 60_000)
        assertEquals(Decision.Start(3090), ResumeAfterUpdate.decide(slightlyAhead, 2201, now, stopped))
    }
}
