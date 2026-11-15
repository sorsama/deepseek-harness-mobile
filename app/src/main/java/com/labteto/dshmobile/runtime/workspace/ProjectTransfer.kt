package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.FileKind
import com.labteto.dshmobile.runtime.FsOps
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Which folders an export leaves out.
 *
 * "Skip heavy folders" (the default) leaves out what a project can make again: dependency trees
 * and caches ([ALWAYS_HEAVY]) always, and build output ([BUILD_OUTPUT]) when a `.gitignore` in the
 * project or in the folder's own parent ignores it, so a `build/` folder that is part of the
 * project's source (some projects keep build scripts there) still goes along. `.git` is its own
 * choice, off by default: a project's history can be much larger than the project.
 */
data class ExportFilter(val skipHeavy: Boolean = true, val includeGit: Boolean = false) {

    /** Whether the folder [name], directly inside [parent] (at [depth] below the project), is left out. */
    fun skipsFolder(name: String, parent: File, projectRoot: File, depth: Int): Boolean {
        if (name == ".git") return !includeGit
        if (!skipHeavy) return false
        if (name in ALWAYS_HEAVY) return true
        if (name in BUILD_OUTPUT) return ignoredByGit(name, parent, projectRoot, depth)
        return false
    }

    companion object {
        val EVERYTHING = ExportFilter(skipHeavy = false, includeGit = true)

        val ALWAYS_HEAVY = setOf(
            "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".next", ".nuxt", ".turbo", ".parcel-cache", ".dart_tool", ".tox", ".cache", ".kotlin",
        )
        val BUILD_OUTPUT = setOf("build", "dist", "target", "out", ".build", "bin", "obj")

        /**
         * Whether a `.gitignore` names [name]: the project's own (any depth unless anchored with a
         * leading `/`, which means the project root) or the parent folder's (where both forms match).
         * Plain names only (`build`, `build/`, `/build`, and the same after a leading double star); patterns with wildcards elsewhere
         * and negations are not interpreted.
         */
        fun ignoredByGit(name: String, parent: File, projectRoot: File, depth: Int): Boolean {
            fun patterns(file: File): List<String> = runCatching {
                if (file.isFile && file.length() < 256 * 1024) file.readLines() else emptyList()
            }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }

            fun matches(pattern: String, anchoredOk: Boolean): Boolean {
                var p = pattern.removeSuffix("/*").removeSuffix("/")
                val anchored = p.startsWith("/")
                p = p.removePrefix("/").removePrefix("**/")
                if (p != name) return false
                return !anchored || anchoredOk
            }
            if (patterns(File(projectRoot, ".gitignore")).any { matches(it, anchoredOk = depth == 0) }) return true
            if (depth > 0 && patterns(File(parent, ".gitignore")).any { matches(it, anchoredOk = true) }) return true
            return false
        }
    }
}

/** What an export will contain, counted before it starts: for the space check and the progress. */
data class ExportScan(val files: Int, val bytes: Long, val skippedFolders: Int, val links: Int)

/** Where "Save a copy to the phone" writes: a folder the person picked (the system's document tree in the app). */
interface TreeSink {
    /** A new folder [name] in [parent] (null: the picked folder); answers its handle. */
    fun createFolder(parent: Any?, name: String): Any

    /** A new file [name] in [parent]; the caller writes and closes it. */
    fun createFile(parent: Any?, name: String, mimeType: String): OutputStream
}

/** Where "Import a folder" reads from: the folder the person picked, walked one level at a time. */
interface SourceTree {
    data class Node(val handle: Any, val name: String, val isDirectory: Boolean, val size: Long)

    fun children(parent: Any?): List<Node>
    fun open(node: Node): InputStream
}

/**
 * Copies a project out (a zip, or a folder through [TreeSink]) and a picked folder in, without
 * following a symlink and with progress and cancellation.
 *
 * Exports leave symlinks out (a zip from `java.util.zip` cannot mark one, and a Files app folder
 * cannot hold one) and keep files and folders only; executable bits do not survive a zip either.
 */
class ProjectTransfer(private val fs: FsOps, private val mime: MimeTypes = MimeTypes()) {

    fun scan(project: File, filter: ExportFilter, cancelled: () -> Boolean = { false }): ExportScan {
        var files = 0
        var bytes = 0L
        var skipped = 0
        var links = 0
        walk(project, filter, cancelled, onSkip = { skipped++ }, onLink = { links++ }) { _, file, kind ->
            if (kind == FileKind.FILE) {
                files++
                bytes += file.length()
            }
        }
        return ExportScan(files, bytes, skipped, links)
    }

