package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Collapsible harness tool card: a 24dp [DisclosureRow] header plus a r12
 * code-block card body (codeBlockBg, borderL1) rendered when [expanded].
 */
@Composable
fun ToolCard(
    view: ToolCardView,
    expanded: Boolean,
    onToggle: () -> Unit,
    /**
     * Header overrides from the transcript's own tool-row model, which knows the tool name and the
     * session's working directory and can therefore say `Read · app\build.gradle.kts` where the
     * card alone would only manage a presenter title and a block count.
     */
    titleOverride: String? = null,
    summaryOverride: String? = null,
    /**
     * Leading glyph from the transcript's tool-row model, which classifies by *tool name* where the
     * card can only classify by the presenter shape it happened to receive.
     */
    iconOverride: ImageVector? = null,
    /** Terminal state from the call's own result; null derives the running bit from the card. */
    state: DisclosureState? = null,
    header: (@Composable () -> Unit)? = null,
    /** Opens the call's hold menu from the default header; a custom [header] wires its own. */
    onHold: (() -> Unit)? = null,
    holdLabel: String = "",
) {
    if (header != null) {
        header()
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) { ToolCardBody(view) }
        return
    }
    DisclosureRow(
        title = titleOverride ?: view.displayTitle(),
        summary = summaryOverride ?: view.summary(),
        icon = iconOverride ?: view.icon(),
        state = state ?: if (view.isRunning()) DisclosureState.Running else DisclosureState.Idle,
        expanded = expanded,
        onToggle = onToggle,
        onHold = onHold,
        holdLabel = holdLabel,
    ) {
        ToolCardBody(view)
    }
}

// ---- Header helpers ---------------------------------------------------------

@Composable
private fun ToolCardView.displayTitle(): String = when (this) {
    is ToolCardView.GenericCard -> title ?: kind ?: stringResource(R.string.toolcard_title_tool)
    is ToolCardView.TerminalCard -> title ?: stringResource(R.string.toolcard_title_terminal)
    is ToolCardView.DiffCard -> title ?: stringResource(R.string.toolcard_title_diff)
    is ToolCardView.SearchCard -> title ?: stringResource(R.string.toolcard_title_search)
    is ToolCardView.ReadCard -> label
    is ToolCardView.WebCard -> title ?: stringResource(R.string.toolcard_title_web)
}

@Composable
private fun ToolCardView.summary(): String? = when (this) {
    is ToolCardView.GenericCard ->
        content?.size?.let { pluralStringResource(R.plurals.toolcard_blocks, it, it) }
            ?: locations?.size?.let { pluralStringResource(R.plurals.toolcard_locations, it, it) }
    is ToolCardView.TerminalCard -> terminalStatus() ?: description ?: cwd
    is ToolCardView.DiffCard -> {
        val (added, removed, files) = diffStats(diffs)
        "+$added -$removed · " + pluralStringResource(R.plurals.toolcard_files, files, files)
    }
    is ToolCardView.SearchCard -> (total ?: resultCount()).let { pluralStringResource(R.plurals.toolcard_results, it, it) }
    is ToolCardView.ReadCard -> pluralStringResource(R.plurals.toolcard_lines, totalLines, totalLines)
    is ToolCardView.WebCard -> when (val kind = kind) {
        is WebCardKind.Search -> kind.answer?.take(64)
            ?: kind.sources.size.let { pluralStringResource(R.plurals.toolcard_sources, it, it) }
        is WebCardKind.Fetch -> kind.statusCode?.let { "HTTP $it" } ?: kind.url
    }
}

/** "running", "killed by SIGTERM" or "exit 1" for a terminal card; null when it has none of those. */
@Composable
private fun ToolCardView.TerminalCard.terminalStatus(): String? {
    val signal = signal
    val exitCode = exitCode
    return when {
        running == true -> stringResource(R.string.toolcard_running)
        signal != null -> stringResource(R.string.toolcard_killed_by, signal)
        exitCode != null -> stringResource(R.string.toolcard_exit_code, exitCode)
        else -> null
    }
}

