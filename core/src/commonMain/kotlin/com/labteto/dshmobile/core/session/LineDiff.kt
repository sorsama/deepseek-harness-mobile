package com.labteto.dshmobile.core.session

data class DiffLine(val kind: Char, val text: String, val oldLine: Int?, val newLine: Int?)

/** Bounded LCS: ordinary edits retain context; huge edits fall back to linear replacement. */
fun lineDiff(before: String?, after: String?): List<DiffLine> {
    val a = before?.takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val b = after?.takeIf { it.isNotEmpty() }?.lines().orEmpty()
    if (a.size.toLong() * b.size > 250_000 || a.size + b.size > 10_000) return a.mapIndexed { i, s -> DiffLine('-', s, i + 1, null) } +
        b.mapIndexed { i, s -> DiffLine('+', s, null, i + 1) }
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lengths[i][j] = if (a[i] == b[j]) 1 + lengths[i + 1][j + 1] else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    val lines = mutableListOf<DiffLine>()
    var i = 0; var j = 0
    while (i < a.size || j < b.size) {
        if (i < a.size && j < b.size && a[i] == b[j]) { lines.add(DiffLine(' ', a[i], i + 1, j + 1)); i++; j++ }
        else if (i < a.size && (j == b.size || lengths[i + 1][j] >= lengths[i][j + 1])) { lines.add(DiffLine('-', a[i], i + 1, null)); i++ }
        else { lines.add(DiffLine('+', b[j], null, j + 1)); j++ }
    }
    return lines
}
