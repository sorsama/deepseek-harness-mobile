package com.labteto.dshmobile.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `manifest.json` of a runtime release, schema version 1.
 *
 * The contract is the runtime repository's README ("Manifest schema"). Unknown fields are ignored,
 * as the schema asks. Only fields the app acts on are modelled; the rest of the manifest (Debian
 * snapshot, NDK version, source tarballs) is for people and for the release notes.
 *
 * Never build one of these from bytes whose signature has not been checked: [ManifestVerifier]
 * comes first, then [RuntimeManifest.parse].
 */
@Serializable
data class RuntimeManifest(
    val schemaVersion: Int,
    val releaseId: String,
    val sequence: Long,
    val createdAt: String = "",
    val minAppVersion: String,
    val signing: Signing,
    val assetBaseUrl: String,
    val harness: Harness,
    val node: Node = Node(),
    val runtime: Runtime,
    val archs: Map<String, Arch>,
    /** The release before this one (README "How updates reach the app"); absent in the first release. */
    val previous: Previous? = null,
) {
    /**
     * Where "go back to the previous version" leads. Fetched and verified like any manifest, and
     * installed only when the person asks for it: its `sequence` is lower than this one's.
     */
    @Serializable
    data class Previous(val releaseId: String, val harnessVersion: String = "", val sequence: Long, val manifestUrl: String)

    @Serializable
    data class Signing(val algorithm: String, val keyId: String, val signatureFile: String = "manifest.json.sig")

    @Serializable
    data class Harness(@SerialName("package") val packageName: String = "", val version: String)

    @Serializable
    data class Node(val version: String = "", val home: String = "/opt/node")

    @Serializable
    data class Runtime(
        val entrypoint: String,
        val readinessPrefix: String,
        val fakeProc: List<FakeProc> = emptyList(),
        val resolvConf: String = "/etc/resolv.conf",
        /**
         * The Android permission presets and the switch for their default (release `2026.11.15-1`
         * and later; README "Shell commands and permissions on Android"). Null in older releases,
         * which have neither, so the app passes no `DSH_RUNTIME_*` variable for them.
         */
        val permissions: Permissions? = null,
    )

    @Serializable
    data class FakeProc(val source: String, val target: String)

    /** `runtime.permissions`: the presets this harness layer adds and the variables that steer them. */
    @Serializable
    data class Permissions(
        val version: Int = 1,
        val catalogMethod: String = "permissionPresets/catalog",
        val presets: List<PermissionPreset> = emptyList(),
        /** The guest variable that picks the preset new sessions start on. */
        val defaultEnv: String = "DSH_RUNTIME_DEFAULT_PERMISSION",
        /** The values [defaultEnv] takes (`full`, `ask`). */
        val defaultEnvValues: List<String> = emptyList(),
        val defaultWhenUnset: String = "",
        val disableEnv: String = "DSH_RUNTIME_MOBILE_PATCH",
        val disableValue: String = "0",
    )

    @Serializable
    data class PermissionPreset(
        val id: String,
        val name: String = "",
        val envValue: String = "",
        val asksBeforeCommands: Boolean = false,
    )

    @Serializable
    data class Arch(
        val debianArch: String = "",
        val proot: List<AssetFile> = emptyList(),
        val layers: List<Layer>,
    )

    @Serializable
    data class AssetFile(val file: String, val size: Long, val sha256: String, val installAs: String? = null)

    @Serializable
    data class Layer(
        val file: String,
        val size: Long,
        val sha256: String,
        val name: String,
        val id: String,
        val compression: String,
        val zstdWindowLog: Int = 0,
        val format: String = "",
        val unpackedSize: Long,
        val entries: Int,
        val owns: List<String> = emptyList(),
        val excludes: List<String> = emptyList(),
    )

    companion object {
        /** The highest schema this app understands. */
        const val SCHEMA_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        /** Parse verified manifest bytes. Throws [ManifestException] on anything malformed. */
        fun parse(bytes: ByteArray): RuntimeManifest {
            val manifest = try {
                json.decodeFromString(serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                throw ManifestException("not a runtime manifest: ${e.message?.take(200)}")
            }
            manifest.validate()
            return manifest
        }
    }

    /**
     * Shape checks that do not depend on the device or on what is installed.
     *
     * Asset names become URLs and file names here, so they are held to plain names; a manifest is
     * signed, but checking that a signed file says something sensible costs nothing.
     */
    private fun validate() {
        if (schemaVersion > SCHEMA_VERSION) throw ManifestException("schema $schemaVersion is newer than $SCHEMA_VERSION")
        if (schemaVersion < 1) throw ManifestException("schema $schemaVersion")
        if (signing.algorithm != "Ed25519") throw ManifestException("signing algorithm ${signing.algorithm}")
        if (!assetBaseUrl.startsWith("https://") || !assetBaseUrl.endsWith("/")) {
            throw ManifestException("assetBaseUrl must be https and end with /")
        }
        if (!runtime.entrypoint.startsWith("/")) throw ManifestException("entrypoint ${runtime.entrypoint}")
        if (runtime.readinessPrefix.isBlank()) throw ManifestException("readinessPrefix is empty")
        previous?.let { p ->
            if (!p.manifestUrl.startsWith("https://") || !p.manifestUrl.endsWith("/manifest.json")) {
                throw ManifestException("previous.manifestUrl must be an https URL of a manifest.json")
            }
            if (p.sequence >= sequence) throw ManifestException("previous.sequence ${p.sequence} is not below $sequence")
        }
        // These end up as `NAME=value` words of the guest's `env -i` list: plain names and values only.
        runtime.permissions?.let { p ->
            if (!GUEST_ENV_NAME.matches(p.defaultEnv) || !GUEST_ENV_NAME.matches(p.disableEnv)) {
                throw ManifestException("permissions: variable names ${p.defaultEnv}, ${p.disableEnv}")
            }
            if ((p.defaultEnvValues + p.presets.map { it.envValue } + p.disableValue).any { it.isNotEmpty() && !GUEST_ENV_VALUE.matches(it) }) {
                throw ManifestException("permissions: a variable value is not a plain word")
            }
        }
        for (proc in runtime.fakeProc) {
            if (!isGuestPath(proc.source) || !isGuestPath(proc.target) || !proc.target.startsWith("/proc/")) {
                throw ManifestException("fakeProc ${proc.source} -> ${proc.target}")
            }
        }
        for ((abi, arch) in archs) {
            for (asset in arch.proot) checkAsset(abi, asset.file, asset.size, asset.sha256)
            for (layer in arch.layers) {
                checkAsset(abi, layer.file, layer.size, layer.sha256)
                if (layer.compression != "zstd") throw ManifestException("$abi ${layer.name}: compression ${layer.compression}")
                if (layer.id.isBlank()) throw ManifestException("$abi ${layer.name}: empty id")
                if (layer.unpackedSize < 0 || layer.entries < 0) throw ManifestException("$abi ${layer.name}: negative sizes")
            }
            val names = arch.layers.map { it.name }
            if (names != listOf(LAYER_BASE, LAYER_HARNESS)) throw ManifestException("$abi layers $names")
        }
    }

    private fun checkAsset(abi: String, file: String, size: Long, sha256: String) {
        if (!isPlainFileName(file)) throw ManifestException("$abi: asset name '$file'")
        if (size <= 0) throw ManifestException("$abi: $file has size $size")
        if (!SHA256_HEX.matches(sha256)) throw ManifestException("$abi: $file has a malformed sha256")
    }

    fun layer(abi: String, name: String): Layer? = archs[abi]?.layers?.firstOrNull { it.name == name }
}

const val LAYER_BASE = "base"
const val LAYER_HARNESS = "harness"

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val GUEST_ENV_NAME = Regex("[A-Z_][A-Z0-9_]{0,63}")
private val GUEST_ENV_VALUE = Regex("[A-Za-z0-9._-]{1,64}")

internal fun isPlainFileName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 200 && name != "." && name != ".." &&
        name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } && name.none { it.code > 127 }

private fun isGuestPath(path: String): Boolean =
    path.startsWith("/") && path.split('/').none { it == ".." } && '\u0000' !in path

class ManifestException(message: String) : Exception(message)
