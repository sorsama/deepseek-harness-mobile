package com.labteto.dshmobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Why a download stopped. Every one of these leaves the partial file where it was. */
sealed class DownloadException(message: String) : IOException(message) {
    /** A request or a redirect pointed somewhere [DownloadHostPolicy] does not allow. */
    class HostRefused(val host: String) : DownloadException("refused host $host")

    /** The server answered with a status that is not a download. */
    class HttpStatus(val code: Int) : DownloadException("HTTP $code")

    class TooManyRedirects : DownloadException("too many redirects")

    /** The disk filled up while writing. */
    class NoSpace : DownloadException("no space left")

    /** The server sent more or fewer bytes than it promised. */
    class SizeMismatch(val expected: Long, val actual: Long) : DownloadException("expected $expected bytes, got $actual")
}

/** What to do with a response to a (possibly ranged) request, given [existing] bytes on disk. */
internal sealed interface ResponsePlan {
    /** Append the body to the partial file; the whole file is [total] bytes (-1 when unknown). */
    data class Append(val total: Long) : ResponsePlan

    /** The server sent the whole file: start the partial file over. */
    data class Restart(val total: Long) : ResponsePlan

    /** The partial file is already the whole file. */
    data object Complete : ResponsePlan

    /** The range was refused or did not line up: ask again for the whole file. */
    data object RetryWhole : ResponsePlan

    data class Fail(val code: Int) : ResponsePlan
}

/**
 * Decide what a response means for a resume.
 *
 * A `206` is only trusted when its `Content-Range` starts exactly where the partial file ends; a
 * server that answers a range with a `200` (it ignored `Range`, or `If-Range` said the file
 * changed) sends the whole file, which replaces the partial one. A `416` means the range starts at
 * or past the end: when the partial file is already as long as the file is known to be, it is
 * complete; otherwise the partial file is not trusted and the whole file is fetched again.
 */
internal fun planResponse(
    code: Int,
    contentRange: String?,
    contentLength: Long,
    existing: Long,
    expectedTotal: Long?,
): ResponsePlan = when (code) {
    200 -> ResponsePlan.Restart(contentLength)
    206 -> {
        val range = parseContentRange(contentRange)
        when {
            range == null -> ResponsePlan.RetryWhole
            range.first != existing -> ResponsePlan.RetryWhole
            else -> ResponsePlan.Append(range.second)
        }
    }
    416 -> {
        val total = parseContentRange(contentRange)?.second?.takeIf { it >= 0 } ?: expectedTotal
        if (total != null && total == existing && existing > 0) ResponsePlan.Complete else ResponsePlan.RetryWhole
    }
    else -> ResponsePlan.Fail(code)
}

/**
 * `bytes 100-199/1000` is (100, 1000); an unsatisfied range (a star, then the total) is (-1, 1000),
 * and a star for the total reads as -1.
 * Null for anything else.
 */
internal fun parseContentRange(header: String?): Pair<Long, Long>? {
    val value = header?.trim()?.takeIf { it.startsWith("bytes", ignoreCase = true) } ?: return null
    val spec = value.substring(5).trim()
    val rangePart = spec.substringBefore('/').trim()
    val totalPart = spec.substringAfter('/', "").trim()
    val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: return null
    if (rangePart == "*") return -1L to total
    val start = rangePart.substringBefore('-').trim().toLongOrNull() ?: return null
    return start to total
}

/**
 * Where a redirect from [current] leads, if [policy] allows it.
 *
 * `Location` may be relative; it is resolved against the URL that answered. A missing or
 * unparseable `Location` is a failed request, not something to guess at.
 */
internal fun nextHop(current: HttpUrl, location: String?, policy: DownloadHostPolicy): HttpUrl {
    val target = location?.let { current.resolve(it) } ?: throw DownloadException.HttpStatus(302)
    if (!policy.allows(target)) throw DownloadException.HostRefused(target.host)
    return target
}

/**
 * A validator to send as `If-Range`, so a resume only appends when the file has not changed. Weak
 * ETags are not allowed in `If-Range`, so they fall back to `Last-Modified`.
 */
internal fun rangeValidator(etag: String?, lastModified: String?): String? =
    etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") } ?: lastModified?.takeIf { it.isNotBlank() }

private fun isNoSpace(error: IOException): Boolean {
    val text = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)
}

/**
 * Downloads one file over HTTP(S) with resume, following redirects by hand so every hop is checked
 * against a [DownloadHostPolicy].
 *
 * Resume is `Range: bytes=<partial length>-`, guarded by `If-Range` with the validator the first
 * response carried, and read back through [planResponse]. A GitHub release asset answers with a
 * redirect to a signed CDN URL that expires within minutes, so every attempt starts again from the
 * asset's own URL and follows the redirects afresh rather than remembering where they led.
 */
