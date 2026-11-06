package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Jupyter notebook read top to bottom: markdown cells as documents, code cells as highlighted
 * code with their run number and language, and each cell's printed text, tracebacks and
 * pictures under it. Not a notebook, or not one this reader understands, falls back to the raw text.
 */
@Composable
internal fun NotebookSurface(
    text: String,
    mode: String,
    tab: PreviewTab,
    surface: PreviewSurface,
    modifier: Modifier = Modifier,
    find: FindState? = null,
    onNearEnd: (() -> Unit)? = null,
) {
    val result = computedOffMain(text) { parseNotebook(text) }
    when {
        result == null -> SourceSkeleton(modifier)
        mode == PreviewModes.Raw -> CodeSurface(text, "json", tab, surface, "notebook-raw", modifier, find = find, onNearEnd = onNearEnd)
        result is NotebookParse.Invalid -> NoticeCard(
            icon = PreviewIcons.File,
            title = stringResource(R.string.preview_notebook_invalid),
            body = result.reason.ifBlank { null },
            modifier = modifier,
        )
        result is NotebookParse.Valid -> PreviewFrame(modifier, framed = surface == PreviewSurface.Inline) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = tab.listState("${surface.name}:notebook"),
                contentPadding = PaddingValues(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                items(result.doc.cells) { cell -> NotebookCellView(cell, result.doc) }
            }
        }
    }
}

@Composable
private fun NotebookCellView(cell: NotebookCell, doc: NotebookDoc) {
    val colors = DsTheme.colors
    when (cell.kind) {
        NotebookCellKind.Markdown -> if (cell.source.isNotBlank()) MarkdownText(cell.source)
        NotebookCellKind.Raw -> if (cell.source.isNotBlank()) {
            SelectionContainer {
                Text(cell.source, style = DsType.mono12, color = colors.labelSecondary)
            }
        }
        NotebookCellKind.Code -> Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.code)
                    .background(colors.codeBlockBg)
                    .border(1.dp, colors.borderL1, DsShapes.code),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.codeBlockBanner)
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "In [${cell.executionCount?.toString() ?: " "}]",
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    doc.languageName?.let {
                        Text(it, style = DsType.caption11, color = colors.labelCaption, maxLines = 1)
                    }
                }
                SelectionContainer {
                    Text(
                        remember(cell.source, doc.languageKey, colors) { highlightedBlock(cell.source, doc.languageKey, colors) },
                        style = DsType.mono12.copy(textDirection = TextDirection.Ltr),
                        color = colors.labelPrimary,
                        modifier = Modifier.padding(DsSpacing.medium),
                    )
                }
            }
            cell.outputs.forEach { output -> NotebookOutputView(output) }
        }
    }
}

/** A block of code, coloured line by line. */
internal fun highlightedBlock(source: String, language: String?, colors: DsColors): AnnotatedString {
    val key = language ?: return AnnotatedString(source)
    val builder = AnnotatedString.Builder(source)
    var offset = 0
    for (line in source.split('\n')) {
        for (token in tokenizeLine(line, key)) {
            builder.addStyle(SpanStyle(color = tokenColor(token.kind, colors)), offset + token.start, offset + token.end)
        }
        offset += line.length + 1
    }
    return builder.toAnnotatedString()
}

@Composable
private fun NotebookOutputView(output: NotebookOutput) {
    val colors = DsTheme.colors
    when (output) {
        is NotebookOutput.Text -> SelectionContainer {
            Text(
                output.text,
                style = DsType.mono12.copy(textDirection = TextDirection.Ltr),
                color = when {
                    output.error -> colors.error
                    output.stderr -> colors.warnLabel
                    else -> colors.labelSecondary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.code)
                    .background(if (output.error) colors.errorTertiary else colors.bgLayer2)
                    .padding(DsSpacing.medium),
            )
        }
        is NotebookOutput.Image -> {
            val picture: ImageBitmap? = computedOffMain(output.base64) {
                val bytes = runCatching { java.util.Base64.getDecoder().decode(output.base64) }.getOrNull()
                (bytes?.let { decodeImage(it, 1600) } as? DecodedImage.Still)?.bitmap
            }
            picture?.let {
                Image(
                    it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().clip(DsShapes.code),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
        is NotebookOutput.Unsupported -> Text(
            stringResource(R.string.preview_output_hidden),
            style = DsType.caption11,
            color = colors.labelCaption,
        )
    }
}
