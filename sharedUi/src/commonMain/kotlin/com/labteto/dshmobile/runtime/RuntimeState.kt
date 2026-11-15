package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn

// The state of the built-in harness runtime (the harness that runs inside the app, under proot), as
// a screen will read it. Only the types live in the shared module: the installer, the supervisor and
// the foreground service are Android and stay in :app. iOS never leaves NotSupported.

/** What is installed: one base layer (Debian and Node.js) and one harness layer. */
data class InstalledRuntime(
    val releaseId: String,
    val sequence: Long,
    val harnessVersion: String,
    val nodeVersion: String,
    val baseId: String,
    val harnessId: String,
    /** True after an update until the new release has reached readiness once. */
    val harnessOnTrial: Boolean = false,
    /** The release before this one, from the installed manifest's `previous`: where "Go back" leads. */
    val previousRelease: ReleaseRef? = null,
    /**
     * The release has the Android permission presets (`runtime.permissions`), so the app can
     * choose whether commands run straight away or ask first. False for older releases.
     */
    val commandPermissions: Boolean = false,
    /**
     * `runtime.permissions.version`, 0 without the field. Version 2 and later send the whole text of
     * every approval and a code with every denial; version 1 cut a long command at 2,000 characters,
     * so "Ask me first" cannot be relied on there ([askFirstTrusted]).
     */
    val commandPermissionsVersion: Int = 0,
) {
    /** The runtime asks with the whole command, so "Ask me first" shows what it is asking about. */
    val askFirstTrusted: Boolean get() = commandPermissions && commandPermissionsVersion >= ASK_FIRST_MIN_PERMISSIONS_VERSION
}

/** The first `runtime.permissions.version` that sends an approval's text uncut (release 2026.11.15-2). */
const val ASK_FIRST_MIN_PERMISSIONS_VERSION = 2

/** A release by the three things the screens say about it. */
data class ReleaseRef(
    val releaseId: String,
    val harnessVersion: String,
    val sequence: Long,
)

/** A release newer than what is installed, found by a check. */
data class AvailableRuntimeUpdate(
    val releaseId: String,
    val harnessVersion: String,
    /** Bytes to download: only the layers that changed (id or archive). */
    val downloadBytes: Long,
    val baseChanges: Boolean,
    val harnessChanges: Boolean,
    /** The release's `sequence`, which orders releases: what "skipped" and "notified" compare. */
    val sequence: Long = 0,
    /** Bytes of each layer the update downloads, in download order: progress over all of them. */
    val layerDownloadBytes: List<Long> = emptyList(),
) {
    val ref: ReleaseRef get() = ReleaseRef(releaseId, harnessVersion, sequence)
}

