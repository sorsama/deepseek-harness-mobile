package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Host-domain DTOs, ported from `packages/host/apiproxy/src/api/host.schema.ts` (v0.1.0-rc.8).
 * Wire keys are camelCase exactly as the harness emits them.
 */

/** Value of `host.describe`. */
@Serializable
data class HostDescription(
    /** The host app's version (apps/cli package.json version). */
    @SerialName("version") val version: String,
    /** The host process working directory (root for session persistence and tool execution). */
    @SerialName("cwd") val cwd: String,
    /** Provider applied by default when a new agent does not specify one; absent when unset. */
    @SerialName("provider") val provider: String? = null,
    /** Model applied by default when a new agent does not specify one; absent when unset. */
    @SerialName("model") val model: String? = null,
    /** Count of currently attached sessions (those with a live agent). */
    @SerialName("attachedSessions") val attachedSessions: Int,
    /**
     * The host account's home directory. Required from harness 0.1.0-rc.8 and absent before it,
     * which makes its presence the client's rc.8 signal — see [com.labteto.dshmobile.core.DshCore]
     * and `docs/COMPATIBILITY.md`. Nullable so an rc.7 host still decodes, and so the handshake,
     * which runs `host.describe` before anything else, cannot fail on a missing key.
     */
    @SerialName("home") val home: String? = null,
    /** Whether this deployment can hand a path to a user-visible native desktop. */
    @SerialName("canOpenPath") val canOpenPath: Boolean,
)

/** Value of `host.pickDirectory`; `path` is null when the user cancelled the picker. */
@Serializable
data class HostPickDirectoryValue(
    @SerialName("path") val path: String? = null,
)

/** One directory row: a child entry or a breadcrumb ancestor. */
@Serializable
data class DirectoryEntry(
    /** Base name shown in a browser row (a root crumb carries its full path). */
    @SerialName("name") val name: String,
    /** Absolute host path — the client never joins path segments itself. */
    @SerialName("path") val path: String,
    /** Hidden by the host platform's convention (dot-prefixed on POSIX). */
    @SerialName("hidden") val hidden: Boolean,
)

/** Value of `host.listDirectory`: one directory level plus its ancestry. */
@Serializable
data class DirectoryListing(
    /** Absolute path of the listed directory. */
    @SerialName("path") val path: String,
    /** The host account's home directory (breadcrumb "Home" rooting). */
    @SerialName("home") val home: String,
    /** Ancestor chain from the filesystem root to the listed directory inclusive. */
    @SerialName("crumbs") val crumbs: List<DirectoryEntry> = emptyList(),
    /** Direct child directories, name-sorted; symlinks to directories included. */
    @SerialName("entries") val entries: List<DirectoryEntry> = emptyList(),
    /** True when the backend cut `entries` at its complete-result bound. */
    @SerialName("truncated") val truncated: Boolean,
)

/** Request payload of `host.listDirectory`; an absent path lists the home directory. */
@Serializable
data class HostListDirectoryRequest(
    @SerialName("path") val path: String? = null,
)

/** Request payload of `host.createDirectory`. */
@Serializable
data class HostCreateDirectoryRequest(
    @SerialName("path") val path: String,
    @SerialName("name") val name: String,
)

/** Value of `host.createDirectory`: the created directory's absolute path. */
@Serializable
data class HostCreateDirectoryValue(
    @SerialName("path") val path: String,
)

/** Request payload of `host.openPath`. */
@Serializable
data class HostOpenPathRequest(
    @SerialName("path") val path: String,
)

/** Value of `host.openPath`. */
@Serializable
data class HostOpenPathValue(
    @SerialName("opened") val opened: Boolean = true,
)
