package com.labteto.dshmobile.runtime

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.Collections

/** Builds GNU tar archives byte by byte, including the entries a crafted archive would use. */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    fun dir(name: String, mode: Int = "755".toInt(8)) = apply { entry(name.trimEnd('/') + "/", '5', mode, ByteArray(0)) }

    fun file(name: String, content: String, mode: Int = "644".toInt(8)) = file(name, content.toByteArray(), mode)

    fun file(name: String, content: ByteArray, mode: Int = "644".toInt(8)) = apply { entry(name, '0', mode, content) }

    fun symlink(name: String, target: String) = apply { entry(name, '2', "777".toInt(8), ByteArray(0), link = target) }

    fun hardlink(name: String, target: String) = apply { entry(name, '1', "644".toInt(8), ByteArray(0), link = target) }

    fun fifo(name: String) = apply { entry(name, '6', "644".toInt(8), ByteArray(0)) }

    /** A header with a deliberately wrong checksum. */
    fun corruptHeader(name: String) = apply {
        val header = header(name, '0', "644".toInt(8), 0, "")
        header[148] = '7'.code.toByte()
        header[149] = '7'.code.toByte()
        out.write(header)
    }

    fun bytes(): ByteArray {
        val copy = ByteArrayOutputStream()
        copy.write(out.toByteArray())
        copy.write(ByteArray(1024)) // end-of-archive marker
        return copy.toByteArray()
    }

    private fun entry(name: String, type: Char, mode: Int, data: ByteArray, link: String = "") {
        val nameBytes = name.toByteArray()
        val linkBytes = link.toByteArray()
        if (nameBytes.size > 100) longEntry('L', nameBytes)
        if (linkBytes.size > 100) longEntry('K', linkBytes)
        out.write(header(if (nameBytes.size > 100) name.take(99) else name, type, mode, data.size.toLong(), if (linkBytes.size > 100) link.take(99) else link))
        out.write(data)
        pad(data.size.toLong())
    }

    private fun longEntry(type: Char, value: ByteArray) {
        val data = value + 0
        out.write(header("././@LongLink", type, 0, data.size.toLong(), ""))
        out.write(data)
        pad(data.size.toLong())
    }

    private fun pad(size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        out.write(ByteArray(pad))
    }

    private fun header(name: String, type: Char, mode: Int, size: Long, link: String): ByteArray {
        val h = ByteArray(512)
        put(h, 0, 100, name.toByteArray())
        put(h, 100, 8, octal(mode.toLong(), 7))
        put(h, 108, 8, octal(0, 7))
        put(h, 116, 8, octal(0, 7))
        put(h, 124, 12, octal(size, 11))
        put(h, 136, 12, octal(1_700_000_000, 11))
        h[156] = type.code.toByte()
        put(h, 157, 100, link.toByteArray())
        put(h, 257, 8, "ustar  \u0000".toByteArray(Charsets.ISO_8859_1)) // GNU magic
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        put(h, 148, 8, octal(sum.toLong(), 6) + ' '.code.toByte())
        return h
    }

    private fun octal(value: Long, digits: Int): ByteArray = value.toString(8).padStart(digits, '0').toByteArray() + 0

    private fun put(h: ByteArray, offset: Int, length: Int, bytes: ByteArray) {
        System.arraycopy(bytes, 0, h, offset, minOf(length, bytes.size))
    }
}

/**
 * [FsOps] over the real filesystem that records symlinks and modes instead of relying on the host.
 *
 * Windows (this project is developed there) cannot create symlinks without Developer Mode and has
 * no POSIX modes. A symlink here is an empty marker file plus a recorded target, and [lstat] reports
 * it as a symlink, which is all the extractor and the tree helpers ever ask.
 */
class RecordingFsOps : FsOps {
    val chmods: MutableList<Pair<String, Int>> = Collections.synchronizedList(ArrayList())

    override fun lstat(file: File): FileKind = when {
        file.isDirectory -> FileKind.DIRECTORY
        file.isFile -> if (linkTarget(file) != null) FileKind.SYMLINK else FileKind.FILE
        else -> FileKind.NONE
    }

    /** The marker holds the target, so a "symlink" moves with a renamed directory like a real one. */
    override fun symlink(target: String, link: File) {
        check(!link.exists()) { "symlink target path exists: $link" }
        link.writeBytes(MAGIC + target.toByteArray())
    }

    override fun chmod(file: File, mode: Int) {
        chmods += file.absoluteFile.path to mode
    }

    override fun readlink(file: File): String? = linkTarget(file)

    fun linkTarget(file: File): String? {
        if (!file.isFile || file.length() < MAGIC.size) return null
        val bytes = file.readBytes()
        if (!bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        return String(bytes, MAGIC.size, bytes.size - MAGIC.size)
    }

    private companion object {
        val MAGIC = "\u0000test-symlink\u0000".toByteArray()
    }

    fun modeOf(file: File): Int? = chmods.lastOrNull { it.first == file.absoluteFile.path }?.second
}

/** An Ed25519 key pair from the JDK's own implementation (independent of the app's verifier). */
class TestSigningKey {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val private: PrivateKey = pair.private

    /** The raw 32-byte key: the last 32 bytes of the X.509 encoding. */
    val rawPublicKey: ByteArray = pair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(private)
        update(message)
        sign()
    }

    fun signatureFile(message: ByteArray): ByteArray = (Base64.getEncoder().encodeToString(sign(message)) + "\n").toByteArray()
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * A release server for MockWebServer: serves named files, honours `Range: bytes=N-`, records paths.
 */
class ReleaseServer : Dispatcher() {
    val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(HashMap())
    val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(ArrayList())

    /** Paths whose next response breaks off halfway through the body. */
    val breakNext: MutableSet<String> = Collections.synchronizedSet(HashSet())
    var honourRange = true

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/")
        val range = request.getHeader("Range")
        requests += path to range
        val bytes = files[path] ?: return MockResponse().setResponseCode(404)
        val start = range?.takeIf { honourRange }?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val response = if (start != null) {
            if (start >= bytes.size) return MockResponse().setResponseCode(416)
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
                .setBody(Buffer().write(bytes, start, bytes.size - start))
        } else {
            MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
        }
        if (breakNext.remove(path)) response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        return response
    }

    fun requestsFor(path: String) = requests.filter { it.first == path }
}
