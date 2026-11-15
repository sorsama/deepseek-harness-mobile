package com.labteto.dshmobile.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download is not running, with what is already on disk kept for a resume. */
sealed interface PauseReason {
    /** The connection dropped and the automatic retries ran out. */
    data object Network : PauseReason

    /** The app was closed or restarted part-way. */
    data object Interrupted : PauseReason

    /** Not enough free space: [shortBy] more bytes are needed. */
    data class NoSpace(val shortBy: Long) : PauseReason

    /** The server answered with an error status. */
    data class Server(val code: Int) : PauseReason

    /** The APK is complete, but the checksum file could not be fetched to verify it. */
    data object ChecksumUnreachable : PauseReason
}

/** Why a downloaded update was refused. The file is deleted in every case. */
sealed interface RejectReason {
    /** The APK does not hash to what the release's `SHA256SUMS.txt` says. */
    data object ChecksumMismatch : RejectReason

    /** The server sent a different number of bytes than the release lists. */
    data object SizeMismatch : RejectReason

    /** A request or redirect led to a host the updater does not download from. */
    data class Host(val host: String) : RejectReason

    /** The pre-install check refused the APK itself. */
    data class Apk(val rejection: ApkRejection) : RejectReason
}

/** Where the in-app update is. Every state but [Idle] is about one [update]. */
sealed interface UpdaterState {
    val update: AvailableUpdate?

    data object Idle : UpdaterState {
        override val update: AvailableUpdate? = null
    }

    /** On a metered network: the person is asked before [size] bytes are fetched. */
    data class ConfirmMetered(override val update: AvailableUpdate, val size: Long) : UpdaterState

    data class Downloading(
        override val update: AvailableUpdate,
        val done: Long,
        val total: Long,
        val bytesPerSecond: Long?,
        val secondsLeft: Long?,
        /** The connection dropped and the updater is trying again by itself. */
        val reconnecting: Boolean = false,
    ) : UpdaterState

    data class Paused(override val update: AvailableUpdate, val done: Long, val total: Long, val reason: PauseReason) : UpdaterState

    data class Verifying(override val update: AvailableUpdate) : UpdaterState

    /** The release has no checksum for the APK; installing needs an explicit "install anyway". */
    data class ConfirmUnverified(override val update: AvailableUpdate, val entryMissing: Boolean) : UpdaterState

    /** Downloaded and checked, waiting for a tap (after a restart, say). */
    data class ReadyToInstall(override val update: AvailableUpdate) : UpdaterState

    /** "Install unknown apps" is off for this app; [askedBefore] once the settings page was shown. */
    data class NeedsPermission(override val update: AvailableUpdate, val askedBefore: Boolean) : UpdaterState

    /**
     * Handed to the system. [confirm] is the system prompt to show when the system asks; [stalled]
     * when the person came back from that prompt and no answer followed.
     */
    data class Installing(
        override val update: AvailableUpdate,
        val confirm: Intent? = null,
        val confirmShown: Boolean = false,
        val stalled: Boolean = false,
    ) : UpdaterState

    data class InstallFailed(override val update: AvailableUpdate, val failure: InstallFailure, val detail: String?) : UpdaterState

    data class Rejected(override val update: AvailableUpdate, val reason: RejectReason) : UpdaterState

    /** The system reported success. Usually never seen: replacing the app ends this process. */
    data class Installed(override val update: AvailableUpdate) : UpdaterState
}

/** Whether the updater is in the middle of something a second tap must not restart. */
val UpdaterState.busy: Boolean
    get() = this is UpdaterState.Downloading || this is UpdaterState.Verifying ||
        (this is UpdaterState.Installing && !stalled)

/** The device facts the updater asks before starting. A seam for tests and for the evidence run. */
interface DeviceConditions {
    fun isMetered(): Boolean
    fun freeBytes(): Long
}

