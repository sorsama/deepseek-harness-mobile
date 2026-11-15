package com.labteto.dshmobile.connection

import com.labteto.dshmobile.runtime.RuntimeServiceStarter
import com.labteto.dshmobile.runtime.RuntimeServiceStarter.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The keep-connected service ([ConnectionService]) asked for the way [ConnectionManager] does it:
 * through [RuntimeServiceStarter], with Android's refusals turned into `false` by
 * [serviceStartAccepted]. Before, every (re)connect called `startForegroundService` directly, so
 * a reconnect in the background on Android 12+ threw, and every reconnect opened a new foreground
 * clock even with the service already up.
 */
class ConnectionServiceStartTest {
    /** Android as the manager meets it: each call either goes through or throws. */
    private class Android : RuntimeServiceStarter.Platform {
        var visible = true
        var wantedNow = true
        var refuseBackgroundStarts = false
        val calls = mutableListOf<String>()

        override val appVisible get() = visible
        override fun wanted() = wantedNow

        override fun startService(): Boolean = serviceStartAccepted {
            calls += "startService"
            if (!visible) throw IllegalStateException("BackgroundServiceStartNotAllowedException")
        }

        override fun startForegroundService(): Boolean = serviceStartAccepted {
            calls += "startForegroundService"
            if (!visible && refuseBackgroundStarts) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
    }

    private val android = Android()
    private var now = 0L
    private val starter = RuntimeServiceStarter(android, nowMs = { now })

    @Test
    fun `a refusal is false, not a crash`() {
        val refused = mutableListOf<RuntimeException>()
        assertFalse(serviceStartAccepted(onRefused = { refused += it }) { throw IllegalStateException("not allowed") })
        assertFalse(serviceStartAccepted(onRefused = { refused += it }) { throw SecurityException("no permission") })
        assertTrue(serviceStartAccepted { })
        assertEquals(2, refused.size)
    }

    @Test
    fun `connecting with the app on screen uses startService, so no foreground clock runs`() {
        assertEquals(Result.STARTED, starter.ensureStarted())
        assertEquals(listOf("startService"), android.calls)
    }

    @Test
    fun `reconnects while the service is up ask for nothing`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        repeat(5) { assertEquals(Result.ALREADY_FOREGROUND, starter.ensureStarted()) }
        assertEquals(listOf("startService"), android.calls)
    }

    @Test
    fun `a background reconnect that Android refuses waits for the app to be on screen`() {
        android.visible = false
        android.refuseBackgroundStarts = true
        assertEquals(Result.DEFERRED, starter.ensureStarted())
        assertEquals(listOf("startForegroundService"), android.calls)
        // Back on screen: asked again, the clock-free way.
        android.visible = true
        assertEquals(Result.STARTED, starter.onAppVisible())
        assertEquals("startService", android.calls.last())
    }

    @Test
    fun `a refusal is not retried after a disconnect`() {
        android.visible = false
        android.refuseBackgroundStarts = true
        starter.ensureStarted()
        android.wantedNow = false
        android.visible = true
        assertNull(starter.onAppVisible())
        assertEquals(1, android.calls.size)
    }

    @Test
    fun `a service that left the foreground after a refusal comes back when the app is next visible`() {
        starter.ensureStarted()
        starter.onServiceForeground()
        starter.onServiceRefused()
        starter.onServiceStopped()
        assertFalse(starter.isForeground)
        android.visible = true
        assertEquals(Result.STARTED, starter.onAppVisible())
    }

    /**
     * Both services are `specialUse` with a subtype, and `dataSync` (six hours a day on Android 15)
     * is gone together with its permission. A plain text scan: the manifest is small and stable.
     */
    @Test
    fun `both foreground services are specialUse and dataSync is gone`() {
        val manifest = (File("src/main/AndroidManifest.xml").takeIf { it.isFile } ?: File("app/src/main/AndroidManifest.xml")).readText()
        assertFalse("dataSync" in manifest.replace(Regex("(?s)<!--.*?-->"), ""))
        assertFalse("FOREGROUND_SERVICE_DATA_SYNC" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest)
        for (service in listOf(".connection.ConnectionService", ".runtime.HarnessRuntimeService")) {
            val block = Regex("""(?s)<service\s+android:name="${Regex.escape(service)}".*?</service>""").find(manifest)?.value
            assertTrue("$service has no <service> block with a body", block != null)
            assertTrue("$service is not specialUse", "android:foregroundServiceType=\"specialUse\"" in block!!)
            assertTrue("$service has no subtype", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in block)
        }
    }

    @Test
    fun `a service the system refused to put in the foreground is asked for again when visible`() {
        // onCreate's startForeground threw (started from the background without an exemption).
        android.visible = false
        assertEquals(Result.STARTED_FOREGROUND, starter.ensureStarted())
        starter.onServiceRefused()
        android.visible = true
        assertEquals(Result.STARTED, starter.onAppVisible())
    }
}
