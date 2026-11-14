package com.labteto.dshmobile.runtime

import java.io.File
import java.io.FileOutputStream

/**
 * Removes the launch token from harness output.
 *
 * The readiness line carries the token as `?token=…`. Anything shaped like `token=<value>` is
 * masked whether or not the token is known yet, and once it is known ([remember]) the bare value
 * is masked wherever it appears. The token is a credential for the harness's whole API, and the
 * log is a file; it must never hold one.
 */
class TokenRedactor {
    @Volatile
    private var secrets: List<String> = emptyList()

    fun remember(secret: String) {
        if (secret.length >= MIN_SECRET && secret !in secrets) secrets = secrets + secret
    }

    fun redact(line: String): String {
        var out = TOKEN_PARAM.replace(line) { "${it.groupValues[1]}$MASK" }
        for (secret in secrets) out = out.replace(secret, MASK)
        return out
    }

    companion object {
        const val MASK = "<redacted>"
        private const val MIN_SECRET = 8
        private val TOKEN_PARAM = Regex("""(token=)[^\s&"'#]+""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The harness output log: two files of at most [maxBytes] each, newest in [file].
 *
 * Only redacted lines reach it ([append] takes what the supervisor already passed through a
 * [TokenRedactor]). Kept under the runtime's no-backup directory.
 */
class RotatingLog(private val file: File, private val maxBytes: Long = 512 * 1024) {
    private val previous = File(file.parentFile, file.name + ".1")
    private var out: FileOutputStream? = null
    private var size = 0L

    @Synchronized
    fun append(line: String) {
        val bytes = (line + "\n").toByteArray()
        // Uninstall deletes the log directory under an open stream: write a new file, not the unlinked one.
        if (out != null && !file.exists()) close()
        if (out == null) open()
        if (size + bytes.size > maxBytes && size > 0) rotate()
        out?.write(bytes)
        size += bytes.size
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    /** The last [lines] lines over both files. */
    @Synchronized
    fun tail(lines: Int): String {
        out?.flush()
        val all = buildList {
            if (previous.isFile) addAll(previous.readLines())
            if (file.isFile) addAll(file.readLines())
        }
        return all.takeLast(lines).joinToString("\n")
    }

    @Synchronized
    fun sizeBytes(): Long = (if (file.isFile) file.length() else 0) + (if (previous.isFile) previous.length() else 0)

    private fun open() {
        file.parentFile?.mkdirs()
        size = if (file.isFile) file.length() else 0
        out = FileOutputStream(file, true)
    }

    private fun rotate() {
        close()
        previous.delete()
        file.renameTo(previous)
        size = 0
        out = FileOutputStream(file, false)
    }
}
