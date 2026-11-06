package com.labteto.dshmobile.ui.screens.main.preview

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file the app cannot draw, shown as what it is: a note, its first bytes as a hex dump with
 * offsets, and the way out (save a copy and open it in an app that understands it).
 */
@Composable
internal fun HexSurface(
    tab: PreviewTab,
    type: FileType,
    surface: PreviewSurface,
    actions: PreviewActions,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val whole = tab.bytes
    val head = tab.head ?: whole?.copyOfRange(0, minOf(whole.size, HEAD_BYTES))
    if (head == null) {
        SourceSkeleton(modifier)
        return
    }
    val total = tab.stat?.bytes ?: head.size.toLong()
    val perRow = if (surface == PreviewSurface.Full) 16 else 8
    val rows = computedOffMain(head, perRow, total) { hexDump(head, HEAD_BYTES, perRow, total) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                Box(
                    Modifier.size(40.dp).clip(DsShapes.control).background(colors.bgLayer3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PreviewIcons.File, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.preview_binary_title), style = DsType.rowTitle, color = colors.labelPrimary)
                    Text(
                        stringResource(R.string.preview_binary_body),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val dump = rows
        if (dump == null) {
            SourceSkeleton(Modifier.weight(1f).fillMaxWidth())
        } else {
            PreviewFrame(
                Modifier.weight(1f).fillMaxWidth(),
                background = colors.codeBlockBg,
                framed = surface == PreviewSurface.Inline,
            ) {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        state = tab.listState("${surface.name}:hex"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(DsSpacing.small),
                    ) {
                        items(dump) { line ->
                            Text(
                                line,
                                style = DsType.mono12.copy(textDirection = TextDirection.Ltr),
                                color = colors.labelSecondary,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.preview_hex_note, formatBytes(minOf(total, HEAD_BYTES.toLong())), formatBytes(total)),
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f).padding(horizontal = DsSpacing.tiny),
            )
            SaveCopyButton(actions)
        }
    }
}

private sealed interface ArchiveLoad {
    data object Failed : ArchiveLoad
    class Ready(val listing: ZipListing) : ArchiveLoad
}

/** The table of contents of a zip, jar, apk or Office file: names with their folders and sizes. */
@Composable
internal fun ArchiveSurface(
    bytes: ByteArray,
    tab: PreviewTab,
    surface: PreviewSurface,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current.applicationContext
    val load: ArchiveLoad? = computedOffMain(bytes) {
        val file = PreviewTempFiles.write(context, bytes, "zip")
        val listing = try {
            withContext(Dispatchers.IO) { listZipEntries(file) }
        } finally {
            file.delete()
        }
        if (listing == null) ArchiveLoad.Failed else ArchiveLoad.Ready(listing)
    }
    when (val result = load) {
        null -> SourceSkeleton(modifier)
        ArchiveLoad.Failed -> NoticeCard(
            icon = FeatherIcons.Archive,
            title = stringResource(R.string.preview_cant_show_title),
            body = stringResource(R.string.preview_archive_failed),
            modifier = modifier,
        )
        is ArchiveLoad.Ready -> {
            val listing = result.listing
            val summary = stringResource(R.string.preview_archive_summary, listing.total)
            LaunchedEffect(summary) { tab.detail = summary }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                PreviewFrame(Modifier.weight(1f).fillMaxWidth(), framed = surface == PreviewSurface.Inline) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = tab.listState("${surface.name}:archive"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(DsSpacing.small),
                    ) {
                        items(listing.entries) { entry ->
                            val (folder, name) = splitPath(entry.name.trimEnd('/'))
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall, horizontal = DsSpacing.tiny),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                Icon(
                                    if (entry.directory) FeatherIcons.Folder else FeatherIcons.FileText,
                                    contentDescription = null,
                                    tint = if (entry.directory) colors.accent else colors.labelTertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name.ifEmpty { entry.name },
                                        style = DsType.mono12,
                                        color = colors.labelPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (folder.isNotEmpty()) {
                                        Text(
                                            folder,
                                            style = DsType.caption11,
                                            color = colors.labelCaption,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (!entry.directory && entry.size >= 0) {
                                    Text(formatBytes(entry.size), style = DsType.mono12, color = colors.labelCaption, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Text(
                    if (listing.truncated) stringResource(R.string.preview_archive_truncated, listing.entries.size) else summary,
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.tiny),
                )
            }
        }
    }
}

private sealed interface FontLoad {
    data object Failed : FontLoad
    class Ready(val family: FontFamily) : FontLoad
}

private const val FONT_PANGRAM = "The quick brown fox jumps over the lazy dog"
private const val FONT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val FONT_LOWER = "abcdefghijklmnopqrstuvwxyz"
private const val FONT_DIGITS = "0123456789 .,:;!?&@#%()[]{}"

/** A TrueType or OpenType font drawn as a type specimen: a big glyph pair, the alphabets and a sentence. */
@Composable
internal fun FontSurface(
    bytes: ByteArray,
    type: FileType,
    surface: PreviewSurface,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current.applicationContext
    // The font file has to outlive the typeface made from it, so it is deleted when this leaves the screen.
    var file by remember(bytes) { mutableStateOf<File?>(null) }
    var load by remember(bytes) { mutableStateOf<FontLoad?>(null) }
    LaunchedEffect(bytes) {
        val written = PreviewTempFiles.write(context, bytes, type.extension)
        file = written
        val typeface = withContext(Dispatchers.Default) { runCatching { Typeface.createFromFile(written) }.getOrNull() }
        load = if (typeface == null) FontLoad.Failed else FontLoad.Ready(FontFamily(typeface))
    }
    DisposableEffect(bytes) { onDispose { file?.delete() } }
    when (val result = load) {
        null -> SourceSkeleton(modifier)
        FontLoad.Failed -> NoticeCard(
            icon = PreviewIcons.Type,
            title = stringResource(R.string.preview_cant_show_title),
            body = stringResource(R.string.preview_font_failed),
            modifier = modifier,
        )
        is FontLoad.Ready -> PreviewFrame(modifier, framed = surface == PreviewSurface.Inline) {
            // Specimen sizes are the content here, not interface type: they show the font at the sizes it is used.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                listOf("Aa Gg" to 64, FONT_UPPER to 24, FONT_LOWER to 24, FONT_DIGITS to 20, FONT_PANGRAM to 28, FONT_PANGRAM to 16, FONT_PANGRAM to 12).forEach { (sample, size) ->
                    Text(
                        sample,
                        style = TextStyle(fontFamily = result.family, fontSize = size.sp),
                        color = colors.labelPrimary,
                    )
                }
            }
        }
    }
}
