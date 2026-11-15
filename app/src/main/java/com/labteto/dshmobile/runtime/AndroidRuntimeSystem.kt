package com.labteto.dshmobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** [FsOps] on `android.system.Os`: exact, and no `java.nio` surprises on API 26. */
object AndroidFsOps : FsOps {
    override fun lstat(file: File): FileKind {
        val stat = try {
            Os.lstat(file.path)
        } catch (e: ErrnoException) {
            return FileKind.NONE
        }
        return when {
            OsConstants.S_ISLNK(stat.st_mode) -> FileKind.SYMLINK
            OsConstants.S_ISDIR(stat.st_mode) -> FileKind.DIRECTORY
            OsConstants.S_ISREG(stat.st_mode) -> FileKind.FILE
            else -> FileKind.OTHER
        }
    }

    override fun symlink(target: String, link: File) {
        try {
            Os.symlink(target, link.path)
        } catch (e: ErrnoException) {
            throw java.io.IOException("symlink ${link.name}: ${e.message}", e)
        }
    }

    override fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.path, mode)
        } catch (e: ErrnoException) {
            throw java.io.IOException("chmod ${file.name}: ${e.message}", e)
        }
    }

    override fun readlink(file: File): String? = try {
        Os.readlink(file.path)
    } catch (e: ErrnoException) {
        null
    }
}

/**
 * This uid's processes, read from `/proc`.
 *
 * Android mounts `/proc` with `hidepid=2`, so an app sees only its own processes; the uid is
 * still checked, so a device that mounts it differently cannot make this signal anyone else's.
 */
object AndroidProcessTable : ProcessTable {
    private val myUid = android.os.Process.myUid()

    override val selfPid: Int get() = android.os.Process.myPid()

    override fun snapshot(): List<ProcInfo> =
        File("/proc").list().orEmpty().mapNotNull { name -> name.toIntOrNull()?.let(::read) }

    override fun signal(pid: Int, signal: Int) {
        if (pid <= 1 || pid == selfPid) return
        android.os.Process.sendSignal(pid, signal)
    }

    override fun isAlive(pid: Int): Boolean = read(pid) != null

    private fun read(pid: Int): ProcInfo? {
        val dir = File("/proc/$pid")
        return try {
            val stat = File(dir, "stat").readText()
            // comm is in parentheses and may itself hold spaces or ')'; fields start after the last ')'.
            val fields = stat.substringAfterLast(')').trim().split(' ')
            val state = fields.getOrNull(0) ?: return null
            if (state == "Z" || state == "X") return null
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: return null
            val startTime = fields.getOrNull(19)?.toLongOrNull() ?: 0L
            val uid = File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            }
            if (uid != myUid) return null
            val argv0 = File(dir, "cmdline").readBytes().let { bytes ->
                val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
                String(bytes, 0, end)
            }
            ProcInfo(pid, ppid, startTime, argv0)
        } catch (e: Exception) {
            null
        }
    }
}

/** Device facts the runtime needs, behind one seam. */
class AndroidRuntimeSystem(private val context: Context) {
    val nativeLibraryDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** Device ABIs, most preferred first, 64-bit only: the runtime has no 32-bit build. */
    val deviceAbis: List<String> get() = Build.SUPPORTED_64_BIT_ABIS.toList()

    val allAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()

    fun unsupportedReason(): RuntimeUnsupported? {
        if (ManifestPolicy.deviceAbi(deviceAbis) == null) return RuntimeUnsupported.NoSupportedAbi(allAbis)
        val proot = File(nativeLibraryDir, ProotCommand.PROOT)
        val loader = File(nativeLibraryDir, ProotCommand.LOADER)
        if (!proot.isFile || !loader.isFile) return RuntimeUnsupported.BinariesMissing
        return null
    }

    val space = SpaceProbe { dir ->
        var probe: File? = dir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        StatFs((probe ?: context.filesDir).path).availableBytes
    }

    /** The active network's DNS servers, as text; empty when there is no network. */
    fun dnsServers(): List<String> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        cm?.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
    } catch (e: SecurityException) {
        emptyList()
    }

    val cpuCount: Int get() = Runtime.getRuntime().availableProcessors()
}
