package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Workspace-domain DTOs, ported from `packages/host/apiproxy/src/api/workspace.schema.ts` and
 * `packages/host/apiproxy/src/api/workspace.ts` (v0.1.1-rc.2).
 */

/** One workspace row: the record projection every workspace.* value carries. */
@Serializable
data class WorkspaceView(
    @SerialName("workspaceId") val workspaceId: String,
    /** Canonical directory path (host-side realpath canon). */
    @SerialName("path") val path: String,
    /** Display title (defaults to the path basename at create). */
    @SerialName("title") val title: String,
    /** Sessions accounted under this workspace, in manually owned order. */
    @SerialName("sessionIds") val sessionIds: List<String> = emptyList(),
    /** ISO-8601 creation instant. */
    @SerialName("createdAt") val createdAt: String,
    /** ISO-8601 last-mutation instant. */
    @SerialName("updatedAt") val updatedAt: String,
)

// ---- workspace.* request payloads ----

/** Request payload of `workspace.create`: the existing directory to adopt. */
@Serializable
data class WorkspaceCreateRequest(
    @SerialName("path") val path: String,
)

/** Request payload of `workspace.rename`. */
@Serializable
data class WorkspaceRenameRequest(
    @SerialName("workspaceId") val workspaceId: String,
    @SerialName("title") val title: String,
)

/** Request payload of `workspace.delete`. */
@Serializable
data class WorkspaceDeleteRequest(
    @SerialName("workspaceId") val workspaceId: String,
)

/** Request payload of `workspace.insertBefore` (anchor omitted = append to end). */
@Serializable
data class WorkspaceInsertBeforeRequest(
    @SerialName("workspaceId") val workspaceId: String,
    @SerialName("beforeWorkspaceId") val beforeWorkspaceId: String? = null,
)

/** Request payload of `workspace.insertSessionBefore` (anchor omitted = append to end). */
@Serializable
data class WorkspaceInsertSessionBeforeRequest(
    @SerialName("workspaceId") val workspaceId: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("beforeSessionId") val beforeSessionId: String? = null,
)

/** Request payload of `workspace.archiveSession`. */
@Serializable
data class WorkspaceArchiveSessionRequest(
    @SerialName("sessionId") val sessionId: String,
    /**
     * Harness 0.1.7: stop the session's running work instead of refusing the archive as
     * `workspace/session-active`. Null is omitted on the wire, which an older host requires.
     */
    @SerialName("stopActivity") val stopActivity: Boolean? = null,
)

// ---- workspace.* response values ----

/** Value of `workspace.list`. */
@Serializable
data class WorkspaceListValue(
    @SerialName("items") val items: List<WorkspaceView> = emptyList(),
    @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
)

/** Value of `workspace.create`. */
@Serializable
data class WorkspaceCreateValue(
    @SerialName("workspace") val workspace: WorkspaceView,
    @SerialName("created") val created: Boolean,
)

/** Value of `workspace.rename`. */
@Serializable
data class WorkspaceRenameValue(
    @SerialName("workspace") val workspace: WorkspaceView,
)

/** Value of `workspace.delete`. */
@Serializable
data class WorkspaceDeleteValue(
    @SerialName("deleted") val deleted: Boolean = true,
)

/** Value of `workspace.insertBefore`: the complete durable display order. */
@Serializable
data class WorkspaceInsertBeforeValue(
    @SerialName("workspaceIds") val workspaceIds: List<String> = emptyList(),
)

/** Value of `workspace.insertSessionBefore`. */
@Serializable
data class WorkspaceInsertSessionBeforeValue(
    @SerialName("workspace") val workspace: WorkspaceView,
)

/** Value of `workspace.archiveSession`: the full updated archive set. */
@Serializable
data class WorkspaceArchiveSessionValue(
    @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
)

// ============================================================================================
// harness 0.1.2 values
// ============================================================================================

/** Value of `workspace/rename` and `workspace/insertSessionBefore`. */
@Serializable
data class WorkspaceValue(
    @SerialName("workspace") val workspace: WorkspaceView,
)

/**
 * Value of `workspace/insertBefore`: the complete registry order after the mutation.
 *
 * Complete rather than a delta, and authoritative: the client replaces its order with this
 * rather than inferring one from the sequence of upserts it happened to observe.
 */
@Serializable
data class WorkspaceOrderValue(
    @SerialName("workspaceIds") val workspaceIds: List<String> = emptyList(),
)

/** Value of `workspace/archiveSession`: the complete registry-global archived set. */
@Serializable
data class WorkspaceArchiveValue(
    @SerialName("archivedSessionIds") val archivedSessionIds: List<String> = emptyList(),
)
