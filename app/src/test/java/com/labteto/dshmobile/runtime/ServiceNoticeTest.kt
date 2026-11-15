package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.runtime.ServiceNotice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNoticeTest {
    private val installed = InstalledRuntime(
        releaseId = "r1",
        sequence = 1,
        harnessVersion = "1.0.0",
        nodeVersion = "24.0.0",
        baseId = "trixie-r1",
        harnessId = "dsh-1.0.0",
    )

    private fun download(done: Long, total: Long = 1000) =
        RuntimeState.Downloading(DownloadProgress(LAYER_BASE, 0, 2, done, total))

    private fun unpack(done: Int, total: Int = 100) =
        RuntimeState.Unpacking(UnpackProgress(LAYER_BASE, 0, 2, done, total, 0))

    @Test
    fun `progress inside one percent is the same notice`() {
        // A thousand steps are at most a hundred and one distinct notices: the service posts only on a difference.
        val notices = (0..1000L step 3).map { download(it).toServiceNotice() }.distinct()
        assertTrue(notices.size <= 101)
        assertEquals(download(10).toServiceNotice(), download(14).toServiceNotice())
        assertEquals(ServiceNotice(Kind.DOWNLOADING, percent = 1, cancellable = true), download(10).toServiceNotice())
    }

    @Test
    fun `an unpack is its own notice with its percent`() {
        assertEquals(ServiceNotice(Kind.UNPACKING, percent = 42, cancellable = true), unpack(42).toServiceNotice())
        assertEquals(unpack(42).toServiceNotice(), unpack(42).toServiceNotice())
    }

    @Test
    fun `checking and verifying are an indeterminate notice that can be cancelled`() {
        val verifying = RuntimeState.Verifying(LAYER_BASE).toServiceNotice()
        assertEquals(ServiceNotice(Kind.WORKING, cancellable = true), verifying)
        val checking = RuntimeState.Updating(UpdatePhase.Checking, installed).toServiceNotice()
        assertEquals(ServiceNotice(Kind.WORKING, cancellable = true), checking)
    }

    @Test
    fun `an update that is switching over cannot be cancelled`() {
        val switching = RuntimeState.Updating(UpdatePhase.Switching, installed).toServiceNotice()
        assertFalse(switching.cancellable)
    }

    @Test
    fun `an update shows the phase it is in`() {
        val progress = DownloadProgress(LAYER_HARNESS, 1, 2, 500, 1000)
        assertEquals(
            ServiceNotice(Kind.DOWNLOADING, percent = 50, cancellable = true),
            RuntimeState.Updating(UpdatePhase.Downloading(progress), installed).toServiceNotice(),
        )
    }

    @Test
    fun `running starting and stopping`() {
        assertEquals(Kind.STARTING, RuntimeState.Starting(3080, installed).toServiceNotice().kind)
        assertEquals(Kind.STOPPING, RuntimeState.Stopping(3080, installed).toServiceNotice().kind)
    }

    @Test
    fun `the service is needed while work is in flight or the harness runs`() {
        assertTrue(download(1).needsService())
        assertTrue(RuntimeState.Starting(3080, installed).needsService())
        assertTrue(RuntimeState.Stopping(3080, installed).needsService())
        assertFalse(RuntimeState.Stopped(installed).needsService())
        assertFalse(RuntimeState.NotInstalled.needsService())
        assertFalse(RuntimeState.Failed(RuntimeFailure.Network(null), null).needsService())
    }
}
