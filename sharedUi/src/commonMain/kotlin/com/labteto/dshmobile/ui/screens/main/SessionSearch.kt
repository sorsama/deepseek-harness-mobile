package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.WorkspaceRow
import java.util.Locale

/** One row of the drawer's search result list. */
internal data class SearchHit(
    val session: SessionRow,
    /** The workspace title, or the working directory's folder when the session belongs to none. */
    val workspaceLabel: String,
    /** The matching excerpt from `session.search`, when the host supplied one. */
    val snippet: String?,
)

/** Bounded, deduplicated results plus the bit that says the query was too broad to show it all. */
internal data class SearchResultSet(
    val items: List<SearchHit>,
    val hasMore: Boolean,
)

/**
 * How many rows the drawer will show before asking for a narrower query.
 *
 * The harness web sidebar caps its merged list the same way. Past a screenful the list stops being
 * an answer and starts being the thing you were trying to search.
 */
internal const val SEARCH_RESULT_LIMIT = 30

/**
 * Merge immediate title/workspace matches with the host's ranked content matches.
 *
 * This is the half of search the app was missing. `session.search` is full-text over *message
 * content*, and it is opt-in: the shipped harness configures its query index `openAt: never`, so
 * the call fails outright and the drawer — whose only source of results was that call — appeared to
 * do nothing at all. The harness's own sidebar has always matched titles and workspace names
 * locally and treated content hits as an extra, which is what this reproduces.
 *
 * Local rows lead, newest first; content-only rows follow in the host's ranking. A session matched
 * both ways keeps its local position and gains the host's snippet.
 *
 * Two exclusions carry over from the reference. Blank sessions never match: their displayed title
 * is a localized placeholder, so matching on it would make results depend on the UI language.
 * Archived sessions never match either — they are out of the list the query is asking about.
 *
 * A free function so the ordering and dedup rules can be tested without a device, in the same
 * spirit as `sameSubnet` in the discovery engine.
 */
internal fun deriveSearchResults(
    sessions: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    archivedIds: Set<String>,
    query: String,
    contentHits: List<Pair<String, String>>,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResultSet {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return SearchResultSet(emptyList(), hasMore = false)

    val workspaceTitleOf = workspaces
        .flatMap { ws -> ws.sessionIds.map { it to ws.title.ifBlank { basename(ws.path) } } }
        .toMap()
    val sessionsById = sessions.associateBy { it.sessionId }
    fun labelOf(session: SessionRow): String =
        workspaceTitleOf[session.sessionId] ?: session.cwd?.let { basename(it) } ?: ""

    fun eligible(session: SessionRow): Boolean =
        !session.blank && session.sessionId !in archivedIds

    val local = sessions
        .filter { eligible(it) }
        .filter {
            sessionTitle(it).lowercase(Locale.ROOT).contains(q) ||
                labelOf(it).lowercase(Locale.ROOT).contains(q)
        }
        .sortedByDescending(SessionRow::updatedAt)

    val snippetOf = contentHits.toMap()
    val ordered = LinkedHashMap<String, SessionRow>()
    local.forEach { ordered.putIfAbsent(it.sessionId, it) }
    contentHits.forEach { (sessionId, _) ->
        sessionsById[sessionId]?.takeIf { eligible(it) }?.let { ordered.putIfAbsent(sessionId, it) }
    }

    val rows = ordered.values.map { session ->
        SearchHit(
            session = session,
            workspaceLabel = labelOf(session),
            snippet = snippetOf[session.sessionId]?.takeIf { it.isNotBlank() },
        )
    }
    return SearchResultSet(items = rows.take(limit), hasMore = rows.size > limit)
}