@Singleton
class AndroidDeviceConditions @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceConditions {
    override fun isMetered(): Boolean = runCatching {
        ContextCompat.getSystemService(context, ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)

    override fun freeBytes(): Long = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
}

/**
 * Downloads, verifies and installs an update without leaving the app.
 *
 * App-scoped with its own supervisor, like [com.labteto.dshmobile.termux.TermuxHarnessController]:
 * a download takes a while, and the person who started it may close the dialog, open Settings or
 * leave the app. ViewModels observe [state]; none of them owns the work.
 *
 * The order is fixed: fetch the APK into the app's private cache (resumable, see
 * [ResumableDownloader]), check it against the release's `SHA256SUMS.txt`, read it with the package
 * manager and refuse anything that is not a newer build of this app from the same signer
 * ([judgeCandidate]), and only then hand it to [PackageInstallerGateway]. Android checks the
 * signature again before it replaces anything, and that check is the one that cannot be skipped.
 */
@Singleton
class InAppUpdater @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient,
    private val feeds: UpdateFeedSource,
    private val inspector: ApkInspector,
    private val installer: PackageInstallerGateway,
    private val device: DeviceConditions,
    private val checker: UpdateChecker,
    justUpdated: AppJustUpdatedTracker,
    private val hooks: SelfUpdateHooks,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DownloadStore(File(context.cacheDir, "updates"))
    private val downloader = ResumableDownloader(client)
    private val tracker = justUpdated.tracker
    private val rate = TransferRate()

    private val _state = MutableStateFlow<UpdaterState>(UpdaterState.Idle)
    val state: StateFlow<UpdaterState> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var meteredApprovedFor: String? = null
    @Volatile private var lastPublishAt = 0L

    init {
        scope.launch { restore() }
    }

    /** Start (or resume) downloading [update]. On a metered network this asks first. */
    fun download(update: AvailableUpdate, allowMetered: Boolean = false) {
        if (_state.value.busy) return
        val apk = update.apk ?: return
        if (allowMetered) meteredApprovedFor = update.version
        val onDisk = store.bytesOnDisk(update.version)
        val left = if (apk.size > 0) apk.size - onDisk else Long.MAX_VALUE
        // Nothing left to fetch (a finished file waiting to be checked) costs no data: no question.
        if (left > 0 && meteredApprovedFor != update.version && device.isMetered()) {
            _state.value = UpdaterState.ConfirmMetered(update, apk.size)
            return
        }
        val needed = bytesNeededFor(apk.size, onDisk)
        val free = device.freeBytes()
        if (free < needed) {
            _state.value = UpdaterState.Paused(update, onDisk, apk.size, PauseReason.NoSpace(needed - free))
            return
        }
        _state.value = UpdaterState.Downloading(update, onDisk, apk.size, null, null)
        job = scope.launch { runDownload(update, apk) }
    }

    /** Stop the download and delete what was fetched. */
    fun cancel() {
        val update = _state.value.update
        val running = job
        job = null
        scope.launch {
            running?.cancel()
            running?.join()
            update?.let { store.clear(it.version) }
            _state.value = UpdaterState.Idle
        }
    }

    /** The person chose to install a release that carries no checksum for its APK. */
    fun installAnyway() {
        val current = _state.value as? UpdaterState.ConfirmUnverified ?: return
        launchStep {
            val record = store.load(current.update.version)?.copy(unverifiedConsent = true) ?: return@launchStep
            store.save(record)
            inspectAndInstall(current.update)
        }
    }

    /** Install a file that is already downloaded and checked, or try a failed install again. */
    fun install() {
        val update = _state.value.update ?: return
        if (_state.value.busy) return
        if (!store.apkFile(update.version).isFile) {
            download(update, allowMetered = meteredApprovedFor == update.version)
            return
        }
        launchStep { inspectAndInstall(update) }
    }

    /** The system page for "install unknown apps", for this app. */
    fun permissionIntent(): Intent = installer.permissionIntent()

    /** The settings page for "install unknown apps" is about to open. */
    fun permissionRequested() {
        val current = _state.value as? UpdaterState.NeedsPermission ?: return
        scope.launch {
            store.load(current.update.version)?.let { store.save(it.copy(awaitingPermission = true)) }
        }
    }

    /** Back from that settings page: install if it was allowed, or say it is still off. */
    fun permissionReturned() {
        val current = _state.value as? UpdaterState.NeedsPermission ?: return
        if (installer.canInstall()) {
            launchStep { inspectAndInstall(current.update) }
        } else {
            _state.value = current.copy(askedBefore = true)
        }
    }

    /** The UI showed the system's confirmation prompt. */
    fun confirmShown() {
        val current = _state.value as? UpdaterState.Installing ?: return
        _state.value = current.copy(confirmShown = true)
    }

    /**
     * Back from the system prompt. The answer normally arrives as a broadcast within moments; if
     * none does (the prompt was dismissed on a version that does not report it), offer to retry.
     */
    fun confirmReturned() {
        scope.launch {
            delay(STALL_AFTER_MS)
            val current = _state.value as? UpdaterState.Installing ?: return@launch
            if (current.confirmShown) _state.value = current.copy(stalled = true)
        }
    }

