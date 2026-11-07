package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.DocumentMarkdown
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The file itself: whatever renderer its kind calls for, or the notice that stands in for it
 * (loading, failed, too large). The same composable fills the quick look in the Preview tab and the
 * full-screen viewer; [surface] says which, and [viewer] carries the viewer's own state (zoom, find)
 * when it is the second.
 *
 * [suspended] is for the quick look while the full viewer is open on top of it: the heavy
 * renderers (a decoded picture, an open PDF, a player) belong to one surface at a time.
 */
@Composable
internal fun PreviewBody(
    store: SessionStore,
    key: ComposerKey,
    tab: PreviewTab,
    loader: PreviewLoader,
    surface: PreviewSurface,
    actions: PreviewActions,
    viewer: ViewerState?,
    modifier: Modifier = Modifier,
    suspended: Boolean = false,
) {
    if (suspended) {
        PreviewFrame(modifier) {}
        return
    }
    val type = tab.type
    val text = tab.text
    val bytes = tab.bytes
    val hasContent = text != null || bytes != null || tab.head != null
    val full = surface == PreviewSurface.Full
    val pagedText = type != null && type.source == PreviewSource.Text && !type.needsWholeText
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        if (type != null && text != null) {
            ModeBar(tab, type, Modifier.then(if (full) Modifier.padding(horizontal = DsSpacing.comfortable) else Modifier))
        }
        tab.error?.let { message ->
            if (hasContent) InlineError(message, loader::refresh)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val fill = Modifier.fillMaxSize()
            val error = tab.error
            when {
                error != null && !hasContent -> ErrorCard(error, loader::refresh, fill)
                tab.tooLarge -> NoticeCard(
                    icon = PreviewIcons.File,
                    title = stringResource(R.string.preview_too_large_title),
                    body = stringResource(R.string.preview_too_large_body, formatBytes(tab.stat?.bytes ?: 0L)),
                    modifier = fill,
                    action = { SaveCopyButton(actions) },
                )
                type == null -> if (full || typeFromName(tab.path)?.source == PreviewSource.Bytes) ImageSkeleton(fill) else SourceSkeleton(fill)
                else -> Rendered(store, key, tab, loader, type, surface, actions, viewer, fill)
            }
        }
        if (pagedText) PagedFooter(tab, loader)
    }
}

