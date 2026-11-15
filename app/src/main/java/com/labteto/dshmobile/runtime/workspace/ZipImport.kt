package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.FileKind
import com.labteto.dshmobile.runtime.FsOps
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Why an import or an export stopped, in words the screens turn into a sentence. */
enum class TransferProblem {
    NOT_A_ZIP,
    /** An entry would land outside the project folder (zip slip), or names a device path. */
    UNSAFE_ZIP,
    /** Encrypted entries or a compression method `java.util.zip` cannot read. */
    UNSUPPORTED_ZIP,
    /** An entry is larger than the zip said, or the zip is cut short. */
    DAMAGED_ZIP,
    TOO_MANY_ENTRIES,
    NO_SPACE,
    NOT_READABLE,
    NOT_WRITABLE,
}

class TransferException(val problem: TransferProblem, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: problem.name, cause)

/** A long transfer was cancelled by the person; whatever it wrote is removed by its caller. */
class TransferCancelled : IOException("cancelled")

/** Progress of a copy: work done of work known (files and bytes). */
data class TransferProgress(val files: Int, val filesTotal: Int, val bytes: Long, val bytesTotal: Long) {
    /** The mean of the byte share and the file share: a thousand tiny files move the bar as much as one big one. */
    val fraction: Float? get() = when {
        bytesTotal > 0 && filesTotal > 0 -> ((bytes.toDouble() / bytesTotal + files.toDouble() / filesTotal) / 2).toFloat().coerceIn(0f, 1f)
        bytesTotal > 0 -> (bytes.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
        filesTotal > 0 -> (files.toDouble() / filesTotal).toFloat().coerceIn(0f, 1f)
        else -> null
    }
}

/**
 * The parts of a zip's central directory `java.util.zip` does not expose: which system wrote each
 * entry and its external attributes, where a Unix zip keeps the file mode. That is what says an
 * entry is a symlink (`S_IFLNK`, its data is the target) or an executable (`gradlew`), so an
 * import can keep the one and refuse to follow the other.
 *
 * Reads the end-of-central-directory record (and its zip64 form), then every central header. Only
 * names, attributes and uncompressed sizes are taken; the data is read through [ZipFile].
 */
object ZipDirectory {
    data class Record(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        /** `st_mode` when a Unix system wrote the entry and stored one; null otherwise. */
        val unixMode: Int?,
        val dosDirectory: Boolean,
        val encrypted: Boolean,
        val method: Int,
    )

    private const val EOCD = 0x06054b50
    private const val ZIP64_LOCATOR = 0x07064b50
    private const val ZIP64_EOCD = 0x06064b50
    private const val CENTRAL = 0x02014b50

    fun read(file: File, maxEntries: Int): List<Record> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length < 22) throw TransferException(TransferProblem.NOT_A_ZIP)
        // The record is 22 bytes plus a comment of up to 65535.
        val tailSize = minOf(length, 22L + 65_535).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(length - tailSize)
        raf.readFully(tail)
        var eocd = -1
        for (i in tailSize - 22 downTo 0) {
            if (le32(tail, i) == EOCD) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw TransferException(TransferProblem.NOT_A_ZIP)
        var count = le16(tail, eocd + 10).toLong()
        var cdSize = le32(tail, eocd + 12).toLong() and 0xffffffffL
        var cdOffset = le32(tail, eocd + 16).toLong() and 0xffffffffL
        if (count == 0xffffL || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
            val locator = eocd - 20
            if (locator < 0 || le32(tail, locator) != ZIP64_LOCATOR) throw TransferException(TransferProblem.DAMAGED_ZIP)
            val zip64At = le64(tail, locator + 8)
            if (zip64At < 0 || zip64At + 56 > length) throw TransferException(TransferProblem.DAMAGED_ZIP)
            val rec = ByteArray(56)
            raf.seek(zip64At)
            raf.readFully(rec)
            if (le32(rec, 0) != ZIP64_EOCD) throw TransferException(TransferProblem.DAMAGED_ZIP)
            count = le64(rec, 32)
            cdSize = le64(rec, 40)
            cdOffset = le64(rec, 48)
        }
        if (count > maxEntries) throw TransferException(TransferProblem.TOO_MANY_ENTRIES)
        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > length || cdSize > Int.MAX_VALUE) throw TransferException(TransferProblem.DAMAGED_ZIP)
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val out = ArrayList<Record>(count.toInt())
        var p = 0
        while (out.size < count) {
            if (p + 46 > cd.size || le32(cd, p) != CENTRAL) throw TransferException(TransferProblem.DAMAGED_ZIP)
            val madeBy = le16(cd, p + 4)
            val flags = le16(cd, p + 8)
            val method = le16(cd, p + 10)
            var compressed = le32(cd, p + 20).toLong() and 0xffffffffL
            var size = le32(cd, p + 24).toLong() and 0xffffffffL
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            val external = le32(cd, p + 38)
            val end = p + 46 + nameLen + extraLen + commentLen
            if (end > cd.size) throw TransferException(TransferProblem.DAMAGED_ZIP)
            // UTF-8, as java.util.zip reads names by default, so the two always agree on a name.
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = le16(cd, e)
                val len = le16(cd, e + 2)
                if (id == 0x0001) {
                    var q = e + 4
                    if (size == 0xffffffffL && q + 8 <= e + 4 + len) { size = le64(cd, q); q += 8 }
                    if (compressed == 0xffffffffL && q + 8 <= e + 4 + len) { compressed = le64(cd, q) }
                }
                e += 4 + len
            }
            val unix = (madeBy ushr 8) == 3 && (external ushr 16) != 0
            out += Record(
                name = name,
                size = size,
                compressedSize = compressed,
                unixMode = if (unix) external ushr 16 else null,
                dosDirectory = external and 0x10 != 0,
                encrypted = flags and 1 != 0,
                method = method,
            )
            p = end
        }
        out
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
    private fun le64(b: ByteArray, i: Int) = (le32(b, i).toLong() and 0xffffffffL) or (le32(b, i + 4).toLong() shl 32)
}

