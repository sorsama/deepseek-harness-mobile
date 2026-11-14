package com.labteto.dshmobile.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The resumable download against a real HTTP server: ranges, `If-Range`, the answers a server
 * may give to a range, redirects and the host policy applied to every hop, and a connection that
 * drops half-way through.
 */
class ResumableDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val payload = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val downloader = ResumableDownloader(OkHttpClient())

    /** In these tests the mock server stands in for GitHub: its own host is the only one allowed. */
    private val policy get() = DownloadHostPolicy { it.host == server.hostName && it.port == server.port }

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun part(): File = File(temp.root, "dsh-1.0.0.part")

    private fun body(from: Int = 0) = Buffer().write(payload, from, payload.size - from)

    private suspend fun fetch(validator: String? = null, expected: Long? = payload.size.toLong()) =
        downloader.download(server.url("/app-release.apk").toString(), part(), policy, validator, expected) { _, _ -> }

    @Test
    fun `a fresh download writes the whole file and reports its validator`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()).setHeader("ETag", "\"abc\""))
        val result = fetch()
        assertArrayEquals(payload, part().readBytes())
        assertEquals(payload.size.toLong(), result.total)
        assertEquals("\"abc\"", result.validator)
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a partial file resumes with Range and If-Range and appends`() = runBlocking {
        part().writeBytes(payload.copyOfRange(0, 50_000))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(body(50_000))
                .setHeader("Content-Range", "bytes 50000-199999/200000"),
        )
        fetch(validator = "\"abc\"")
        val request = server.takeRequest()
        assertEquals("bytes=50000-", request.getHeader("Range"))
        assertEquals("\"abc\"", request.getHeader("If-Range"))
        assertArrayEquals(payload, part().readBytes())
    }

    @Test
    fun `a server that ignores the range sends the whole file which replaces the partial one`() = runBlocking {
        part().writeBytes(ByteArray(50_000) { 7 })
        server.enqueue(MockResponse().setBody(body()))
        fetch(validator = "\"old\"")
        assertArrayEquals(payload, part().readBytes())
    }

    @Test
    fun `a range that starts somewhere else is not trusted and the whole file is fetched again`() = runBlocking {
        part().writeBytes(payload.copyOfRange(0, 50_000))
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(body(40_000))
                .setHeader("Content-Range", "bytes 40000-199999/200000"),
        )
        server.enqueue(MockResponse().setBody(body()))
        fetch()
        server.takeRequest()
        assertNull("the retry asks for the whole file", server.takeRequest().getHeader("Range"))
        assertArrayEquals(payload, part().readBytes())
    }

    @Test
    fun `416 on a partial file that is already whole means done`() = runBlocking {
        part().writeBytes(payload)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */200000"))
        val result = fetch()
        assertEquals(payload.size.toLong(), result.total)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a dropped connection keeps what arrived and the next attempt resumes from there`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            fetch()
            fail("the first attempt should have failed")
        } catch (_: IOException) {
        }
        val kept = part().length()
        assertTrue("something arrived before the drop ($kept bytes)", kept in 1 until payload.size)
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206).setBody(body(kept.toInt()))
                .setHeader("Content-Range", "bytes $kept-199999/200000"),
        )
        fetch()
        assertEquals("bytes=$kept-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(payload, part().readBytes())
    }

    @Test
    fun `redirects to an allowed host are followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/asset?sig=1"))
        server.enqueue(MockResponse().setBody(body()))
        fetch()
        server.takeRequest()
        assertEquals("/cdn/asset?sig=1", server.takeRequest().path)
        assertArrayEquals(payload, part().readBytes())
    }

    @Test
    fun `a redirect to any other host stops before anything is fetched from it`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://downloads.example.net/app.apk"))
        try {
            fetch()
            fail("the redirect should have been refused")
        } catch (refused: DownloadException.HostRefused) {
            assertEquals("downloads.example.net", refused.host)
        }
        assertEquals(1, server.requestCount)
        assertTrue(!part().exists() || part().length() == 0L)
    }

    @Test
    fun `endless redirects give up`() = runBlocking {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again")) }
        try {
            fetch()
            fail("should have given up")
        } catch (_: DownloadException.TooManyRedirects) {
        }
    }

    @Test
    fun `a file of another size than the release lists is refused`() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        try {
            fetch(expected = 123L)
            fail("size mismatch expected")
        } catch (_: DownloadException.SizeMismatch) {
        }
    }

    @Test
    fun `an error status is reported as such`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            fetch()
            fail("404 expected")
        } catch (error: DownloadException.HttpStatus) {
            assertEquals(404, error.code)
        }
    }

    @Test
    fun `the checksum file is fetched through the same policy and size cap`() = runBlocking {
        server.enqueue(MockResponse().setBody("${"a".repeat(64)}  app-release.apk\n"))
        val text = downloader.fetchText(server.url("/SHA256SUMS.txt").toString(), policy)
        assertEquals("a".repeat(64), parseSha256Sums(text)["app-release.apk"])

        server.enqueue(MockResponse().setBody("x".repeat(2_000)))
        try {
            downloader.fetchText(server.url("/SHA256SUMS.txt").toString(), policy, maxBytes = 1_000)
            fail("oversized checksum file accepted")
        } catch (_: DownloadException.SizeMismatch) {
        }
    }
}
