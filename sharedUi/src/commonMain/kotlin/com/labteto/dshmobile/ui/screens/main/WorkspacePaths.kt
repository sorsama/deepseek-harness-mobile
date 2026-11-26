package com.labteto.dshmobile.ui.screens.main

/**
 * Path helpers for the Files and Preview panels.
 *
 * `workspaceFiles/list` answers `path` as the directory path RELATIVE to the workspace root, and
 * the EMPTY STRING for the root itself (the harness `workspacePathOf` derives it from the root and
 * target `file:` URLs and says "Empty for the root"). Every request path is resolved by the host
 * against the workspace root, so the panel needs one canonical shape to join entry names onto:
 * `.` for the root, and a path meant to be workspace-relative never carrying a leading `/`.
 * The bug these helpers defend against: joining the raw root listing (`""`) produced `/name`,
 * which is not a workspace file, so the host answered
 * `workspace-file/not-found: no entry at "/name"`.
 */

/** Segments of a host path: `/`-separated on the wire even when a reference arrives with `\`. */
private fun pathSegments(value: String): List<String> =
    value.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

/**
 * Canonical form of one host path for a request, a join, or display: `\` folded to `/`, no empty
 * or `.` segments, no trailing separator, `.` for the workspace root. An absolute path keeps its
 * root (`/x`, `G:/x`, and the `//server/share` a Windows workspace on a network share uses); a
 * workspace-relative path never gets one.
 */
internal fun workspacePath(value: String?): String {
    val raw = value.orEmpty().trim().replace('\\', '/')
    val joined = pathSegments(raw).joinToString("/")
    return when {
        joined.isEmpty() -> when {
            // The `//` of a UNC path is a root marker, not an empty segment to collapse to "/".
            raw.startsWith("//") -> "//"
            raw.startsWith('/') -> "/"
            else -> "."
        }
        raw.startsWith("//") -> "//$joined"
        raw.startsWith('/') -> "/$joined"
        else -> joined
    }
}

/**
 * The workspace path of the entry called [name] inside [directory].
 *
 * A root listing (`directory` empty, `"."`, or the host's `""`) joins as `name`; any other
 * directory joins workspace-relative, so the result never starts with `/` and never contains `//`
 * or a `./` artifact. An absolute directory keeps its shape (`G:/work` + `README.md`).
 */
internal fun workspaceChildPath(directory: String?, name: String): String {
    val base = workspacePath(directory)
    val entry = pathSegments(name).joinToString("/")
    return when {
        entry.isEmpty() -> base
        base == "." -> entry
        base == "/" -> "/$entry"
        base == "//" -> "//$entry"
        else -> "$base/$entry"
    }
}

/**
 * The parent directory shown by the Files "up" action, or `.` when there is none.
 *
 * The root has no parent, so it stays `.`: an empty result would make the panel request an empty
 * `path`, which `workspaceFiles/list` refuses with `gateway/bad-request`.
 */
internal fun workspaceParentPath(directory: String?): String {
    val path = workspacePath(directory)
    if (path == "." || path == "/" || path == "//") return "."
    val cut = path.substringBeforeLast('/', "")
    return when {
        cut.isNotEmpty() -> cut
        // A path whose own entry is directly under an absolute root ("/x", "G:").
        path.startsWith('/') -> "/"
        else -> "."
    }
}