/**
 * Unpacks a `.zip` into a new project folder, refusing what could reach outside it.
 *
 * - **Zip slip:** an entry name that is absolute, has a drive letter, or has a `..` (with `/` or
 *   `\` as the separator) makes [plan] refuse the whole zip with [TransferProblem.UNSAFE_ZIP]; no
 *   file is written. Parents are created one name at a time and must be real directories.
 * - **Sizes:** the sum of the declared sizes is what the free-space check uses, and an entry that
 *   inflates past its declared size stops the import ([TransferProblem.DAMAGED_ZIP]), so a zip
 *   that lies about its sizes cannot fill the disk.
 * - **Symlinks** (Unix `S_IFLNK` entries) are created only when the target is relative and stays
 *   inside the project folder; any other link is skipped and counted. Links are made after every
 *   file, so no entry is ever written through one.
 * - **Modes:** an entry with an executable bit becomes 0755, everything else 0644.
 * - One folder around everything (GitHub's `repo-main/`) is unwrapped, and its name is offered as
 *   the project's; a macOS `__MACOSX/` folder is left out.
 */
class ZipImporter(private val fs: FsOps, private val maxEntries: Int = 200_000) {

    enum class EntryKind { FILE, DIRECTORY, LINK }

    data class PlannedEntry(val zipName: String, val path: List<String>, val kind: EntryKind, val size: Long, val executable: Boolean)

    data class Plan(val entries: List<PlannedEntry>, val totalBytes: Long, val suggestedName: String?) {
        val fileCount: Int get() = entries.count { it.kind == EntryKind.FILE }
    }

    data class Result(val files: Int, val bytes: Long, val skippedLinks: Int)

    fun plan(zip: File, fallbackName: String?): Plan {
        val records = ZipDirectory.read(zip, maxEntries)
        val planned = ArrayList<PlannedEntry>(records.size)
        for (r in records) {
            if (r.encrypted) throw TransferException(TransferProblem.UNSUPPORTED_ZIP)
            if (r.method != 0 && r.method != 8) throw TransferException(TransferProblem.UNSUPPORTED_ZIP)
            val path = safePath(r.name) ?: throw TransferException(TransferProblem.UNSAFE_ZIP, "unsafe entry")
            if (path.isEmpty()) continue
            if (path.first() == "__MACOSX") continue
            val type = r.unixMode?.and(0xf000)
            val kind = when {
                type == 0xa000 -> EntryKind.LINK
                type == 0x4000 || r.name.endsWith("/") || r.name.endsWith("\\") || r.dosDirectory -> EntryKind.DIRECTORY
                type == null || type == 0x8000 -> EntryKind.FILE
                // FIFOs, devices and sockets have no business in a project.
                else -> continue
            }
            if (r.size < 0) throw TransferException(TransferProblem.DAMAGED_ZIP)
            val executable = (r.unixMode ?: 0) and 0b001_001_001 != 0
            planned += PlannedEntry(r.name, path, kind, if (kind == EntryKind.FILE) r.size else 0, executable)
        }
        // The same file twice is how a crafted zip makes an import write something it never showed.
        val seen = HashSet<List<String>>()
        planned.filter { it.kind != EntryKind.DIRECTORY }.forEach { if (!seen.add(it.path)) throw TransferException(TransferProblem.DAMAGED_ZIP, "duplicate entry") }
        val unwrapped = unwrap(planned)
        val entries = unwrapped?.second ?: planned
        val total = entries.sumOf { it.size }
        return Plan(entries, total, unwrapped?.first ?: fallbackName)
    }

    /** Strips one folder that holds everything; answers its name and the entries below it. */
    private fun unwrap(entries: List<PlannedEntry>): Pair<String, List<PlannedEntry>>? {
        if (entries.isEmpty()) return null
        val top = entries.first().path.first()
        val wrapped = entries.all { it.path.first() == top && (it.path.size > 1 || it.kind == EntryKind.DIRECTORY) }
        if (!wrapped) return null
        val inner = entries.filter { it.path.size > 1 }.map { it.copy(path = it.path.drop(1)) }
        if (inner.isEmpty()) return null
        return top to inner
    }

