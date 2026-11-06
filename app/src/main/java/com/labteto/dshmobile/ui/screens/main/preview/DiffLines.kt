package com.labteto.dshmobile.ui.screens.main.preview

internal enum class DiffLineKind {
    /** `--- a/file` and `+++ b/file`. */
    FileHeader,

    /** `diff --git`, `index`, mode and rename lines, and `\ No newline at end of file`. */
    Meta,

    /** `@@ -1,4 +1,5 @@`. */
    Hunk,
    Added,
    Removed,
    Context,
}

private val HUNK = Regex("""^@@+ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
private val META_PREFIXES = listOf(
    "diff ", "index ", "new file mode", "deleted file mode", "old mode", "new mode", "similarity index",
    "dissimilarity index", "rename from", "rename to", "copy from", "copy to", "Binary files", "GIT binary patch",
    "Index: ", "=====",
)

/**
 * Tells each line of a unified diff what it is.
 *
 * The hunk header's counts decide when the body ends. That matters: a removed line whose own text
 * starts with `-- ` looks exactly like a `--- ` file header, and only the counts can tell them apart.
 * A diff with no usable hunk header (a pasted fragment) falls back to going by the first character.
 */
internal fun classifyDiffLines(lines: List<String>): List<DiffLineKind> {
    val kinds = ArrayList<DiffLineKind>(lines.size)
    var oldLeft = 0
    var newLeft = 0
    for (line in lines) {
        val inBody = oldLeft > 0 || newLeft > 0
        val kind = when {
            inBody && line.startsWith("\\") -> DiffLineKind.Meta
            inBody && line.startsWith("+") -> { newLeft--; DiffLineKind.Added }
            inBody && line.startsWith("-") -> { oldLeft--; DiffLineKind.Removed }
            inBody -> { oldLeft--; newLeft--; DiffLineKind.Context }
            line.startsWith("@@") -> {
                val match = HUNK.find(line)
                oldLeft = match?.groupValues?.get(2)?.ifEmpty { "1" }?.toIntOrNull() ?: match?.let { 1 } ?: 0
                newLeft = match?.groupValues?.get(4)?.ifEmpty { "1" }?.toIntOrNull() ?: match?.let { 1 } ?: 0
                DiffLineKind.Hunk
            }
            line.startsWith("+++ ") || line.startsWith("--- ") -> DiffLineKind.FileHeader
            line.startsWith("\\") -> DiffLineKind.Meta
            META_PREFIXES.any { line.startsWith(it) } -> DiffLineKind.Meta
            line.startsWith("+") -> DiffLineKind.Added
            line.startsWith("-") -> DiffLineKind.Removed
            else -> DiffLineKind.Context
        }
        kinds.add(kind)
        if (oldLeft < 0) oldLeft = 0
        if (newLeft < 0) newLeft = 0
    }
    return kinds
}

/** `(added, removed)` line counts of a classified diff. */
internal fun diffTotals(kinds: List<DiffLineKind>): Pair<Int, Int> =
    kinds.count { it == DiffLineKind.Added } to kinds.count { it == DiffLineKind.Removed }