private fun ToolCardView.icon(): ImageVector = when (this) {
    is ToolCardView.GenericCard -> FeatherIcons.Tool
    is ToolCardView.TerminalCard -> FeatherIcons.Terminal
    is ToolCardView.DiffCard -> FeatherIcons.Code
    is ToolCardView.SearchCard -> FeatherIcons.Search
    is ToolCardView.ReadCard -> FeatherIcons.FileText
    is ToolCardView.WebCard -> FeatherIcons.Globe
}

private fun ToolCardView.isRunning(): Boolean = (this as? ToolCardView.TerminalCard)?.running == true

private fun ToolCardView.SearchCard.resultCount(): Int = when (val matches = matches) {
    is SearchMatches.FileMatches -> matches.files.sumOf { it.matches.size }
    is SearchMatches.PathList -> matches.paths.size
}

/** Counts added lines, removed lines and distinct file paths across hunks. */
private fun diffStats(diffs: List<DiffHunk>): Triple<Int, Int, Int> {
    var added = 0
    var removed = 0
    diffs.forEach { hunk ->
        val lines = com.labteto.dshmobile.core.session.lineDiff(hunk.oldText, hunk.newText)
        added += lines.count { it.kind == '+' }
        removed += lines.count { it.kind == '-' }
    }
    return Triple(added, removed, diffs.map { it.path }.distinct().size)
}

// ---- Bodies -----------------------------------------------------------------

@Composable
private fun ToolCardBody(view: ToolCardView) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (view) {
            is ToolCardView.GenericCard -> GenericBody(view)
            is ToolCardView.TerminalCard -> TerminalBody(view)
            is ToolCardView.DiffCard -> DiffBody(view)
            is ToolCardView.SearchCard -> SearchBody(view)
            is ToolCardView.ReadCard -> ReadBody(view)
            is ToolCardView.WebCard -> WebBody(view)
        }
    }
}

