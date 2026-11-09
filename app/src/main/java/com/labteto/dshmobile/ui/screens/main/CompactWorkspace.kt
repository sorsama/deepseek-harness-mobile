package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow

/** Ordered workspace prefix; the baseline has no user-requested expansion. */
internal data class WorkspaceWindow(val rows: List<SessionRow>, val remaining: Int, val canCollapse: Boolean)

/**
 * Desktop's nonblank prefix, without overriding the caller's selected order. Automatic attention
 * scans only the first 50 roots. An explicit current/search target is never subject to that cap.
 * SessionRow has no unread-completion projection: do not guess one from status or timestamps.
 */
internal fun compactWorkspaceWindow(
    roots: List<SessionRow>,
    requested: Int,
    openPath: Set<String>,
    childrenByParent: Map<String, List<SessionRow>> = emptyMap(),
    retainedAttention: Int = 0,
): WorkspaceWindow {
    val eligible = roots.filterNot { it.blank }
    val attentionEnd = workspaceAttentionBaseline(eligible, childrenByParent)
    val targetEnd = eligible.indexOfLast { it.sessionId in openPath } + 1
    val baseline = maxOf(WORKSPACE_PREVIEW_LIMIT, attentionEnd, targetEnd).coerceAtMost(eligible.size)
    val count = maxOf(baseline, requested, retainedAttention).coerceAtMost(eligible.size)
    return WorkspaceWindow(eligible.take(count), eligible.size - count, count > baseline)
}

internal fun workspaceAttentionBaseline(roots: List<SessionRow>, children: Map<String, List<SessionRow>> = emptyMap()): Int =
    maxOf(WORKSPACE_PREVIEW_LIMIT, roots.filterNot { it.blank }.take(50).indexOfLast { root ->
        sessionTreeMatches(root, children) { it.running || it.pendingInteraction != null }
    } + 1).coerceAtMost(roots.count { !it.blank })

internal fun usesCompactWorkspace(byProject: Boolean, all: Boolean, other: Boolean): Boolean = byProject && all && !other
internal fun canReorderWorkspace(enabled: Boolean, window: WorkspaceWindow?): Boolean = enabled && (window?.remaining ?: 0) == 0
internal fun workspaceControlsKey(key: String): String = "workspace-controls-$key"
internal fun projectUnitKeys(header: String, rows: List<String>, workspace: String, expanded: Boolean): List<String> =
    listOf(header) + if (expanded) rows + workspaceControlsKey(workspace) else emptyList()

internal fun sessionOpenPath(current: String?, sessions: Map<String, SessionRow>): Set<String> = buildSet {
    var cursor = current
    while (cursor != null && add(cursor)) cursor = sessions[cursor]?.parentSessionId
}

/** Includes an ancestor when a filter matches a child; guards malformed lineage cycles. */
internal fun sessionTreeMatches(
    root: SessionRow,
    childrenByParent: Map<String, List<SessionRow>>,
    predicate: (SessionRow) -> Boolean,
): Boolean {
    val visited = HashSet<String>()
    fun matches(row: SessionRow): Boolean = visited.add(row.sessionId) &&
        (predicate(row) || childrenByParent[row.sessionId].orEmpty().any(::matches))
    return matches(root)
}
