package com.labteto.dshmobile.core.markdown

/**
 * Turns the little HTML an agent's reply sometimes carries into the Markdown the chat renderer
 * already understands, so `<b>done</b>` reads as **done** instead of showing its tags.
 *
 * Handled: `<br>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<s>`/`<del>`, `<code>`/`<kbd>`, `<a href>`,
 * `<img alt>`, `<details>`/`<summary>`, `<p>`/`<div>`, headings, `<hr>`, list items and comments.
 * `<sub>`, `<sup>`, `<span>`, `<center>`, `<u>`, `<mark>` and `<small>` lose their tags and keep
 * their text.
 *
 * Code is left alone: fenced blocks and `` `code spans` `` are never touched. Nothing is dropped
 * that cannot be understood, and a tag that is cut off, as in a reply that is still streaming, is
 * shown as typed until its end arrives.
 */
fun inlineHtmlToMarkdown(source: String): String {
    if ('<' !in source) return source
    val converter = Converter()
    val out = StringBuilder(source.length)
    var inFence = false
    source.split("\n").forEachIndexed { index, line ->
        if (index > 0) out.append('\n')
        if (!converter.inComment && line.trimStart().startsWith("```")) inFence = !inFence
        if (inFence || line.trimStart().startsWith("```")) out.append(line) else out.append(converter.line(line))
    }
    return out.toString()
}

private val TAG = Regex("""<(/?)([A-Za-z][A-Za-z0-9]*)((?:"[^"]*"|'[^']*'|[^'">])*?)/?>""")
private val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val ALT = Regex("""alt\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val SRC = Regex("""src\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

/** Tags that only wrap text: their tags go and their text stays. */
private val TRANSPARENT = setOf(
    "sub", "sup", "span", "center", "u", "mark", "small", "big", "font", "abbr", "cite", "var", "samp", "tt",
    "section", "article", "main", "nav", "header", "footer", "aside", "figure", "figcaption", "picture",
)

private class Converter {
    var inComment = false
    /** What closes each open link, innermost last; a link that was dropped leaves an empty entry. */
    private val links = ArrayDeque<String>()

    fun line(line: String): String {
        val visible = stripComments(line)
        if ('<' !in visible) return visible
        val out = StringBuilder()
        var position = 0
        while (position < visible.length) {
            val tick = visible.indexOf('`', position)
            val end = if (tick == -1) visible.length else tick
            out.append(tags(visible.substring(position, end)))
            if (tick == -1) break
            val close = visible.indexOf('`', tick + 1)
            if (close == -1) { out.append(visible, tick, visible.length); break }
            out.append(visible, tick, close + 1)
            position = close + 1
        }
        return out.toString()
    }

    private fun stripComments(line: String): String {
        if (!inComment && "<!--" !in line) return line
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (inComment) {
                val end = line.indexOf("-->", i)
                if (end == -1) return out.toString()
                inComment = false
                i = end + 3
            } else {
                val start = line.indexOf("<!--", i)
                if (start == -1) { out.append(line, i, line.length); break }
                out.append(line, i, start)
                inComment = true
                i = start + 4
            }
        }
        return out.toString()
    }

    private fun tags(text: String): String {
        if ('<' !in text) return text
        return TAG.replace(text) { match ->
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val attributes = match.groupValues[3]
            when (name) {
                "br" -> "\n"
                "b", "strong" -> "**"
                "i", "em" -> "*"
                "s", "del", "strike" -> "~~"
                "code", "kbd" -> "`"
                "p", "div", "details", "ul", "ol" -> "\n\n"
                "summary" -> if (closing) "**\n\n" else "**"
                "h1", "h2", "h3", "h4", "h5", "h6" -> if (closing) "**\n\n" else "\n\n**"
                "hr" -> "\n\n---\n\n"
                "li" -> if (closing) "" else "\n- "
                "a" -> if (closing) links.removeLastOrNull().orEmpty() else openLink(attributes)
                "img" -> image(attributes)
                in TRANSPARENT -> ""
                else -> match.value // not one of ours: shown as typed
            }
        }
    }

    private fun openLink(attributes: String): String {
        val href = attribute(HREF, attributes)
        // A `)` or a space in the address would end the Markdown link early, so they are encoded.
        return if (href != null && usable(href)) { links.addLast("](${href.trim().replace(")", "%29").replace(" ", "%20")})"); "[" } else { links.addLast(""); "" }
    }

    private fun image(attributes: String): String {
        val alt = attribute(ALT, attributes).orEmpty().trim()
        val src = attribute(SRC, attributes).orEmpty()
        return when {
            alt.isBlank() -> ""
            safeHttpUrl(src) != null -> "[$alt]($src)"
            else -> alt
        }
    }

    private fun attribute(pattern: Regex, attributes: String): String? =
        pattern.find(attributes)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.takeIf { it.isNotBlank() }

    /** An http(s) link, or a relative one. Anything with another scheme is shown as plain text. */
    private fun usable(href: String): Boolean = safeHttpUrl(href) != null || !href.contains(':')
}