/** The renderer for [type], once there is something to render. */
@Composable
private fun Rendered(
    store: SessionStore,
    key: ComposerKey,
    tab: PreviewTab,
    loader: PreviewLoader,
    type: FileType,
    surface: PreviewSurface,
    actions: PreviewActions,
    viewer: ViewerState?,
    modifier: Modifier,
) {
    val text = tab.text
    val bytes = tab.bytes
    val full = surface == PreviewSurface.Full
    val find = viewer?.find
    // In the full viewer a paged file pulls its next page in as the reader nears the end.
    val nearEnd: (() -> Unit)? = if (full && type.source == PreviewSource.Text && !type.needsWholeText && !tab.eof && !tab.capped) {
        { loader.load(true) }
    } else null
    val mode = PreviewModes.resolve(type.kind, tab.mode)
    when (type.source) {
        PreviewSource.Bytes -> {
            if (bytes == null) {
                ImageSkeleton(modifier)
                return
            }
            when (type.kind) {
                PreviewKind.Image -> ImageStage(
                    bytes = bytes,
                    maxEdge = if (full) 2560 else 1536,
                    actions = actions,
                    modifier = modifier,
                    zoom = viewer?.zoom,
                    onDecoded = { tab.detail = formatDimensions(it.originalWidth, it.originalHeight) },
                )
                PreviewKind.Pdf -> PdfSurface(
                    bytes, tab, viewer?.pdf ?: remember(tab) { PdfViewState(tab.listState("Inline:pdf")) }, surface, actions, modifier,
                )
                PreviewKind.Video, PreviewKind.Audio -> MediaSurface(bytes, type, tab, surface, actions, modifier)
                PreviewKind.Archive -> ArchiveSurface(bytes, tab, surface, modifier)
                PreviewKind.Font -> FontSurface(bytes, type, surface, modifier)
                else -> HexSurface(tab, type, surface, actions, modifier)
            }
        }
        PreviewSource.Head -> HexSurface(tab, type, surface, actions, modifier)
        PreviewSource.Text -> {
            if (text == null) {
                SourceSkeleton(modifier)
                return
            }
            if (text.isEmpty() && tab.eof) {
                NoticeCard(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.preview_empty_file),
                    body = null,
                    modifier = modifier,
                )
                return
            }
            when (type.kind) {
                PreviewKind.Markdown -> if (mode == PreviewModes.Source) {
                    CodeSurface(text, "md", tab, surface, "md-source", modifier, find = find, onNearEnd = nearEnd)
                } else {
                    PreviewFrame(modifier, framed = !full) {
                        MarkdownPageView(store, key, tab, text, full)
                    }
                }
                PreviewKind.Html -> if (mode == PreviewModes.Source) {
                    CodeSurface(text, "html", tab, surface, "html-source", modifier, find = find, onNearEnd = nearEnd)
                } else {
                    PreviewFrame(modifier, framed = !full) {
                        SandboxedWebView(store, key, tab.path, text, "text/html", zoomable = full)
                    }
                }
                PreviewKind.Svg -> if (mode == PreviewModes.Source) {
                    CodeSurface(text, "xml", tab, surface, "svg-source", modifier, find = find, onNearEnd = nearEnd)
                } else {
                    PreviewFrame(modifier, framed = !full) {
                        SandboxedWebView(store, key, tab.path, remember(text) { svgPage(text) }, "text/html", zoomable = full)
                    }
                }
                PreviewKind.Table -> if (mode == PreviewModes.Raw) {
                    CodeSurface(text, type.extension, tab, surface, "table-raw", modifier, find = find, onNearEnd = nearEnd)
                } else {
                    TableSurface(text, type.extension, tab, surface, modifier)
                }
                PreviewKind.Json -> JsonSurface(text, type.extension, mode ?: PreviewModes.Pretty, tab, surface, modifier, find, nearEnd)
                PreviewKind.Notebook -> NotebookSurface(text, mode ?: PreviewModes.Rendered, tab, surface, modifier, find, nearEnd)
                PreviewKind.Diff -> CodeSurface(text, "diff", tab, surface, "diff", modifier, diff = true, find = find, onNearEnd = nearEnd)
                else -> CodeSurface(text, type.extension, tab, surface, "code", modifier, find = find, onNearEnd = nearEnd)
            }
        }
    }
}

/** The mode toggle for kinds that have two or three faces (rendered or source, table or raw...). */
@Composable
internal fun ModeBar(tab: PreviewTab, type: FileType, modifier: Modifier = Modifier) {
    val modes = PreviewModes.forKind(type.kind)
    if (modes.isEmpty()) return
    val labels = modes.map { mode ->
        DsSegment(
            mode,
            stringResource(
                when (mode) {
                    PreviewModes.Rendered -> R.string.ink_files_rendered
                    PreviewModes.Source -> R.string.ink_files_source
                    PreviewModes.Table -> R.string.preview_mode_table
                    PreviewModes.Pretty -> R.string.preview_mode_pretty
                    PreviewModes.Tree -> R.string.preview_mode_tree
                    else -> R.string.preview_mode_raw
                },
            ),
        )
    }
    DsSegmented(
        segments = labels,
        selectedKey = PreviewModes.resolve(type.kind, tab.mode),
        onSelect = { tab.mode = it },
        role = Role.Tab,
        stretch = true,
        minSegmentHeight = 36.dp,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A failure that leaves the loaded content in place: the message and a retry, above the content. */
@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.code)
            .background(colors.errorTertiary)
            .border(1.dp, colors.errorBorder, DsShapes.code)
            .padding(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        SelectionContainer {
            Text(message, style = DsType.small13, color = colors.error)
        }
        DsButton(
            text = stringResource(R.string.common_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Outline,
            size = DsButtonSize.Small,
        )
    }
}
