package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the find bar is looking for and where it has got to. */
@Stable
internal class FindState {
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<TextMatch>>(emptyList())
    var capped by mutableStateOf(false)
    var index by mutableIntStateOf(0)

    val current: TextMatch? get() = matches.getOrNull(index)

    fun next() {
        if (matches.isNotEmpty()) index = (index + 1) % matches.size
    }

    fun previous() {
        if (matches.isNotEmpty()) index = (index - 1 + matches.size) % matches.size
    }
}

/** The colour a token is drawn in. */
internal fun tokenColor(kind: TokenKind, colors: DsColors): Color = when (kind) {
    TokenKind.Keyword -> colors.syntaxKeyword
    TokenKind.String -> colors.syntaxString
    TokenKind.Number -> colors.syntaxConstant
    TokenKind.Comment -> colors.syntaxComment
    TokenKind.Function -> colors.syntaxFunction
    TokenKind.Key, TokenKind.Attribute -> colors.syntaxParameter
}

/** One row's text with its syntax colours and any find highlights laid over it. */
internal fun styledCode(
    text: String,
    language: String,
    plain: Boolean,
    matches: List<TextMatch>?,
    current: TextMatch?,
    colors: DsColors,
): AnnotatedString {
    if (plain && matches.isNullOrEmpty()) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    if (!plain) {
        for (token in tokenizeLine(text, language)) {
            builder.addStyle(SpanStyle(color = tokenColor(token.kind, colors)), token.start, token.end)
        }
    }
    matches?.forEach { match ->
        val start = match.start.coerceIn(0, text.length)
        val end = match.end.coerceIn(start, text.length)
        if (end > start) {
            val alpha = if (match == current) 0.65f else 0.28f
            builder.addStyle(SpanStyle(background = colors.warn.copy(alpha = alpha)), start, end)
        }
    }
    return builder.toAnnotatedString()
}

/** The rows for [text], built off the main thread; null until the first build lands. */
@Composable
internal fun rememberDisplayLines(text: String): List<DisplayLine>? =
    computedOffMain(text) { buildDisplayLines(text) }

/**
 * Text as numbered, optionally wrapped lines with syntax colour, find highlights and diff shading.
 *
 * Long lines are already cut into rows by [buildDisplayLines], so no row is ever wider than a few
 * thousand characters. Wrapping off lays every row out at the width of the longest one and lets the
 * whole list slide sideways together. Selection works inside a row; copying a whole file is the
 * header's job.
 */
@Composable
internal fun CodeView(
    lines: List<DisplayLine>,
    language: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    numbers: Boolean = true,
    kinds: List<DiffLineKind>? = null,
    find: FindState? = null,
    onNearEnd: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val style = remember { DsType.mono12.copy(textDirection = TextDirection.Ltr) }
    val measurer = rememberTextMeasurer()
    val charPx = remember(style) { measurer.measure("0000000000", style, softWrap = false, maxLines = 1).size.width / 10f }
    val digits = remember(lines) { (lines.lastOrNull()?.number ?: 1).toString().length.coerceAtLeast(2) }
    val numberWidth = if (numbers) with(density) { (charPx * digits).toDp() } + 16.dp else 0.dp
    val widest = remember(lines) { lines.maxOfOrNull { it.text.length } ?: 0 }
    val rowWidth = numberWidth + with(density) { (charPx * widest).toDp() } + DsSpacing.large
    val byLine = remember(find?.matches) { find?.matches?.groupBy { it.line } ?: emptyMap() }
    val current = find?.current

    if (onNearEnd != null) {
        val latest by rememberUpdatedState(onNearEnd)
        LaunchedEffect(listState, lines.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= lines.size - 25) latest() }
        }
    }
    // Bring the match the reader stepped to into the upper third of the window.
    LaunchedEffect(current) {
        val at = current?.line ?: return@LaunchedEffect
        val lift = listState.layoutInfo.viewportSize.height / 3
        listState.animateScrollToItem(at.coerceIn(0, (lines.size - 1).coerceAtLeast(0)), -lift)
    }

    val list: @Composable () -> Unit = {
        SelectionContainer {
            LazyColumn(
                (if (wrap) Modifier.fillMaxSize() else Modifier.width(rowWidth + DsSpacing.small * 2).fillMaxHeight()),
                state = listState,
                contentPadding = PaddingValues(vertical = DsSpacing.small, horizontal = DsSpacing.small),
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    CodeRow(
                        line = line,
                        language = language,
                        numbers = numbers,
                        numberWidth = numberWidth,
                        wrap = wrap,
                        rowWidth = rowWidth,
                        kind = kinds?.getOrNull(line.number - 1),
                        matches = byLine[index],
                        // Only the row holding the current match needs to know which match it is.
                        current = current?.takeIf { it.line == index },
                        style = style,
                        colors = colors,
                    )
                }
            }
        }
    }
    Box(modifier) {
        if (wrap) list() else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) { list() }
    }
}