    /** Clear a finished outcome the UI has shown. Files worth keeping stay on disk. */
    fun acknowledge() {
        val current = _state.value
        if (current is UpdaterState.Rejected || current is UpdaterState.InstallFailed || current is UpdaterState.ConfirmMetered) {
            _state.value = UpdaterState.Idle
        }
    }

    /** What [PackageInstaller][android.content.pm.PackageInstaller] reported; see [UpdateInstallReceiver]. */
    fun onInstallStatus(sessionId: Int, status: Int, message: String?, confirm: Intent?) {
        val update = _state.value.update ?: store.newest()?.toUpdate()
        when (val outcome = mapInstallStatus(status, message)) {
            InstallOutcome.NeedsUserAction -> {
                if (update != null && confirm != null) _state.value = UpdaterState.Installing(update, confirm)
            }
            InstallOutcome.Success -> {
                if (update != null) {
                    store.clear(update.version)
                    _state.value = UpdaterState.Installed(update)
                }
            }
            is InstallOutcome.Failed -> {
                tracker.clear()
                hooks.installFailed()
                if (update == null) return
                if (outcome.failure.discardFile) store.clear(update.version)
                _state.value = UpdaterState.InstallFailed(update, outcome.failure, outcome.detail)
            }
        }
    }

    // ---- The work -------------------------------------------------------------------------

