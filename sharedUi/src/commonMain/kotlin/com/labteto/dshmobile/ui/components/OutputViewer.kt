package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The text of a viewer and its line index, built together off the main thread. */
private class ViewerText(val text: String, val index: LineIndex)

/**
 * A long output in a full-screen page: the whole text, however long, lazy by line, selectable, with
 * Copy all and a search that steps from match to match.
 *
 * Nothing in the page is ever one big `Text`. The text is indexed once ([LineIndex], one integer per
 * line, built on a background thread because the text itself may need unwrapping first) and the list
 * asks for the lines on screen. A 200,000-line log or a 5 MB single line scrolls the same as ten
 * lines; the single line is shown as consecutive pieces of at most 2,000 characters.
 *
 * [contentKey] says when [load] would give a different text, so the page reloads only then.
 */
@Composable
fun OutputViewerDialog(
    title: String,
    contentKey: Any?,
    onDismiss: () -> Unit,
    load: () -> String,
) {
    var loaded by remember(contentKey) { mutableStateOf<ViewerText?>(null) }
    LaunchedEffect(contentKey) {
        loaded = withContext(Dispatchers.Default) {
            val text = load()
            ViewerText(text, LineIndex.of(text))
        }
    }
    val copy = rememberCopier()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    DsFullScreenDialog(onDismissRequest = onDismiss) {
        DsTopBar(
            title = title,
            leading = { DsIconButton(Icons.Filled.Close, stringResource(R.string.common_close), onDismiss) },
            trailing = {
                DsIconButton(
                    Icons.Filled.Search, stringResource(R.string.output_viewer_search), { searching = !searching },
                    selected = searching,
                )
                DsIconButton(
                    Icons.Outlined.ContentCopy, stringResource(R.string.hold_copy_all), { loaded?.let { copy(it.text) } },
                    enabled = loaded != null,
                )
            },
        )
        val ready = loaded
        if (ready == null) {
            DsProgress(Modifier.padding(horizontal = DsSpacing.comfortable))
            return@DsFullScreenDialog
        }
        ViewerBody(ready, searching, query, onQuery = { query = it })
    }
}

@Composable
private fun ColumnScope.ViewerBody(ready: ViewerText, searching: Boolean, query: String, onQuery: (String) -> Unit) {
    val colors = DsTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val needle = if (searching) query.trim() else ""
    // The matching lines, found on a background thread a moment after the last keystroke: scanning
    // 5 MB for a prefix that changes with every letter should not run for every letter.
    var matches by remember(ready) { mutableStateOf(IntArray(0)) }
    LaunchedEffect(ready, needle) {
        if (needle.isEmpty()) {
            matches = IntArray(0)
            return@LaunchedEffect
        }
        delay(180)
        matches = withContext(Dispatchers.Default) { ready.index.linesContaining(needle) }
    }
    var current by remember(needle) { mutableIntStateOf(0) }
    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) { current = 0; listState.scrollToItem(matches[0]) }
    }
    fun step(delta: Int) {
        if (matches.isEmpty()) return
        current = (current + delta).mod(matches.size)
        scope.launch { listState.animateScrollToItem(matches[current]) }
    }
    if (searching) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            DsSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = stringResource(R.string.output_viewer_search),
                modifier = Modifier.weight(1f),
                autoFocus = true,
            )
            DsIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.output_viewer_prev), { step(-1) }, enabled = matches.isNotEmpty())
            DsIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.output_viewer_next), { step(1) }, enabled = matches.isNotEmpty())
        }
        if (needle.isNotEmpty()) {
            val capped = matches.size >= LineIndex.DEFAULT_MATCH_LIMIT
            Text(
                if (matches.isEmpty()) stringResource(R.string.output_viewer_no_matches)
                else stringResource(R.string.output_viewer_matches, current + 1, if (capped) "${matches.size}+" else "${matches.size}"),
                style = DsType.caption11,
                color = colors.labelCaption,
                modifier = Modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.tiny),
            )
        }
    }
    val highlight = colors.accentTertiary
    val currentLine = matches.getOrNull(current)
    // Code and logs keep their order whatever the app's language is.
    LeftToRight {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            ) {
                items(ready.index.count) { i ->
                    val line = ready.index.line(i)
                    Text(
                        if (needle.isEmpty()) AnnotatedString(line.ifEmpty { " " }) else highlighted(line, needle, highlight, strong = i == currentLine),
                        style = DsType.mdCode,
                        color = colors.labelPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** [line] with each occurrence of [needle] marked; the line the search is on is marked harder. */
private fun highlighted(line: String, needle: String, color: androidx.compose.ui.graphics.Color, strong: Boolean): AnnotatedString {
    val ranges = occurrences(line, needle)
    if (ranges.isEmpty()) return AnnotatedString(line.ifEmpty { " " })
    val shade = color.copy(alpha = if (strong) 0.9f else 0.45f)
    return buildAnnotatedString {
        var at = 0
        for (range in ranges) {
            append(line.substring(at, range.first))
            withStyle(SpanStyle(background = shade)) { append(line.substring(range.first, range.last + 1)) }
            at = range.last + 1
        }
        append(line.substring(at))
    }
}
