package com.labteto.dshmobile.ui.screens.main.preview

import java.io.File
import java.util.zip.ZipFile

/**
 * A classic hex dump of the start of [bytes]: an offset, the bytes in hex, then the same bytes as
 * ASCII with `.` for anything unprintable.
 *
 * @param limit how many bytes to dump at most.
 * @param bytesPerRow 16 on a wide screen, 8 on a phone.
 * @param totalSize the file's full size, which sets how wide the offset column is; it defaults to
 * the bytes given.
 */
internal fun hexDump(
    bytes: ByteArray,
    limit: Int = 16 * 1024,
    bytesPerRow: Int = 16,
    totalSize: Long = bytes.size.toLong(),
): List<String> {
    val count = minOf(bytes.size, limit)
    val digits = maxOf(4, java.lang.Long.toHexString(maxOf(totalSize - 1, 0)).length)
    val rows = ArrayList<String>((count + bytesPerRow - 1) / bytesPerRow)
    var offset = 0
    while (offset < count) {
        val end = minOf(offset + bytesPerRow, count)
        val row = StringBuilder()
        row.append(java.lang.Long.toHexString(offset.toLong()).padStart(digits, '0')).append("  ")
        for (i in 0 until bytesPerRow) {
            if (offset + i < end) {
                val value = bytes[offset + i].toInt() and 0xFF
                row.append(HEX[value shr 4]).append(HEX[value and 0x0F])
            } else row.append("  ")
            row.append(' ')
            // A wider gap in the middle of a 16-byte row makes it easier to count across.
            if (bytesPerRow == 16 && i == 7) row.append(' ')
        }
        row.append(" |")
        for (i in offset until end) {
            val value = bytes[i].toInt() and 0xFF
            row.append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        row.append('|')
        rows.add(row.toString())
        offset = end
    }
    return rows
}

private const val HEX = "0123456789abcdef"

/** One entry of a zip file's table of contents. */
internal data class ZipEntryInfo(val name: String, val size: Long, val compressed: Long, val directory: Boolean)

/** The first entries of a zip file, and how many it holds in all. */
internal data class ZipListing(val entries: List<ZipEntryInfo>, val total: Int) {
    val truncated: Boolean get() = total > entries.size
}

/**
 * The table of contents of the zip at [file], read from its central directory. Nothing is
 * decompressed, so a zip bomb costs no more than an ordinary archive. Null when it is not a zip.
 */
internal fun listZipEntries(file: File, maxEntries: Int = 5000): ZipListing? = try {
    ZipFile(file).use { zip ->
        val entries = ArrayList<ZipEntryInfo>()
        var total = 0
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            total++
            if (entries.size < maxEntries) {
                entries.add(ZipEntryInfo(entry.name, entry.size, entry.compressedSize, entry.isDirectory))
            }
        }
        ZipListing(entries, total)
    }
} catch (_: Exception) {
    null
}

/** A match of the find bar: [line] is a 0-based index into the displayed lines. */
internal data class TextMatch(val line: Int, val start: Int, val end: Int)

/** Every match found, and whether the search stopped at the cap. */
internal data class FindResult(val matches: List<TextMatch>, val capped: Boolean)

/**
 * Finds [query] in [lines], in reading order, up to [limit] matches. An empty query matches nothing.
 * The lines searched must be the lines drawn, so a match lands on the characters the reader sees.
 */
internal fun findMatches(lines: List<String>, query: String, ignoreCase: Boolean = true, limit: Int = 5000): FindResult {
    if (query.isEmpty()) return FindResult(emptyList(), false)
    val matches = ArrayList<TextMatch>()
    for ((index, line) in lines.withIndex()) {
        var from = 0
        while (true) {
            val at = line.indexOf(query, from, ignoreCase)
            if (at < 0) break
            if (matches.size >= limit) return FindResult(matches, true)
            matches.add(TextMatch(index, at, at + query.length))
            from = at + query.length
        }
    }
    return FindResult(matches, false)
}
