package com.labteto.dshmobile.ui.screens.main

/**
 * Whether a file-explorer search [query] keeps [text]: blank keeps everything, otherwise a
 * case-insensitive substring match on the trimmed query.
 */
internal fun matchesFileQuery(text: String, query: String): Boolean =
    query.isBlank() || text.contains(query.trim(), ignoreCase = true)

/**
 * The session's changed files that survive the explorer's filter.
 *
 * The "Changed this session" card lists whole workspace paths, so it matches on the path — typing
 * `todos` finds `src/todos.js` — where the folder listing below matches on the entry name alone.
 * Order is preserved.
 */
internal fun filterChangedFiles(paths: List<String>, query: String): List<String> =
    if (query.isBlank()) paths else paths.filter { matchesFileQuery(it, query) }