    /**
     * Unpack [plan] from [zip] into [into], a new empty folder. [progress] gets every few files;
     * [cancelled] is asked between buffers. On any failure [into] is left for the caller to remove.
     */
    fun extract(zip: File, plan: Plan, into: File, progress: (TransferProgress) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
        if (!into.isDirectory && !into.mkdirs()) throw TransferException(TransferProblem.NOT_WRITABLE)
        var files = 0
        var bytes = 0L
        val links = ArrayList<Pair<PlannedEntry, String>>()
        val buffer = ByteArray(64 * 1024)
        try {
            ZipFile(zip).use { zf ->
                for (entry in plan.entries) {
                    if (cancelled()) throw TransferCancelled()
                    when (entry.kind) {
                        EntryKind.DIRECTORY -> mkdirs(into, entry.path)
                        EntryKind.FILE -> {
                            val parent = mkdirs(into, entry.path.dropLast(1))
                            val target = File(parent, entry.path.last())
                            if (fs.lstat(target) != FileKind.NONE) throw TransferException(TransferProblem.DAMAGED_ZIP, "duplicate entry")
                            val ze = zf.getEntry(entry.zipName) ?: throw TransferException(TransferProblem.DAMAGED_ZIP)
                            zf.getInputStream(ze).use { input ->
                                target.outputStream().use { output ->
                                    var written = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        written += n
                                        if (written > entry.size) throw TransferException(TransferProblem.DAMAGED_ZIP, "entry larger than declared")
                                        output.write(buffer, 0, n)
                                        bytes += n
                                        if (cancelled()) throw TransferCancelled()
                                    }
                                }
                            }
                            runCatching { fs.chmod(target, if (entry.executable) WorkspaceTree.EXEC_MODE else WorkspaceTree.FILE_MODE) }
                            ze.time.takeIf { it > 0 }?.let { target.setLastModified(it) }
                            files++
                            if (files % 25 == 0) progress(TransferProgress(files, plan.fileCount, bytes, plan.totalBytes))
                        }
                        EntryKind.LINK -> {
                            val ze = zf.getEntry(entry.zipName) ?: throw TransferException(TransferProblem.DAMAGED_ZIP)
                            val target = zf.getInputStream(ze).use { readSmall(it, 4096) }
                            links += entry to target
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw TransferException(TransferProblem.DAMAGED_ZIP, e.message, e)
        }
        var skipped = 0
        for ((entry, target) in links) {
            if (!linkStaysInside(entry.path, target)) {
                skipped++
                continue
            }
            val parent = mkdirs(into, entry.path.dropLast(1))
            val link = File(parent, entry.path.last())
            if (fs.lstat(link) != FileKind.NONE) {
                skipped++
                continue
            }
            try {
                fs.symlink(target, link)
            } catch (e: IOException) {
                skipped++
            }
        }
        progress(TransferProgress(files, plan.fileCount, bytes, plan.totalBytes))
        return Result(files, bytes, skipped)
    }

    /** Create [path] under [base] one name at a time; every name on the way must be a real directory. */
    private fun mkdirs(base: File, path: List<String>): File {
        var dir = base
        for (name in path) {
            val next = File(dir, name)
            when (fs.lstat(next)) {
                FileKind.DIRECTORY -> Unit
                FileKind.NONE -> {
                    if (!next.mkdir()) throw TransferException(TransferProblem.NOT_WRITABLE)
                    runCatching { fs.chmod(next, WorkspaceTree.DIR_MODE) }
                }
                else -> throw TransferException(TransferProblem.UNSAFE_ZIP, "a file is in the way of a folder")
            }
            dir = next
        }
        return dir
    }

    private fun readSmall(input: InputStream, limit: Int): String {
        val bytes = input.readNBytesCompat(limit + 1)
        if (bytes.size > limit) throw TransferException(TransferProblem.DAMAGED_ZIP, "link target too long")
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /**
         * The names an entry should be unpacked to, or null when the name is unsafe: absolute, a
         * drive letter, a NUL, or a `..` anywhere. `\` counts as a separator too (zips written on
         * Windows use it), and empty or `.` names are dropped.
         */
        fun safePath(name: String): List<String>? {
            if (name.isEmpty() || '\u0000' in name) return null
            val normal = name.replace('\\', '/')
            if (normal.startsWith("/")) return null
            if (normal.length >= 2 && normal[1] == ':' && normal[0].isLetter()) return null
            val parts = normal.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) return null
            return parts
        }

        /** Whether a link at [linkPath] with [target] resolves inside the folder the link is unpacked in. */
        fun linkStaysInside(linkPath: List<String>, target: String): Boolean {
            if (target.isEmpty() || target.startsWith("/") || target.startsWith("\\") || '\u0000' in target) return false
            if (target.length >= 2 && target[1] == ':') return false
            val resolved = ArrayList(linkPath.dropLast(1))
            for (part in target.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (resolved.isEmpty()) return false else resolved.removeAt(resolved.lastIndex)
                    else -> resolved += part
                }
            }
            return resolved.isNotEmpty()
        }

        private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
