package com.labteto.dshmobile.core.markdown

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Cleans the HTML of a rendered Markdown document before it goes into the preview's locked-down web
 * view.
 *
 * The web view already has JavaScript off, network loads blocked and every navigation refused, so
 * this is the second wall rather than the only one: it keeps a README's layout tags (`<p
 * align="center">`, `<img>`, `<table>`, `<details>`) and removes everything that could run, load or
 * navigate. It is an allow-list: a tag or attribute that is not named here is dropped, and for
 * `<script>`, `<style>`, `<iframe>` and their kin the contents go too.
 *
 * Pictures from the web never reach the web view. A remote `<img>` becomes a small pill showing its
 * alt text, or, when the reader has asked for web images, a reference to a path under
 * [WEB_IMAGE_PATH] that the app fetches itself over HTTPS with a size limit.
 */
@OptIn(ExperimentalEncodingApi::class)
object HtmlSanitizer {
    /** Where an allowed web image is served from, relative to the preview origin. */
    const val WEB_IMAGE_PATH = "/__web/"

    class Result(val html: String, val remoteImages: List<String>)

    // `[\s\S]` where the pattern wants "any character including a newline": `RegexOption.DOT_MATCHES_ALL`
    // is JVM-only, and this object has to compile for iOS. The two spellings match the same text.
    private val TOKEN = Regex(
        """<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?]]>|<![^>]*>|<\?[^>]*>|<(/?)([A-Za-z][A-Za-z0-9-]*)((?:"[^"]*"|'[^']*'|[^'">])*)>""",
    )
    private val ATTRIBUTE = Regex("""([^\s"'<>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")

    /** Elements dropped together with everything inside them. */
    private val DROP_WITH_CONTENT = setOf(
        "script", "style", "iframe", "object", "embed", "noscript", "template", "textarea", "select",
        "svg", "math", "title", "head", "audio", "video", "canvas", "applet", "frameset", "frame",
    )
    private val ALLOWED_TAGS = setOf(
        "a", "abbr", "b", "bdi", "bdo", "blockquote", "br", "caption", "center", "cite", "code", "col", "colgroup",
        "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "h1", "h2", "h3", "h4",
        "h5", "h6", "hr", "i", "img", "input", "ins", "kbd", "li", "mark", "ol", "p", "picture", "pre", "q",
        "rp", "rt", "ruby", "s", "samp", "section", "small", "span", "strike", "strong", "sub", "summary", "sup",
        "table", "tbody", "td", "tfoot", "th", "thead", "tr", "tt", "u", "ul", "var", "wbr", "article", "aside",
        "nav", "header", "footer", "main", "address", "big",
    )
    private val VOID_TAGS = setOf("br", "hr", "img", "input", "wbr", "col")
    private val PLAIN_ATTRIBUTES = setOf(
        "title", "lang", "dir", "colspan", "rowspan", "border", "cellpadding", "cellspacing", "open", "start",
        "checked", "disabled", "cite", "alt", "id",
    )
    private val ALIGN_VALUES = setOf("left", "right", "center", "justify", "middle", "top", "bottom", "baseline")
    private val SIZE = Regex("""\d{1,5}(\.\d+)?(%|px)?""")
    private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")
    private val SCHEME = Regex("""^([a-z][a-z0-9+.-]*):""")
    private val DATA_IMAGE = Regex("""^data:image/(png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=]+$""", RegexOption.IGNORE_CASE)
    private val TEXT_ALIGN = Regex("""text-align\s*:\s*(left|right|center)""", RegexOption.IGNORE_CASE)

    fun clean(html: String, allowWebImages: Boolean, imageFallbackLabel: String = "image"): Result {
        val out = StringBuilder(html.length)
        val remote = LinkedHashSet<String>()
        var position = 0
        while (position < html.length) {
            val match = TOKEN.find(html, position)
            if (match == null) {
                out.append(escapeText(html.substring(position)))
                break
            }
            if (match.range.first > position) out.append(escapeText(html.substring(position, match.range.first)))
            position = match.range.last + 1
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            if (name.isEmpty()) continue // a comment, doctype or processing instruction
            if (name in DROP_WITH_CONTENT) {
                if (!closing) {
                    val end = Regex("""</\s*${Regex.escape(name)}\b[^>]*>""", RegexOption.IGNORE_CASE).find(html, position)
                    position = end?.let { it.range.last + 1 } ?: html.length
                }
                continue
            }
            if (name !in ALLOWED_TAGS) continue
            if (closing) {
                if (name !in VOID_TAGS) out.append("</").append(name).append('>')
                continue
            }
            val attributes = readAttributes(match.groupValues[3])
            when (name) {
                "img" -> out.append(image(attributes, allowWebImages, imageFallbackLabel, remote))
                "input" -> {
                    if (attributes["type"].equals("checkbox", ignoreCase = true)) {
                        out.append("<input type=\"checkbox\" disabled")
                        if ("checked" in attributes) out.append(" checked")
                        out.append('>')
                    }
                }
                else -> out.append(openTag(name, attributes))
            }
        }
        return Result(out.toString(), remote.toList())
    }

    private fun image(attributes: Map<String, String>, allowWebImages: Boolean, fallback: String, remote: MutableSet<String>): String {
        val alt = attributes["alt"].orEmpty().trim()
        val source = decodeEntities(attributes["src"].orEmpty()).filterNot { it.isISOControl() || it.isWhitespace() }
        val safeSource: String? = when {
            source.isEmpty() -> null
            DATA_IMAGE.matches(source) -> source
            source.startsWith("//") || source.lowercase().startsWith("http://") || source.lowercase().startsWith("https://") -> {
                val absolute = if (source.startsWith("//")) "https:$source" else source
                if (absolute.lowercase().startsWith("https://") && absolute.length <= 2048) {
                    remote += absolute
                    if (allowWebImages) WEB_IMAGE_PATH + base64Url(absolute) else null
                } else {
                    remote += absolute
                    null
                }
            }
            else -> {
                val scheme = SCHEME.find(source.lowercase())?.groupValues?.get(1)
                if (scheme == null) attributes["src"]!!.trim() else null
            }
        }
        if (safeSource == null) {
            val label = alt.ifEmpty { fallback }
            return "<span class=\"web-image\">${escapeText(label)}</span>"
        }
        val tag = StringBuilder("<img src=\"").append(escapeAttribute(
            if (safeSource.startsWith(WEB_IMAGE_PATH)) "https://preview.invalid$safeSource" else safeSource,
        )).append('"')
        tag.append(" alt=\"").append(escapeAttribute(alt)).append('"')
        attributes["title"]?.let { tag.append(" title=\"").append(escapeAttribute(it)).append('"') }
        attributes["width"]?.trim()?.takeIf { SIZE.matches(it) }?.let { tag.append(" width=\"").append(it).append('"') }
        attributes["height"]?.trim()?.takeIf { SIZE.matches(it) }?.let { tag.append(" height=\"").append(it).append('"') }
        attributes["align"]?.lowercase()?.takeIf { it in ALIGN_VALUES }?.let { tag.append(" align=\"").append(it).append('"') }
        return tag.append('>').toString()
    }

    private fun openTag(name: String, attributes: Map<String, String>): String {
        val tag = StringBuilder("<").append(name)
        for ((key, raw) in attributes) {
            when {
                key == "href" && name == "a" -> safeLink(raw)?.let { tag.append(" href=\"").append(escapeAttribute(it)).append('"') }
                key == "name" && name == "a" -> tag.append(" name=\"").append(escapeAttribute(raw)).append('"')
                key == "align" || key == "valign" ->
                    raw.lowercase().trim().takeIf { it in ALIGN_VALUES }?.let { tag.append(' ').append(key).append("=\"").append(it).append('"') }
                key == "width" || key == "height" ->
                    raw.trim().takeIf { SIZE.matches(it) }?.let { tag.append(' ').append(key).append("=\"").append(it).append('"') }
                key == "style" && (name == "td" || name == "th") ->
                    TEXT_ALIGN.find(raw)?.let { tag.append(" align=\"").append(it.groupValues[1].lowercase()).append('"') }
                key == "type" && (name == "ol" || name == "ul" || name == "li") ->
                    tag.append(" type=\"").append(escapeAttribute(raw)).append('"')
                key in PLAIN_ATTRIBUTES && key != "alt" -> tag.append(' ').append(key).append("=\"").append(escapeAttribute(raw)).append('"')
            }
        }
        return tag.append('>').toString()
    }

    /** The link as it is allowed to appear, or null when it is dropped. Relative links and `#anchors` are fine. */
    internal fun safeLink(raw: String): String? {
        val decoded = decodeEntities(raw).filterNot { it.isISOControl() || it.isWhitespace() }
        if (decoded.isEmpty()) return null
        val scheme = SCHEME.find(decoded.lowercase())?.groupValues?.get(1)
        if (scheme != null && scheme !in SAFE_LINK_SCHEMES) return null
        return raw.trim()
    }

    private fun readAttributes(source: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (match in ATTRIBUTE.findAll(source)) {
            val name = match.groupValues[1].lowercase()
            if (name.startsWith("on") || name in result) continue
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: ""
            result[name] = value
        }
        return result
    }

    private fun escapeText(text: String): String = text.replace("<", "&lt;")

    private fun escapeAttribute(text: String): String {
        // The value arrives already entity-encoded where it came from Markdown, or as the author wrote it
        // in raw HTML. Only the characters that could end the attribute need touching.
        return text.replace("\"", "&quot;").replace("<", "&lt;")
    }

    private fun base64Url(text: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray())

    /** Decodes the entities a browser would before it reads a URL scheme, so `java&#x73;cript:` is still caught. */
    internal fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "colon" to ":", "tab" to "\t", "newline" to "\n")
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|[A-Za-z]+);?""").replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let { codePoint(it) }
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePoint(it) }
                else -> named[body.lowercase()]
            } ?: m.value
        }
    }

    private fun codePoint(value: Int): String? {
        // Same guard as Character.toChars: anything outside Unicode or inside the surrogate range
        // is refused rather than re-encoded.
        if (value !in 0..0x10FFFF || value in 0xD800..0xDFFF) return null
        return buildString {
            if (value < 0x10000) {
                append(value.toChar())
            } else {
                // Encode as a surrogate pair, the way a four-byte code point must reach UTF-16.
                val bits = value - 0x10000
                append((0xD800 + (bits shr 10)).toChar())
                append((0xDC00 + (bits and 0x3FF)).toChar())
            }
        }
    }
}
