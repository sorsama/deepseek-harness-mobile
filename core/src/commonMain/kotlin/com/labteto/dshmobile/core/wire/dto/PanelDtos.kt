package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Browser-safe contracts, first transcribed at upstream 0d1f500. */
@Serializable data class PermissionCatalog(val options: List<PresetOption> = emptyList())
@Serializable data class WorkspaceUnarchiveSessionRequest(val sessionId: String)
@Serializable data class WorkspaceFileRange(val offset: Int = 1, val limit: Int = 500)
@Serializable data class WorkspaceByteRange(val offset: Long = 0, val length: Int = 262144)
@Serializable data class WorkspaceFileStat(val absolutePath: String, val version: String, val bytes: Long? = null)
@Serializable data class WorkspaceFileText(val absolutePath: String, val version: String, val bytes: Long? = null,
    val offset: Int, val text: String, val lines: Int, val eof: Boolean)
@Serializable data class WorkspaceDirectoryEntry(val name: String, val type: String, val size: Long? = null)
@Serializable data class WorkspaceDirectoryListing(val path: String, val entries: List<WorkspaceDirectoryEntry>, val truncated: Boolean)
@Serializable data class WorkspaceFileChange(val absolutePath: String, val version: String? = null, val absent: Boolean = false)
@Serializable data class WorkspaceFileWatchFrame(val kind: String, val change: WorkspaceFileChange? = null)

@Serializable data class TerminalShell(val path: String, val args: List<String> = emptyList(), val name: String)
/** [maxBufferedBytes] arrived with the duplex terminal (0.2.1-alpha.3) and is absent before it. */
@Serializable data class TerminalEnvironment(val cwd: String, val maxInputBytes: Int, val maxCols: Int,
    val maxRows: Int, val scrollback: Int, val maxBufferedBytes: Int? = null)
/** [controllerId] is the pre-alpha.3 input-control marker; duplex hosts send `writable` on frames instead. */
@Serializable data class WebTerminalInfo(val id: String, val title: String, val shell: TerminalShell,
    val cwd: String, val cols: Int, val rows: Int, val state: String, val exitCode: Int? = null,
    val error: String? = null, val controllerId: String? = null)
@Serializable data class TerminalCreateRequest(val id: String, val cols: Int, val rows: Int, val shellPath: String? = null)
/** The host's uplink inbox budget for one duplex stream (`RemoteUplinkWindow`). */
@Serializable data class RemoteUplinkWindow(val maxBytes: Int, val frameOverheadBytes: Int)
/**
 * One `terminal/attach` (or legacy `terminal/follow`) frame: `snapshot`, `output`, `state`, and on
 * duplex hosts `ack`, which acknowledges input command [sequence] and may carry a [refusal].
 */
@Serializable data class TerminalFrame(val type: String, val sequence: Long? = null, val screen: String? = null,
    val info: WebTerminalInfo? = null, val data: String? = null, val writable: Boolean? = null,
    val inputWindow: RemoteUplinkWindow? = null, val refusal: String? = null)

@Serializable data class MessageFeedbackItem(val messageId: String, val rating: String, val version: String,
    val createdAt: Long, val updatedAt: Long, val note: String? = null, val category: String? = null)
@Serializable data class MessageFeedbackListValue(val items: List<MessageFeedbackItem> = emptyList())
@Serializable data class MessageFeedbackFailure(val code: String, val current: MessageFeedbackItem? = null,
    val maxBytes: Long? = null, val actualBytes: Long? = null)
@Serializable data class MessageFeedbackResult<T>(val ok: Boolean, val value: T? = null, val error: MessageFeedbackFailure? = null)
@Serializable data class MessageFeedbackListRequest(val sessionId: String)
@Serializable data class MessageFeedbackPutRequest(val sessionId: String, val messageId: String, val rating: String,
    val ifVersion: String?, val note: String? = null, val category: String? = null)
@Serializable data class MessageFeedbackDeleteRequest(val sessionId: String, val messageId: String, val ifVersion: String)
@Serializable data class MessageFeedbackDeleteValue(val absent: Boolean)
