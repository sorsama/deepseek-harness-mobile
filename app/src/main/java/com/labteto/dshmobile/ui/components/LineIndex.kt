package com.labteto.dshmobile.ui.components

/**
 * A long text read line by line without copying it, for the full-screen output viewer.
 *
 * Laying a multi-megabyte output out as one `Text` freezes the app, and splitting it into a list of
 * strings up front doubles its memory. This keeps the text as it is and records where each display
 * line starts (one `Int` per line), so a lazy list can ask for line N and get a substring only for
 * what is on screen.
 *
 * A display line is a source line, or at most [maxLineChars] characters of one: minified output and
 * one-line logs can be megabytes long, and no single `Text` should be asked to lay that out. The
 * pieces of a long line are consecutive display lines. A trailing newline does not make an empty last
 * line, and a `\r` before a `\n` is not part of the line.
 */
class LineIndex private constructor(
    private val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
    /** How many display lines the text has. */
    val count: Int,
) {
    /** The display line at [index], without its line break. */
    fun line(index: Int): String {
        require(index in 0 until count) { "line $index of $count" }
        return text.substring(starts[index], ends[index])
    }

    /** The offset of the first character of display line [index] in the text. */
    fun startOf(index: Int): Int = starts[index]

    /** Which display line holds the character at [offset], or the last one past the end. */
    fun lineAt(offset: Int): Int {
        if (count == 0) return 0
        var low = 0
        var high = count - 1
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= offset) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return found
    }

    /**
     * The display lines that contain [query], in order, at most [limit] of them. Case-insensitive.
     * The text is scanned once, so a match is found in a 5 MB output as fast as `indexOf` goes; an
     * empty or multi-line query matches nothing.
     */
    fun linesContaining(query: String, limit: Int = DEFAULT_MATCH_LIMIT): IntArray {
        if (query.isEmpty() || query.indexOf('\n') >= 0 || count == 0) return IntArray(0)
        val found = IntArrayBuilder()
        var from = 0
        var lastLine = -1
        while (found.size < limit) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            val line = lineAt(at)
            if (line != lastLine) { found.add(line); lastLine = line }
            // Continue after this line: one hit per line is all that is wanted.
            from = if (line + 1 < count) maxOf(starts[line + 1], at + 1) else text.length
            if (from >= text.length) break
        }
        return found.toArray()
    }

    companion object {
        /** The most display lines a search keeps; a query that hits more is "5,000+", not a freeze. */
        const val DEFAULT_MATCH_LIMIT = 5_000

        /** The longest display line: longer source lines continue on the next one. */
        const val DEFAULT_MAX_LINE_CHARS = 2_000

        fun of(text: String, maxLineChars: Int = DEFAULT_MAX_LINE_CHARS): LineIndex {
            require(maxLineChars >= 2) { "maxLineChars $maxLineChars" }
            val starts = IntArrayBuilder()
            val ends = IntArrayBuilder()
            var lineStart = 0
            var i = 0
            val n = text.length
            fun emit(from: Int, to: Int) {
                // A long source line becomes pieces; a piece never ends between the halves of a surrogate pair.
                var s = from
                if (to - s <= maxLineChars) { starts.add(s); ends.add(to); return }
                while (to - s > maxLineChars) {
                    var e = s + maxLineChars
                    if (text[e - 1].isHighSurrogate()) e--
                    starts.add(s); ends.add(e)
                    s = e
                }
                starts.add(s); ends.add(to)
            }
            while (i < n) {
                if (text[i] == '\n') {
                    val end = if (i > lineStart && text[i - 1] == '\r') i - 1 else i
                    emit(lineStart, end)
                    lineStart = i + 1
                }
                i++
            }
            // The last line has no newline after it; an empty one is the text's trailing break.
            if (lineStart < n) {
                val end = if (text[n - 1] == '\r') n - 1 else n
                emit(lineStart, end)
            }
            return LineIndex(text, starts.toArray(), ends.toArray(), starts.size)
        }
    }
}

/**
 * The character ranges of [line] that contain [needle]: case-insensitive, in order, not overlapping.
 * Empty for an empty needle. The viewer marks them as the search steps through the output.
 */
fun occurrences(line: String, needle: String): List<IntRange> {
    if (needle.isEmpty() || line.length < needle.length) return emptyList()
    val found = ArrayList<IntRange>()
    var from = 0
    while (from <= line.length - needle.length) {
        val at = line.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        found.add(at until at + needle.length)
        from = at + needle.length
    }
    return found
}

/** A growable `IntArray`, so a 200,000-line output is not 200,000 boxed integers. */
private class IntArrayBuilder(initial: Int = 256) {
    private var data = IntArray(initial)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}
