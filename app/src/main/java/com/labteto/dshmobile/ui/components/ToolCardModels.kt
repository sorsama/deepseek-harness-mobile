package com.labteto.dshmobile.ui.components

/**
 * View models for the harness tool cards. Pure data — rendering lives in
 * [ToolCards.kt].
 */

/** A rendered content block produced by a tool call. */
sealed interface ContentBlockView {
    /** Plain text output, rendered in mono. */
    data class TextBlock(val text: String) : ContentBlockView

    /** Reasoning trace, rendered muted. */
    data class ReasoningBlock(val text: String) : ContentBlockView

    /**
     * A raster the tool produced — a screenshot, a rendered chart. The intrinsic size travels with
     * the reference so the decoder can downsample without a bounds-only pass.
     */
    data class ImageBlock(
        val attachmentId: String,
        val mediaType: String,
        val bytes: Int,
        val width: Int,
        val height: Int,
        val name: String? = null,
    ) : ContentBlockView
}

/** One diff hunk: [path] plus the removed [oldText] and/or added [newText] lines. */
data class DiffHunk(
    val path: String,
    val oldText: String? = null,
    val newText: String? = null,
)

/** How much a diff changed: lines added, lines removed, files touched. */
data class DiffTotals(val added: Int, val removed: Int, val files: Int) {
    companion object {
        /** The totals of [diffs], line by line the way the card draws them. */
        fun of(diffs: List<DiffHunk>): DiffTotals {
            var added = 0
            var removed = 0
            diffs.forEach { hunk ->
                com.labteto.dshmobile.core.session.lineDiff(hunk.oldText, hunk.newText).forEach {
                    if (it.kind == '+') added++ else if (it.kind == '-') removed++
                }
            }
            return DiffTotals(added, removed, diffs.map { it.path }.distinct().size)
        }
    }
}

/** One file matched by a search, with its matching lines. */
data class SearchFile(
    val path: String,
    val matches: List<SearchMatch>,
)

/** One matching line inside a searched file. */
data class SearchMatch(
    val lineNumber: Int,
    val line: String,
)

/** One numbered line of a file read. */
data class ReadLine(
    val number: Int,
    val text: String,
)

/** A web source citation. */
data class WebSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
)

/** Shape of a search result: grouped file matches or a flat list of paths. */
sealed interface SearchMatches {
    data class FileMatches(val files: List<SearchFile>) : SearchMatches
    data class PathList(val paths: List<String>) : SearchMatches
}

/** Shape of a web tool result: an answer with sources, or a fetched URL. */
sealed interface WebCardKind {
    data class Search(val answer: String? = null, val sources: List<WebSource> = emptyList()) : WebCardKind
    data class Fetch(val url: String, val statusCode: Int? = null) : WebCardKind
}

/** A harness tool card in display form. */
sealed interface ToolCardView {
    /** Generic tool: pretty-printed input JSON, locations, and content blocks. */
    data class GenericCard(
        val title: String? = null,
        val kind: String? = null,
        val rawInput: String? = null,
        val locations: List<String>? = null,
        val content: List<ContentBlockView>? = null,
    ) : ToolCardView

    /** Terminal run: output stream plus exit status. */
    data class TerminalCard(
        val title: String? = null,
        val description: String? = null,
        val cwd: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        val signal: String? = null,
        val running: Boolean? = null,
        /**
         * Started with `run_in_background`: the call only launched a job, so the card has no output
         * and no exit status of its own. [jobId] is the job it started, once the result names it.
         */
        val background: Boolean = false,
        val jobId: String? = null,
        /**
         * The call failed before the command could report an exit (the tool threw, or a persistent
         * shell gave no single status): the card keeps the command and claims no exit code.
         */
        val failed: Boolean = false,
        /**
         * The turn's stop cut the call short. It is not a failure: the card's own dot is amber, like
         * its row's, and like a failed one the card keeps the command and claims no exit code.
         */
        val stopped: Boolean = false,
    ) : ToolCardView {
        /** The call ended without reporting an exit, so there is no output and the card shows the command. */
        val cutShort: Boolean get() = failed || stopped
    }

    /**
     * Diff: per-path hunks with added/removed line counts. [totals] is set when [diffs] was cut for
     * an inline preview, so the counts still describe the whole change.
     */
    data class DiffCard(
        val title: String? = null,
        val diffs: List<DiffHunk>,
        val totals: DiffTotals? = null,
    ) : ToolCardView

    /** Search: file matches or path list, with truncation info. */
    data class SearchCard(
        val title: String? = null,
        val matches: SearchMatches,
        val truncated: Boolean = false,
        val total: Int? = null,
    ) : ToolCardView

    /** File read: numbered lines with a line-number gutter. */
    data class ReadCard(
        val label: String,
        val path: String? = null,
        val lines: List<ReadLine> = emptyList(),
        val totalLines: Int = lines.size,
        val lang: String? = null,
    ) : ToolCardView

    /** Web: search answer with sources, or a fetched URL with status. */
    data class WebCard(val title: String? = null, val kind: WebCardKind) : ToolCardView
}
