package com.labteto.dshmobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** What sits at a path, looked at without following a symlink. */
enum class FileKind { NONE, FILE, DIRECTORY, SYMLINK, OTHER }

/**
 * The few file operations `java.io.File` cannot do or cannot do without following symlinks.
 *
 * On Android these are `android.system.Os` calls ([AndroidFsOps]); [NioFsOps] is the plain-JVM
 * version for tests. Everything else (writing files, `mkdir`, `rename`) is `java.io.File`.
 */
interface FsOps {
    fun lstat(file: File): FileKind
    fun symlink(target: String, link: File)
    fun chmod(file: File, mode: Int)

    /** The target a symlink at [file] holds, as written; null when [file] is not a symlink. */
    fun readlink(file: File): String?
}

/** `java.nio.file`: exact on Linux and macOS; on Windows symlinks need Developer Mode and modes are ignored. */
object NioFsOps : FsOps {
    override fun lstat(file: File): FileKind {
        val attrs = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return FileKind.NONE
        }
        return when {
            attrs.isSymbolicLink -> FileKind.SYMLINK
            attrs.isDirectory -> FileKind.DIRECTORY
            attrs.isRegularFile -> FileKind.FILE
            else -> FileKind.OTHER
        }
    }

    override fun symlink(target: String, link: File) {
        Files.createSymbolicLink(link.toPath(), Paths.get(target))
    }

    override fun readlink(file: File): String? = try {
        Files.readSymbolicLink(file.toPath()).toString()
    } catch (e: IOException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    override fun chmod(file: File, mode: Int) {
        val perms = buildSet {
            if (mode and 0b100_000_000 != 0) add(PosixFilePermission.OWNER_READ)
            if (mode and 0b010_000_000 != 0) add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0b001_000_000 != 0) add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0b000_100_000 != 0) add(PosixFilePermission.GROUP_READ)
            if (mode and 0b000_010_000 != 0) add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0b000_001_000 != 0) add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0b000_000_100 != 0) add(PosixFilePermission.OTHERS_READ)
            if (mode and 0b000_000_010 != 0) add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0b000_000_001 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
        }
        try {
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (e: UnsupportedOperationException) {
            // Windows: no POSIX modes to set.
        }
    }
}

/** Tree helpers that never follow a symlink. */
object FsTree {

    /**
     * Delete [root] and everything under it.
     *
     * A layer is full of symlinks with absolute targets (`/usr/bin/...`) that mean the guest's
     * paths, not this device's, so following one while deleting would walk out of the tree. Every
     * entry is looked at with `lstat`; a symlink is unlinked, never entered. Read-only directories
     * are made writable first, since some layers ship them.
     */
    fun delete(fs: FsOps, root: File) {
        when (fs.lstat(root)) {
            FileKind.NONE -> return
            FileKind.DIRECTORY -> {
                runCatching { fs.chmod(root, 0b111_000_000) }
                root.list()?.forEach { delete(fs, File(root, it)) }
                if (!root.delete() && fs.lstat(root) != FileKind.NONE) throw IOException("could not delete $root")
            }
            else -> if (!root.delete() && fs.lstat(root) != FileKind.NONE) throw IOException("could not delete $root")
        }
    }

    /** Bytes held by regular files under [root], symlinks not followed. */
    fun size(fs: FsOps, root: File): Long = when (fs.lstat(root)) {
        FileKind.FILE -> root.length()
        FileKind.DIRECTORY -> root.list()?.sumOf { size(fs, File(root, it)) } ?: 0L
        else -> 0L
    }
}
