package com.labteto.dshmobile.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scripts are shell source assembled from a port and two URLs, and the app reads them back
 * through `DSHM:` markers. What has to hold: every value spliced in is validated and quoted, the
 * start script asks for exactly the harness flags Android needs, and every marker the scripts
 * can print is one the app can name.
 */
class TermuxScriptsTest {

    @Test
    fun `the start script runs dsh web on the given port without opening a browser`() {
        val script = TermuxScripts.startHarness(3081)
        assertTrue(script.contains("dsh web --no-open --port \"\$PORT\""))
        assertTrue(script.contains("PORT=3081"))
    }

    /** Termux stops its service, and everything it spawned, once nothing holds it up. */
    @Test
    fun `the start script takes a wake lock and the stop script releases it`() {
        assertTrue(TermuxScripts.startHarness(3080).contains("termux-wake-lock"))
        assertTrue(TermuxScripts.stopHarness().contains("termux-wake-unlock"))
    }

    @Test
    fun `the start script waits for the readiness line and prints it`() {
        val script = TermuxScripts.startHarness(3080)
        assertTrue(script.contains("grep -m1 'dsh web: http'"))
        assertTrue(script.contains("DSHM:started"))
        assertTrue(script.contains("DSHM:already-running"))
        assertTrue(script.contains("DSHM:exited"))
        assertTrue(script.contains("DSHM:timeout"))
    }

    @Test
    fun `the start script checks for node and dsh before anything else`() {
        val script = TermuxScripts.startHarness(3080)
        val launch = script.indexOf("nohup dsh web")
        assertTrue(launch > 0)
        assertTrue(script.indexOf("command -v node") < launch)
        assertTrue(script.indexOf("command -v dsh") < launch)
        assertTrue(script.contains("DSHM:missing-node"))
        assertTrue(script.contains("DSHM:missing-dsh"))
    }

    @Test
    fun `an impossible port is refused before it reaches a shell`() {
        assertTrue(runCatching { TermuxScripts.startHarness(0) }.isFailure)
        assertTrue(runCatching { TermuxScripts.startHarness(70_000) }.isFailure)
    }

    /** The stop script only ever signals the pid it recorded; never a guess. */
    @Test
    fun `the stop script signals the recorded process and never pkills`() {
        val script = TermuxScripts.stopHarness()
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
        assertFalse(script.contains("pkill"))
        assertTrue(script.contains("DSHM:not-running"))
        assertTrue(script.contains("DSHM:stopped"))
    }

    @Test
    fun `the install script verifies against the checksum file when there is one`() {
        val url = "https://github.com/sorsama/deepseek-harness-mobile/releases/download/v0.11.0/app-release.apk"
        val sums = "https://github.com/sorsama/deepseek-harness-mobile/releases/download/v0.11.0/SHA256SUMS.txt"
        val script = TermuxScripts.installApk(url, "app-release.apk", sums)
        assertTrue(script.contains("curl -fL --retry 3 -o \"\$APK\" '$url'"))
        assertTrue(script.contains("sha256sum -c --ignore-missing SHA256SUMS.txt"))
        assertTrue(script.contains("termux-open --content-type application/vnd.android.package-archive"))
        assertTrue(script.contains("DSHM:installing"))
    }

    @Test
    fun `the install script skips verification when no checksum file was published`() {
        val script = TermuxScripts.installApk("https://example.com/app-release.apk", "app-release.apk", null)
        assertFalse(script.contains("sha256sum"))
        assertTrue(script.contains("termux-open"))
    }

    /** The URL is spliced into shell source; anything that could close the quote is refused. */
    @Test
    fun `an unsafe download URL is refused`() {
        for (bad in listOf(
            "http://example.com/app.apk",
            "https://example.com/app.apk'; rm -rf ~",
            "https://example.com/app release.apk",
            "https://example.com/app.apk\necho",
        )) {
            assertTrue(bad, runCatching { TermuxScripts.installApk(bad, "app-release.apk", null) }.isFailure)
        }
        assertTrue(runCatching { TermuxScripts.installApk("https://example.com/a.apk", "../a.apk", null) }.isFailure)
    }

    @Test
    fun `the one-liner downloads and opens the same file`() {
        val line = TermuxScripts.installOneLiner("https://example.com/dsh/app-release.apk", "app-release.apk")
        assertEquals(
            "mkdir -p ~/downloads && curl -fL -o ~/downloads/app-release.apk 'https://example.com/dsh/app-release.apk' && termux-open ~/downloads/app-release.apk",
            line,
        )
    }

    @Test
    fun `markers are read back with the log tail where there is one`() {
        assertEquals(ScriptMarker.Started, TermuxScripts.marker("DSHM:started\ndsh web: http://127.0.0.1:3080/?token=x"))
        assertEquals(ScriptMarker.AlreadyRunning, TermuxScripts.marker("DSHM:already-running\n"))
        assertEquals(ScriptMarker.MissingDsh, TermuxScripts.marker("DSHM:missing-dsh"))
        assertEquals(ScriptMarker.MissingNode, TermuxScripts.marker("DSHM:missing-node"))
        assertEquals(ScriptMarker.Exited("boom\nline two"), TermuxScripts.marker("DSHM:exited\nboom\nline two\n"))
        assertEquals(ScriptMarker.ReadyTimeout("still loading"), TermuxScripts.marker("DSHM:timeout\nstill loading"))
        assertEquals(ScriptMarker.Stopped, TermuxScripts.marker("DSHM:stopped"))
        assertEquals(ScriptMarker.NotRunning, TermuxScripts.marker("DSHM:not-running"))
        assertEquals(ScriptMarker.DownloadFailed, TermuxScripts.marker("curl: (22) ...\nDSHM:download-failed"))
        assertEquals(ScriptMarker.ChecksumFailed, TermuxScripts.marker("DSHM:checksum-failed"))
        assertEquals(ScriptMarker.OpenFailed, TermuxScripts.marker("DSHM:open-failed"))
        assertEquals(ScriptMarker.Installing, TermuxScripts.marker("DSHM:installing"))
    }

    @Test
    fun `output without a marker is not mistaken for one`() {
        assertNull(TermuxScripts.marker(""))
        assertNull(TermuxScripts.marker("dsh web: http://127.0.0.1:3080"))
        assertNull(TermuxScripts.marker("DSHM:unknown-thing"))
    }

    /** Every marker a script can print is one [TermuxScripts.marker] reads, and vice versa. */
    @Test
    fun `every marker the scripts print is one the app reads`() {
        val printed = Regex("DSHM:[a-z-]+").findAll(
            TermuxScripts.startHarness(3080) + TermuxScripts.stopHarness() +
                TermuxScripts.installApk("https://example.com/a.apk", "a.apk", "https://example.com/s.txt"),
        ).map { it.value }.toSet()
        for (marker in printed) {
            assertTrue(marker, TermuxScripts.marker(marker) != null)
        }
        assertEquals(12, printed.size)
    }
}
