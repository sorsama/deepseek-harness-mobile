package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.FileKind
import com.labteto.dshmobile.runtime.FsOps
import com.labteto.dshmobile.runtime.FsTree
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The built-in harness's `workspaces` directory as documents: what the system Files app and every
 * app's file picker see through [WorkspacesProvider], and what the in-app project screens use.
 *
 * Pure Kotlin over [FsOps], so every rule here is a JVM test, including the ones that keep another
 * app inside this directory:
 *
 * - **Document ids** are `workspaces` for the root and `workspaces/<relative path>` below it. An id
 *   is never trusted: [resolve] splits it into names and refuses an empty name, `.`, `..`, a NUL,
 *   and any name that is not a real directory on the way down. So `workspaces/../home/.dsh` and
 *   `workspaces/link-to-root/x` both resolve to nothing. The provider checks the opened file
 *   descriptor's real path once more ([isInside]).
 * - **Symlinks** are never followed out of this directory and never walked through. A link to a
 *   regular file whose target (relative, or an absolute guest path under `/workspaces`) is inside
 *   this directory, and is reached only through real directories, is shown as that file. Every
 *   other link is skipped: links to directories (they would show the same tree twice and can make a
 *   loop that a Files app copies forever), links that point outside, links to links, and broken
 *   links. A project's `node_modules/.bin` links are mostly skipped this way.
 * - **Names Android cannot represent** (bytes that are not UTF-8, which a Linux tool can write) come
 *   back from `File.list()` with replacement characters and then name no file at all. Such an entry
 *   is skipped instead of being shown as something that fails to open.
 * - **Staging directories** of an import or a clone in progress (top-level names starting with
 *   [RESERVED_PREFIX]) are not shown and cannot be named.
 * - FIFOs, sockets and devices are skipped.
 */
