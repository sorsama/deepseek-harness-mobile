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

    /**
     * [allowOlder] accepts a release with a lower `sequence` than the installed one. Only the
     * person's own "go back to the previous version" passes it; every automatic path and the
     * ordinary update keep refusing a lower sequence (README, "Manifest schema": `sequence`).
     */
    fun decide(
        manifest: RuntimeManifest,
        appVersion: String,
        deviceAbis: List<String>,
        installed: InstalledRecord?,
        allowOlder: Boolean = false,
    ): Decision {
        if (!AppVersion.satisfies(appVersion, manifest.minAppVersion)) {
            return Decision.Refused(RuntimeFailure.AppTooOld(manifest.minAppVersion))
        }
        if (manifest.signing.keyId != ManifestVerifier.PINNED_KEY_ID) {
            // The signature already checked out against the pinned key, so this is a manifest that
            // names the wrong key: a release-tooling bug, refused rather than guessed about.
            return Decision.Refused(RuntimeFailure.BadManifest("keyId ${manifest.signing.keyId}"))
        }
        if (installed != null && manifest.sequence < installed.sequence && !allowOlder) {
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
                LAYER_BASE -> !installed?.base.isLayer(layer)
                LAYER_HARNESS -> !installed?.harness.isLayer(layer)
                else -> false
            }
        }
        return if (needed.isEmpty()) Decision.UpToDate(abi) else Decision.Install(abi, needed)
    }
}

/**
 * Whether this unpacked layer is [layer]: same id *and* same archive hash.
 *
 * The README says the id alone decides, but release 2026.11.14-2 shipped a different harness
 * archive under the same id (`dsh-0.2.2-rc.1`) as 2026.11.14-1. Comparing the hash as well means
 * such a release still reaches devices; the signed manifest vouches for both values.
 */
fun LayerRecord?.isLayer(layer: RuntimeManifest.Layer): Boolean =
    this != null && id == layer.id && sha256 == layer.sha256
