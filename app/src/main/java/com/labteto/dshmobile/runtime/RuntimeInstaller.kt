package com.labteto.dshmobile.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Free bytes on the filesystem holding a directory. */
fun interface SpaceProbe {
    fun availableBytes(dir: File): Long
}

/** What the installer is doing, for progress. */
sealed interface InstallEvent {
    data object Checking : InstallEvent
    data class Downloading(val progress: DownloadProgress) : InstallEvent
    data class Verifying(val layer: String) : InstallEvent
    data class Unpacking(val progress: UnpackProgress) : InstallEvent
}

/** An install step failed in a way the state can name. */
class InstallException(val failure: RuntimeFailure, cause: Throwable? = null) : IOException(failure.toString(), cause)

/**
 * Downloads, verifies and unpacks runtime releases, and commits them with one atomic write.
 *
 * Plain Kotlin over [FsOps], [SpaceProbe] and an OkHttp client, so the whole flow runs in JVM tests
 * against a local HTTP server. [decoder] turns a downloaded layer into its tar stream (zstd on the
 * device).
 */
class RuntimeInstaller(
    private val layout: RuntimeLayout,
    private val store: InstalledStore,
    private val fs: FsOps,
    private val client: OkHttpClient,
    private val downloader: LayerDownloader,
    private val space: SpaceProbe,
    private val manifestUrl: () -> String,
    private val appVersion: () -> String,
    private val deviceAbis: List<String>,
    private val verifier: ManifestVerifier = ManifestVerifier(),
    private val decoder: (InputStream) -> InputStream,
) {
    sealed interface CheckResult {
        data class Refused(val failure: RuntimeFailure) : CheckResult
        data class UpToDate(val manifest: RuntimeManifest) : CheckResult
        /**
         * [downloadBytes] is what an install of [decision] fetches: the changed layers, less any
         * that are still on the device from before (the release kept for a rollback).
         */
        data class Available(
            val manifest: RuntimeManifest,
            val decision: ManifestPolicy.Decision.Install,
            val downloadBytes: Long = decision.downloadBytes,
            /** The sizes of the layers that are fetched, in order (those not already on the device). */
            val fetchedLayerBytes: List<Long> = decision.layers.map { it.size },
        ) : CheckResult {
            fun toUpdate() = AvailableRuntimeUpdate(
                releaseId = manifest.releaseId,
                harnessVersion = manifest.harness.version,
                downloadBytes = downloadBytes,
                baseChanges = decision.baseChanges,
                harnessChanges = decision.harnessChanges,
                sequence = manifest.sequence,
                layerDownloadBytes = fetchedLayerBytes,
            )
        }
    }

    fun installed(): InstalledRecord? = store.read()

    /**
     * Fetch the latest manifest (or the one at [url]), check its signature before reading a byte of
     * it, and decide. [allowOlder] is for the person's own "go back" only: see [ManifestPolicy.decide].
     */
    suspend fun check(url: String? = null, allowOlder: Boolean = false): CheckResult = withContext(Dispatchers.IO) {
        val manifestBytes: ByteArray
        val signature: ByteArray
        try {
            val url = url ?: manifestUrl()
            manifestBytes = fetchSmall(url)
            signature = fetchSmall(url.substringBeforeLast('/') + "/" + SIGNATURE_FILE)
        } catch (e: InstallException) {
            return@withContext CheckResult.Refused(e.failure)
        }
        if (!verifier.verify(manifestBytes, signature)) return@withContext CheckResult.Refused(RuntimeFailure.BadSignature)
        val manifest = try {
            RuntimeManifest.parse(manifestBytes)
        } catch (e: ManifestException) {
            return@withContext CheckResult.Refused(RuntimeFailure.BadManifest(e.message ?: "unreadable"))
        }
        val installed = store.read()
        when (val decision = ManifestPolicy.decide(manifest, appVersion(), deviceAbis, installed, allowOlder)) {
            is ManifestPolicy.Decision.Refused -> CheckResult.Refused(decision.failure)
            is ManifestPolicy.Decision.UpToDate -> CheckResult.UpToDate(manifest)
            is ManifestPolicy.Decision.Install -> {
                val sameAbi = installed?.takeIf { it.abi == decision.abi }
                val fetched = decision.layers.filter { reusable(it, sameAbi) == null }
                CheckResult.Available(
                    manifest,
                    decision,
                    downloadBytes = changedDownloadBytes(decision.layers) { reusable(it, sameAbi) != null },
                    fetchedLayerBytes = fetched.map { it.size },
                )
            }
        }
    }

    /**
     * The installed release's `previous`, fetched and verified with the same pinned key: what
     * "go back to the previous version" would install. A lower sequence is accepted here and only
     * here, and the manifest must be the release the pointer names.
     */
    suspend fun checkPrevious(): CheckResult {
        val pointer = store.read()?.previousRelease
            ?: return CheckResult.Refused(RuntimeFailure.BadManifest("no previous release"))
        val result = check(pointer.manifestUrl, allowOlder = true)
        val manifest = when (result) {
            is CheckResult.Available -> result.manifest
            is CheckResult.UpToDate -> result.manifest
            is CheckResult.Refused -> return result
        }
        if (manifest.releaseId != pointer.releaseId || manifest.sequence != pointer.sequence) {
            return CheckResult.Refused(RuntimeFailure.BadManifest("previous is ${manifest.releaseId}, expected ${pointer.releaseId}"))
        }
        return result
    }

    /** A layer of the release kept for a rollback that [layer] names, still unpacked on disk. */
    private fun reusable(layer: RuntimeManifest.Layer, installed: InstalledRecord?): LayerRecord? {
        val prior = installed?.trial ?: return null
        val candidate = if (layer.name == LAYER_BASE) prior.base else prior.harness
        return candidate.takeIf { it.isLayer(layer) && File(layout.root, it.dir).isDirectory }
    }

    /**
     * Download, verify and unpack [decision]'s layers, then commit them.
     *
     * [beforeCommit] runs after everything is unpacked and before `installed.json` changes: the
     * controller stops a running harness there, so no directory is deleted under a live process.
     * A harness-only update over an installed runtime commits the new harness *on trial*: the
     * previous harness stays on disk until [commitTrial] or [rollbackTrial].
     */
    suspend fun apply(
        manifest: RuntimeManifest,
        decision: ManifestPolicy.Decision.Install,
        onEvent: (InstallEvent) -> Unit,
        beforeCommit: suspend () -> Unit = {},
    ): InstalledRecord = withContext(Dispatchers.IO) {
        layout.root.mkdirs()
        layout.downloads.mkdirs()
        val installed = store.read()?.takeIf { it.abi == decision.abi }
        val layers = decision.layers
        val reuse = layers.mapNotNull { layer -> reusable(layer, installed)?.let { layer to it } }
        val toUnpack = layers - reuse.map { it.first }.toSet()
        val assets = toUnpack.map { asset(manifest, it) }

        // Before any byte is fetched: is there room for the download and for what it unpacks to?
        requireSpace(assets.sumOf { downloader.remainingBytes(it) } + unpackNeed(toUnpack))

        val files = assets.mapIndexed { index, asset ->
            val layer = toUnpack[index]
            downloader.download(
                asset,
                onBytes = { done, total ->
                    onEvent(InstallEvent.Downloading(DownloadProgress(layer.name, index, assets.size, done, total)))
                },
                onVerifying = { onEvent(InstallEvent.Verifying(layer.name)) },
            ).let { layer to it }
        }

        // Again before unpacking: the download itself used some of it, and other apps use the rest.
        requireSpace(unpackNeed(toUnpack))

        val unpacked = mutableMapOf<String, LayerRecord>()
        try {
            files.forEachIndexed { index, (layer, file) ->
                unpacked[layer.name] = unpack(layer, file, index, files.size, onEvent)
            }
        } catch (e: Throwable) {
            // Nothing below is referenced by installed.json yet; take it all away again.
            unpacked.values.forEach { FsTree.delete(fs, File(layout.root, it.dir)) }
            throw e
        }
        reuse.forEach { (layer, record) -> unpacked[layer.name] = record }

        val base = unpacked[LAYER_BASE] ?: installed?.base ?: throw InstallException(RuntimeFailure.BadManifest("no base layer to install"))
        val harness = unpacked[LAYER_HARNESS] ?: installed?.harness ?: throw InstallException(RuntimeFailure.BadManifest("no harness layer to install"))
        // Every update over an installed runtime goes on trial, a base change as much as a harness
        // one: the layers it replaces stay on disk until the new release has been ready once.
        val kept = installed?.trial
        val trial = when {
            installed == null -> null
            // The update is exactly the release kept for a rollback: there is nothing left to try.
            kept != null && kept.base == base && kept.harness == harness -> null
            // Keep the last release known to work, not the one still on trial.
            kept != null -> kept
            else -> installed.asPrior()
        }
        val record = InstalledRecord(
            releaseId = manifest.releaseId,
            sequence = manifest.sequence,
            abi = decision.abi,
            harnessVersion = manifest.harness.version,
            nodeVersion = manifest.node.version,
            base = base,
            harness = harness,
            entrypoint = manifest.runtime.entrypoint,
            readinessPrefix = manifest.runtime.readinessPrefix,
            fakeProc = manifest.runtime.fakeProc,
            resolvConf = manifest.runtime.resolvConf,
            previousRelease = manifest.previous,
            permissions = manifest.runtime.permissions,
            trial = trial,
        )
        beforeCommit()
        store.write(record)
        cleanOrphans()
        downloader.prune()
        record
    }

    /** The release on trial reached readiness: forget the one before it and free its layers. */
    fun commitTrial(): InstalledRecord? {
        val record = store.read() ?: return null
        if (record.trial == null) return record
        val next = record.copy(trial = null)
        store.write(next)
        cleanOrphans()
        return next
    }

    /**
     * The release on trial never became ready: put the one before it back, whole (release id,
     * sequence, both layers, its command line), and delete what only the new one used.
     */
    fun rollbackTrial(): InstalledRecord? {
        val record = store.read() ?: return null
        val prior = record.trial ?: return record
        val next = prior.restore(record.abi)
        store.write(next)
        cleanOrphans()
        return next
    }

    /**
     * Remove the runtime. `installed.json` goes first, so a crash halfway leaves garbage that the
     * next start deletes, never a runtime that looks installed.
     */
    fun uninstall(keepUserData: Boolean) {
        store.clear()
        layout.root.listFiles().orEmpty().forEach { child ->
            val userData = child.name == layout.home.name || child.name == layout.workspaces.name
            if (!userData || !keepUserData) FsTree.delete(fs, child)
        }
        FsTree.delete(fs, layout.downloads)
        FsTree.delete(fs, layout.prootTmp)
    }

    /** Delete unpacks that were abandoned and layers no record names. */
    fun cleanOrphans() {
        layout.orphans(store.read()).forEach { runCatching { FsTree.delete(fs, it) } }
    }

    private suspend fun unpack(
        layer: RuntimeManifest.Layer,
        file: File,
        index: Int,
        count: Int,
        onEvent: (InstallEvent) -> Unit,
    ): LayerRecord {
        val finalDir = if (layer.name == LAYER_BASE) layout.baseDir(layer.id, layer.sha256) else layout.harnessDir(layer.id, layer.sha256)
        val staging = layout.staging(layer.name + "-" + layer.id)
        FsTree.delete(fs, staging)
        if (!staging.mkdirs()) throw InstallException(RuntimeFailure.Storage("mkdir ${staging.name}"))
        val job = currentCoroutineContext()[Job]
        try {
            onEvent(InstallEvent.Unpacking(UnpackProgress(layer.name, index, count, 0, layer.entries, 0)))
            BufferedInputStream(decoder(BufferedInputStream(FileInputStream(file), READ_BUFFER)), READ_BUFFER).use { tar ->
                TarExtractor(fs).extract(
                    tar,
                    staging,
                    onProgress = { entries, bytes ->
                        onEvent(InstallEvent.Unpacking(UnpackProgress(layer.name, index, count, entries, layer.entries, bytes)))
                    },
                    checkCancelled = { if (job?.isActive == false) throw CancellationException("unpack cancelled") },
                )
            }
            if (layer.name == LAYER_BASE) prepareBase(staging) else checkHarness(staging)
            FsTree.delete(fs, finalDir)
            if (!staging.renameTo(finalDir)) throw InstallException(RuntimeFailure.Storage("rename ${staging.name}"))
            return LayerRecord(layer.id, finalDir.name, layer.sha256)
        } catch (e: ArchiveException) {
            throw InstallException(RuntimeFailure.BadArchive("${layer.file}: ${e.message}"), e)
        } catch (e: InstallException) {
            throw e
        } catch (e: IOException) {
            throw InstallException(storageFailure(e, unpackNeed(listOf(layer))), e)
        } finally {
            FsTree.delete(fs, staging)
        }
    }

    /**
     * Mount points for what start binds into the base: the harness paths the base excludes, the
     * workspaces directory. Bind targets that exist keep proot from inventing glue directories.
     */
    private fun prepareBase(rootfs: File) {
        for (dir in listOf("opt/dsh", "usr/local/bin", "workspaces", "root", "tmp")) {
            val target = File(rootfs, dir)
            if (fs.lstat(target) == FileKind.NONE && !target.mkdirs()) throw IOException("mkdir $dir")
        }
        val dshMount = File(rootfs, "usr/local/bin/dsh")
        if (fs.lstat(dshMount) == FileKind.NONE) dshMount.createNewFile()
    }

    private fun checkHarness(dir: File) {
        if (fs.lstat(File(dir, "opt/dsh")) != FileKind.DIRECTORY) throw ArchiveException("harness layer has no opt/dsh")
        if (fs.lstat(File(dir, "usr/local/bin/dsh")) == FileKind.NONE) throw ArchiveException("harness layer has no usr/local/bin/dsh")
    }

    private fun requireSpace(needed: Long) {
        layout.root.mkdirs()
        val available = space.availableBytes(layout.root)
        if (available < needed) throw InstallException(RuntimeFailure.NoSpace(needed, available))
    }

    /** ENOSPC surfaces as all sorts of errors mid-unpack (even ENOENT on chmod); free space decides. */
    private fun storageFailure(e: IOException, needed: Long): RuntimeFailure {
        val available = runCatching { space.availableBytes(layout.root) }.getOrDefault(Long.MAX_VALUE)
        val message = e.message.orEmpty()
        return if (available < LOW_SPACE || "ENOSPC" in message || "No space" in message) {
            RuntimeFailure.NoSpace(needed, available)
        } else {
            RuntimeFailure.Storage(message.ifEmpty { e.javaClass.simpleName })
        }
    }

    private fun asset(manifest: RuntimeManifest, layer: RuntimeManifest.Layer) =
        LayerDownloader.Asset(manifest.assetBaseUrl + layer.file, layer.file, layer.size, layer.sha256)

    private fun fetchSmall(url: String): ByteArray {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code != 200) throw InstallException(RuntimeFailure.Network("HTTP ${response.code} for ${url.substringAfterLast('/')}"))
                val body = response.body ?: throw InstallException(RuntimeFailure.Network("empty body"))
                val length = body.contentLength()
                if (length > MAX_MANIFEST) throw InstallException(RuntimeFailure.BadManifest("$length bytes"))
                val bytes = body.byteStream().use { it.readNBytesCompat(MAX_MANIFEST + 1) }
                if (bytes.size > MAX_MANIFEST) throw InstallException(RuntimeFailure.BadManifest("too large"))
                return bytes
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: IOException) {
            throw InstallException(RuntimeFailure.Network(e.message ?: e.javaClass.simpleName), e)
        } catch (e: IllegalArgumentException) {
            // OkHttp refuses a URL it cannot parse with an unchecked exception; that is a bad
            // address to read a manifest from, never a reason to take the app down.
            throw InstallException(RuntimeFailure.BadManifest("not a manifest URL"), e)
        }
    }

    companion object {
        const val SIGNATURE_FILE = "manifest.json.sig"
        const val DEFAULT_MANIFEST_URL = "https://github.com/sorsama/dsh-mobile-runtime/releases/latest/download/manifest.json"
        private const val MAX_MANIFEST = 1 shl 20
        private const val READ_BUFFER = 1 shl 20
        private const val HEADROOM = 128L shl 20
        private const val LOW_SPACE = 16L shl 20

        /** Bytes an install of [layers] downloads: every layer but those [onDevice] says are already there. */
        fun changedDownloadBytes(layers: List<RuntimeManifest.Layer>, onDevice: (RuntimeManifest.Layer) -> Boolean): Long =
            layers.filterNot(onDevice).sumOf { it.size }

        /** Disk an unpack needs: the README's 1.3 times the file bytes, plus headroom. */
        fun unpackNeed(layers: List<RuntimeManifest.Layer>): Long =
            if (layers.isEmpty()) 0 else layers.sumOf { it.unpackedSize + it.unpackedSize * 3 / 10 } + HEADROOM

        private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
