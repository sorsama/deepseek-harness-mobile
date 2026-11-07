package com.labteto.dshmobile.core.session

import java.net.URI

/** Where a tap on a link in a rendered Markdown file should lead. */
sealed interface PreviewLink {
    /** A `#heading` in the page that is already open; the page scrolls itself. */
    data object InPage : PreviewLink

    /** A file or folder in the workspace, as a path relative to the workspace root. */
    data class Workspace(val path: String) : PreviewLink

    /** Somewhere outside the app. The person is asked before it opens. */
    data class External(val url: String) : PreviewLink

    /** Anything else: dropped, because nothing here can use it safely. */
    data object Ignored : PreviewLink
}

private const val PREVIEW_HOST = "preview.invalid"
private const val WORKSPACE_PREFIX = "/workspace/"

/**
 * Sorts the URL a web view reports for a tapped link. [documentUrlPath] is the URL path of the page
 * itself (`/workspace/README.md`), which is what tells an in-page anchor from a link to another
 * file. A relative link in the document arrives as `https://preview.invalid/workspace/<path>`.
 */
fun classifyPreviewLink(url: String, documentUrlPath: String): PreviewLink {
    val uri = try { URI(url.trim()) } catch (_: Exception) { return PreviewLink.Ignored }
    val scheme = uri.scheme?.lowercase() ?: return PreviewLink.Ignored
    if (scheme == "https" && uri.host.equals(PREVIEW_HOST, ignoreCase = true)) {
        val path = uri.path.orEmpty()
        if (path == documentUrlPath && uri.fragment != null) return PreviewLink.InPage
        if (!path.startsWith(WORKSPACE_PREFIX)) return PreviewLink.Ignored
        val relative = path.removePrefix(WORKSPACE_PREFIX)
        return if (relative.isBlank()) PreviewLink.Ignored else PreviewLink.Workspace(relative)
    }
    return when (scheme) {
        "http", "https", "mailto", "tel" -> if (url.length <= 4096) PreviewLink.External(url.trim()) else PreviewLink.Ignored
        else -> PreviewLink.Ignored
    }
}
