package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Host-domain DTOs.
 *
 * Harness 0.1.2 deleted `host.describe` and split what it carried three ways: the stable host
 * home now rides the `$events` ready frame, each capability is answered by the domain that owns
 * it when its page appears, and the process metadata (version, cwd, attached session count) is
 * simply no longer sent. The directory verbs moved to the `directoryPicker` namespace on the
 * Workspace Controller; the shapes below are ported from
 * `packages/api/workspace-controller/src/directory-picker.ts` (v0.1.2-alpha.1).
 */

/**
 * What this client knows about the host after a generation is ready.
 *
 * The sole field is what the ready frame carries, and it exists for one purpose: abbreviating
 * displayed filesystem paths. Nothing else survived `host.describe`.
 *
 * In particular there is no `version`. The connect list, the details panel and Settings screens
 * used to show the harness's own version here; no 0.1.2 wire field replaces it, so they show
 * this client's pinned baseline instead — see `docs/COMPATIBILITY.md`.
 */
@Serializable
data class HostDescription(
    /** The host account's home directory. Always present from 0.1.2; the ready frame requires it. */
    @SerialName("home") val home: String,
)

/**
 * Value of `directoryPicker/pick`; null when the operator cancelled the chooser.
 *
 * The remote returns a bare `string | null` rather than an object, so this is a thin holder the
 * client wraps around the decoded value rather than a wire shape of its own.
 */
data class HostPickDirectoryValue(
    val path: String? = null,
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

/** Named arguments of `directoryPicker/list`; an absent path lists the home directory. */
@Serializable
data class HostListDirectoryRequest(
    @SerialName("path") val path: String? = null,
)

/** Named arguments of `directoryPicker/createDirectory`. */
@Serializable
data class HostCreateDirectoryRequest(
    @SerialName("path") val path: String,
    @SerialName("name") val name: String,
)

/**
 * Value of `directoryPicker/createDirectory`: the created directory's absolute path.
 *
 * Like [HostPickDirectoryValue] this wraps a bare wire `string`; 0.1.1 wrapped it in an object.
 */
data class HostCreateDirectoryValue(
    val path: String,
)

/**
 * Named arguments of `session/openWorkspacePath`.
 *
 * This replaces `host.openPath`, and the move is not only a rename: the path is now resolved
 * against the addressed session's workspace before the host opens it, so a caller must name a
 * session rather than handing the host an absolute filesystem target of its choosing.
 */
@Serializable
data class SessionOpenWorkspacePathRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("path") val path: String,
)

/** Value of `session/openWorkspacePath`. */
@Serializable
data class HostOpenPathValue(
    @SerialName("opened") val opened: Boolean = true,
)
