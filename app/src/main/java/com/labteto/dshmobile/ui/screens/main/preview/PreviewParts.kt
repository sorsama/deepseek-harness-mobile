package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Where a file is being shown: the quick look inside the Preview tab, or the full-screen viewer. */
internal enum class PreviewSurface { Inline, Full }

/** What the file body can ask its host to do. */
@Immutable
internal data class PreviewActions(
    /** A copy can be saved (the file exists on the host). */
    val canSave: Boolean,
    /** A copy is being written. */
    val saving: Boolean,
    val onSave: () -> Unit,
    /** Opens the full-screen viewer; null on the surface that already is one. */
    val onOpenFull: (() -> Unit)?,
)

/** The rounded surface every renderer sits on: a code-block radius, a hairline, a quiet fill. */
@Composable
internal fun PreviewFrame(
    modifier: Modifier = Modifier,
    background: Color = DsTheme.colors.bgLayer1,
    /** False for the full-screen viewer, where the content runs to the screen edges. */
    framed: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        if (framed) {
            modifier
                .clip(DsShapes.code)
                .background(background)
                .border(1.dp, DsTheme.colors.borderL1, DsShapes.code)
        } else {
            modifier.background(background)
        },
        content = content,
    )
}

/** A centred message with an icon, optional detail, and an optional action under it. */
@Composable
internal fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    tint: Color = DsTheme.colors.labelTertiary,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    PreviewFrame(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(DsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(44.dp).clip(DsShapes.control).background(colors.bgLayer3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Text(title, style = DsType.rowTitle, color = colors.labelPrimary, textAlign = TextAlign.Center)
            if (body != null) {
                SelectionContainer {
                    Text(body, style = DsType.small13, color = colors.labelTertiary, textAlign = TextAlign.Center)
                }
            }
            if (action != null) {
                Box(Modifier.padding(top = DsSpacing.xsmall)) { action() }
            }
        }
    }
}

/** "Save a copy" as a button, for the notices that have nothing else to offer. */
@Composable
internal fun SaveCopyButton(actions: PreviewActions, modifier: Modifier = Modifier) {
    DsButton(
        text = stringResource(R.string.preview_save_copy),
        onClick = actions.onSave,
        modifier = modifier,
        enabled = actions.canSave && !actions.saving,
        variant = DsButtonVariant.Outline,
        size = DsButtonSize.Small,
        icon = PreviewIcons.Download,
    )
}

/** A load that failed: what the host said, and a way to try again. */
@Composable
internal fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    NoticeCard(
        icon = FeatherIcons.AlertTriangle,
        title = stringResource(R.string.panel_failed),
        body = message,
        modifier = modifier,
        tint = colors.error,
        action = {
            DsButton(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
                icon = FeatherIcons.RefreshCw,
            )
        },
    )
}

/** Placeholder lines for a text file that is still loading. */
@Composable
internal fun SourceSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    PreviewFrame(modifier, background = colors.codeBlockBg) {
        Column(
            Modifier.fillMaxSize().padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            repeat(10) { index ->
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 4 == 0) 0.62f else if (index % 4 == 1) 0.86f else 0.74f)
                        .height(10.dp)
                        .skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip),
                )
            }
        }
    }
}

/** A grey plate where a picture, page or player will be. */
@Composable
internal fun ImageSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    PreviewFrame(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(DsSpacing.large)
                .skeleton(colors.bgLayer2, colors.borderL2, DsShapes.code),
        )
    }
}
