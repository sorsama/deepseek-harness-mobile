package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.labteto.dshmobile.connection.WorkStepDisplay
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DiffHunk
import com.labteto.dshmobile.ui.components.DiffTotals
import com.labteto.dshmobile.ui.components.SearchFile
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.ToolCardView

/**
 * When a turn's tool calls sit behind a collapsible group header. The three values are the DSH Web
 * client's own (`stepGrouping`): every turn, only turns that are no longer running, or none.
 */
internal enum class StepGrouping { Collapsed, History, None }

/**
 * What one [WorkStepDisplay] level changes in the transcript.
 *
 * The first four fields are the DSH Web client's `ChatPresentationPolicy`, level for level
 * (`packages/client/ui-chat/src/client/presentation-policy.ts`), so a level means the same thing on
 * the phone as on the desktop. [rowsStartOpen] is the one addition: the web client keeps every row
 * closed until it is tapped, in every level, but this feature's reporter wants the fully expanded
 * level to open the rows themselves, so on a phone that level also opens them.
 */
internal data class WorkStepPolicy(
    val mode: WorkStepDisplay,
    /** Whether a finished turn folds its steps behind one row (see [foldProcessRows]). */
    val foldCompletedTurns: Boolean,
    val stepGrouping: StepGrouping,
    /** Whether a running tool group's header names the step it is on. */
    val liveProcessDetail: Boolean,
    /** Whether a closed thinking row shows the first line of its text. */
    val settledReasoningPreview: Boolean,
    /** Whether tool calls, nested calls and workflow rows open on their own, as a bounded preview. */
    val rowsStartOpen: Boolean,
)

/** The policy for [mode]. One instance per level, so equal levels compare equal by reference too. */
internal fun workStepPolicyFor(mode: WorkStepDisplay): WorkStepPolicy = POLICIES.getValue(mode)

private val POLICIES: Map<WorkStepDisplay, WorkStepPolicy> = mapOf(
    WorkStepDisplay.Compact to WorkStepPolicy(
        mode = WorkStepDisplay.Compact,
        foldCompletedTurns = true,
        stepGrouping = StepGrouping.Collapsed,
        liveProcessDetail = false,
        settledReasoningPreview = false,
        rowsStartOpen = false,
    ),
    WorkStepDisplay.Standard to WorkStepPolicy(
        mode = WorkStepDisplay.Standard,
        foldCompletedTurns = true,
        stepGrouping = StepGrouping.Collapsed,
        liveProcessDetail = true,
        settledReasoningPreview = true,
        rowsStartOpen = false,
    ),
    WorkStepDisplay.Detailed to WorkStepPolicy(
        mode = WorkStepDisplay.Detailed,
        foldCompletedTurns = true,
        stepGrouping = StepGrouping.History,
        liveProcessDetail = true,
        settledReasoningPreview = true,
        rowsStartOpen = false,
    ),
    WorkStepDisplay.Verbose to WorkStepPolicy(
        mode = WorkStepDisplay.Verbose,
        foldCompletedTurns = false,
        stepGrouping = StepGrouping.None,
        liveProcessDetail = false,
        settledReasoningPreview = true,
        rowsStartOpen = true,
    ),
)

/**
 * How open one row is. [Preview] is a row the level opened: its body is cut to the bounds below
 * and says so. [Full] is a row the reader opened by tapping it, which shows everything, as every
 * manual expansion always has.
 */
internal enum class RowDisclosure { Closed, Preview, Full }

/**
 * The reader's own taps, by row. A tap is a temporary override of the level's default: it lives
 * here, never in the stored setting, and a new level gets a new memory, so every row goes back to
 * what the level says.
 *
 * Held above the lazy list because a row that scrolls away is disposed, and a collapsed row coming
 * back open (in the fully expanded level) would undo the reader's tap.
 */
@Stable
internal class RowDisclosureMemory {
    private val taps = mutableStateMapOf<String, RowDisclosure>()

    /** The state of [key]: the reader's tap if there is one, otherwise [default]. */
    fun resolve(key: String, default: RowDisclosure): RowDisclosure = taps[key] ?: default

    /** A tap on the row: anything open closes, a closed row opens in full. */
    fun toggle(key: String, default: RowDisclosure) {
        taps[key] = if (resolve(key, default) == RowDisclosure.Closed) RowDisclosure.Full else RowDisclosure.Closed
    }