@Composable
private fun TerminalBody(card: ToolCardView.TerminalCard) {
    val colors = DsTheme.colors
    val state = when {
        card.running == true -> StateDotState.Running
        card.signal != null || (card.exitCode != null && card.exitCode != 0) -> StateDotState.Error
        card.exitCode == 0 -> StateDotState.Done
        else -> StateDotState.Idle
    }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StateDot(state, size = 8.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.description?.let {
                Text(it, style = DsType.mdSmall, color = colors.labelSecondary)
            }
            card.cwd?.let {
                Text(
                    it,
                    style = DsType.caption11.copy(fontFamily = DsType.codeFont),
                    color = colors.labelCaption,
                )
            }
            card.output?.let {
                Text(
                    it,
                    style = DsType.mdCode,
                    color = colors.labelPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The status line under the output is a finished call's: while it runs, the header
            // already says so.
            val status = if (card.running == true) null else card.terminalStatus()
            if (status != null) {
                val ok = card.exitCode == 0 && card.signal == null
                Text(
                    status,
                    style = DsType.caption11Strong,
                    color = if (ok) colors.success else colors.error,
                    modifier = Modifier
                        .clip(DsShapes.pillFull)
                        .background(if (ok) colors.successTertiary else colors.errorTertiary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DiffBody(card: ToolCardView.DiffCard) {
    val colors = DsTheme.colors
    val (added, removed, files) = diffStats(card.diffs)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        card.diffs.forEach { hunk ->
            Text(
                hunk.path,
                style = DsType.small13Strong,
                color = colors.labelSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            val lines = remember(hunk) { com.labteto.dshmobile.core.session.lineDiff(hunk.oldText, hunk.newText) }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column {
                    lines.forEach { line ->
                        Row {
                            // Two cells, one line each: both numbers in one narrow text wrapped onto
                            // a second line once either reached four digits, and pushed the code
                            // under it out of step with its own gutter.
                            DiffGutterCell(line.oldLine)
                            DiffGutterCell(line.newLine)
                            DiffLine(line.kind.toString(), line.text, when (line.kind) {
                                '+' -> colors.success
                                '-' -> colors.error
                                else -> colors.labelSecondary
                            })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+$added", style = DsType.caption11Strong, color = colors.success)
            Spacer(Modifier.width(4.dp))
            Text("-$removed", style = DsType.caption11Strong, color = colors.error)
            Spacer(Modifier.width(4.dp))
            Text(
                "· " + pluralStringResource(R.plurals.toolcard_files, files, files),
                style = DsType.caption11,
                color = colors.labelCaption,
            )
        }
    }
}

/** One line number of a diff's gutter: a fixed width, right-aligned, and never wrapped. */
@Composable
private fun DiffGutterCell(number: Int?) {
    Text(
        number?.toString().orEmpty(),
        style = DsType.caption11,
        color = DsTheme.colors.labelCaption,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(DiffGutterCellWidth).padding(end = 4.dp),
    )
}

/** Room for five digits at the caption size, so a very long file still fits its cell. */
private val DiffGutterCellWidth = 36.dp

@Composable
private fun DiffLine(prefix: String, line: String, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(prefix, style = DsType.mdCode, color = color, modifier = Modifier.width(18.dp))
        Text(line, style = DsType.mdCode, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SearchBody(card: ToolCardView.SearchCard) {
    val colors = DsTheme.colors
    val shown = when (val matches = card.matches) {
        is SearchMatches.FileMatches -> matches.files.sumOf { it.matches.size }
        is SearchMatches.PathList -> matches.paths.size
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val matches = card.matches) {
            is SearchMatches.FileMatches -> matches.files.forEach { file ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        file.path,
                        style = DsType.small13Strong.copy(fontFamily = DsType.codeFont),
                        color = colors.labelSecondary,
                    )
                    file.matches.forEach { match ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${match.lineNumber}",
                                style = DsType.mdCode.copy(color = colors.labelCaption),
                                color = colors.labelCaption,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(40.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                match.line,
                                style = DsType.mdCode,
                                color = colors.labelTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            is SearchMatches.PathList -> matches.paths.forEach { path ->
                Text(
                    path,
                    style = DsType.mdCode,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (card.truncated) {
            Text(
                stringResource(R.string.toolcard_showing_of, shown, card.total ?: shown),
                style = DsType.caption11,
                color = colors.labelCaption,
            )
        }
    }
}

@Composable
private fun ReadBody(card: ToolCardView.ReadCard) {
    val colors = DsTheme.colors
    if (card.lines.isEmpty()) {
        Text(
            "(" + pluralStringResource(R.plurals.toolcard_lines, card.totalLines, card.totalLines) + ")",
            style = DsType.caption11,
            color = colors.labelCaption,
        )
        return
    }
    val lineStyle = DsType.mdCode
    // One row per source line, number and text together. They were two independent columns, so the
    // first line long enough to wrap made every row under it taller than its number, and line N's
    // number ended up beside line N+k's text.
    Column(Modifier.fillMaxWidth()) {
        card.lines.forEach {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${it.number}",
                    style = lineStyle,
                    color = colors.labelCaption,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(48.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(it.text, style = lineStyle, color = colors.labelPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WebBody(card: ToolCardView.WebCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val kind = card.kind) {
            is WebCardKind.Search -> {
                kind.answer?.let {
                    Text(it, style = DsType.mdSmall, color = colors.labelPrimary)
                }
                kind.sources.take(8).forEachIndexed { index, source ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(18.dp).clip(DsShapes.chip).background(colors.citation),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", style = DsType.caption11Strong, color = colors.accent)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            source.title?.let {
                                Text(
                                    it,
                                    style = DsType.small13Strong,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                source.url,
                                style = DsType.caption11,
                                color = colors.labelCaption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            source.snippet?.let {
                                Text(
                                    it,
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (kind.sources.size > 8) {
                    Text(
                        stringResource(R.string.toolcard_more, kind.sources.size - 8),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
            }
            is WebCardKind.Fetch -> {
                Text(
                    kind.url,
                    style = DsType.mdCode,
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                kind.statusCode?.let { code ->
                    val ok = code in 200..299
                    Text(
                        "HTTP $code",
                        style = DsType.caption11Strong,
                        color = if (ok) colors.success else colors.error,
                        modifier = Modifier
                            .clip(DsShapes.pillFull)
                            .background(if (ok) colors.successTertiary else colors.errorTertiary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericBody(card: ToolCardView.GenericCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.rawInput?.let { raw ->
            SectionLabel(stringResource(R.string.toolcard_section_in))
            Text(
                prettyJson(raw),
                style = DsType.mdCode,
                color = colors.labelPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.row)
                    .background(colors.bgLayer2)
                    .border(1.dp, colors.borderL1, DsShapes.row)
                    .padding(10.dp),
            )
        }
        card.locations?.takeIf { it.isNotEmpty() }?.let { locations ->
            SectionLabel(stringResource(R.string.toolcard_section_locations))
            locations.forEach { location ->
                Text(
                    location,
                    style = DsType.mdCode,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        card.content?.takeIf { it.isNotEmpty() }?.let { blocks ->
            SectionLabel(stringResource(R.string.toolcard_section_out))
            blocks.forEach { block ->
                when (block) {
                    is ContentBlockView.TextBlock -> Text(
                        block.text,
                        style = DsType.mdCode,
                        color = colors.labelPrimary,
                    )
                    is ContentBlockView.ReasoningBlock -> Text(
                        block.text,
                        style = DsType.mdSmall.copy(color = colors.labelTertiary),
                        color = colors.labelTertiary,
                    )
                    // A tool that returns a screenshot arrives here, not on the message path.
                    is ContentBlockView.ImageBlock -> AttachmentImage(
                        ref = com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef(
                            attachmentId = block.attachmentId,
                            mediaType = block.mediaType,
                            bytes = block.bytes,
                            width = block.width,
                            height = block.height,
                            name = block.name,
                        ),
                        contentDescription = block.name,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = DsType.caption11Strong,
        color = DsTheme.colors.labelCaption,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** Tiny deterministic JSON pretty-printer (2-space indent, strings/escapes preserved). */
private fun prettyJson(raw: String): String {
    val input = raw.trim()
    if (input.isEmpty()) return raw
    val out = StringBuilder()
    var indent = 0
    var inString = false
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        when {
            inString -> {
                out.append(ch)
                if (ch == '\\' && i + 1 < input.length) {
                    out.append(input[i + 1])
                    i += 2
                } else {
                    if (ch == '"') inString = false
                    i++
                }
            }
            ch == '"' -> {
                out.append(ch)
                inString = true
                i++
            }
            ch == '{' || ch == '[' -> {
                out.append(ch).append('\n')
                indent++
                repeat(indent) { out.append("  ") }
                i++
            }
            ch == '}' || ch == ']' -> {
                out.append('\n')
                indent = (indent - 1).coerceAtLeast(0)
                repeat(indent) { out.append("  ") }
                out.append(ch)
                i++
            }
            ch == ',' -> {
                out.append(ch).append('\n')
                repeat(indent) { out.append("  ") }
                i++
            }
            ch == ':' -> {
                out.append(": ")
                i++
            }
            else -> {
                out.append(ch)
                i++
            }
        }
    }
    return out.toString().trimEnd()
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ToolCardsPreview() {
    DshTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard(
                view = ToolCardView.TerminalCard(
                    title = "Bash",
                    cwd = "~/dsh",
                    output = "building…\nok",
                    exitCode = 0,
                ),
                expanded = true,
                onToggle = {},
            )
            ToolCard(
                view = ToolCardView.DiffCard(
                    diffs = listOf(DiffHunk("src/Main.kt", oldText = "val a = 1", newText = "val a = 2")),
                ),
                expanded = true,
                onToggle = {},
            )
            ToolCard(
                view = ToolCardView.GenericCard(
                    kind = "list",
                    rawInput = """{"path": "src", "recursive": true}""",
                    content = listOf(ContentBlockView.TextBlock("src/Main.kt\nsrc/App.kt")),
                ),
                expanded = true,
                onToggle = {},
            )
        }
    }
}
