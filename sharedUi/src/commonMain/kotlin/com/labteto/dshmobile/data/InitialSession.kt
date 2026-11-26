package com.labteto.dshmobile.data

/**
 * Which session the app should land on after connecting, when nothing is open yet.
 *
 * Pure so the fallback chain can be reasoned about — and tested — without a live connection: the
 * session you were last in, else the most recently active session anywhere, else a blank one.
 *
 * Ranking is by **session** `updatedAt`, deliberately. `workspace.updatedAt` stamps the
 * registration record — a rename, a session being added — and `workspace.list` order is the manual
 * display order; neither tracks conversation activity, which is what "most recent" means to
 * someone reopening the app. The workspace stamp only breaks ties.
 *
 * @return the session to open, or null to leave the empty hero on screen.
 */
internal fun pickInitialSession(
    sessions: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    archived: Set<String>,
    lastSessionId: String?,
): String? {
    val byId = sessions.associateBy { it.sessionId }

    // Subagent transcripts appear in the session list but are records of another agent's work;
    // landing in one would be baffling, and archived sessions were explicitly put away.
    fun eligible(row: SessionRow?): Boolean =
        row != null && row.sessionId !in archived && row.origin != "subagent"

    if (lastSessionId != null && eligible(byId[lastSessionId])) return lastSessionId

    // Ranking is global rather than workspace-first: a session that belongs to no workspace is
    // still work the user did, and picking the newest session in some workspace over a much more
    // recent ungrouped one would feel arbitrary.
    val workspaceStamp = workspaces
        .flatMap { workspace -> workspace.sessionIds.map { it to workspace.updatedAtEpoch } }
        .toMap()
    val byRecency = compareBy<SessionRow>({ it.updatedAt }, { workspaceStamp[it.sessionId] ?: 0L })
    // A blank session is the last resort: the harness treats one as reusable scratch space rather
    // than work worth resuming.
    sessions.filter { eligible(it) && !it.blank }.maxWithOrNull(byRecency)?.let { return it.sessionId }
    return sessions.filter { eligible(it) }.maxWithOrNull(byRecency)?.sessionId
}