    private fun launchStep(block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    private suspend fun runDownload(update: AvailableUpdate, apk: ReleaseAsset) {
        val version = update.version
        var record = store.load(version)?.takeIf { it.apkUrl == apk.url && it.apkName == apk.name }
            ?: DownloadRecord.of(update, apk).also { store.clear(version) }
        record = record.copy(notes = update.notes, releaseUrl = update.url, checksumsUrl = update.checksums?.url)
        store.save(record)
        if (record.complete && store.apkFile(version).isFile) {
            verify(update)
            return
        }
        val policy = feeds.current().policy
        val expected = apk.size.takeIf { it > 0 }
        var failures = 0
        var lastDone = store.bytesOnDisk(version)
        rate.reset()
        while (true) {
            try {
                downloader.download(
                    url = apk.url,
                    part = store.partFile(version),
                    policy = policy,
                    validator = record.validator,
                    expectedTotal = expected,
                    onStart = { validator, total ->
                        record = record.copy(validator = validator, total = total)
                        store.save(record)
                    },
                    onProgress = { done, total -> publishProgress(update, done, total, reconnecting = false) },
                )
                store.promote(version)
                record = record.copy(complete = true)
                store.save(record)
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DownloadException.HostRefused) {
                store.clear(version)
                _state.value = UpdaterState.Rejected(update, RejectReason.Host(refused.host))
                return
            } catch (_: DownloadException.NoSpace) {
                val onDisk = store.bytesOnDisk(version)
                val shortBy = (bytesNeededFor(apk.size, onDisk) - device.freeBytes()).coerceAtLeast(SPACE_MARGIN)
                _state.value = UpdaterState.Paused(update, onDisk, apk.size, PauseReason.NoSpace(shortBy))
                return
            } catch (_: DownloadException.SizeMismatch) {
                store.clear(version)
                _state.value = UpdaterState.Rejected(update, RejectReason.SizeMismatch)
                return
            } catch (error: IOException) {
                val status = (error as? DownloadException.HttpStatus)?.code
                if (status != null && status !in RETRYABLE_STATUS) {
                    _state.value = UpdaterState.Paused(update, store.bytesOnDisk(version), apk.size, PauseReason.Server(status))
                    return
                }
                val onDisk = store.bytesOnDisk(version)
                if (onDisk > lastDone) failures = 0
                lastDone = onDisk
                failures++
                if (failures > RETRY_DELAYS_MS.size) {
                    _state.value = UpdaterState.Paused(update, onDisk, apk.size, PauseReason.Network)
                    return
                }
                _state.value = UpdaterState.Downloading(update, onDisk, apk.size, null, null, reconnecting = true)
                delay(RETRY_DELAYS_MS[failures - 1])
            }
        }
        verify(update)
    }

    private fun publishProgress(update: AvailableUpdate, done: Long, total: Long, reconnecting: Boolean) {
        val now = SystemClock.elapsedRealtime()
        rate.sample(now, done)
        if (now - lastPublishAt < PUBLISH_EVERY_MS && done != total) return
        lastPublishAt = now
        val speed = rate.bytesPerSecond()
        _state.value = UpdaterState.Downloading(update, done, total, speed, secondsLeft(done, total, speed), reconnecting)
    }

    private suspend fun verify(update: AvailableUpdate) {
        val version = update.version
        val apk = update.apk ?: return
        val record = store.load(version) ?: return
        _state.value = UpdaterState.Verifying(update)
        val sumsUrl = update.checksums?.url
        if (sumsUrl == null) {
            if (record.unverifiedConsent) inspectAndInstall(update)
            else _state.value = UpdaterState.ConfirmUnverified(update, entryMissing = false)
            return
        }
        val text = try {
            downloader.fetchText(sumsUrl, feeds.current().policy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DownloadException.HostRefused) {
            store.clear(version)
            _state.value = UpdaterState.Rejected(update, RejectReason.Host(refused.host))
            return
        } catch (_: IOException) {
            val size = store.bytesOnDisk(version)
            _state.value = UpdaterState.Paused(update, size, size, PauseReason.ChecksumUnreachable)
            return
        }
        val expected = expectedSha256(parseSha256Sums(text), apk.name)
        if (expected == null) {
            if (record.unverifiedConsent) inspectAndInstall(update)
            else _state.value = UpdaterState.ConfirmUnverified(update, entryMissing = true)
            return
        }
        val actual = withContext(Dispatchers.IO) { sha256Of(store.apkFile(version)) }
        if (!actual.equals(expected, ignoreCase = true)) {
            store.clear(version)
            _state.value = UpdaterState.Rejected(update, RejectReason.ChecksumMismatch)
            return
        }
        store.save(record.copy(verifiedSha256 = actual))
        inspectAndInstall(update)
    }

    private suspend fun inspectAndInstall(update: AvailableUpdate) {
        val version = update.version
        val file = store.apkFile(version)
        _state.value = UpdaterState.Verifying(update)
        val candidate = withContext(Dispatchers.IO) { inspector.inspectArchive(file) }
        val installed = inspector.installed()
        judgeCandidate(candidate, installed, BuildConfig.DEBUG)?.let { rejection ->
            store.clear(version)
            _state.value = UpdaterState.Rejected(update, RejectReason.Apk(rejection))
            return
        }
        if (!installer.canInstall()) {
            _state.value = UpdaterState.NeedsPermission(update, askedBefore = false)
            return
        }
        store.load(version)?.let { store.save(it.copy(awaitingPermission = false)) }
        _state.value = UpdaterState.Installing(update)
        // Written before the commit: replacing the app ends this process, and the next launch reads
        // this to say "Updated to …".
        tracker.markPending(UpdateMarker(version, candidate?.versionCode ?: 0, update.notes, update.url))
        // And whether the built-in harness, which ends with this process, should come back.
        runCatching { hooks.beforeInstall() }
        try {
            withContext(Dispatchers.IO) { installer.commit(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            tracker.clear()
            hooks.installFailed()
            val failure = if (error is IOException && error.message.orEmpty().contains("space", ignoreCase = true)) {
                InstallFailure.Storage
            } else {
                InstallFailure.Unknown
            }
            _state.value = UpdaterState.InstallFailed(update, failure, error.message)
        }
    }

    /**
     * Put the updater back where the last process left it: a partial download to resume, a checked
     * APK to install, or an install that was waiting on "allow installs from this app". Everything
     * else in the download directory is deleted.
     */
    private suspend fun restore() {
        runCatching { installer.abandonStaleSessions() }
        val record = store.newest()
        store.cleanup(BuildConfig.VERSION_NAME, record?.version)
        if (record == null || !isNewerVersion(record.version, BuildConfig.VERSION_NAME)) return
        val update = record.toUpdate()
        checker.offer(update)
        if (_state.value != UpdaterState.Idle) return
        val apk = store.apkFile(record.version)
        val trusted = record.complete && apk.isFile && (
            record.unverifiedConsent ||
                (record.verifiedSha256 != null && withContext(Dispatchers.IO) { sha256Of(apk) } == record.verifiedSha256)
            )
        when {
            trusted && record.awaitingPermission && installer.canInstall() -> inspectAndInstall(update)
            trusted && record.awaitingPermission -> _state.value = UpdaterState.NeedsPermission(update, askedBefore = true)
            trusted -> _state.value = UpdaterState.ReadyToInstall(update)
            store.bytesOnDisk(record.version) > 0 -> {
                val done = store.bytesOnDisk(record.version)
                val total = record.total.takeIf { it > 0 } ?: record.apkSize
                _state.value = UpdaterState.Paused(update, done, total, PauseReason.Interrupted)
            }
        }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)
        val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
        const val PUBLISH_EVERY_MS = 120L
        const val STALL_AFTER_MS = 1_500L
    }
}