@Composable
private fun CodeRow(
    line: DisplayLine,
    language: String,
    numbers: Boolean,
    numberWidth: Dp,
    wrap: Boolean,
    rowWidth: Dp,
    kind: DiffLineKind?,
    matches: List<TextMatch>?,
    current: TextMatch?,
    style: TextStyle,
    colors: DsColors,
) {
    val shade = when (kind) {
        DiffLineKind.Added -> colors.successTertiary
        DiffLineKind.Removed -> colors.errorTertiary
        DiffLineKind.Hunk -> colors.accentTertiary
        else -> Color.Transparent
    }
    val ink = when (kind) {
        DiffLineKind.Added -> colors.success
        DiffLineKind.Removed -> colors.error
        DiffLineKind.Hunk -> colors.accent
        DiffLineKind.FileHeader, DiffLineKind.Meta -> colors.labelSecondary
        else -> colors.labelPrimary
    }
    val text = remember(line.text, language, kind, matches, current, colors) {
        styledCode(line.text, language, kind != null, matches, current, colors)
    }
    Row(
        (if (wrap) Modifier.fillMaxWidth() else Modifier.width(rowWidth)).background(shade),
    ) {
        if (numbers) {
            Text(
                if (line.continuation) "" else line.number.toString(),
                style = style,
                color = colors.labelDimmed,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(numberWidth).padding(end = 10.dp),
            )
        }
        Text(
            text,
            style = style,
            color = ink,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = if (wrap) Modifier.weight(1f) else Modifier,
        )
    }
}

/**
 * A text file as code: highlighted by its language, with the toggles the tab remembers. Also the
 * face of every "source" or "raw" mode and of unformatted text.
 */
@Composable
internal fun CodeSurface(
    text: String,
    language: String,
    tab: PreviewTab,
    surface: PreviewSurface,
    stateKey: String,
    modifier: Modifier = Modifier,
    diff: Boolean = false,
    find: FindState? = null,
    onNearEnd: (() -> Unit)? = null,
) {
    val lines = rememberDisplayLines(text)
    val kinds: List<DiffLineKind>? = computedOffMain(lines, diff) {
        if (!diff || lines == null) null else classifyDiffLines(lines.filter { !it.continuation }.map { it.text })
    }
    if (lines == null) {
        SourceSkeleton(modifier)
        return
    }
    if (find != null) {
        LaunchedEffect(find.query) { find.index = 0 }
        LaunchedEffect(find.query, lines) {
            delay(120)
            val found = withContext(Dispatchers.Default) { findMatches(lines.map { it.text }, find.query) }
            find.matches = found.matches
            find.capped = found.capped
            find.index = find.index.coerceIn(0, (found.matches.size - 1).coerceAtLeast(0))
        }
    }
    PreviewFrame(modifier, background = DsTheme.colors.codeBlockBg, framed = surface == PreviewSurface.Inline) {
        CodeView(
            lines = lines,
            language = language,
            listState = tab.listState("${surface.name}:$stateKey"),
            modifier = Modifier.fillMaxSize(),
            wrap = tab.wrap,
            numbers = tab.numbers,
            kinds = kinds,
            find = find,
            onNearEnd = onNearEnd,
        )
    }
}

/** The find field with its match count and the two step buttons. */
@Composable
internal fun FindBar(find: FindState, partial: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsSearchField(
                value = find.query,
                onValueChange = { find.query = it },
                placeholder = stringResource(R.string.preview_find_placeholder),
                modifier = Modifier.weight(1f),
                autoFocus = true,
                onSearch = find::next,
            )
            val label = when {
                find.query.isEmpty() -> ""
                find.matches.isEmpty() -> stringResource(R.string.preview_find_none)
                else -> "${find.index + 1} / ${find.matches.size}" + if (find.capped) "+" else ""
            }
            if (label.isNotEmpty()) {
                Text(
                    label,
                    style = DsType.mono12,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = DsSpacing.xsmall),
                )
            }
            DsIconButton(
                icon = PreviewIcons.ChevronUp,
                contentDescription = stringResource(R.string.preview_find_previous),
                onClick = find::previous,
                enabled = find.matches.isNotEmpty(),
            )
            DsIconButton(
                icon = PreviewIcons.ChevronDown,
                contentDescription = stringResource(R.string.preview_find_next),
                onClick = find::next,
                enabled = find.matches.isNotEmpty(),
            )
        }
        if (partial) {
            Text(
                stringResource(R.string.preview_find_partial),
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DsSpacing.tiny),
            )
        }
    }
}

/**
 * Under a paged text file: a button for the next page, or, once the preview's text cap has been
 * reached, a note that says so. Nothing when the whole file is already here.
 */
@Composable
internal fun PagedFooter(tab: PreviewTab, loader: PreviewLoader, modifier: Modifier = Modifier) {
    if (tab.text == null || tab.eof) return
    if (tab.capped) {
        Text(
            stringResource(R.string.preview_text_truncated, formatBytes(MAX_PREVIEW_TEXT.toLong())),
            style = DsType.caption11,
            color = DsTheme.colors.warnLabel,
            modifier = modifier.padding(horizontal = DsSpacing.tiny),
        )
    } else {
        DsButton(
            text = stringResource(R.string.panel_more),
            onClick = { loader.load(true) },
            modifier = modifier.fillMaxWidth(),
            enabled = !tab.busy,
            variant = DsButtonVariant.Outline,
            size = DsButtonSize.Small,
        )
    }
}
