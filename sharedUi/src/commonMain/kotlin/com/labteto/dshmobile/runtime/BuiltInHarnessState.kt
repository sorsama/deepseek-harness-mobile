package com.labteto.dshmobile.runtime

// What a screen needs to know about the built-in harness beyond the engine's own RuntimeState: the
// release it would install (sizes, before anything is downloaded) and the set-up flow the person
// started, which outlives the screen that started it. Types only; Android fills them in
// (`BuiltInHarnessHost` in :app) and iOS never leaves NotSupported.

/** One layer of the release this device would fetch: what the step tracker weighs progress by. */
data class ReleaseLayerPlan(
    /** `base` or `harness`. */
    val name: String,
    val downloadBytes: Long,
    val unpackedBytes: Long,
    val entries: Int,
)

/** The release a set-up or an update would install, read from its signed manifest. */
data class RuntimeReleaseInfo(
    val releaseId: String,
    val harnessVersion: String,
    /** The layers this device would fetch, in the order the installer fetches them. */
    val layers: List<ReleaseLayerPlan>,
    /** The release has the Android permission presets (see [InstalledRuntime.commandPermissions]). */
    val commandPermissions: Boolean = false,
    /** `runtime.permissions.version` of the release (see [InstalledRuntime.commandPermissionsVersion]). */
    val commandPermissionsVersion: Int = 0,
) {
    val downloadBytes: Long get() = layers.sumOf { it.downloadBytes }
    val unpackedBytes: Long get() = layers.sumOf { it.unpackedBytes }
    val entries: Int get() = layers.sumOf { it.entries }
}

/**
 * The built-in harness as the Connect screen, the set-up page and the control page see it.
 *
 * @property runtime the engine's state, unchanged.
 * @property release the latest release for this device, once a check has read its manifest.
 * @property releaseProblem why the latest release cannot be installed here (the app is too old for
 *   it, the release server is unreachable...), found by that same check before any download.
 * @property update a newer release than the installed one, when a check found one.
 * @property settingUp the person asked for a set-up and it has not finished or been cancelled: the
 *   flow carries on by itself from download to a running harness.
 * @property waitingForNetwork a set-up's download broke off with the network and resumes on its own
 *   when the network is back. Shown as waiting, never as an error.
 */
data class BuiltInHarnessState(
    val runtime: RuntimeState,
    val release: RuntimeReleaseInfo? = null,
    val releaseProblem: RuntimeFailure? = null,
    val update: AvailableRuntimeUpdate? = null,
    val settingUp: Boolean = false,
    val waitingForNetwork: Boolean = false,
    /** The last download reading, so a pause (a check, a lost network) holds the bar where it was. */
    val lastDownload: DownloadProgress? = null,
    /**
     * The release the person went back from, while [update] is that release (or an older one):
     * nothing nags about it or installs it by itself, and the control page offers "Update anyway".
     * Null again once a newer release appears.
     */
    val skippedRelease: String? = null,
    /** How the last update or "go back" ended, until the person moves on. */
    val outcome: UpdateOutcome? = null,
) {
    /** False on iOS and on a device without a 64-bit ABI the runtime is built for. */
    val supported: Boolean get() = runtime !is RuntimeState.NotSupported

    /** The newer release needs a newer DSH Mobile: the version it asks for, or null. */
    val updateNeedsApp: String?
        get() = (releaseProblem as? RuntimeFailure.AppTooOld)?.minAppVersion?.takeIf { installed != null }

    /** An update worth a quiet mark: one that can be installed and was not left on purpose, or one that needs the app updated first. */
    val updateWorthShowing: Boolean
        get() = update != null && skippedRelease == null || updateNeedsApp != null

    /** What is on the device, whatever the engine is doing right now. */
    val installed: InstalledRuntime?
        get() = when (val r = runtime) {
            is RuntimeState.Stopped -> r.installed
            is RuntimeState.Starting -> r.installed
            is RuntimeState.Running -> r.installed
            is RuntimeState.Stopping -> r.installed
            is RuntimeState.Updating -> r.installed
            is RuntimeState.Failed -> r.installed
            else -> null
        }

    companion object {
        val Unsupported = BuiltInHarnessState(RuntimeState.NotSupported(RuntimeUnsupported.Platform))
    }
}
