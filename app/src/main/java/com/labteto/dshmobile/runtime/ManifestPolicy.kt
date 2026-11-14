package com.labteto.dshmobile.runtime

/**
 * Decides whether a verified manifest may be installed on this device, and what it would take.
 *
 * Pure: the device's ABIs, the app's version and the installed record come in as values.
 */
object ManifestPolicy {

    /** The ABIs a runtime is built for, in preference order. */
    val SUPPORTED_ABIS = listOf("arm64-v8a", "x86_64")

    /** The first of the device's ABIs (most preferred first, like `Build.SUPPORTED_ABIS`) we ship. */
    fun deviceAbi(deviceAbis: List<String>): String? = deviceAbis.firstOrNull { it in SUPPORTED_ABIS }

    /** What an install or update of [manifest] means for this device. */
    sealed interface Decision {
        data class Refused(val failure: RuntimeFailure) : Decision

        /** Nothing to do: the installed layers are the manifest's. */
        data class UpToDate(val abi: String) : Decision

        /** [layers] are the ones to download and unpack, in install order. */
        data class Install(val abi: String, val layers: List<RuntimeManifest.Layer>) : Decision {
            val downloadBytes: Long get() = layers.sumOf { it.size }
            val baseChanges: Boolean get() = layers.any { it.name == LAYER_BASE }
            val harnessChanges: Boolean get() = layers.any { it.name == LAYER_HARNESS }
        }
    }

    fun decide(
        manifest: RuntimeManifest,
        appVersion: String,
        deviceAbis: List<String>,
        installed: InstalledRecord?,
    ): Decision {
        if (!AppVersion.satisfies(appVersion, manifest.minAppVersion)) {
            return Decision.Refused(RuntimeFailure.AppTooOld(manifest.minAppVersion))
        }
        if (manifest.signing.keyId != ManifestVerifier.PINNED_KEY_ID) {
            // The signature already checked out against the pinned key, so this is a manifest that
            // names the wrong key: a release-tooling bug, refused rather than guessed about.
            return Decision.Refused(RuntimeFailure.BadManifest("keyId ${manifest.signing.keyId}"))
        }
        if (installed != null && manifest.sequence < installed.sequence) {
            return Decision.Refused(RuntimeFailure.OlderRelease(installed.sequence, manifest.sequence))
        }
        val abi = deviceAbi(deviceAbis)
            ?: return Decision.Refused(RuntimeFailure.NoBuildForDevice(deviceAbis.firstOrNull() ?: "unknown"))
        val arch = manifest.archs[abi] ?: return Decision.Refused(RuntimeFailure.NoBuildForDevice(abi))
        if (installed != null && installed.abi != abi) {
            // The same app data on a different ABI cannot happen on one device; start over cleanly.
            return Decision.Install(abi, arch.layers)
        }
        val needed = arch.layers.filter { layer ->
            when (layer.name) {
                LAYER_BASE -> installed?.base?.id != layer.id
                LAYER_HARNESS -> installed?.harness?.id != layer.id
                else -> false
            }
        }
        return if (needed.isEmpty()) Decision.UpToDate(abi) else Decision.Install(abi, needed)
    }
}
