package com.labteto.dshmobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which release files "install via Termux" downloads. The URL ends up inside a shell command
 * in Termux, so the choice has to be conservative: the file the workflow names, over HTTPS,
 * or nothing at all.
 */
class UpdateAssetsTest {

    private val base = "https://github.com/sorsama/deepseek-harness-mobile/releases/download/v0.11.0"

    @Test
    fun `the release APK and its checksum file are picked by name`() {
        val (apk, sums) = pickReleaseAssets(
            listOf(
                ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt"),
                ReleaseAsset("app-release.apk", "$base/app-release.apk"),
                ReleaseAsset("mapping.txt", "$base/mapping.txt"),
            ),
        )
        assertEquals(ReleaseAsset("app-release.apk", "$base/app-release.apk"), apk)
        assertEquals(ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt"), sums)
    }

    @Test
    fun `a renamed APK still installs, unverified when no checksum file was published`() {
        val (apk, sums) = pickReleaseAssets(listOf(ReleaseAsset("dsh-mobile-0.11.0.apk", "$base/dsh-mobile-0.11.0.apk")))
        assertEquals("dsh-mobile-0.11.0.apk", apk?.name)
        assertNull(sums)
    }

    @Test
    fun `the named APK wins over another apk`() {
        val (apk, _) = pickReleaseAssets(
            listOf(
                ReleaseAsset("app-debug.apk", "$base/app-debug.apk"),
                ReleaseAsset("app-release.apk", "$base/app-release.apk"),
            ),
        )
        assertEquals("app-release.apk", apk?.name)
    }

    /** The URL is spliced into shell source, so a non-HTTPS one is not an option. */
    @Test
    fun `a plain-http asset is not offered`() {
        val (apk, sums) = pickReleaseAssets(
            listOf(
                ReleaseAsset("app-release.apk", "http://example.com/app-release.apk"),
                ReleaseAsset("SHA256SUMS.txt", "http://example.com/SHA256SUMS.txt"),
            ),
        )
        assertNull(apk)
        assertNull(sums)
    }

    @Test
    fun `a release without an APK offers nothing`() {
        val (apk, sums) = pickReleaseAssets(emptyList())
        assertNull(apk)
        assertNull(sums)
    }
}