    /** "Show all" on a row that was opened as a preview. */
    fun showAll(key: String) {
        taps[key] = RowDisclosure.Full
    }

    /** How many rows the reader has overridden; for tests. */
    val tapCount: Int get() = taps.size
}

/**
 * The level and the taps, as one value a row can read from its [ChatNodeContext].
 *
 * [openFromSeq] bounds [WorkStepPolicy.rowsStartOpen] for a list that is not lazy. The main
 * transcript is a lazy list, so only rows on screen are ever composed and every row may start open.
 * The subagent sheet lays its child's rows out in a plain column, so it only lets the newest ones
 * start open.
 */
internal data class WorkStepPresentation(
    val policy: WorkStepPolicy,
    val taps: RowDisclosureMemory? = null,
    val openFromSeq: Long = Long.MIN_VALUE,
) {
    /** What the level says for the row at [seq], before any tap. */
    fun defaultFor(seq: Long): RowDisclosure =
        if (policy.rowsStartOpen && seq >= openFromSeq) RowDisclosure.Preview else RowDisclosure.Closed

    companion object {
        /** The look the transcript had before the setting existed: the default level, no memory. */
        val Default = WorkStepPresentation(workStepPolicyFor(com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY))
    }
}

/** One row's open state and what its two gestures do. */
internal class RowDisclosureHandle(
    val state: RowDisclosure,
    val toggle: () -> Unit,
    val showAll: () -> Unit,
) {
    val open: Boolean get() = state != RowDisclosure.Closed
    val preview: Boolean get() = state == RowDisclosure.Preview
}

/**
 * The open state of the row [key], at [seq] in the conversation. Without a [WorkStepPresentation.taps]
 * the row keeps its own, which is right wherever rows are short-lived.
 */
@Composable
internal fun rememberRowDisclosure(presentation: WorkStepPresentation, key: String, seq: Long): RowDisclosureHandle {
    val taps = presentation.taps ?: remember(presentation.policy.mode) { RowDisclosureMemory() }
    val default = presentation.defaultFor(seq)
    return RowDisclosureHandle(
        state = taps.resolve(key, default),
        toggle = { taps.toggle(key, default) },
        showAll = { taps.showAll(key) },
    )
}

// ---------------------------------------------------------------------------------------------
// Bounds on a row the level opened
// ---------------------------------------------------------------------------------------------

/**
 * The most characters of one text a block lays out inline. The bound applies to every open row, the
 * ones the level opened and the ones the reader tapped alike: the rest is one tap away in the
 * full-screen viewer ("Show all"), which is lazy by line.
 */
internal const val PREVIEW_MAX_CHARS = 4_000

/**
 * The most lines of one text a block lays out inline. Code does not wrap, so a line is a line of
 * height: 24 of them are about a screen, and a block never takes several screens of the transcript.
 */
internal const val PREVIEW_MAX_LINES = 24

/** The most items of a list (search hits, locations, content blocks, nested calls) a preview lists. */
internal const val PREVIEW_MAX_ITEMS = 8

/** The most rows of the subagent sheet's child transcript that start open. */
internal const val CHILD_OPEN_ROWS = 12

/** The first line of [text] with blank lines skipped, at most [maxChars] long; null if it has none. */
internal fun firstLinePreview(text: String, maxChars: Int = 160): String? {
    var start = 0
    // Bounded scan: a reasoning block can be megabytes and the preview must not touch all of it.
    val limit = minOf(text.length, 4_000)
    while (start < limit) {
        var end = start
        while (end < limit && text[end] != '\n') end++
        val line = text.substring(start, end).trim().replace("**", "")
        if (line.isNotEmpty()) return if (line.length > maxChars) line.take(maxChars) + "…" else line
        start = end + 1
    }
    return null
}

/** [text] cut to [PREVIEW_MAX_LINES] lines and [PREVIEW_MAX_CHARS] characters, ending in an ellipsis when cut. */
internal fun clipPreview(text: String): String {
    val limit = minOf(text.length, PREVIEW_MAX_CHARS)
    var lines = 0
    var index = 0
    while (index < limit) {
        if (text[index] == '\n' && ++lines >= PREVIEW_MAX_LINES) break
        index++
    }
    return if (index >= text.length) text else text.substring(0, index).trimEnd('\n') + "\n…"
}

