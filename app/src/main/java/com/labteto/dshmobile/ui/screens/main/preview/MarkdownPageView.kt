package com.labteto.dshmobile.ui.screens.main.preview

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.markdown.MarkdownPageOptions
import com.labteto.dshmobile.core.markdown.MarkdownPageTheme
import com.labteto.dshmobile.core.markdown.MarkdownPages
import com.labteto.dshmobile.core.session.PreviewLink
import com.labteto.dshmobile.core.session.classifyPreviewLink
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.LocalFileOpener
import com.labteto.dshmobile.ui.components.LocalFolderOpener
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.isFolderReference
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.DocumentMarkdown
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.screens.main.documentFrontmatter
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.isDsDark
import java.util.Locale

/**
 * A Markdown file as it reads on GitHub, in the preview's locked-down web view: the Markdown is
 * turned into HTML (tables, task lists, strikethrough, autolinks, heading anchors), the HTML a
 * README mixes in (`<p align="center">`, `<img>`, `<details>`) is kept after the sanitizer has
 * removed whatever could run or load, and the page wears the app's colours.
 *
 * Pictures next to the file (`docs/banner.png`) load from the workspace. Pictures from the web are
 * off, because opening a README should not tell a stranger's server that you did; each one shows
 * its alt text as a small pill, and one switch above the page turns them on for this file. A link
 * to another file in the workspace opens it in the preview; a link out of the app asks first.
 */
@Composable
internal fun MarkdownPageView(
    store: SessionStore,
    key: ComposerKey,
    tab: PreviewTab,
    text: String,
    full: Boolean,
    modifier: Modifier = Modifier,
) {
    // A phone with no web view installed cannot show this; the Compose renderer still can.
    if (!webViewAvailable()) {
        SelectionContainer(
            modifier.fillMaxSize().verticalScroll(if (full) tab.scrollState("Full:md") else tab.scroll).padding(DsSpacing.medium),
        ) { DocumentMarkdown(text) }
        return
    }
    val colors = DsTheme.colors
    val dark = isDsDark(DsTheme.appearance)
    var showWebImages by remember(tab.path) { mutableStateOf(false) }
    var pendingLink by remember(tab.path) { mutableStateOf<String?>(null) }
    val frontmatterLabel = stringResource(R.string.harness_frontmatter)
    val imageLabel = stringResource(R.string.preview_md_image_label)
    val theme = remember(colors, dark) {
        MarkdownPageTheme(
            dark = dark,
            background = colors.bgLayer1.css(),
            text = colors.labelPrimary.css(),
            textSecondary = colors.labelSecondary.css(),
            link = colors.accent.css(),
            border = colors.borderL2.css(),
            codeBackground = colors.bgLayer2.css(),
            quoteBar = colors.borderL3.css(),
            pillBackground = colors.bgLayer3.css(),
            pillText = colors.labelSecondary.css(),
        )
    }
    val page = remember(text, theme, showWebImages, frontmatterLabel, imageLabel) {
        val frontmatter = documentFrontmatter(text)
        MarkdownPages.render(
            frontmatter?.body ?: text,
            theme,
            MarkdownPageOptions(
                showWebImages = showWebImages,
                frontmatter = frontmatter?.fields.orEmpty(),
                frontmatterLabel = frontmatterLabel,
                imageFallbackLabel = imageLabel,
            ),
        )
    }
    val openFile = LocalFileOpener.current
    val openFolder = LocalFolderOpener.current ?: openFile
    val documentUrlPath = "/workspace/" + tab.path.replace('\\', '/').removePrefix("/")
    Column(modifier.fillMaxSize()) {
        if (page.remoteImages.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny)) {
                ToggleRow(
                    label = stringResource(R.string.preview_md_web_images),
                    checked = showWebImages,
                    onChange = { showWebImages = !showWebImages },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SandboxedWebView(
                store = store,
                key = key,
                documentPath = tab.path,
                html = page.html,
                mime = "text/html",
                zoomable = full,
                webImages = showWebImages,
                backgroundColor = colors.bgLayer1.toArgb(),
                onLink = { url ->
                    when (val link = classifyPreviewLink(url, documentUrlPath)) {
                        is PreviewLink.Workspace -> if (isFolderReference(link.path)) openFolder(link.path) else openFile(link.path)
                        is PreviewLink.External -> pendingLink = link.url
                        PreviewLink.InPage, PreviewLink.Ignored -> Unit
                    }
                },
            )
        }
    }
    pendingLink?.let { url -> OpenLinkDialog(url, onDismiss = { pendingLink = null }) }
}

/** Says where a link goes before the app is left. */
@Composable
private fun OpenLinkDialog(url: String, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    DsDialog(title = stringResource(R.string.preview_link_title), onDismiss = onDismiss) {
        Text(url, style = DsType.mono12, color = colors.labelPrimary)
        Text(stringResource(R.string.preview_link_body), style = DsType.std14, color = colors.labelSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, variant = DsButtonVariant.Ghost)
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.preview_link_open),
                onClick = {
                    // An address nothing on the phone can open must not take the app down.
                    runCatching { uriHandler.openUri(url) }
                    onDismiss()
                },
                variant = DsButtonVariant.Primary,
            )
        }
    }
}

private fun webViewAvailable(): Boolean =
    runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(true)

private fun Color.css(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xFF
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return if (alpha == 0xFF) String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue)
    else String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", red, green, blue, alpha / 255f)
}
