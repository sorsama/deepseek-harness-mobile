package com.labteto.dshmobile.update

import java.io.File
import java.security.MessageDigest

/** A byte-order mark a Windows editor may have left at the start of the file. */
private val BYTE_ORDER_MARK = Char(0xFEFF)

private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * The entries of a `SHA256SUMS.txt`, file name to lower-case hex digest.
 *
 * The release workflow writes it with `sha256sum *.apk`: one `<digest>  <name>` line per file, or
 * `<digest> *<name>` in binary mode. The BSD form (`SHA256 (<name>) = <digest>`) is read as well,
 * so a checksum file made on a Mac still verifies. Names are reduced to their last path segment
 * (`./app-release.apk` is `app-release.apk`). Lines that are neither are skipped rather than
 * failing the whole file; a name listed twice keeps its first digest.
 */
internal fun parseSha256Sums(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    text.lineSequence().map { it.trim().trimStart(BYTE_ORDER_MARK) }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
        val bsd = Regex("""^SHA256 \((.+)\) ?= ?([0-9a-fA-F]{64})$""").find(line)
        val (name, digest) = if (bsd != null) {
            bsd.groupValues[1] to bsd.groupValues[2]
        } else {
            val digest = line.substringBefore(' ').substringBefore('\t')
            if (!SHA256_HEX.matches(digest)) return@forEach
            val rest = line.substring(digest.length).trimStart(' ', '\t').removePrefix("*")
            rest to digest
        }
        val baseName = name.trim().substringAfterLast('/').substringAfterLast('\\')
        if (baseName.isNotEmpty()) out.putIfAbsent(baseName, digest.lowercase())
    }
    return out
}

/** What the checksum file says [assetName] should hash to, or null when it does not list it. */
internal fun expectedSha256(sums: Map<String, String>, assetName: String): String? =
    sums[assetName] ?: sums.entries.firstOrNull { it.key.equals(assetName, ignoreCase = true) }?.value

/** Lower-case hex SHA-256 of [file]. */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