class WorkspaceTree(
    val root: File,
    private val fs: FsOps,
    val mime: MimeTypes = MimeTypes(),
) {
    enum class Kind { DIRECTORY, FILE }

    data class Entry(
        val id: String,
        val name: String,
        val kind: Kind,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        /** True for a symlink shown as the file it points to. */
        val link: Boolean = false,
    ) {
        val isDirectory: Boolean get() = kind == Kind.DIRECTORY
    }

    /**
     * Where a document is on disk. [file] is the path of the entry itself (the link, for a link);
     * [content] is what to open (the link's target). Both are inside [root].
     */
    data class Resolved(val id: String, val file: File, val content: File, val kind: Kind, val link: Boolean)

    fun ensureRoot() {
        if (fs.lstat(root) == FileKind.NONE) root.mkdirs()
    }

    // ---- Ids ----------------------------------------------------------------------------------

    fun idOf(segments: List<String>): String = if (segments.isEmpty()) ROOT_ID else ROOT_ID + "/" + segments.joinToString("/")

    /** The names an id stands for, or null when it is not one this tree hands out. */
    fun segmentsOf(id: String): List<String>? {
        if (id == ROOT_ID) return emptyList()
        if (!id.startsWith("$ROOT_ID/")) return null
        val parts = id.substring(ROOT_ID.length + 1).split('/')
        if (parts.any { !validSegment(it) }) return null
        if (parts.first().startsWith(RESERVED_PREFIX)) return null
        return parts
    }

    fun parentId(id: String): String? {
        val segments = segmentsOf(id) ?: return null
        if (segments.isEmpty()) return null
        return idOf(segments.dropLast(1))
    }

    /** Whether [childId] is below [parentId] (not the same document). Pure id logic, as the framework asks. */
    fun isChild(parentId: String, childId: String): Boolean {
        val parent = segmentsOf(parentId) ?: return false
        val child = segmentsOf(childId) ?: return false
        return child.size > parent.size && child.subList(0, parent.size) == parent
    }

    /** The id of the project folder a guest path such as `/workspaces/app/src` is in, or null. */
    fun idForGuestPath(guestPath: String): String? {
        val clean = guestPath.trim().trimEnd('/')
        if (clean == GUEST_ROOT) return ROOT_ID
        if (!clean.startsWith("$GUEST_ROOT/")) return null
        val segments = clean.removePrefix("$GUEST_ROOT/").split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return ROOT_ID
        return idOf(segments).takeIf { segmentsOf(it) != null }
    }

    fun guestPathOf(id: String): String? {
        val segments = segmentsOf(id) ?: return null
        return if (segments.isEmpty()) GUEST_ROOT else GUEST_ROOT + "/" + segments.joinToString("/")
    }

    // ---- Resolving ----------------------------------------------------------------------------

    /**
     * The document [id] names, walked one name at a time without following a symlink, or null when
     * the id is not valid, names nothing, or would leave this directory.
     */
    fun resolve(id: String): Resolved? {
        val segments = segmentsOf(id) ?: return null
        if (fs.lstat(root) != FileKind.DIRECTORY) return null
        if (segments.isEmpty()) return Resolved(id, root, root, Kind.DIRECTORY, link = false)
        var dir = root
        for ((index, name) in segments.withIndex()) {
            val file = File(dir, name)
            val last = index == segments.lastIndex
            when (fs.lstat(file)) {
                FileKind.DIRECTORY -> if (last) return Resolved(id, file, file, Kind.DIRECTORY, link = false) else dir = file
                FileKind.FILE -> return if (last) Resolved(id, file, file, Kind.FILE, link = false) else null
                FileKind.SYMLINK -> {
                    if (!last) return null
                    val target = safeLinkTarget(file, segments.dropLast(1)) ?: return null
                    return Resolved(id, file, target, Kind.FILE, link = true)
                }
                FileKind.NONE, FileKind.OTHER -> return null
            }
        }
        return null
    }

    /**
     * The regular file a link at [link] (in the directory [parentSegments]) points to, when that
     * file is inside this tree and reached through real directories only; null otherwise.
     */
    fun safeLinkTarget(link: File, parentSegments: List<String>): File? {
        val target = fs.readlink(link) ?: return null
        if (target.isEmpty() || target.contains('\u0000')) return null
        val start: List<String> = when {
            target.startsWith("$GUEST_ROOT/") -> emptyList()
            target.startsWith("/") -> return null
            else -> parentSegments
        }
        val rest = if (target.startsWith("$GUEST_ROOT/")) target.removePrefix("$GUEST_ROOT/") else target
        val resolved = ArrayList(start)
        for (part in rest.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (resolved.isEmpty()) return null else resolved.removeAt(resolved.lastIndex)
                else -> resolved += part
            }
        }
        if (resolved.isEmpty() || resolved.first().startsWith(RESERVED_PREFIX)) return null
        var dir = root
        for ((index, name) in resolved.withIndex()) {
            val file = File(dir, name)
            val kind = fs.lstat(file)
            if (index == resolved.lastIndex) return if (kind == FileKind.FILE) file else null
            if (kind != FileKind.DIRECTORY) return null
            dir = file
        }
        return null
    }

    // ---- Reading ------------------------------------------------------------------------------

    fun entry(id: String): Entry? = resolve(id)?.let { toEntry(it) }

    private fun toEntry(r: Resolved): Entry {
        val segments = segmentsOf(r.id).orEmpty()
        val name = segments.lastOrNull() ?: root.name
        return if (r.kind == Kind.DIRECTORY) {
            Entry(r.id, name, Kind.DIRECTORY, 0, r.file.lastModified(), MimeTypes.DIRECTORY)
        } else {
            Entry(r.id, name, Kind.FILE, r.content.length(), r.content.lastModified(), mime.forName(name), link = r.link)
        }
    }

    /** The entries of the directory [id], by the rules in the class comment. Empty for anything else. */
    fun list(id: String): List<Entry> {
        val dir = resolve(id)?.takeIf { it.kind == Kind.DIRECTORY } ?: return emptyList()
        val segments = segmentsOf(id).orEmpty()
        val names = dir.file.list() ?: return emptyList()
        return names.sorted().mapNotNull { name ->
            if (!validSegment(name)) return@mapNotNull null
            if (segments.isEmpty() && name.startsWith(RESERVED_PREFIX)) return@mapNotNull null
            val file = File(dir.file, name)
            val childId = idOf(segments + name)
            when (fs.lstat(file)) {
                FileKind.DIRECTORY -> Entry(childId, name, Kind.DIRECTORY, 0, file.lastModified(), MimeTypes.DIRECTORY)
                FileKind.FILE -> Entry(childId, name, Kind.FILE, file.length(), file.lastModified(), mime.forName(name))
                FileKind.SYMLINK -> safeLinkTarget(file, segments)?.let { target ->
                    Entry(childId, name, Kind.FILE, target.length(), target.lastModified(), mime.forName(name), link = true)
                }
                // NONE: the name did not survive decoding (see the class comment); OTHER: a FIFO or socket.
                FileKind.NONE, FileKind.OTHER -> null
            }
        }
    }

    /** Ids from the root down to [id], both included; null when [id] does not resolve. */
    fun pathTo(id: String): List<String>? {
        val segments = segmentsOf(id) ?: return null
        if (resolve(id) == null) return null
        return (0..segments.size).map { idOf(segments.subList(0, it)) }
    }

    /**
     * Files and folders whose name contains [query] (any case), breadth first, at most [limit]
     * results and [budget] entries looked at. Heavy folders ([SEARCH_SKIP]) are not entered.
     */
    fun search(query: String, limit: Int = 50, budget: Int = 20_000): List<Entry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        walk(budget) { entry ->
            if (entry.name.lowercase().contains(needle)) out += entry
            out.size < limit
        }
        return out
    }

    /** The files changed most recently, newest first: what a Files app shows under Recent. */
    fun recent(limit: Int = 64, budget: Int = 20_000): List<Entry> {
        val files = ArrayList<Entry>()
        walk(budget) { entry ->
            if (!entry.isDirectory) files += entry
            true
        }
        return files.sortedByDescending { it.lastModified }.take(limit)
    }

    private fun walk(budget: Int, visit: (Entry) -> Boolean) {
        val queue = ArrayDeque(listOf(ROOT_ID))
        var seen = 0
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (entry in list(dir)) {
                if (++seen > budget) return
                if (!visit(entry)) return
                if (entry.isDirectory && entry.name !in SEARCH_SKIP) queue += entry.id
            }
        }
    }

    // ---- Changing -----------------------------------------------------------------------------

    /** Create a file or folder called [name] (made unique) in the folder [parentId]. */
    fun create(parentId: String, name: String, directory: Boolean): Entry {
        val parent = requireDirectory(parentId)
        val clean = cleanName(name, topLevel = parent.id == ROOT_ID)
        val file = uniqueChild(parent.file, clean)
        if (directory) {
            if (!file.mkdir()) throw IOException("could not create folder")
            chmodQuiet(file, DIR_MODE)
        } else {
            if (!file.createNewFile()) throw IOException("could not create file")
            chmodQuiet(file, FILE_MODE)
        }
        return entry(idOf(segmentsOf(parent.id)!! + file.name)) ?: throw IOException("created entry vanished")
    }

    /** Rename the entry [id] (made unique next to its siblings); answers the new entry. */
    fun rename(id: String, name: String): Entry {
        val doc = resolve(id) ?: throw FileNotFoundException("no such document")
        val segments = segmentsOf(id)!!
        if (segments.isEmpty()) throw IllegalArgumentException("the root cannot be renamed")
        val clean = cleanName(name, topLevel = segments.size == 1)
        if (clean == segments.last()) return entry(id)!!
        val target = uniqueChild(doc.file.parentFile!!, clean)
        if (!doc.file.renameTo(target)) throw IOException("could not rename")
        return entry(idOf(segments.dropLast(1) + target.name)) ?: throw IOException("renamed entry vanished")
    }

    /** Delete [id] and everything under it; a link is removed, never its target. */
    fun delete(id: String) {
        val doc = resolve(id) ?: throw FileNotFoundException("no such document")
        if (doc.id == ROOT_ID) throw IllegalArgumentException("the root cannot be deleted")
        FsTree.delete(fs, doc.file)
    }

    /** Copy [id] into the folder [targetParentId]; answers the copy. Links are copied as links. */
    fun copy(id: String, targetParentId: String): Entry {
        val doc = resolve(id) ?: throw FileNotFoundException("no such document")
        val parent = requireDirectory(targetParentId)
        if (doc.id == ROOT_ID || targetParentId == id || isChild(id, targetParentId)) throw IllegalArgumentException("cannot copy a folder into itself")
        val target = uniqueChild(parent.file, doc.file.name)
        try {
            copyTree(doc.file, target)
        } catch (e: IOException) {
            runCatching { FsTree.delete(fs, target) }
            throw e
        }
        return entry(idOf(segmentsOf(parent.id)!! + target.name)) ?: throw IOException("copied entry vanished")
    }

    /** Move [id] into the folder [targetParentId] (a rename on the same disk); answers the moved entry. */
    fun move(id: String, targetParentId: String): Entry {
        val doc = resolve(id) ?: throw FileNotFoundException("no such document")
        val parent = requireDirectory(targetParentId)
        if (doc.id == ROOT_ID || targetParentId == id || isChild(id, targetParentId)) throw IllegalArgumentException("cannot move a folder into itself")
        val segments = segmentsOf(id)!!
        if (parent.id == ROOT_ID) cleanName(segments.last(), topLevel = true)
        val target = uniqueChild(parent.file, doc.file.name)
        if (!doc.file.renameTo(target)) throw IOException("could not move")
        return entry(idOf(segmentsOf(parent.id)!! + target.name)) ?: throw IOException("moved entry vanished")
    }

    /** Copy without following links; the executable bit is kept, everything else gets the usual modes. */
    fun copyTree(src: File, dst: File, onFile: (bytes: Long) -> Unit = {}) {
        when (fs.lstat(src)) {
            FileKind.FILE -> {
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it, BUFFER) } }
                chmodQuiet(dst, if (src.canExecute()) EXEC_MODE else FILE_MODE)
                dst.setLastModified(src.lastModified())
                onFile(src.length())
            }
            FileKind.DIRECTORY -> {
                if (!dst.mkdir()) throw IOException("could not create folder")
                chmodQuiet(dst, DIR_MODE)
                src.list()?.sorted()?.forEach { copyTree(File(src, it), File(dst, it), onFile) }
            }
            FileKind.SYMLINK -> fs.readlink(src)?.let { fs.symlink(it, dst) }
            FileKind.NONE, FileKind.OTHER -> Unit
        }
    }

    private fun requireDirectory(id: String): Resolved {
        val doc = resolve(id) ?: throw FileNotFoundException("no such folder")
        if (doc.kind != Kind.DIRECTORY) throw IllegalArgumentException("not a folder")
        return doc
    }

    /** [name], or `name (1).ext`, `name (2).ext`... whichever does not exist yet in [dir]. */
    fun uniqueChild(dir: File, name: String): File {
        val first = File(dir, name)
        if (fs.lstat(first) == FileKind.NONE) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        for (n in 1..9_999) {
            val candidate = File(dir, "$stem ($n)$ext")
            if (fs.lstat(candidate) == FileKind.NONE) return candidate
        }
        throw IOException("too many files with this name")
    }

    private fun chmodQuiet(file: File, mode: Int) {
        runCatching { fs.chmod(file, mode) }
    }

    companion object {
        const val ROOT_ID = "workspaces"
        const val GUEST_ROOT = "/workspaces"

        /** Top-level names the app keeps for imports and clones in progress. */
        const val RESERVED_PREFIX = ".dsh-"

        /** ext4 and f2fs limit a name to 255 bytes. */
        const val MAX_NAME_BYTES = 255

        const val FILE_MODE = 0b110_100_100 // 0644
        const val EXEC_MODE = 0b111_101_101 // 0755
        const val DIR_MODE = 0b111_101_101 // 0755
        private const val BUFFER = 64 * 1024

        /** Folders search and Recent do not walk into: big, generated, and never what someone looks for. */
        val SEARCH_SKIP = setOf(".git", "node_modules", ".gradle", "build", "dist", "target", ".venv", "venv", "__pycache__", ".next")

        fun validSegment(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

        /**
         * A name typed by a person or sent by a Files app, made safe to create: trimmed, no `/` or
         * NUL, not `.` or `..`, at most [MAX_NAME_BYTES] bytes (cut before the extension), and not a
         * reserved staging name at the top level. Throws [IllegalArgumentException] when nothing
         * usable is left.
         */
        fun cleanName(name: String, topLevel: Boolean): String {
            val trimmed = name.trim().replace('/', '_').replace("\u0000", "")
            require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..") { "not a usable name" }
            require(!(topLevel && trimmed.startsWith(RESERVED_PREFIX))) { "this name is kept for the app" }
            return fitBytes(trimmed)
        }

        private fun fitBytes(name: String): String {
            if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 } ?: name.length
            val ext = name.substring(dot)
            var stem = name.substring(0, dot)
            val room = MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size
            while (stem.isNotEmpty() && stem.toByteArray(Charsets.UTF_8).size > room) {
                // Drop whole code points, never half a surrogate pair.
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
            }
            return stem + ext
        }

        /** Whether [path] (a real, resolved path) is [root] or below it. */
        fun isInside(rootCanonical: String, path: String): Boolean =
            path == rootCanonical || path.startsWith(rootCanonical.trimEnd('/') + "/")
    }
}
