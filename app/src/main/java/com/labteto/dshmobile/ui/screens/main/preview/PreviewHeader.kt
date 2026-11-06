package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The glyph that stands for a kind of file. */
internal fun kindIcon(kind: PreviewKind?): ImageVector = when (kind) {
    PreviewKind.Image, PreviewKind.Svg -> FeatherIcons.Image
    PreviewKind.Code, PreviewKind.Html, PreviewKind.Json, PreviewKind.Notebook -> FeatherIcons.Code
    PreviewKind.Table -> PreviewIcons.Grid
    PreviewKind.Video -> PreviewIcons.Film
    PreviewKind.Audio -> PreviewIcons.Music
    PreviewKind.Archive -> FeatherIcons.Archive
    PreviewKind.Font -> PreviewIcons.Type
    PreviewKind.Binary -> PreviewIcons.File
    else -> FeatherIcons.FileText
}

/**
 * Who the file is: a tile with its kind's glyph; the name, with a badge for its type; the folder it
 * is in, muted and cut from the left so the end of the path (the part that tells folders apart)
 * stays visible; and a line of facts (size, then whatever the renderer learned: pixels, pages, time).
 */
@Composable
internal fun FileIdentity(tab: PreviewTab, modifier: Modifier = Modifier, showFolder: Boolean = true) {
    val colors = DsTheme.colors
    val type = tab.type ?: typeFromName(tab.path)
    val (folder, name) = splitPath(tab.path)
    val facts = listOfNotNull(tab.stat?.bytes?.let { formatBytes(it) }, tab.detail).joinToString(" · ")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        Box(
            Modifier.size(40.dp).clip(DsShapes.control).background(colors.bgLayer3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindIcon(type?.kind), contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                Text(
                    technicalDisplay(name.ifEmpty { tab.path }),
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                type?.let {
                    DsPill(
                        text = it.label ?: stringResource(
                            if (it.kind == PreviewKind.Binary) R.string.preview_kind_binary else R.string.preview_kind_text,
                        ),
                    )
                }
            }
            if (showFolder && folder.isNotEmpty()) {
                StartEllipsisText(folder, DsType.mono12, colors.labelTertiary)
            }
            if (facts.isNotEmpty()) {
                Text(
                    facts,
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One line of text that, when it is too long, loses its start instead of its end. */
@Composable
internal fun StartEllipsisText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val shown = remember(text, style, available) {
            ellipsizeStart(text) { candidate ->
                measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= available
            }
        }
        Text(technicalDisplay(shown), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** What the header and the viewer's bar can do to the file as a whole. */
internal class FileCommands(
    /** Copies the loaded text; null for a kind with no text. */
    val onCopy: (() -> Unit)?,
    val onCopyPath: () -> Unit,
    val onRefresh: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The quick-look header: identity, then one row of actions. "Full preview" is the main one; copy
 * (for text) and save sit beside it as icon buttons, and the rest (refresh, copy the path, close the
 * file) are behind the overflow so the row never outgrows a narrow phone.
 */
@Composable
internal fun PreviewHeader(
    tab: PreviewTab,
    actions: PreviewActions,
    commands: FileCommands,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        FileIdentity(tab)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            DsButton(
                text = stringResource(R.string.preview_open_full),
                onClick = { actions.onOpenFull?.invoke() },
                modifier = Modifier.weight(1f),
                enabled = tab.stat != null && actions.onOpenFull != null,
                variant = DsButtonVariant.Primary,
                size = DsButtonSize.Small,
                icon = PreviewIcons.Expand,
            )
            commands.onCopy?.let { copy ->
                DsIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.ink_files_copy_contents),
                    onClick = copy,
                    enabled = tab.text != null,
                )
            }
            DsIconButton(
                icon = PreviewIcons.Download,
                contentDescription = stringResource(R.string.preview_save_copy),
                onClick = actions.onSave,
                enabled = actions.canSave && !actions.saving,
            )
            FileMenu(commands, includeClose = true, tint = colors.labelSecondary)
        }
        if (actions.saving) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        }
    }
}

/** The overflow menu: refresh, copy the path, and, in the quick look, close the file. */
@Composable
internal fun FileMenu(
    commands: FileCommands,
    includeClose: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        add(MenuItem(stringResource(R.string.preview_refresh), FeatherIcons.RefreshCw, onClick = commands.onRefresh))
        add(MenuItem(stringResource(R.string.preview_copy_path), Icons.Outlined.ContentCopy, onClick = commands.onCopyPath))
        if (includeClose) add(MenuItem(stringResource(R.string.common_close), Icons.Filled.Close, onClick = commands.onClose))
    }
    DsIconMenu(
        icon = PreviewIcons.MoreVertical,
        contentDescription = stringResource(R.string.preview_more_actions),
        items = items,
        modifier = modifier,
        tint = tint,
    )
}