/** Download progress over every layer this install or update fetches. */
data class DownloadProgress(
    /** `base` or `harness`. */
    val layer: String,
    val layerIndex: Int,
    val layerCount: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    /** 0 to 100 over this layer. */
    val percent: Int get() = if (bytesTotal <= 0) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

/** Unpacking progress of one layer. */
data class UnpackProgress(
    val layer: String,
    val layerIndex: Int,
    val layerCount: Int,
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesDone: Long,
) {
    val percent: Int get() = if (entriesTotal <= 0) 0 else ((entriesDone.toLong() * 100) / entriesTotal).toInt().coerceIn(0, 100)
}

/** The steps of an update of an installed runtime. */
sealed interface UpdatePhase {
    data object Checking : UpdatePhase
    data class Downloading(val progress: DownloadProgress) : UpdatePhase
    data class Verifying(val layer: String) : UpdatePhase
    data class Unpacking(val progress: UnpackProgress) : UpdatePhase

    /** The new files are in place; the harness is being stopped and started again on them. */
    data object Switching : UpdatePhase
}

/** Why this device cannot run the built-in harness at all. */
sealed interface RuntimeUnsupported {
    /** No 64-bit ABI the runtime is built for (`arm64-v8a`, `x86_64`); [abis] is what the device has. */
    data class NoSupportedAbi(val abis: List<String>) : RuntimeUnsupported

    /** The app was installed without its proot binaries (a split or repackaged APK). */
    data object BinariesMissing : RuntimeUnsupported

    /** The platform has no way to run a local process (iOS). */
    data object Platform : RuntimeUnsupported
}

/** Why an operation failed. Each one is a different thing to tell the person, or to do about it. */
sealed interface RuntimeFailure {
    /** Not enough free storage; nothing was changed. */
    data class NoSpace(val neededBytes: Long, val availableBytes: Long) : RuntimeFailure

    /** The release server could not be reached, or a download broke off. A retry resumes it. */
    data class Network(val detail: String?) : RuntimeFailure

    /** The release manifest was not signed by the key compiled into this app. Nothing was trusted. */
    data object BadSignature : RuntimeFailure

    /** A downloaded file did not match the size or hash in the signed manifest; it was deleted. */
    data class ChecksumMismatch(val file: String) : RuntimeFailure

    /** The release needs a newer DSH Mobile. */
    data class AppTooOld(val minAppVersion: String) : RuntimeFailure

    /** The offered release is older than the installed one (a rollback), so it was refused. */
    data class OlderRelease(val installedSequence: Long, val offeredSequence: Long) : RuntimeFailure

    /** The manifest uses a format this app does not understand, or is missing something it needs. */
    data class BadManifest(val detail: String) : RuntimeFailure

    /** The release has no build for this device's ABI. */
    data class NoBuildForDevice(val abi: String) : RuntimeFailure

    /** A layer archive broke an unpacking rule (a path escaping the tree, an unknown entry type). */
    data class BadArchive(val detail: String) : RuntimeFailure

    /** Reading or writing app storage failed. */
    data class Storage(val detail: String?) : RuntimeFailure

    /** proot could not be started at all. */
    data class LaunchFailed(val detail: String?) : RuntimeFailure

    /** The harness ended before it printed its readiness line; [logTail] is the end of its output. */
    data class ExitedBeforeReady(val exitCode: Int?, val logTail: String) : RuntimeFailure

    /** The harness never printed its readiness line in time; it was stopped. */
    data class ReadyTimeout(val logTail: String) : RuntimeFailure

    /** The harness ended on its own after it was ready. */
    data class Crashed(val exitCode: Int?, val logTail: String) : RuntimeFailure

    /** Something that is not this app's harness holds the port. */
    data class PortInUse(val port: Int) : RuntimeFailure
}

/** An update whose new release never became ready; the previous one was put back. */
data class RolledBackUpdate(
    val failedVersion: String,
    val reason: RuntimeFailure,
    /** The release that did not start, so automatic updates do not try it again. */
    val failedReleaseId: String = "",
    val failedSequence: Long = 0,
)

/**
 * How the last update (or the person's "go back") ended, for the control page and for the
 * notification that tells the person afterwards what changed.
 */
sealed interface UpdateOutcome {
    val from: ReleaseRef
    val to: ReleaseRef

    /** The new release is installed. [wentBack] when the person chose an older one. */
    data class Applied(override val from: ReleaseRef, override val to: ReleaseRef, val wentBack: Boolean = false) : UpdateOutcome

    /** The new release never started, so [from] is back. [reason] has the end of its log. */
    data class RolledBack(override val from: ReleaseRef, override val to: ReleaseRef, val reason: RuntimeFailure) : UpdateOutcome

    /** The update stopped before anything changed: what is installed is still [from]. */
    data class Failed(override val from: ReleaseRef, override val to: ReleaseRef, val reason: RuntimeFailure, val wentBack: Boolean = false) : UpdateOutcome
}

/** What the built-in runtime is doing, or last did. */
sealed interface RuntimeState {
    data class NotSupported(val reason: RuntimeUnsupported) : RuntimeState

    data object NotInstalled : RuntimeState

    /** First install: downloading the layers. */
    data class Downloading(val progress: DownloadProgress) : RuntimeState

    /** First install: checking a downloaded layer against the signed manifest. */
    data class Verifying(val layer: String) : RuntimeState

    /** First install: unpacking a layer into app storage. */
    data class Unpacking(val progress: UnpackProgress) : RuntimeState

    /** Installed, nothing running. */
    data class Stopped(val installed: InstalledRuntime) : RuntimeState

    data class Starting(val port: Int, val installed: InstalledRuntime) : RuntimeState

    /**
     * The harness is up on [port] and answered its readiness line.
     *
     * [signIn] says whether the app's cookie for the loopback record is fresh. The controller
     * does not connect; the connect screen decides what [signIn] means for it.
     */
    data class Running(
        val port: Int,
        val harnessVersion: String,
        val sinceEpochMs: Long,
        val signIn: StartSignIn,
        val installed: InstalledRuntime,
        val rolledBack: RolledBackUpdate? = null,
    ) : RuntimeState

    data class Stopping(val port: Int, val installed: InstalledRuntime) : RuntimeState

    /**
     * Updating an installed runtime; [installed] is what runs until the switch. [target] is the
     * release being installed once its manifest is read, and [goingBack] says the person chose an
     * older one.
     */
    data class Updating(
        val phase: UpdatePhase,
        val installed: InstalledRuntime,
        val target: ReleaseRef? = null,
        val goingBack: Boolean = false,
    ) : RuntimeState

    /** The last operation failed; [installed] is what is still on the device, if anything. */
    data class Failed(val reason: RuntimeFailure, val installed: InstalledRuntime?) : RuntimeState

    /** An install, update, start or stop is in flight; other operations wait for it. */
    val busy: Boolean
        get() = this is Downloading || this is Verifying || this is Unpacking || this is Starting ||
            this is Stopping || this is Updating
}

/** Bytes the runtime holds on the device. */
data class RuntimeStorageUsage(
    /** The unpacked layers. */
    val layersBytes: Long,
    /** The harness's own state (`/root`) and the workspaces directory. Kept across updates. */
    val userDataBytes: Long,
    /** Downloads kept for a resume or a retry, and proot's temporary files. */
    val cacheBytes: Long,
    /** The harness output log. */
    val logBytes: Long,
) {
    val totalBytes: Long get() = layersBytes + userDataBytes + cacheBytes + logBytes
}
