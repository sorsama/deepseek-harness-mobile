package com.labteto.dshmobile.update

import android.content.pm.PackageInstaller
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's pure rules: hosts, response planning, checksums, the pre-install verdict and status mapping. */
class UpdatePolicyTest {

    // ---- Host policy ----------------------------------------------------------------------

    @Test
    fun `GitHub and its release download hosts are allowed over https`() {
        listOf(
            "https://api.github.com/repos/sorsama/deepseek-harness-mobile/releases/latest",
            "https://github.com/sorsama/deepseek-harness-mobile/releases/download/v0.22.0/app-release.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/1?sig=x",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x",
        ).forEach { assertTrue(it, DownloadHostPolicy.GitHub.allowsUrl(it)) }
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "http://github.com/sorsama/deepseek-harness-mobile/releases/download/v1/app-release.apk",
            "https://github.com:8443/x.apk",
            "https://github.com.example.net/x.apk",
            "https://evilgithub.com/x.apk",
            "https://raw.githubusercontent.com/x.apk",
            "https://user:pass@github.com/x.apk",
            "https://10.0.2.2/x.apk",
            "not a url",
        ).forEach { assertFalse(it, DownloadHostPolicy.GitHub.allowsUrl(it)) }
    }

    @Test
    fun `a relative redirect resolves against the URL that answered`() {
        val next = nextHop(
            "https://github.com/o/r/releases/download/v1/app-release.apk".toHttpUrl(),
            "https://release-assets.githubusercontent.com/asset?sig=1",
            DownloadHostPolicy.GitHub,
        )
        assertEquals("release-assets.githubusercontent.com", next.host)
        assertEquals("/o/r/other", nextHop("https://github.com/o/r/x".toHttpUrl(), "other", DownloadHostPolicy.GitHub).encodedPath)
    }

    @Test(expected = DownloadException.HostRefused::class)
    fun `a redirect off GitHub is refused`() {
        nextHop("https://github.com/o/r/x".toHttpUrl(), "https://cdn.example.com/x", DownloadHostPolicy.GitHub)
    }

    @Test(expected = DownloadException.HostRefused::class)
    fun `a redirect from https down to http is refused`() {
        nextHop("https://github.com/o/r/x".toHttpUrl(), "http://objects.githubusercontent.com/x", DownloadHostPolicy.GitHub)
    }

    // ---- Response planning ------------------------------------------------------------------

    @Test
    fun `a 206 that starts where the file ends appends`() {
        assertEquals(ResponsePlan.Append(1000), planResponse(206, "bytes 400-999/1000", 600, 400, 1000))
    }

    @Test
    fun `a 206 that starts elsewhere or has no range is not trusted`() {
        assertEquals(ResponsePlan.RetryWhole, planResponse(206, "bytes 0-999/1000", 1000, 400, 1000))
        assertEquals(ResponsePlan.RetryWhole, planResponse(206, null, 600, 400, 1000))
    }

    @Test
    fun `a 200 restarts and a 416 on a whole file completes`() {
        assertEquals(ResponsePlan.Restart(1000), planResponse(200, null, 1000, 400, 1000))
        assertEquals(ResponsePlan.Complete, planResponse(416, "bytes */1000", 0, 1000, 1000))
        assertEquals(ResponsePlan.Complete, planResponse(416, null, 0, 1000, 1000))
        assertEquals(ResponsePlan.RetryWhole, planResponse(416, "bytes */1000", 0, 1200, 1000))
        assertEquals(ResponsePlan.Fail(500), planResponse(500, null, 0, 0, null))
    }

    @Test
    fun `Content-Range parses its three shapes`() {
        assertEquals(100L to 1000L, parseContentRange("bytes 100-199/1000"))
        assertEquals(-1L to 1000L, parseContentRange("bytes */1000"))
        assertEquals(100L to -1L, parseContentRange("bytes 100-199/*"))
        assertNull(parseContentRange("items 1-2/3"))
        assertNull(parseContentRange(null))
    }

    @Test
    fun `weak ETags fall back to Last-Modified for If-Range`() {
        assertEquals("\"s\"", rangeValidator("\"s\"", "Mon"))
        assertEquals("Mon", rangeValidator("W/\"w\"", "Mon"))
        assertNull(rangeValidator(null, null))
    }

    // ---- Checksums --------------------------------------------------------------------------

    private val digest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun `sha256sum text and binary lines both parse`() {
        val sums = parseSha256Sums("$digest  app-release.apk\n${digest.uppercase()} *other.apk\n")
        assertEquals(digest, sums["app-release.apk"])
        assertEquals("upper case reads as lower case", digest, sums["other.apk"])
    }

    @Test
    fun `the BSD form and path prefixes are understood`() {
        assertEquals(digest, parseSha256Sums("SHA256 (app-release.apk) = $digest")["app-release.apk"])
        assertEquals(digest, parseSha256Sums("$digest  ./build/app-release.apk")["app-release.apk"])
    }

    @Test
    fun `junk lines are skipped rather than failing the file`() {
        val sums = parseSha256Sums("# comment\n\nnot a digest  app.apk\n$digest  app-release.apk\r\n")
        assertEquals(mapOf("app-release.apk" to digest), sums)
    }

    @Test
    fun `an APK the file does not list has no expected digest`() {
        val sums = parseSha256Sums("$digest  app-release.apk")
        assertNull(expectedSha256(sums, "dsh-mobile.apk"))
        assertEquals(digest, expectedSha256(sums, "APP-RELEASE.apk"))
    }

    @Test
    fun `the computed digest matches and a changed byte does not`() {
        val file = kotlin.io.path.createTempFile().toFile()
        try {
            file.writeText("test")
            assertEquals(digest, sha256Of(file))
            file.writeText("tesT")
            assertFalse(sha256Of(file) == digest)
        } finally {
            file.delete()
        }
    }

    // ---- Pre-install verdict ------------------------------------------------------------------

    private val installed = ApkFacts("com.labteto.dshmobile", 2101, "0.21.1", setOf("key-a"))

    @Test
    fun `a newer build of this app from the same signer may be installed`() {
        assertNull(judgeCandidate(installed.copy(versionCode = 2200, versionName = "0.22.0"), installed, runningDebugBuild = false))
    }

    @Test
    fun `another package is refused`() {
        assertEquals(
            ApkRejection.WrongPackage("com.example.other"),
            judgeCandidate(installed.copy(packageName = "com.example.other", versionCode = 9999), installed, false),
        )
    }

    @Test
    fun `the same or an older version is refused`() {
        assertEquals(ApkRejection.NotNewer("0.21.1"), judgeCandidate(installed, installed, false))
        assertEquals(ApkRejection.NotNewer("0.20.0"), judgeCandidate(installed.copy(versionCode = 2000, versionName = "0.20.0"), installed, false))
    }

    @Test
    fun `another signer is refused`() {
        assertEquals(
            ApkRejection.SignatureMismatch,
            judgeCandidate(installed.copy(versionCode = 2200, signers = setOf("key-b")), installed, false),
        )
    }

    @Test
    fun `a rotated key whose history still names the old one matches`() {
        assertNull(judgeCandidate(installed.copy(versionCode = 2200, signers = setOf("key-a", "key-c")), installed, false))
    }

    @Test
    fun `signers that could not be read are left to Android`() {
        assertNull(judgeCandidate(installed.copy(versionCode = 2200, signers = emptySet()), installed, false))
    }

    @Test
    fun `a debug build is told a release cannot replace it`() {
        val debug = installed.copy(packageName = "com.labteto.dshmobile.debug", signers = setOf("debug-key"))
        assertEquals(ApkRejection.DebugBuild, judgeCandidate(installed.copy(versionCode = 2200), debug, runningDebugBuild = true))
    }

    @Test
    fun `a debug build updated by a debug build of the same id is fine`() {
        val debug = installed.copy(packageName = "com.labteto.dshmobile.debug", signers = setOf("debug-key"))
        assertNull(judgeCandidate(debug.copy(versionCode = 2102), debug, runningDebugBuild = true))
    }

    @Test
    fun `an unreadable file is refused`() {
        assertEquals(ApkRejection.Unreadable, judgeCandidate(null, installed, false))
    }

    // ---- Install status -----------------------------------------------------------------------

    @Test
    fun `every installer status maps to an outcome`() {
        assertEquals(InstallOutcome.NeedsUserAction, mapInstallStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertEquals(InstallOutcome.Success, mapInstallStatus(PackageInstaller.STATUS_SUCCESS, null))
        mapOf(
            PackageInstaller.STATUS_FAILURE_ABORTED to InstallFailure.Aborted,
            PackageInstaller.STATUS_FAILURE_BLOCKED to InstallFailure.Blocked,
            PackageInstaller.STATUS_FAILURE_CONFLICT to InstallFailure.Conflict,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to InstallFailure.Incompatible,
            PackageInstaller.STATUS_FAILURE_INVALID to InstallFailure.Invalid,
            PackageInstaller.STATUS_FAILURE_STORAGE to InstallFailure.Storage,
            8 to InstallFailure.Timeout,
            PackageInstaller.STATUS_FAILURE to InstallFailure.Unknown,
            42 to InstallFailure.Unknown,
        ).forEach { (status, failure) ->
            assertEquals("status $status", InstallOutcome.Failed(failure, "why"), mapInstallStatus(status, " why "))
        }
    }

    @Test
    fun `an abort caused by the app verifier is told apart from the person saying no`() {
        val message = "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl1.tmp"
        assertEquals(InstallFailure.Verification, (mapInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, message) as InstallOutcome.Failed).failure)
        assertEquals(InstallFailure.Aborted, (mapInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected permissions") as InstallOutcome.Failed).failure)
        assertTrue(InstallFailure.Verification.retryable)
    }

    @Test
    fun `a blank status message is no message`() {
        assertEquals(InstallOutcome.Failed(InstallFailure.Unknown, null), mapInstallStatus(PackageInstaller.STATUS_FAILURE, "  "))
    }

    @Test
    fun `only failures the file caused delete it and a refusal by the person can be retried`() {
        assertTrue(InstallFailure.Aborted.retryable)
        assertFalse(InstallFailure.Aborted.discardFile)
        assertTrue(InstallFailure.Invalid.discardFile)
        assertFalse(InstallFailure.Blocked.retryable)
    }

    // ---- Speed, time left and space -----------------------------------------------------------

    @Test
    fun `speed comes from the recent window and time left from the bytes remaining`() {
        val rate = TransferRate(windowMs = 3_000)
        assertNull(rate.bytesPerSecond())
        rate.sample(0, 0)
        rate.sample(1_000, 1_000_000)
        assertEquals(1_000_000L, rate.bytesPerSecond())
        rate.sample(10_000, 1_500_000)
        rate.sample(11_000, 1_600_000)
        assertEquals("old samples fall out of the window", 100_000L, rate.bytesPerSecond())
        assertEquals(5L, secondsLeft(500_000, 1_000_000, 100_000))
        assertNull(secondsLeft(0, -1, 100))
    }

    @Test
    fun `the space check counts what is left plus the install copy and a margin`() {
        val mb = 1024L * 1024
        assertEquals(30 * mb + 30 * mb + SPACE_MARGIN, bytesNeededFor(30 * mb, 0))
        assertEquals(10 * mb + 30 * mb + SPACE_MARGIN, bytesNeededFor(30 * mb, 20 * mb))
    }

    // ---- Background check -------------------------------------------------------------------

    @Test
    fun `the background check runs at most once a day`() {
        val day = BackgroundUpdateCheck.INTERVAL_MS
        assertTrue(backgroundCheckDue(nowMs = 1_000, lastRunMs = 0))
        assertFalse(backgroundCheckDue(nowMs = day / 2, lastRunMs = 1))
        assertTrue(backgroundCheckDue(nowMs = day + 1, lastRunMs = 1))
        assertTrue("a clock that went backwards does not hold it off", backgroundCheckDue(nowMs = 5, lastRunMs = 1_000))
    }

    // ---- Release notes ------------------------------------------------------------------------

    @Test
    fun `release notes are trimmed and cut`() {
        assertNull(trimNotes("  \n "))
        assertEquals("Fixes", trimNotes("\nFixes\n"))
        val long = trimNotes("x".repeat(MAX_NOTES_CHARS + 50))!!
        assertTrue(long.length <= MAX_NOTES_CHARS + 3)
        assertTrue(long.endsWith("…"))
    }
}
