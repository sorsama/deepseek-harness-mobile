package com.labteto.dshmobile.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class LayerDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val release = ReleaseServer()
    private val payload = Random(7).nextBytes(300_000)
    private lateinit var dir: File
    private lateinit var downloader: LayerDownloader

    @Before
    fun setUp() {
        server.dispatcher = release
        server.start()
        release.files["layer.tar.zst"] = payload
        dir = tmp.newFolder("downloads")
        downloader = LayerDownloader(OkHttpClient(), dir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun asset(sha: String = sha256Hex(payload), size: Long = payload.size.toLong()) =
        LayerDownloader.Asset(server.url("/layer.tar.zst").toString(), "layer.tar.zst", size, sha)

    @Test
    fun `a full download is verified and kept under its hash`() = runBlocking {
        var last = 0L
        var verifying = false
        val file = downloader.download(asset(), onBytes = { done, _ -> last = done }, onVerifying = { verifying = true })
        assertArrayEquals(payload, file.readBytes())
        assertEquals(payload.size.toLong(), last)
        assertTrue(verifying)
        assertEquals(listOf("layer.tar.zst" to null), release.requests)
        // A second call finds it and fetches nothing.
        downloader.download(asset(), onBytes = { _, _ -> })
        assertEquals(1, release.requests.size)
    }

    @Test
    fun `a part file is resumed with a Range request`() = runBlocking {
        File(dir, sha256Hex(payload) + ".part").writeBytes(payload.copyOf(120_000))
        assertEquals(180_000L, downloader.remainingBytes(asset()))
        val file = downloader.download(asset(), onBytes = { _, _ -> })
        assertArrayEquals(payload, file.readBytes())
        assertEquals(listOf("layer.tar.zst" to "bytes=120000-"), release.requests)
    }

    @Test
    fun `a download that breaks off resumes on the next attempt`() = runBlocking {
        release.breakNext += "layer.tar.zst"
        try {
            downloader.download(asset(), onBytes = { _, _ -> })
            fail("the broken connection must surface")
        } catch (e: DownloadException) {
            assertTrue(e.failure is RuntimeFailure.Network)
        }
        val part = File(dir, sha256Hex(payload) + ".part")
        val kept = part.length()
        assertTrue("some bytes should have been kept, got $kept", kept in 1 until payload.size)
        val file = downloader.download(asset(), onBytes = { _, _ -> })
        assertArrayEquals(payload, file.readBytes())
        assertEquals("bytes=$kept-", release.requests.last().second)
    }

    @Test
    fun `a server that ignores Range starts over and still ends correct`() = runBlocking {
        release.honourRange = false
        File(dir, sha256Hex(payload) + ".part").writeBytes(payload.copyOf(50_000))
        val file = downloader.download(asset(), onBytes = { _, _ -> })
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `a hash mismatch deletes the file and names it`() = runBlocking {
        try {
            downloader.download(asset(sha = "a".repeat(64)), onBytes = { _, _ -> })
            fail("mismatch must fail")
        } catch (e: DownloadException) {
            assertEquals(RuntimeFailure.ChecksumMismatch("layer.tar.zst"), e.failure)
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `more bytes than the manifest says is a mismatch`() = runBlocking {
        try {
            downloader.download(asset(size = 1000), onBytes = { _, _ -> })
            fail("oversize must fail")
        } catch (e: DownloadException) {
            assertTrue(e.failure is RuntimeFailure.ChecksumMismatch)
        }
        assertFalse(dir.listFiles()!!.any { it.length() > 0 })
    }

    @Test
    fun `an HTTP error is a network failure`() = runBlocking {
        val missing = LayerDownloader.Asset(server.url("/nope").toString(), "nope", 10, "b".repeat(64))
        try {
            downloader.download(missing, onBytes = { _, _ -> })
            fail("404 must fail")
        } catch (e: DownloadException) {
            assertEquals(RuntimeFailure.Network("HTTP 404 for nope"), e.failure)
        }
    }

    @Test
    fun `Content-Range parsing`() {
        assertEquals(120000L, LayerDownloader.rangeStart("bytes 120000-299999/300000"))
        assertEquals(null, LayerDownloader.rangeStart("bytes */300000"))
    }
}
