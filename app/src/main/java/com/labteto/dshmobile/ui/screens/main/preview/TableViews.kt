package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A CSV or TSV file as a table: the first row as a pinned header, a row-number gutter, alternate
 * rows shaded, columns as wide as their content (within bounds), and the whole grid sliding
 * sideways. Only [TABLE_ROW_CAP] data rows are built; a note says how many there are in all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableSurface(
    text: String,
    extension: String,
    tab: PreviewTab,
    surface: PreviewSurface,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val table = computedOffMain(text, extension) {
        parseDelimited(text, detectDelimiter(text, extension), keep = TABLE_ROW_CAP + 1)
    }
    if (table == null) {
        SourceSkeleton(modifier)
        return
    }
    if (table.rows.isEmpty()) {
        NoticeCard(
            icon = PreviewIcons.Grid,
            title = stringResource(R.string.preview_empty_file),
            body = null,
            modifier = modifier,
        )
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val cell = remember { DsType.mono12.copy(textDirection = TextDirection.Ltr) }
    val header = remember { cell.copy(fontWeight = FontWeight.SemiBold) }
    val charDp = remember(cell) {
        with(density) { (measurer.measure("0000000000", cell, softWrap = false, maxLines = 1).size.width / 10f).toDp() }
    }
    val columns = table.columns
    val widths = remember(table) { columnWidths(table.rows, columns).map { it * charDp.value + 20f } }
    val data = remember(table) { table.rows.drop(1) }
    val gutter = (data.size.toString().length.coerceAtLeast(2) * charDp.value + 20f).dp
    val total = gutter + widths.sumOf { it.toDouble() }.toFloat().dp
    val shown = data.size
    val all = (table.totalRecords - 1).coerceAtLeast(0)

    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DsSpacing.tiny)) {
        PreviewFrame(Modifier.weight(1f).fillMaxWidth(), framed = surface == PreviewSurface.Inline) {
            Box(Modifier.fillMaxSize().horizontalScroll(tab.scrollState("${surface.name}:table-h"))) {
                LazyColumn(
                    Modifier.width(total).fillMaxHeight(),
                    state = tab.listState("${surface.name}:table"),
                ) {
                    stickyHeader(key = "header") {
                        Row(
                            Modifier.width(total).background(colors.bgLayer3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(gutter).height(36.dp))
                            table.rows[0].let { head ->
                                repeat(columns) { column ->
                                    Text(
                                        head.getOrElse(column) { "" },
                                        style = header,
                                        color = colors.labelPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(widths[column].dp).padding(horizontal = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(data, key = { index, _ -> index }) { index, row ->
                        Row(
                            Modifier
                                .width(total)
                                .background(if (index % 2 == 1) colors.bgLayer2 else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (index + 1).toString(),
                                style = cell,
                                color = colors.labelDimmed,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                modifier = Modifier.width(gutter).padding(end = 10.dp),
                            )
                            repeat(columns) { column ->
                                Text(
                                    row.getOrElse(column) { "" },
                                    style = cell,
                                    color = colors.labelPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .width(widths[column].dp)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        val summary = when {
            table.truncated -> stringResource(R.string.preview_table_truncated, shown, all)
            !tab.eof -> stringResource(R.string.preview_table_partial, all)
            else -> stringResource(R.string.preview_table_summary, all, columns)
        }
        Text(
            summary,
            style = DsType.caption11,
            color = colors.labelTertiary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.tiny),
        )
    }
}
