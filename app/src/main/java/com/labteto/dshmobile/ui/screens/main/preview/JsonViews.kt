package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A JSON, GeoJSON or JSON Lines file in one of three faces: pretty-printed and coloured, a tree
 * whose branches open and close, or the raw text. A file that does not parse says where it went
 * wrong and still shows its text, so the person can find the fault.
 */
@Composable
internal fun JsonSurface(
    text: String,
    extension: String,
    mode: String,
    tab: PreviewTab,
    surface: PreviewSurface,
    modifier: Modifier = Modifier,
    find: FindState? = null,
    onNearEnd: (() -> Unit)? = null,
) {
    val lineDelimited = extension == "jsonl" || extension == "ndjson"
    val parsed = computedOffMain(text, lineDelimited) { parseJsonText(text, lineDelimited) }
    when (val result = parsed) {
        null -> SourceSkeleton(modifier)
        is JsonParse.Invalid -> Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (text.isBlank()) {
                NoticeCard(
                    icon = PreviewIcons.File,
                    title = stringResource(R.string.preview_empty_file),
                    body = null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                JsonProblem(result)
                CodeSurface(text, "json", tab, surface, "json-raw", Modifier.weight(1f).fillMaxWidth(), find = find, onNearEnd = onNearEnd)
            }
        }
        is JsonParse.Valid -> when (mode) {
            PreviewModes.Tree -> JsonTree(result, tab, surface, modifier)
            PreviewModes.Raw -> CodeSurface(text, "json", tab, surface, "json-raw", modifier, find = find, onNearEnd = onNearEnd)
            else -> {
                val pretty = computedOffMain(result) { prettyJson(result) }
                pretty?.let { CodeSurface(it, "json", tab, surface, "json-pretty", modifier, find = find) } ?: SourceSkeleton(modifier)
            }
        }
    }
}

/** Why a JSON file did not parse, in the parser's own words, with the place. */
@Composable
private fun JsonProblem(problem: JsonParse.Invalid) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.code)
            .background(colors.errorTertiary)
            .border(1.dp, colors.errorBorder, DsShapes.code)
            .padding(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Text(stringResource(R.string.preview_json_invalid), style = DsType.std14Strong, color = colors.error)
        val where = when {
            problem.line != null && problem.column != null ->
                stringResource(R.string.preview_json_at, problem.line, problem.column)
            problem.line != null -> stringResource(R.string.preview_json_at_line, problem.line)
            else -> null
        }
        where?.let { Text(it, style = DsType.mono12, color = colors.labelSecondary) }
        if (problem.reason.isNotBlank()) {
            SelectionContainer {
                Text(problem.reason, style = DsType.caption11, color = colors.labelTertiary)
            }
        }
    }
}

/** The collapsible tree: a row per visible node, tapped to open or close a branch. */
@Composable
private fun JsonTree(valid: JsonParse.Valid, tab: PreviewTab, surface: PreviewSurface, modifier: Modifier) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val open = tab.jsonOpen
    val rows = remember(valid, open) { flattenJson(valid.root, open) }
    val style = remember { DsType.mono12.copy(textDirection = TextDirection.Ltr) }
    PreviewFrame(modifier, background = colors.codeBlockBg, framed = surface == PreviewSurface.Inline) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = tab.listState("${surface.name}:json-tree"),
            contentPadding = PaddingValues(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        ) {
            items(rows, key = { it.id }) { row ->
                val toggle = if (row.expandable) {
                    {
                        haptics.selection()
                        tab.jsonOpen = if (row.id in tab.jsonOpen) tab.jsonOpen - row.id else tab.jsonOpen + row.id
                    }
                } else null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .clip(DsShapes.chip)
                        .then(
                            if (toggle != null) {
                                Modifier.clickable(
                                    onClickLabel = stringResource(if (row.expanded) R.string.preview_collapse else R.string.preview_expand),
                                    role = Role.Button,
                                    onClick = toggle,
                                )
                            } else Modifier,
                        )
                        .padding(start = (row.depth * 14).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (row.expandable) {
                        Icon(
                            PreviewIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.labelTertiary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(16.dp)
                                .rotate(if (row.expanded) 90f else 0f),
                        )
                    } else {
                        Spacer(Modifier.width(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    if (row.kind == JsonNodeKind.More) {
                        Text(
                            stringResource(R.string.preview_json_more, row.value),
                            style = style,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        if (row.label != null) {
                            Text(
                                row.label,
                                style = style,
                                color = colors.syntaxParameter,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 160.dp),
                            )
                            Text(": ", style = style, color = colors.labelTertiary, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        Text(
                            row.value,
                            style = style,
                            color = when (row.kind) {
                                JsonNodeKind.String -> colors.syntaxString
                                JsonNodeKind.Number -> colors.syntaxConstant
                                JsonNodeKind.Bool, JsonNodeKind.Null -> colors.syntaxKeyword
                                else -> colors.labelTertiary
                            },
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
