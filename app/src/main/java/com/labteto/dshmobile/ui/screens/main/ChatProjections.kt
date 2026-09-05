package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.FileAttachmentRef
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Defensive readers over the loosely-typed slots of a [com.labteto.dshmobile.core.session
 * .ConversationSnapshot]: projection payloads and event `data` blobs. Everything here returns null
 * rather than throwing — the wire layer's rule is that unknown or partial data degrades, never
 * crashes.
 *
 * Projections with a stable shape are decoded once in `SessionStore` instead; these are the ones
 * that arrive inside individual nodes or need shape-sniffing.
 */

// ---------------------------------------------------------------------------
// Goal / todos
// ---------------------------------------------------------------------------

/** A goal snapshot, whether it arrived bare or wrapped in a `{ goal: … }` envelope. */
internal fun parseGoal(element: JsonElement?): GoalSnapshot? {
    if (element == null) return null
    val direct = runCatching { decodeFromJsonElement(GoalSnapshot.serializer(), element) }.getOrNull()
    if (direct != null) return direct
    val inner = (element as? JsonObject)?.get("goal") ?: return null
    return runCatching { decodeFromJsonElement(GoalSnapshot.serializer(), inner) }.getOrNull()
}

/** One to-do entry as the dock renders it. */
internal data class TodoEntry(val content: String, val status: String)

/** The todo list, whether it arrived bare or wrapped in a `{ todos: [...] }` envelope. */
internal fun parseTodos(element: JsonElement?): List<TodoEntry>? {
    if (element == null) return null
    return runCatching {
        val obj = element as? JsonObject
        val array = (obj?.get("todos") ?: element) as? JsonArray ?: return@runCatching emptyList()
        array.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val content = row["content"].asString() ?: return@mapNotNull null
            TodoEntry(content = content, status = row["status"].asString() ?: "pending")
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

/**
 * The attachment reference carried by an `image` content block.
 *
 * [ChatBlock.raw] keeps the whole wire object, so the ref — including the intrinsic width and
 * height a decoder needs to downsample correctly — is available without a second round trip.
 */
internal fun parseImageRef(block: ChatBlock): ImageAttachmentRef? {
    val raw = block.raw as? JsonObject ?: return null
    val attachment = raw["attachment"] ?: raw
    return runCatching { decodeFromJsonElement(ImageAttachmentRef.serializer(), attachment) }.getOrNull()
}

/** The durable file reference behind a `file` block (harness 0.1.3), or null when malformed. */
internal fun parseFileRef(block: ChatBlock): FileAttachmentRef? {
    val raw = block.raw as? JsonObject ?: return null
    val attachment = raw["attachment"] ?: raw
    return runCatching { decodeFromJsonElement(FileAttachmentRef.serializer(), attachment) }.getOrNull()
}

/**
 * A file size for a chip: bytes below a kilobyte, then KB, then MB with one decimal.
 *
 * `imageSizeText` prints megabytes only, because it names a limit the harness itself wrote in
 * megabytes; a 3 KB text file would read as `0.0MB` there, which is a size in name only.
 */
internal fun fileSizeText(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes.toDouble() / (1024 * 1024))
}

// ---------------------------------------------------------------------------
// Workflow
// ---------------------------------------------------------------------------

/** One member row of a workflow disclosure. */
internal data class WorkflowMember(
    val label: String?,
    val childId: String?,
    val status: String?,
)

/** Flatten a workflow event payload into its member rows, tolerating both wire shapes. */
internal fun parseWorkflowMembers(data: JsonElement): List<WorkflowMember> {
    val obj = data as? JsonObject ?: return emptyList()
    val status = obj["status"].asString() ?: obj["stopReason"].asString() ?: obj["outcome"].asString()
    val array = obj["members"] as? JsonArray ?: obj["phases"] as? JsonArray
    if (array != null) {
        return array.mapNotNull { member ->
            val row = member as? JsonObject ?: return@mapNotNull null
            WorkflowMember(
                label = row["label"].asString() ?: row["name"].asString(),
                childId = row["childId"].asString(),
                status = row["status"].asString() ?: row["outcome"].asString(),
            )
        }
    }
    val childId = obj["childId"].asString()
    val label = obj["label"].asString()
    return if (childId != null || label != null) {
        listOf(WorkflowMember(label, childId, status))
    } else {
        emptyList()
    }
}

// ---------------------------------------------------------------------------
// Turn grouping
// ---------------------------------------------------------------------------

/**
 * Split a node list into `(turn, nodes)` pairs. Nodes before the first `turn/start` — a history
 * page can begin mid-turn — are dropped, which is what both the trajectory and the details ledger
 * want.
 */
internal fun groupByTurn(nodes: List<ChatNode>): List<Pair<Int, List<ChatNode>>> {
    val groups = mutableListOf<Pair<Int, MutableList<ChatNode>>>()
    for (node in nodes) {
        when {
            node is TurnStartNode -> groups.add(node.turn to mutableListOf())
            groups.isNotEmpty() -> groups.last().second.add(node)
        }
    }
    return groups.map { (turn, list) -> turn to list.toList() }
}

// ---------------------------------------------------------------------------
// Defensive JSON accessors
// ---------------------------------------------------------------------------

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonElement?.asBoolean(): Boolean? = when (val text = (this as? JsonPrimitive)?.content) {
    "true" -> true
    "false" -> false
    else -> text?.toBooleanStrictOrNull()
}
