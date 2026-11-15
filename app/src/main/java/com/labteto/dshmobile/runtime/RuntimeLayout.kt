package com.labteto.dshmobile.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where the runtime lives on disk.
 *
 * Everything persistent is under [root] (the app's `noBackupFilesDir/runtime`), so none of it is
 * ever part of a backup: 1 GB of Debian has no business in a cloud backup, and the harness's
 * state may hold API keys. Downloads and proot's scratch space are in the cache directory.
 *
 * ```
 * runtime/
 *   installed.json          the commit record; a layer directory it does not name is garbage
 *   base-<id>/              the base layer, unpacked (the guest's /)
 *   harness-<id>/           the harness layer, unpacked (opt/dsh, usr/local/bin/dsh)
 *   home/                   bound over the guest's /root: the harness's own state
 *   workspaces/             bound at /workspaces
 *   run/                    files written before each start (resolv.conf, proc-stat), pid record
 *   logs/                   harness output, size-capped, token redacted
 *   .staging-*              an unpack in progress; deleted on sight
 * ```
 *
 * The layers are kept in separate directories and the harness is bound into the base at start,
 * rather than unpacked over it, so that an update (harness, base or both) can sit next to the
 * layers it replaces until it has proved it starts, and user data never sits inside a directory an
 * update replaces.
 */
class RuntimeLayout(val root: File, val cache: File) {
    val installedFile = File(root, "installed.json")
    val home = File(root, "home")
    val workspaces = File(root, "workspaces")
    val run = File(root, "run")
    val logs = File(root, "logs")
    val downloads = File(cache, "downloads")
    val prootTmp = File(cache, "proot-tmp")

    /**
     * A layer's directory: its id and the start of its archive hash, so two archives released
     * under one id (it has happened) never share a directory, and an update can sit next to
     * the layer it replaces.
     */
    fun baseDir(id: String, sha256: String) = File(root, "base-" + safeName(id) + "-" + sha256.take(12))
    fun harnessDir(id: String, sha256: String) = File(root, "harness-" + safeName(id) + "-" + sha256.take(12))
    fun staging(name: String) = File(root, STAGING_PREFIX + safeName(name))

    /** Directories under [root] that hold no user data and that [record] does not name. */
    fun orphans(record: InstalledRecord?): List<File> {
        val keep = buildSet {
            add(installedFile.name)
            add(home.name)
            add(workspaces.name)
            add(run.name)
            add(logs.name)
            if (record != null) record.layerDirs().forEach { add(File(it).name) }
        }
        return root.listFiles().orEmpty().filter { it.name !in keep && (it.name.startsWith("base-") || it.name.startsWith("harness-") || it.name.startsWith(STAGING_PREFIX) || it.name.startsWith("installed.json.")) }
    }

    companion object {
        const val STAGING_PREFIX = ".staging-"

        fun safeName(id: String): String = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("").take(120)
    }
}

/** One unpacked layer: its manifest id and the directory it lives in (a name under the layout root). */
@Serializable
data class LayerRecord(val id: String, val dir: String, val sha256: String)

/**
 * `installed.json`: what is installed, written last and atomically.
 *
 * A runtime is installed exactly when this file exists and parses. It is written only after every
 * layer it names is complete and in its final directory, so no crash leaves a half-unpacked tree
 * that looks installed. The guest command line comes from the manifest that was installed, so a
 * start never needs the network.
 */
@Serializable
data class InstalledRecord(
    val releaseId: String,
    val sequence: Long,
    val abi: String,
    val harnessVersion: String,
    val nodeVersion: String,
    val base: LayerRecord,
    val harness: LayerRecord,
    val entrypoint: String,
    val readinessPrefix: String,
    val fakeProc: List<RuntimeManifest.FakeProc>,
    val resolvConf: String,
    /** The installed manifest's `previous`: the release "Go back" installs. */
    val previousRelease: RuntimeManifest.Previous? = null,
    /** The installed manifest's `runtime.permissions`; null for a release without the presets. */
    val permissions: RuntimeManifest.Permissions? = null,
    /**
     * What was installed before this update, kept (its layer directories included) until this
     * release has been ready once. A start that never gets there puts it back. Null when nothing
     * is on trial.
     */
    val trial: PriorInstall? = null,
) {
    val onTrial: Boolean get() = trial != null

    /** Every layer directory this record needs on disk, the one on trial's predecessor included. */
    fun layerDirs(): Set<String> = buildSet {
        add(base.dir)
        add(harness.dir)
        trial?.let {
            add(it.base.dir)
            add(it.harness.dir)
        }
    }

    fun ref() = ReleaseRef(releaseId, harnessVersion, sequence)

    /** This record as the state to come back to if the next one does not start. */
    fun asPrior() = PriorInstall(
        releaseId, sequence, harnessVersion, nodeVersion, base, harness, entrypoint, readinessPrefix, fakeProc, resolvConf, previousRelease, permissions,
    )

    fun toInstalledRuntime() = InstalledRuntime(
        releaseId = releaseId,
        sequence = sequence,
        harnessVersion = harnessVersion,
        nodeVersion = nodeVersion,
        baseId = base.id,
        harnessId = harness.id,
        harnessOnTrial = onTrial,
        previousRelease = previousRelease?.let { ReleaseRef(it.releaseId, it.harnessVersion, it.sequence) },
        commandPermissions = permissions != null,
        commandPermissionsVersion = permissions?.version ?: 0,
    )
}

/** An installed release as [InstalledRecord] keeps it while its successor is on trial. */
@Serializable
data class PriorInstall(
    val releaseId: String,
    val sequence: Long,
    val harnessVersion: String,
    val nodeVersion: String,
    val base: LayerRecord,
    val harness: LayerRecord,
    val entrypoint: String,
    val readinessPrefix: String,
    val fakeProc: List<RuntimeManifest.FakeProc>,
    val resolvConf: String,
    val previousRelease: RuntimeManifest.Previous? = null,
    val permissions: RuntimeManifest.Permissions? = null,
) {
    fun ref() = ReleaseRef(releaseId, harnessVersion, sequence)

    fun restore(abi: String) = InstalledRecord(
        releaseId = releaseId,
        sequence = sequence,
        abi = abi,
        harnessVersion = harnessVersion,
        nodeVersion = nodeVersion,
        base = base,
        harness = harness,
        entrypoint = entrypoint,
        readinessPrefix = readinessPrefix,
        fakeProc = fakeProc,
        resolvConf = resolvConf,
        previousRelease = previousRelease,
        permissions = permissions,
    )
}

/** Reads and atomically replaces `installed.json`. */
class InstalledStore(private val layout: RuntimeLayout) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun read(): InstalledRecord? {
        val file = layout.installedFile
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(InstalledRecord.serializer(), file.readText()) }.getOrNull()
    }

    /** Write via a temporary file, flushed to disk, then `rename(2)`: readers see old or new, never half. */
    fun write(record: InstalledRecord) {
        layout.root.mkdirs()
        val tmp = File(layout.root, layout.installedFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(InstalledRecord.serializer(), record).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(layout.installedFile)) {
            // On a JVM test host (Windows) rename does not replace; Android's always does.
            layout.installedFile.delete()
            if (!tmp.renameTo(layout.installedFile)) throw IOException("could not commit ${layout.installedFile}")
        }
    }

    fun clear() {
        layout.installedFile.delete()
    }
}
