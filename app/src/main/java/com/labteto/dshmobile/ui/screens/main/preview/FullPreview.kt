package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.launch

/** What only the full-screen viewer keeps: the zoom, the find bar, and the PDF list and zoom. */
@Stable
internal class ViewerState(tab: PreviewTab) {
    val zoom = ZoomState()
    val find = FindState()
    val pdf = PdfViewState(tab.listState("Full:pdf"))
    var findOpen by mutableStateOf(false)

    fun closeFind() {
        findOpen = false
        find.query = ""
    }
}

/**
 * The file on its own screen: a [DsFullScreenDialog] with a top bar (back, who the file is, save,
 * overflow), a row of tools that fit the kind of file, the mode toggle, and the file filling the
 * rest. Back closes find first, then the viewer.
 *
 * Tools by kind: text in the code view gets find, wrap and line numbers; a picture gets zoom and
 * fit or fill; a PDF gets page and zoom steps; everything with text can be copied.
 */
@Composable
internal fun FullPreview(
    store: SessionStore,
    key: ComposerKey,
    tab: PreviewTab,
    loader: PreviewLoader,
    actions: PreviewActions,
    commands: FileCommands,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val viewer = remember(tab) { ViewerState(tab) }
    val back = {
        if (viewer.findOpen) viewer.closeFind() else onDismiss()
    }
    DsFullScreenDialog(onDismissRequest = back) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = back,
            )
            FileIdentity(tab, Modifier.weight(1f).padding(horizontal = DsSpacing.xsmall), showFolder = false)
            DsIconButton(
                icon = PreviewIcons.Download,
                contentDescription = stringResource(R.string.preview_save_copy),
                onClick = actions.onSave,
                enabled = actions.canSave && !actions.saving,
            )
            FileMenu(commands, includeClose = false, tint = colors.labelSecondary)
        }
        val type = tab.type
        if (type != null) {
            ViewerTools(tab, type, viewer, commands.onCopy)
            val codeFace = type.isText && tab.text != null && usesCodeView(type.kind, tab.mode)
            AnimatedVisibility(
                visible = viewer.findOpen && codeFace,
                enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
            ) {
                FindBar(
                    viewer.find,
                    partial = !tab.eof,
                    modifier = Modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.tiny),
                )
            }
        }
        PreviewBody(
            store = store,
            key = key,
            tab = tab,
            loader = loader,
            surface = PreviewSurface.Full,
            actions = actions.copy(onOpenFull = null),
            viewer = viewer,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** The row of tools that suit the file; empty (and not drawn) for kinds that bring their own controls. */
@Composable
private fun ViewerTools(tab: PreviewTab, type: FileType, viewer: ViewerState, onCopy: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val codeFace = type.isText && tab.text != null && usesCodeView(type.kind, tab.mode)
    val zoom = viewer.zoom
    val pdf = viewer.pdf
    val hasTools = codeFace || (type.isText && onCopy != null) || type.kind == PreviewKind.Image || type.kind == PreviewKind.Pdf
    if (!hasTools) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DsSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (codeFace) {
            DsIconButton(
                icon = FeatherIcons.Search,
                contentDescription = stringResource(R.string.preview_find),
                onClick = { if (viewer.findOpen) viewer.closeFind() else viewer.findOpen = true },
                selected = viewer.findOpen,
            )
            DsIconButton(
                icon = PreviewIcons.Wrap,
                contentDescription = stringResource(R.string.preview_wrap),
                onClick = { tab.wrap = !tab.wrap },
                selected = tab.wrap,
            )
            DsIconButton(
                icon = PreviewIcons.Hash,
                contentDescription = stringResource(R.string.preview_line_numbers),
                onClick = { tab.numbers = !tab.numbers },
                selected = tab.numbers,
            )
        }
        if (type.isText && onCopy != null) {
            DsIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.ink_files_copy_contents),
                onClick = onCopy,
                enabled = tab.text != null,
            )
        }
        if (type.kind == PreviewKind.Image) {
            DsIconButton(
                icon = PreviewIcons.ZoomOut,
                contentDescription = stringResource(R.string.preview_zoom_out),
                onClick = { scope.launch { zoom.zoomBy(1f / 1.6f) } },
            )
            DsIconButton(
                icon = PreviewIcons.ZoomIn,
                contentDescription = stringResource(R.string.preview_zoom_in),
                onClick = { scope.launch { zoom.zoomBy(1.6f) } },
            )
            DsIconButton(
                icon = if (zoom.fill) PreviewIcons.Fit else PreviewIcons.Fill,
                contentDescription = stringResource(if (zoom.fill) R.string.preview_fit else R.string.preview_fill),
                onClick = zoom::toggleFill,
            )
        }
        if (type.kind == PreviewKind.Pdf) {
            DsIconButton(
                icon = PreviewIcons.ChevronUp,
                contentDescription = stringResource(R.string.preview_prev_page),
                onClick = { scope.launch { pdf.list.animateScrollToItem((pdf.page - 1).coerceAtLeast(0)) } },
                enabled = pdf.page > 0,
            )
            DsIconButton(
                icon = PreviewIcons.ChevronDown,
                contentDescription = stringResource(R.string.preview_next_page),
                onClick = { scope.launch { pdf.list.animateScrollToItem((pdf.page + 1).coerceAtMost((pdf.pages - 1).coerceAtLeast(0))) } },
                enabled = pdf.page + 1 < pdf.pages,
            )
            DsIconButton(
                icon = PreviewIcons.ZoomOut,
                contentDescription = stringResource(R.string.preview_zoom_out),
                onClick = { pdf.zoomBy(1f / 1.5f) },
                enabled = pdf.zoom > 1f,
            )
            DsIconButton(
                icon = PreviewIcons.ZoomIn,
                contentDescription = stringResource(R.string.preview_zoom_in),
                onClick = { pdf.zoomBy(1.5f) },
                enabled = pdf.zoom < 4f,
            )
        }
    }
}
