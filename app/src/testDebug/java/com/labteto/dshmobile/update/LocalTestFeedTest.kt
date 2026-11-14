package com.labteto.dshmobile.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug-only update feed override (src/debug). It may only point at this device or at the
 * computer running the emulator, and while it is on, nothing else may be downloaded from.
 */
class LocalTestFeedTest {

    @Test
    fun `loopback and the emulator host are accepted`() {
        listOf("http://10.0.2.2:8787/", "http://127.0.0.1:8787", "http://localhost:9000/feed/").forEach { base ->
            assertTrue(base, localTestFeed(base.toHttpUrl()) != null)
        }
    }

    @Test
    fun `the feed answers GitHub's paths under the base`() {
        val feed = localTestFeed("http://10.0.2.2:8787/feed".toHttpUrl())!!
        assertEquals("http://10.0.2.2:8787/feed/repos/sorsama/deepseek-harness-mobile/releases/latest", feed.latestReleaseUrl)
        assertEquals("http://10.0.2.2:8787/feed/releases/latest", feed.releasesPageUrl)
    }

    @Test
    fun `any other address is refused`() {
        listOf("http://192.168.1.20:8787/", "https://github.com/", "http://example.com/", "http://10.0.2.3/").forEach { base ->
            assertNull(base, localTestFeed(base.toHttpUrl()))
        }
    }

    @Test
    fun `while the override is on only local hosts may be fetched`() {
        val policy = localTestFeed("http://10.0.2.2:8787/".toHttpUrl())!!.policy
        assertTrue(policy.allowsUrl("http://10.0.2.2:8787/download/app-release.apk"))
        assertFalse(policy.allowsUrl("https://github.com/sorsama/deepseek-harness-mobile/releases/download/v1/app-release.apk"))
        assertFalse(policy.allowsUrl("http://192.168.1.20/app.apk"))
    }
}