/** Whether [clipPreview] would cut [text]. */
internal fun exceedsPreview(text: String): Boolean = clipPreview(text).length != text.length

/** A card cut to the preview bounds, and whether anything was cut. */
internal class ClippedCard(val view: ToolCardView, val clipped: Boolean)

/**
 * [card] cut to the preview bounds: text to [clipPreview], lists to [PREVIEW_MAX_ITEMS] items (and
 * search hits and read lines to [PREVIEW_MAX_LINES]). Only the cut is made here; nothing is
 * measured or laid out, and it runs only for a row that is on screen and open.
 */
internal fun clipCardForPreview(card: ToolCardView): ClippedCard {
    var clipped = false
    fun clip(text: String?): String? {
        if (text == null) return null
        val out = clipPreview(text)
        if (out.length != text.length) clipped = true
        return out
    }
    fun <T> take(list: List<T>, max: Int): List<T> {
        if (list.size > max) clipped = true
        return list.take(max)
    }
    val view: ToolCardView = when (card) {
        // The command is bounded too: a model can send a script of tens of thousands of characters.
        is ToolCardView.TerminalCard -> card.copy(title = clip(card.title), output = clip(card.output))
        is ToolCardView.DiffCard -> {
            val cut = card.copy(
                diffs = take(card.diffs, PREVIEW_MAX_ITEMS).map { DiffHunk(it.path, clip(it.oldText), clip(it.newText)) },
            )
            // The footer's "+N -M" counts the whole change, not the part that is drawn.
            if (clipped) cut.copy(totals = DiffTotals.of(card.diffs)) else cut
        }
        is ToolCardView.SearchCard -> {
            val matches = when (val m = card.matches) {
                is SearchMatches.PathList -> SearchMatches.PathList(take(m.paths, PREVIEW_MAX_LINES).map { clip(it).orEmpty() })
                is SearchMatches.FileMatches -> {
                    var budget = PREVIEW_MAX_LINES
                    val files = ArrayList<SearchFile>()
                    for (file in m.files) {
                        if (budget <= 0) { clipped = true; break }
                        val hits = file.matches.take(budget)
                        if (hits.size < file.matches.size) clipped = true
                        budget -= hits.size
                        files += SearchFile(file.path, hits.map { it.copy(line = clip(it.line).orEmpty()) })
                    }
                    SearchMatches.FileMatches(files)
                }
            }
            card.copy(matches = matches)
        }
        is ToolCardView.ReadCard -> card.copy(
            lines = take(card.lines, PREVIEW_MAX_LINES).map { it.copy(text = clip(it.text).orEmpty()) },
        )
        is ToolCardView.WebCard -> card
        // The control tools' cards: lists to the item bound, long text and job output to the text one.
        is com.labteto.dshmobile.ui.components.TodoCard -> card.copy(lines = take(card.lines, PREVIEW_MAX_ITEMS))
        is com.labteto.dshmobile.ui.components.QuestionCard -> card.copy(questions = take(card.questions, PREVIEW_MAX_ITEMS))
        is com.labteto.dshmobile.ui.components.SubagentCard -> card.copy(prompt = clip(card.prompt))
        is com.labteto.dshmobile.ui.components.SkillCard -> card
        is com.labteto.dshmobile.ui.components.ImageCard -> card
        is com.labteto.dshmobile.ui.components.DetailsCard -> card.copy(
            items = take(card.items, PREVIEW_MAX_ITEMS).map { it.copy(description = clip(it.description), code = clip(it.code)) },
        )
        is ToolCardView.GenericCard -> card.copy(
            rawInput = clip(card.rawInput),
            locations = card.locations?.let { take(it, PREVIEW_MAX_ITEMS) },
            content = card.content?.let { blocks ->
                take(blocks, PREVIEW_MAX_ITEMS).map { block ->
                    when (block) {
                        is ContentBlockView.TextBlock -> ContentBlockView.TextBlock(clip(block.text).orEmpty())
                        is ContentBlockView.ReasoningBlock -> ContentBlockView.ReasoningBlock(clip(block.text).orEmpty())
                        is ContentBlockView.ImageBlock -> block
                    }
                }
            },
        )
    }
    return ClippedCard(view, clipped)
}
