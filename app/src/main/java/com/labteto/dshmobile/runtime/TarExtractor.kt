package com.labteto.dshmobile.runtime

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** A layer archive broke one of the unpacking rules; nothing it wrote can be trusted. */
class ArchiveException(message: String) : IOException(message)

/**
 * A streaming reader for the GNU tar files the runtime layers are built as.
 *
 * It enforces the runtime repository's unpacking rules rather than trusting that the build did:
 *
 * - Only regular files, directories and symbolic links. A hard link, a device node, a FIFO or a
 *   pax header is refused: the build flattens hard links and produces none of the rest.
 * - Names are relative. An absolute name, a `..` component, or a name whose parent is a symlink is
 *   refused, so nothing can be written outside [extract]'s target, not even through a link an
 *   earlier entry created. Parents are created with `mkdir`, never by following anything.
 * - Symlinks are created exactly as stored. Their targets are guest paths and are not resolved.
 * - File modes are applied (setuid and setgid dropped; they mean nothing here). Directory modes
 *   are applied last, deepest first, because some directories are read-only.
 * - An entry name may not repeat.
 *
 * The caller decompresses (zstd for real layers) and passes the tar stream.
 */
class TarExtractor(private val fs: FsOps) {

    data class Stats(val entries: Int, val files: Int, val directories: Int, val symlinks: Int, val bytes: Long)

    /**
     * Unpack [input] into [target], which must exist and be empty.
     *
     * [onProgress] gets entries and bytes written so far, every few hundred entries and at the end.
     * [checkCancelled] is called between entries and may throw to abandon the unpack.
     */
    fun extract(
        input: InputStream,
        target: File,
        onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Stats {
        if (fs.lstat(target) != FileKind.DIRECTORY) throw IOException("$target is not a directory")
        val header = ByteArray(BLOCK)
        val buffer = ByteArray(BUFFER)
        val knownDirs = HashSet<String>()
        val dirModes = ArrayList<Pair<File, Int>>()
        var longName: String? = null
        var longLink: String? = null
        var entries = 0
        var files = 0
        var dirs = 0
        var links = 0
        var bytes = 0L

        while (true) {
            checkCancelled()
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker
            verifyChecksum(header)
            val type = header[156].toInt().toChar()
            val size = parseSize(header)
            when (type) {
                'L' -> {
                    longName = readLongText(input, size)
                    continue
                }
                'K' -> {
                    longLink = readLongText(input, size)
                    continue
                }
            }
            val name = longName ?: headerName(header)
            val linkName = longLink ?: cString(header, 157, 100)
            longName = null
            longLink = null
            val mode = parseOctal(header, 100, 8).toInt()
            val parts = components(name)

            when (type) {
                '0', '\u0000', '7' -> {
                    if (parts.isEmpty()) throw ArchiveException("a file entry with no name")
                    val file = prepareLeaf(target, parts, knownDirs)
                    if (fs.lstat(file) != FileKind.NONE) throw ArchiveException("'$name' appears twice")
                    FileOutputStream(file).use { out -> copyExactly(input, out, size, buffer) }
                    skipPadding(input, size)
                    fs.chmod(file, mode and 0b111_111_111)
                    files++
                    bytes += size
                }
                '5' -> {
                    skipData(input, size)
                    if (parts.isEmpty()) {
                        // The archive's own root: the target directory, whose mode stays ours.
                    } else {
                        val dir = prepareLeaf(target, parts, knownDirs)
                        when (fs.lstat(dir)) {
                            FileKind.NONE -> if (!dir.mkdir()) throw IOException("mkdir $dir failed")
                            FileKind.DIRECTORY -> Unit
                            else -> throw ArchiveException("'$name' is a directory and something else")
                        }
                        knownDirs += parts.joinToString("/")
                        // Sticky stays (the guest's /tmp is 1777); setuid and setgid go.
                        dirModes += dir to (mode and 0b1_111_111_111)
                        dirs++
                    }
                }
                '2' -> {
                    skipData(input, size)
                    if (parts.isEmpty()) throw ArchiveException("a symlink entry with no name")
                    if (linkName.isEmpty() || '\u0000' in linkName) throw ArchiveException("'$name' has an empty link target")
                    val link = prepareLeaf(target, parts, knownDirs)
                    if (fs.lstat(link) != FileKind.NONE) throw ArchiveException("'$name' appears twice")
                    fs.symlink(linkName, link)
                    links++
                }
                '1' -> throw ArchiveException("'$name' is a hard link; layers flatten them")
                'x', 'g' -> throw ArchiveException("pax headers are not part of the layer format")
                else -> throw ArchiveException("'$name' has unsupported entry type '$type'")
            }
            entries++
            if (entries % PROGRESS_EVERY == 0) onProgress(entries, bytes)
        }
        if (longName != null || longLink != null) throw ArchiveException("archive ends inside a long-name entry")

        // Deepest first, so making a parent read-only never blocks setting a child.
        dirModes.sortedByDescending { (dir, _) -> dir.path.count { it == File.separatorChar } }
            .forEach { (dir, mode) -> fs.chmod(dir, mode) }
        onProgress(entries, bytes)
        return Stats(entries, files, dirs, links, bytes)
    }

    /**
     * The file for [parts] under [target], with every parent created and checked.
     *
     * Each parent must be a real directory: a symlink there is refused even if it points somewhere
     * harmless, since following it is exactly what a crafted archive would rely on.
     */
    private fun prepareLeaf(target: File, parts: List<String>, knownDirs: MutableSet<String>): File {
        var dir = target
        val path = StringBuilder()
        for (i in 0 until parts.size - 1) {
            if (path.isNotEmpty()) path.append('/')
            path.append(parts[i])
            dir = File(dir, parts[i])
            val key = path.toString()
            if (key in knownDirs) continue
            when (fs.lstat(dir)) {
                FileKind.NONE -> if (!dir.mkdir()) throw IOException("mkdir $dir failed")
                FileKind.DIRECTORY -> Unit
                FileKind.SYMLINK -> throw ArchiveException("'${parts.joinToString("/")}' goes through the symlink '$key'")
                else -> throw ArchiveException("'$key' is not a directory")
            }
            knownDirs += key
        }
        return File(dir, parts.last())
    }

    private fun headerName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val magic = String(header, 257, 6, Charsets.ISO_8859_1)
        // POSIX ustar splits long names into prefix + name; GNU tar uses that space for other
        // fields and writes an 'L' entry instead, so the prefix is read only for "ustar\0".
        if (magic == "ustar\u0000") {
            val prefix = cString(header, 345, 155)
            if (prefix.isNotEmpty()) return "$prefix/$name"
        }
        return name
    }

