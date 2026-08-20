package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Workspace-domain DTOs, ported from `packages/host/apiproxy/src/api/workspace.schema.ts` and
 * `packages/host/apiproxy/src/api/workspace.ts` (v0.1.0-rc.8).
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
