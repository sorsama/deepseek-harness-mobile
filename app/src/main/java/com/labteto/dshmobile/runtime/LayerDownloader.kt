package com.labteto.dshmobile.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** A download that failed; [failure] says how to report it. */
class DownloadException(val failure: RuntimeFailure, cause: Throwable? = null) :
    IOException(failure.toString(), cause)

/**
 * Downloads release assets into [dir], resuming a partial file with an HTTP `Range` request.
 *
 * A file is downloaded to `<sha256>.part` and renamed to `<sha256>.<ext>` only once its size and
 * SHA-256 match the signed manifest; a mismatch deletes it. A broken connection keeps the part
 * file, so the next attempt asks for the rest. GitHub's release downloads redirect to a CDN that
 * honours `Range`; a server that ignores it (answers 200) is handled by starting over.
 */
class LayerDownloader(
    private val client: OkHttpClient,
    private val dir: File,
) {
    /** The verified file for [asset] if it is already here. */
    fun cached(asset: Asset): File? = completeFile(asset).takeIf { it.isFile && it.length() == asset.size }

    data class Asset(val url: String, val file: String, val size: Long, val sha256: String)

    /** Bytes still to fetch for [asset], counting a usable part file. */
    fun remainingBytes(asset: Asset): Long {
        if (cached(asset) != null) return 0
        val part = partFile(asset)
        val have = if (part.isFile && part.length() <= asset.size) part.length() else 0
        return asset.size - have
    }

    /**
     * Fetch [asset] and return the verified file.
     *
     * [onBytes] gets the bytes present so far (including a resumed prefix) and the total.
     * [onVerifying] is called once the bytes are all here and the hash is being checked.
     */
    suspend fun download(
        asset: Asset,
        onBytes: (done: Long, total: Long) -> Unit,
        onVerifying: () -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        cached(asset)?.let { return@withContext it }
        val part = partFile(asset)
        if (part.isFile && part.length() > asset.size) part.delete()

        fetchRest(asset, part, onBytes)

        onVerifying()
        if (part.length() != asset.size) {
            part.delete()
            throw DownloadException(RuntimeFailure.ChecksumMismatch(asset.file))
        }
        if (sha256(part) != asset.sha256) {
            part.delete()
            throw DownloadException(RuntimeFailure.ChecksumMismatch(asset.file))
        }
        val done = completeFile(asset)
        done.delete()
        if (!part.renameTo(done)) throw DownloadException(RuntimeFailure.Storage("rename ${part.name}"))
        done
    }

    /** Delete every download, complete or partial, except the ones in [keep]. */
    fun prune(keep: Collection<Asset> = emptyList()) {
        val names = keep.flatMap { listOf(completeFile(it).name, partFile(it).name) }.toSet()
        dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }

    private suspend fun fetchRest(asset: Asset, part: File, onBytes: (Long, Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have == asset.size) return
        val builder = Request.Builder().url(asset.url)
        if (have > 0) builder.header("Range", "bytes=$have-")
        val call = client.newCall(builder.build())
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                val append = when (response.code) {
                    206 -> {
                        val start = response.header("Content-Range")?.let(::rangeStart)
                        if (start != have) {
                            // A range we did not ask for: trust nothing about the part file.
                            part.delete()
                            throw DownloadException(RuntimeFailure.Network("unexpected range ${response.header("Content-Range")}"))
                        }
                        true
                    }
                    200 -> {
                        have = 0
                        false
                    }
                    416 -> {
                        // The part file already covers the whole asset (or is garbage); the size
                        // and hash check decides which.
                        return
                    }
                    else -> throw DownloadException(RuntimeFailure.Network("HTTP ${response.code} for ${asset.file}"))
                }
                val body = response.body ?: throw DownloadException(RuntimeFailure.Network("empty body for ${asset.file}"))
                onBytes(have, asset.size)
                var overflow = false
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        var lastReport = have
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (have + n > asset.size) {
                                overflow = true
                                break
                            }
                            out.write(buffer, 0, n)
                            have += n
                            if (have - lastReport >= REPORT_EVERY || have == asset.size) {
                                onBytes(have, asset.size)
                                lastReport = have
                            }
                        }
                    }
                }
                if (overflow) {
                    // More bytes than the signed size: not the file the manifest describes.
                    part.delete()
                    throw DownloadException(RuntimeFailure.ChecksumMismatch(asset.file))
                }
                onBytes(have, asset.size)
            }
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw DownloadException(RuntimeFailure.Network(e.message ?: e.javaClass.simpleName), e)
        } finally {
            handle?.dispose()
        }
    }

    private fun partFile(asset: Asset) = File(dir, asset.sha256 + ".part")

    private fun completeFile(asset: Asset) = File(dir, asset.sha256 + "-" + asset.file)

    companion object {
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256 * 1024L

        /** The first byte of a `Content-Range: bytes a-b/c` header. */
        internal fun rangeStart(header: String): Long? =
            header.trim().removePrefix("bytes").trim().substringBefore('-').toLongOrNull()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
