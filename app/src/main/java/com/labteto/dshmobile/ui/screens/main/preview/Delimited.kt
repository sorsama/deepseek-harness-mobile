package com.labteto.dshmobile.ui.screens.main.preview

/** The rows of a delimited file, capped, and how many there were in all. */
internal data class DelimitedTable(
    /** The kept records; the first one is the header row when the file has one. */
    val rows: List<List<String>>,
    /** Every record in the text, kept or not. */
    val totalRecords: Int,
    val delimiter: Char,
) {
    /** The widest kept record, so a ragged file still has a cell grid. */
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
    val truncated: Boolean get() = totalRecords > rows.size
}

private val DELIMITER_CANDIDATES = charArrayOf(',', ';', '\t', '|')

/**
 * The delimiter a file uses: a tab for `.tsv`, otherwise whichever of `,` `;` tab `|` appears most
 * on the first record (outside quotes). A single column falls back to a comma.
 */
internal fun detectDelimiter(text: String, extension: String): Char {
    if (extension == "tsv" || extension == "tab") return '\t'
    val counts = IntArray(DELIMITER_CANDIDATES.size)
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '"') quoted = !quoted
        else if (!quoted && (c == '\n' || c == '\r')) break
        else if (!quoted) {
            val at = DELIMITER_CANDIDATES.indexOf(c)
            if (at >= 0) counts[at]++
        }
        i++
    }
    var best = 0
    for (index in counts.indices) if (counts[index] > counts[best]) best = index
    return if (counts[best] == 0) ',' else DELIMITER_CANDIDATES[best]
}

/**
 * Splits [text] into records and fields the way RFC 4180 does: a field in double quotes may hold the
 * delimiter, line breaks and a doubled `""` for one quote; `\n`, `\r\n` and a lone `\r` end a record
 * outside quotes. A blank line is not a record, and an unterminated quote runs to the end of the text
 * rather than failing, since a half-loaded file ends wherever the page does.
 *
 * Only the first [keep] records are kept; the rest are still scanned, so [DelimitedTable.totalRecords]
 * is the true count and the memory held is bounded.
 */
internal fun parseDelimited(text: String, delimiter: Char, keep: Int = Int.MAX_VALUE): DelimitedTable {
    val rows = ArrayList<List<String>>()
    var total = 0
    var record = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var afterQuote = false
    var fieldStarted = false
    var quotedInRecord = false

    fun endField() {
        record.add(field.toString())
        field.setLength(0)
        afterQuote = false
        fieldStarted = false
    }
    fun endRecord() {
        endField()
        val blank = record.size == 1 && record[0].isEmpty() && !quotedInRecord
        if (!blank) {
            if (rows.size < keep) rows.add(record)
            total++
        }
        record = ArrayList()
        quotedInRecord = false
    }

    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            inQuotes -> {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { field.append('"'); i++ }
                    else { inQuotes = false; afterQuote = true }
                } else field.append(c)
            }
            c == '"' && !fieldStarted && !afterQuote -> { inQuotes = true; fieldStarted = true; quotedInRecord = true }
            c == delimiter -> endField()
            c == '\n' -> endRecord()
            c == '\r' -> { endRecord(); if (i + 1 < n && text[i + 1] == '\n') i++ }
            else -> { fieldStarted = true; field.append(c) }
        }
        i++
    }
    if (inQuotes || fieldStarted || field.isNotEmpty() || record.isNotEmpty()) endRecord()
    return DelimitedTable(rows, total, delimiter)
}

/**
 * A column's width in characters: the widest cell among the first [sample] rows, kept between
 * [minChars] and [maxChars] so one long cell cannot push the rest off the screen.
 */
internal fun columnWidths(
    rows: List<List<String>>,
    columns: Int,
    sample: Int = 200,
    minChars: Int = 4,
    maxChars: Int = 40,
): IntArray {
    val widths = IntArray(columns) { minChars }
    for (row in rows.asSequence().take(sample)) {
        for (column in 0 until minOf(columns, row.size)) {
            val length = row[column].length
            if (length > widths[column]) widths[column] = length
        }
    }
    for (column in widths.indices) widths[column] = widths[column].coerceAtMost(maxChars)
    return widths
}