class ResumableDownloader(client: OkHttpClient, private val maxRedirects: Int = 6) {

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** What a finished [download] learnt about the file. */
    data class Result(val total: Long, val validator: String?)

    /**
     * Fetch a small text file, such as a checksum list, refusing anything over [maxBytes].
     */
    suspend fun fetchText(url: String, policy: DownloadHostPolicy, maxBytes: Long = 256 * 1024): String =
        withContext(Dispatchers.IO) {
            open(url, policy, headers = emptyMap()).use { response ->
                if (response.code != 200) throw DownloadException.HttpStatus(response.code)
                val body = response.body ?: throw DownloadException.HttpStatus(response.code)
                if (body.contentLength() > maxBytes) throw DownloadException.SizeMismatch(maxBytes, body.contentLength())
                val bytes = body.source().let { source ->
                    source.request(maxBytes + 1)
                    val available = source.buffer.size
                    if (available > maxBytes) throw DownloadException.SizeMismatch(maxBytes, available)
                    source.readByteArray()
                }
                String(bytes, Charsets.UTF_8)
            }
        }

    /**
     * Download [url] into [part], appending to whatever [part] already holds when the server agrees.
     *
     * [validator] is the `If-Range` value saved from an earlier attempt; [onStart] reports the one
     * this response carries (and the total size), so the caller can save it before the first byte
     * lands. [onProgress] is called after every chunk with the bytes on disk and the total.
     */
    suspend fun download(
        url: String,
        part: File,
        policy: DownloadHostPolicy,
        validator: String?,
        expectedTotal: Long?,
        onStart: (validator: String?, total: Long) -> Unit = { _, _ -> },
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        var allowRange = true
        repeat(2) {
            val existing = if (part.isFile) part.length() else 0L
            val headers = buildMap {
                put("Accept", "application/octet-stream")
                if (allowRange && existing > 0) {
                    put("Range", "bytes=$existing-")
                    validator?.let { put("If-Range", it) }
                }
            }
            open(url, policy, headers).use { response ->
                val body = response.body ?: throw DownloadException.HttpStatus(response.code)
                val plan = if (headers.containsKey("Range")) {
                    planResponse(response.code, response.header("Content-Range"), body.contentLength(), existing, expectedTotal)
                } else if (response.code == 200) {
                    ResponsePlan.Restart(body.contentLength())
                } else {
                    ResponsePlan.Fail(response.code)
                }
                val newValidator = rangeValidator(response.header("ETag"), response.header("Last-Modified")) ?: validator
                when (plan) {
                    is ResponsePlan.Fail -> throw DownloadException.HttpStatus(plan.code)
                    ResponsePlan.RetryWhole -> {
                        part.delete()
                        allowRange = false
                        return@repeat
                    }
                    ResponsePlan.Complete -> {
                        onProgress(existing, existing)
                        return@withContext Result(existing, validator)
                    }
                    is ResponsePlan.Append, is ResponsePlan.Restart -> {
                        val append = plan is ResponsePlan.Append
                        val total = when (plan) {
                            is ResponsePlan.Append -> plan.total
                            is ResponsePlan.Restart -> plan.total
                            else -> -1L
                        }.takeIf { it > 0 } ?: expectedTotal ?: -1L
                        if (expectedTotal != null && expectedTotal > 0 && total > 0 && total != expectedTotal) {
                            throw DownloadException.SizeMismatch(expectedTotal, total)
                        }
                        onStart(newValidator, total)
                        val written = writeBody(response, part, append, if (append) existing else 0L, total, onProgress)
                        if (total > 0 && written != total) throw DownloadException.SizeMismatch(total, written)
                        return@withContext Result(written, newValidator)
                    }
                }
            }
        }
        throw DownloadException.HttpStatus(416)
    }

    private suspend fun writeBody(
        response: Response,
        part: File,
        append: Boolean,
        start: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var done = start
        part.parentFile?.mkdirs()
        try {
            FileOutputStream(part, append).use { out ->
                val source = response.body!!.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
                out.fd.sync()
            }
        } catch (error: IOException) {
            if (isNoSpace(error)) throw DownloadException.NoSpace()
            throw error
        }
        return done
    }

    /** Send a GET to [url] and follow redirects by hand, checking each hop against [policy]. */
    private fun open(url: String, policy: DownloadHostPolicy, headers: Map<String, String>): Response {
        var current = url.toHttpUrlOrNull() ?: throw DownloadException.HostRefused(url)
        if (!policy.allows(current)) throw DownloadException.HostRefused(current.host)
        repeat(maxRedirects + 1) {
            val request = Request.Builder().url(current).get().apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            val response = client.newCall(request).execute()
            if (response.code !in REDIRECTS) return response
            val location = response.header("Location")
            response.close()
            current = nextHop(current, location, policy)
        }
        throw DownloadException.TooManyRedirects()
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