    /** Write [project] as a zip to [out], every entry under [rootName]/. */
    fun writeZip(
        project: File,
        rootName: String,
        filter: ExportFilter,
        out: OutputStream,
        scan: ExportScan,
        progress: (TransferProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("$rootName/"))
            zip.closeEntry()
            walk(project, filter, cancelled, onSkip = {}, onLink = {}) { rel, file, kind ->
                val name = "$rootName/" + rel.joinToString("/") + if (kind == FileKind.DIRECTORY) "/" else ""
                val entry = ZipEntry(name)
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                if (kind == FileKind.FILE) {
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            bytes += n
                            if (cancelled()) throw TransferCancelled()
                        }
                    }
                    files++
                    if (files % 25 == 0) progress(TransferProgress(files, scan.files, bytes, scan.bytes))
                }
                zip.closeEntry()
            }
        }
        progress(TransferProgress(files, scan.files, bytes, scan.bytes))
    }

    /** Copy [project] into [sink] as a folder called [rootName]. */
    fun copyOut(
        project: File,
        rootName: String,
        filter: ExportFilter,
        sink: TreeSink,
        scan: ExportScan,
        progress: (TransferProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        val handles = HashMap<List<String>, Any>()
        val top = sink.createFolder(null, rootName)
        handles[emptyList()] = top
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        walk(project, filter, cancelled, onSkip = {}, onLink = {}) { rel, file, kind ->
            val parent = handles[rel.dropLast(1)] ?: throw TransferException(TransferProblem.NOT_WRITABLE)
            if (kind == FileKind.DIRECTORY) {
                handles[rel] = sink.createFolder(parent, rel.last())
            } else {
                sink.createFile(parent, rel.last(), mime.forName(rel.last())).use { output ->
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            bytes += n
                            if (cancelled()) throw TransferCancelled()
                        }
                    }
                }
                files++
                if (files % 10 == 0) progress(TransferProgress(files, scan.files, bytes, scan.bytes))
            }
        }
        progress(TransferProgress(files, scan.files, bytes, scan.bytes))
    }

    /** Count what [source] holds below [root]: files and bytes, for the space check and progress. */
    fun scanSource(source: SourceTree, root: Any?, cancelled: () -> Boolean = { false }): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val queue = ArrayDeque<Any?>(listOf(root))
        while (queue.isNotEmpty()) {
            if (cancelled()) throw TransferCancelled()
            for (node in source.children(queue.removeFirst())) {
                if (node.isDirectory) queue += node.handle else {
                    files++
                    bytes += node.size.coerceAtLeast(0)
                }
            }
        }
        return files to bytes
    }

    /**
     * Copy the folder [root] of [source] into [into] (a new empty folder). Names a Linux folder
     * cannot hold are made safe ([WorkspaceTree.cleanName]); two entries with one name (a document
     * tree allows it) get ` (1)`. Returns the files and bytes copied.
     */
    fun copyIn(
        source: SourceTree,
        root: Any?,
        into: File,
        total: Pair<Int, Long>,
        progress: (TransferProgress) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Pair<Int, Long> {
        if (!into.isDirectory && !into.mkdirs()) throw TransferException(TransferProblem.NOT_WRITABLE)
        val tree = WorkspaceTree(into, fs)
        var files = 0
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        val queue = ArrayDeque<Pair<Any?, File>>(listOf(root to into))
        while (queue.isNotEmpty()) {
            val (handle, dir) = queue.removeFirst()
            for (node in source.children(handle)) {
                if (cancelled()) throw TransferCancelled()
                val clean = runCatching { WorkspaceTree.cleanName(node.name, topLevel = false) }.getOrNull() ?: continue
                val target = tree.uniqueChild(dir, clean)
                if (node.isDirectory) {
                    if (!target.mkdir()) throw TransferException(TransferProblem.NOT_WRITABLE)
                    runCatching { fs.chmod(target, WorkspaceTree.DIR_MODE) }
                    queue += node.handle to target
                } else {
                    source.open(node).use { input ->
                        target.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                bytes += n
                                if (cancelled()) throw TransferCancelled()
                            }
                        }
                    }
                    runCatching { fs.chmod(target, WorkspaceTree.FILE_MODE) }
                    files++
                    if (files % 10 == 0) progress(TransferProgress(files, total.first, bytes, total.second))
                }
            }
        }
        progress(TransferProgress(files, total.first, bytes, total.second))
        return files to bytes
    }

    /**
     * Every entry under [project] the filter keeps, parents before children, never through a
     * symlink. [visit] gets the path below the project, the file and its kind (FILE or DIRECTORY).
     */
    private fun walk(
        project: File,
        filter: ExportFilter,
        cancelled: () -> Boolean,
        onSkip: () -> Unit,
        onLink: () -> Unit,
        visit: (List<String>, File, FileKind) -> Unit,
    ) {
        if (fs.lstat(project) != FileKind.DIRECTORY) throw TransferException(TransferProblem.NOT_READABLE)
        val queue = ArrayDeque(listOf(emptyList<String>() to project))
        while (queue.isNotEmpty()) {
            val (rel, dir) = queue.removeFirst()
            for (name in dir.list().orEmpty().sorted()) {
                if (cancelled()) throw TransferCancelled()
                val file = File(dir, name)
                val path = rel + name
                when (fs.lstat(file)) {
                    FileKind.DIRECTORY -> if (filter.skipsFolder(name, dir, project, rel.size)) onSkip() else {
                        visit(path, file, FileKind.DIRECTORY)
                        queue += path to file
                    }
                    FileKind.FILE -> visit(path, file, FileKind.FILE)
                    FileKind.SYMLINK -> onLink()
                    else -> Unit
                }
            }
        }
    }
}

/** Free space is checked against what an operation will write plus this margin, so the phone is never filled to the last byte. */
const val SPACE_MARGIN_BYTES: Long = 200L * 1024 * 1024

fun requireSpace(available: Long, needed: Long) {
    if (available < needed + SPACE_MARGIN_BYTES) throw TransferException(TransferProblem.NO_SPACE, "needs $needed, has $available")
}

/** Remove [dir] quietly; used to clean up a staging folder after a failed or cancelled operation. */
fun FsOps.removeQuietly(dir: File) {
    runCatching { com.labteto.dshmobile.runtime.FsTree.delete(this, dir) }
}

internal fun IOException.isNoSpace(): Boolean =
    message?.contains("ENOSPC") == true || message?.contains("No space left", ignoreCase = true) == true
