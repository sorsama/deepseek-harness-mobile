package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.FileKind
import com.labteto.dshmobile.runtime.FsOps
import java.io.File

/** One project: a folder directly inside `workspaces`. */
data class ProjectInfo(
    val name: String,
    /** Where the harness sees it: `/workspaces/<name>`. */
    val guestPath: String,
    /** Bytes of the files in it, links not followed; null until counted. */
    val sizeBytes: Long?,
    /** The newest change to anything in it, epoch millis. */
    val lastChange: Long,
    val files: Int,
) {
    val documentId: String get() = WorkspaceTree.ROOT_ID + "/" + name
}

/** The Projects page's list: names, sizes and last changes of the folders in `workspaces`. */
object ProjectList {

    /** The folders, newest change first, without sizes (fast: one listing). */
    fun names(root: File, fs: FsOps): List<ProjectInfo> = root.list().orEmpty()
        .filter { WorkspaceTree.validSegment(it) && !it.startsWith(WorkspaceTree.RESERVED_PREFIX) }
        .map { File(root, it) }
        .filter { fs.lstat(it) == FileKind.DIRECTORY }
        .map { ProjectInfo(it.name, WorkspaceTree.GUEST_ROOT + "/" + it.name, null, it.lastModified(), 0) }
        .sortedWith(compareByDescending<ProjectInfo> { it.lastChange }.thenBy { it.name.lowercase() })

    /** [project] with its size, file count and newest change counted (walks the whole folder, links not followed). */
    fun measure(root: File, fs: FsOps, project: ProjectInfo): ProjectInfo {
        var bytes = 0L
        var files = 0
        var newest = project.lastChange
        val queue = ArrayDeque(listOf(File(root, project.name)))
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (name in dir.list().orEmpty()) {
                val file = File(dir, name)
                when (fs.lstat(file)) {
                    FileKind.DIRECTORY -> {
                        newest = maxOf(newest, file.lastModified())
                        queue += file
                    }
                    FileKind.FILE -> {
                        bytes += file.length()
                        files++
                        newest = maxOf(newest, file.lastModified())
                    }
                    else -> Unit
                }
            }
        }
        return project.copy(sizeBytes = bytes, files = files, lastChange = newest)
    }

    /**
     * A folder name for a new project from [wanted]: cleaned ([WorkspaceTree.cleanName]), spaces
     * kept, and `-2`, `-3`... added when a folder of that name exists. Null when nothing usable is left.
     */
    fun uniqueName(root: File, fs: FsOps, wanted: String): String? {
        val clean = runCatching { WorkspaceTree.cleanName(wanted, topLevel = true) }.getOrNull() ?: return null
        if (fs.lstat(File(root, clean)) == FileKind.NONE) return clean
        for (n in 2..9_999) {
            val candidate = "$clean-$n"
            if (fs.lstat(File(root, candidate)) == FileKind.NONE) return candidate
        }
        return null
    }

    /** Whether [name] can be a project folder's name as it is (for a rename, which never adds a suffix). */
    fun nameProblem(root: File, fs: FsOps, name: String, current: String? = null): NameProblem? {
        val clean = runCatching { WorkspaceTree.cleanName(name, topLevel = true) }.getOrNull() ?: return NameProblem.INVALID
        if (clean != name.trim()) return NameProblem.INVALID
        if (clean == current) return null
        if (fs.lstat(File(root, clean)) != FileKind.NONE) return NameProblem.TAKEN
        return null
    }

    enum class NameProblem { INVALID, TAKEN }

    /**
     * Whether a harness error says the session's working folder is gone (a project deleted or
     * renamed under it): `working-directory: directory does not exist: "/workspaces/x"`. The chat
     * then says so in plain words instead of the harness's own.
     */
    fun isMissingFolderError(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return text.contains("working-directory") && (text.contains("does not exist") || text.contains("not a directory"))
    }
}