    private fun readLongText(input: InputStream, size: Long): String {
        if (size <= 0 || size > MAX_LONG_NAME) throw ArchiveException("long-name entry of $size bytes")
        val data = ByteArray(size.toInt())
        readFully(input, data, 0, data.size)
        skipPadding(input, size)
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        return String(data, 0, end, Charsets.UTF_8)
    }

    private fun copyExactly(input: InputStream, out: FileOutputStream, size: Long, buffer: ByteArray) {
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("archive ends inside a file")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        if (size == 0L) return
        skipExactly(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        if (pad > 0) skipExactly(input, pad)
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(minOf(count, BUFFER.toLong()).toInt())
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("archive ends early")
            left -= n
        }
    }

    companion object {
        private const val BLOCK = 512
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_EVERY = 256
        private const val MAX_LONG_NAME = 64 * 1024L

        /** Split a stored name into checked components; empty for the archive root (`./`). */
        internal fun components(name: String): List<String> {
            if (name.isEmpty()) throw ArchiveException("an entry with an empty name")
            if (name.startsWith("/")) throw ArchiveException("'$name' is an absolute path")
            if ('\u0000' in name) throw ArchiveException("'$name' contains a NUL")
            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw ArchiveException("'$name' climbs out with ..")
            return parts
        }

        /** Read one 512-byte block; false at a clean end of stream before it. */
        private fun readBlock(input: InputStream, block: ByteArray): Boolean {
            var read = 0
            while (read < block.size) {
                val n = input.read(block, read, block.size - read)
                if (n < 0) {
                    if (read == 0) return false
                    throw EOFException("archive ends inside a header")
                }
                read += n
            }
            return true
        }

        private fun readFully(input: InputStream, data: ByteArray, offset: Int, length: Int) {
            var read = 0
            while (read < length) {
                val n = input.read(data, offset + read, length - read)
                if (n < 0) throw EOFException("archive ends early")
                read += n
            }
        }

        private fun verifyChecksum(header: ByteArray) {
            val stored = parseOctal(header, 148, 8)
            var unsigned = 0L
            var signed = 0L
            for (i in header.indices) {
                val b = if (i in 148 until 156) 0x20.toByte() else header[i]
                unsigned += b.toInt() and 0xff
                signed += b.toInt()
            }
            if (stored != unsigned && stored != signed) throw ArchiveException("bad header checksum")
        }

        /** Octal or, with the top bit set (GNU), big-endian base-256. */
        private fun parseSize(header: ByteArray): Long {
            if (header[124].toInt() and 0x80 != 0) {
                var value = 0L
                for (i in 125 until 136) value = (value shl 8) or (header[i].toLong() and 0xff)
                if (value < 0) throw ArchiveException("negative entry size")
                return value
            }
            return parseOctal(header, 124, 12)
        }

        private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
            var value = 0L
            var i = offset
            val end = offset + length
            while (i < end && (header[i] == ' '.code.toByte() || header[i] == 0.toByte())) i++
            while (i < end) {
                val c = header[i].toInt()
                if (c == 0 || c == ' '.code) break
                if (c < '0'.code || c > '7'.code) throw ArchiveException("bad octal field")
                value = (value shl 3) or (c - '0'.code).toLong()
                i++
            }
            return value
        }

        private fun cString(header: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && header[end] != 0.toByte()) end++
            return String(header, offset, end - offset, Charsets.UTF_8)
        }
    }
}
