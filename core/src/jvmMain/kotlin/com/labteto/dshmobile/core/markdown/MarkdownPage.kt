package com.labteto.dshmobile.core.markdown

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** The colours of a Markdown page, as CSS values (`#rrggbb` or `rgba(...)`). */
class MarkdownPageTheme(
    val dark: Boolean,
    val background: String,
    val text: String,
    val textSecondary: String,
    val link: String,
    val border: String,
    val codeBackground: String,
    val quoteBar: String,
    val pillBackground: String,
    val pillText: String,
)

/** What the page needs besides the Markdown itself. */
class MarkdownPageOptions(
    /** Whether the reader has asked for pictures from the web to load. */
    val showWebImages: Boolean = false,
    /** Front matter as label to value pairs, drawn as a closed `<details>` at the top. */
    val frontmatter: List<Pair<String, String>> = emptyList(),
    val frontmatterLabel: String = "Front matter",
    /** Shown in place of a blocked picture that has no alt text. */
    val imageFallbackLabel: String = "image",
)

class MarkdownPage(
    /** A complete HTML document for the web view. */
    val html: String,
    /** The https pictures the document mentions, in order, without repeats. */
    val remoteImages: List<String>,
)

/**
 * Turns a Markdown file into a themed HTML page the way GitHub shows it: tables, strikethrough,
 * task lists, autolinks and heading anchors, with the HTML a README mixes in (`<p align="center">`,
 * `<img>`, `<details>`) kept after [HtmlSanitizer] has removed whatever could run or load.
 */
object MarkdownPages {
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create(),
        TaskListItemsExtension.create(),
        HeadingAnchorExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(false).sanitizeUrls(false).build()

    fun render(markdown: String, theme: MarkdownPageTheme, options: MarkdownPageOptions = MarkdownPageOptions()): MarkdownPage {
        val body = renderer.render(parser.parse(markdown))
        val cleaned = HtmlSanitizer.clean(body, options.showWebImages, options.imageFallbackLabel)
        val frontmatter = if (options.frontmatter.isEmpty()) "" else frontmatterHtml(options)
        // Pictures can only come from the preview origin (the workspace, or a web image the app has
        // fetched itself) or be inlined; the page has no other way to reach the network.
        val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"${if (theme.dark) "dark" else "light"}\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https://preview.invalid data:; style-src 'unsafe-inline'\">" +
            "<style>${css(theme)}</style></head><body><main class=\"markdown\">" +
            frontmatter + cleaned.html + "</main></body></html>"
        return MarkdownPage(html, cleaned.remoteImages)
    }

    private fun frontmatterHtml(options: MarkdownPageOptions): String {
        val rows = options.frontmatter.joinToString("") { (key, value) ->
            "<tr><th>${escape(key)}</th><td>${escape(value).replace("\n", "<br>")}</td></tr>"
        }
        return "<details class=\"frontmatter\"><summary>${escape(options.frontmatterLabel)} · ${options.frontmatter.size}</summary>" +
            "<table>$rows</table></details>"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun css(t: MarkdownPageTheme): String = """
        :root{color-scheme:${if (t.dark) "dark" else "light"}}
        html,body{margin:0;padding:0;background:${t.background};color:${t.text}}
        body{font:16px/1.55 -apple-system,Roboto,'Segoe UI',Helvetica,Arial,sans-serif;word-wrap:break-word;overflow-wrap:anywhere}
        .markdown{padding:16px 16px 28px;max-width:860px;margin:0 auto}
        .markdown>*:first-child{margin-top:0}
        h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.4em 0 .6em;font-weight:650}
        h1{font-size:1.75em;padding-bottom:.3em;border-bottom:1px solid ${t.border}}
        h2{font-size:1.4em;padding-bottom:.25em;border-bottom:1px solid ${t.border}}
        h3{font-size:1.2em}h4{font-size:1.05em}h5,h6{font-size:1em;color:${t.textSecondary}}
        p,ul,ol,dl,table,pre,blockquote,details{margin:0 0 1em}
        a{color:${t.link};text-decoration:none}
        a:active{text-decoration:underline}
        img{max-width:100%;height:auto;vertical-align:middle}
        ul,ol{padding-left:1.6em}
        li+li{margin-top:.25em}
        li>input[type=checkbox]{margin:0 .5em 0 -1.2em;vertical-align:middle}
        code,kbd,tt,samp{font:.88em ui-monospace,SFMono-Regular,Menlo,Consolas,'Roboto Mono',monospace;background:${t.codeBackground};padding:.15em .4em;border-radius:6px}
        kbd{border:1px solid ${t.border};border-bottom-width:2px}
        pre{background:${t.codeBackground};padding:12px 14px;border-radius:10px;overflow-x:auto;line-height:1.45}
        pre code{background:none;padding:0;font-size:.85em;white-space:pre}
        blockquote{margin-left:0;padding:0 1em;color:${t.textSecondary};border-left:4px solid ${t.quoteBar}}
        hr{border:0;border-top:1px solid ${t.border};margin:1.6em 0}
        table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
        th,td{border:1px solid ${t.border};padding:6px 8px;text-align:left;vertical-align:top;font-size:.92em}
        th{background:${t.codeBackground};font-weight:650}
        details{border:1px solid ${t.border};border-radius:10px;padding:.2em .8em}
        summary{cursor:pointer;font-weight:600;padding:.4em 0}
        .web-image{display:inline-block;max-width:100%;margin:2px 3px;padding:1px 9px;border-radius:999px;background:${t.pillBackground};color:${t.pillText};font-size:.78em;line-height:1.6;vertical-align:middle}
        a .web-image{color:${t.link}}
        center{text-align:center}
    """.trimIndent().replace("\n", "")
}
