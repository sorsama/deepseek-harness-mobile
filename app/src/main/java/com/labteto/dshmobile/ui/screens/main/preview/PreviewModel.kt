package com.labteto.dshmobile.ui.screens.main.preview

/** Largest file the preview will pull as raw bytes (images, PDFs, media, archives). */
internal const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024

/** Most text the preview will hold in memory, in characters. */
internal const val MAX_PREVIEW_TEXT = 4 * 1024 * 1024

/** How many bytes of a file are read to recognise it, or to dump it as hex. */
internal const val HEAD_BYTES = 16 * 1024

/** Rows a table shows before it says how many more there are. */
internal const val TABLE_ROW_CAP = 1000

/** Longest run of characters drawn as one row of the code view; longer lines continue on the next row. */
internal const val MAX_ROW_CHARS = 2000

/** The names of the view modes a [PreviewKind] offers; the first one is the default. */
internal object PreviewModes {
    const val Rendered = "rendered"
    const val Source = "source"
    const val Table = "table"
    const val Raw = "raw"
    const val Pretty = "pretty"
    const val Tree = "tree"

    fun forKind(kind: PreviewKind): List<String> = when (kind) {
        PreviewKind.Markdown, PreviewKind.Html, PreviewKind.Svg, PreviewKind.Notebook -> listOf(Rendered, if (kind == PreviewKind.Notebook) Raw else Source)
        PreviewKind.Table -> listOf(Table, Raw)
        PreviewKind.Json -> listOf(Pretty, Tree, Raw)
        else -> emptyList()
    }

    /** [requested] if this kind offers it, otherwise the kind's first mode (or null when it has none). */
    fun resolve(kind: PreviewKind, requested: String?): String? {
        val modes = forKind(kind)
        return if (requested != null && requested in modes) requested else modes.firstOrNull()
    }
}

/**
 * Whether the face a file is shown in is the line-numbered code view, which is where wrapping,
 * line numbers and find in file apply.
 */
internal fun usesCodeView(kind: PreviewKind, mode: String?): Boolean {
    val face = PreviewModes.resolve(kind, mode)
    return when (kind) {
        PreviewKind.Code, PreviewKind.Text, PreviewKind.Diff -> true
        PreviewKind.Markdown, PreviewKind.Html, PreviewKind.Svg -> face == PreviewModes.Source
        PreviewKind.Table, PreviewKind.Notebook -> face == PreviewModes.Raw
        PreviewKind.Json -> face != PreviewModes.Tree
        else -> false
    }
}

/**
 * One row of the code view. A line longer than [MAX_ROW_CHARS] is cut into several rows so that no
 * single text layout is ever megabytes wide; the later pieces carry no line number.
 */
internal class DisplayLine(
    /** 1-based line number in the file. */
    val number: Int,
    val continuation: Boolean,
    val text: String,
)

/**
 * The rows to draw for [text]: split on `\n` (a `\r` before it is dropped), tabs widened to four
 * spaces so columns line up, and very long lines cut up.
 */
internal fun buildDisplayLines(text: String, maxRowChars: Int = MAX_ROW_CHARS): List<DisplayLine> {
    val rows = ArrayList<DisplayLine>()
    var number = 0
    var start = 0
    val n = text.length
    while (start <= n) {
        var end = text.indexOf('\n', start)
        if (end < 0) end = n
        number++
        var line = text.substring(start, if (end > start && text[end - 1] == '\r') end - 1 else end)
        if (line.indexOf('\t') >= 0) line = line.replace("\t", "    ")
        if (line.length <= maxRowChars) {
            rows.add(DisplayLine(number, false, line))
        } else {
            var from = 0
            while (from < line.length) {
                val to = minOf(from + maxRowChars, line.length)
                rows.add(DisplayLine(number, from > 0, line.substring(from, to)))
                from = to
            }
        }
        start = end + 1
    }
    // A trailing newline is the end of the last line, not an empty line after it.
    if (rows.size > 1 && n > 0 && text[n - 1] == '\n') rows.removeAt(rows.lastIndex)
    return rows
}

private val XML_PROLOG = Regex("""^\s*<\?xml[^>]*\?>""")
private val DOCTYPE = Regex("""<!DOCTYPE[^>\[]*(\[[^\]]*])?\s*>""", RegexOption.IGNORE_CASE)

/**
 * A page that shows an SVG at a size that fits the screen.
 *
 * Opened as its own document an SVG draws at its own width and height, which on a phone is usually
 * a corner of a large image. Inline in a small HTML page it can be told to shrink to the width
 * available. The prolog and doctype are dropped because HTML parsing turns them into stray
 * comments. The page is still shown in the preview's locked-down web view: no script runs, and
 * nothing outside the page can be fetched.
 */
internal fun svgPage(svg: String): String {
    val body = DOCTYPE.replace(XML_PROLOG.replace(svg.removePrefix(BYTE_ORDER_MARK), ""), "").trim()
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;background:transparent}" +
        "body{display:flex;align-items:center;justify-content:center;min-height:100vh}" +
        "svg{max-width:100%;max-height:100vh;height:auto}</style></head><body>" +
        body + "</body></html>"
}
