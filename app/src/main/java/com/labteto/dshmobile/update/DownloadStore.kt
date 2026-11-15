package com.labteto.dshmobile.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the updater remembers about one release's download, next to the file itself.
 *
 * Enough to put the update dialog back after the process died (the release, its notes and its
 * files) and to resume (the `If-Range` validator and the total size). [verifiedSha256] is set once
 * the file matched the release's checksum; [unverifiedConsent] once the person chose to install a
 * release that had none. [awaitingPermission] marks a hand-off to "allow installs from this app":
 * on Android 11 granting it kills the app, and the next launch carries on from here.
 */
@Serializable
data class DownloadRecord(
    val version: String,
    val releaseUrl: String,
    val notes: String? = null,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long = -1,
    val checksumsUrl: String? = null,
    val validator: String? = null,
    val total: Long = -1,
    val complete: Boolean = false,
    val verifiedSha256: String? = null,
    val unverifiedConsent: Boolean = false,
    val awaitingPermission: Boolean = false,
) {
    fun toUpdate(): AvailableUpdate = AvailableUpdate(
        version = version,
        url = releaseUrl,
        apk = ReleaseAsset(apkName, apkUrl, apkSize),
        checksums = checksumsUrl?.let { ReleaseAsset(CHECKSUMS_NAME, it) },
        notes = notes,
    )

    companion object {
        const val CHECKSUMS_NAME = "SHA256SUMS.txt"

        fun of(update: AvailableUpdate, apk: ReleaseAsset): DownloadRecord = DownloadRecord(
            version = update.version,
            releaseUrl = update.url,
            notes = update.notes,
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            checksumsUrl = update.checksums?.url,
        )
    }
}

private const val PREFIX = "dsh-"
private val SAFE_VERSION = Regex("[^0-9A-Za-z._-]")

/** The file-name stem for [version]: only characters that are safe in a file name survive. */
internal fun downloadStem(version: String): String = PREFIX + version.replace(SAFE_VERSION, "_").take(64)

/**
 * Which files in the download directory to delete.
 *
 * Kept: the files of [keepVersion] (the one release the updater is working on), and only while
 * that release is newer than [currentVersion]. Everything else goes: earlier releases, the release
 * that was just installed, files with a name this updater never writes, and leftovers of a crash.
 */
internal fun staleDownloadFiles(names: List<String>, currentVersion: String, keepVersion: String?): List<String> {
    val keepStem = keepVersion?.takeIf { isNewerVersion(it, currentVersion) }?.let(::downloadStem)
    return names.filter { name ->
        val stem = name.substringBeforeLast('.')
        keepStem == null || stem != keepStem
    }
}

/**
 * The updater's directory in the app's private cache: one `dsh-<version>.part` while downloading,
 * renamed to `dsh-<version>.apk` once complete, and `dsh-<version>.json` with its [DownloadRecord].
 * Cache rather than files: the system may reclaim it under pressure, which costs a re-download and
 * nothing else.
 */
class DownloadStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun partFile(version: String) = File(dir, downloadStem(version) + ".part")

    fun apkFile(version: String) = File(dir, downloadStem(version) + ".apk")

    private fun recordFile(version: String) = File(dir, downloadStem(version) + ".json")

    fun save(record: DownloadRecord) {
        dir.mkdirs()
        val target = recordFile(record.version)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(json.encodeToString(DownloadRecord.serializer(), record))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun load(version: String): DownloadRecord? = readRecord(recordFile(version))

    /** The newest release a record exists for. */
    fun newest(): DownloadRecord? = dir.listFiles { file -> file.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull(::readRecord)
        .reduceOrNull { best, next -> if (isNewerVersion(next.version, best.version)) next else best }

    /** Bytes already on disk for [version], whether partial or complete. */
    fun bytesOnDisk(version: String): Long = apkFile(version).takeIf { it.isFile }?.length()
        ?: partFile(version).takeIf { it.isFile }?.length()
        ?: 0L

    /** Move the finished partial file into place. */
    fun promote(version: String): File {
        val part = partFile(version)
        val apk = apkFile(version)
        apk.delete()
        if (!part.renameTo(apk)) {
            part.copyTo(apk, overwrite = true)
            part.delete()
        }
        return apk
    }

    /** Forget [version] entirely: partial file, finished file and record. */
    fun clear(version: String) {
        partFile(version).delete()
        apkFile(version).delete()
        recordFile(version).delete()
    }

    /** Delete everything [staleDownloadFiles] says is stale. */
    fun cleanup(currentVersion: String, keepVersion: String?) {
        val names = dir.list()?.toList().orEmpty()
        staleDownloadFiles(names, currentVersion, keepVersion).forEach { File(dir, it).delete() }
    }

    private fun readRecord(file: File): DownloadRecord? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString(DownloadRecord.serializer(), file.readText())
    }.getOrNull()
}
