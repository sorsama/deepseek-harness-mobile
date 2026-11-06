package com.labteto.dshmobile.ui.screens.main

/**
 * A job's output as lines, kept to a bounded size.
 *
 * The sheet used to hold the whole log as one string: every batch rebuilt `output + chunks` and the
 * sheet then laid the entire thing out as a single text, up to 256K characters, again. That is
 * what made a chatty job slow. Lines are appended in place instead, so a batch costs what it adds,
 * and the list that draws them only lays out the rows on screen.
 *
 * [lines] is whatever mutable list the caller wants to observe; a Compose state list works, and so
 * does a plain one in a test. The last line is "open" while the text so far has not ended in a
 * newline: the next chunk continues it rather than starting a new one, because a chunk boundary
 * can fall anywhere, including in the middle of a word.
 */
internal class JobLog(
    private val lines: MutableList<String>,
    private val maxChars: Int,
) {
    /** True once any output has been cut from the head to stay inside [maxChars]. */
    var dropped: Boolean = false
        private set

    /** How many lines have been cut from the head, so a line's identity outlives the cut. */
    var removedLines: Long = 0
        private set

    private var chars = 0
    private var lastIsOpen = false

    val size: Int get() = lines.size

    /** Add a chunk of output. A chunk may hold part of a line, several lines, or end mid-line. */
    fun append(text: String) {
        if (text.isEmpty()) return
        var rest = text
        while (rest.isNotEmpty()) {
            val newline = rest.indexOf('\n')
            val piece = if (newline < 0) rest else rest.substring(0, newline)
            if (lastIsOpen) {
                lines[lines.lastIndex] = lines[lines.lastIndex] + piece
            } else {
                lines.add(piece)
            }
            chars += piece.length
            if (newline < 0) {
                lastIsOpen = true
                break
            }
            // A completed line sheds the carriage return a CRLF host leaves on it.
            if (lines[lines.lastIndex].endsWith('\r')) {
                lines[lines.lastIndex] = lines[lines.lastIndex].dropLast(1)
                chars -= 1
            }
            chars += 1
            lastIsOpen = false
            rest = rest.substring(newline + 1)
        }
        trim()
    }

    /** Cut the oldest lines until the log fits, always keeping the newest line, however long it is. */
    private fun trim() {
        if (chars <= maxChars) return
        var cut = 0
        while (chars > maxChars && cut < lines.size - 1) {
            chars -= lines[cut].length + 1
            cut++
        }
        if (cut > 0) {
            lines.subList(0, cut).clear()
            removedLines += cut
            dropped = true
        }
        // One line longer than the whole budget (a progress bar redrawn in place, say): keep its tail.
        val last = lines.lastIndex
        if (last >= 0 && chars > maxChars && lines[last].length > maxChars) {
            val kept = lines[last].takeLast(maxChars)
            chars -= lines[last].length - kept.length
            lines[last] = kept
            dropped = true
        }
    }
}
