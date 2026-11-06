package com.labteto.dshmobile.ui.screens.main.preview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteReadOptions
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.requireValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * An HTML or SVG document in a locked-down web view.
 *
 * The sandbox is the same one the preview has always had, and nothing here loosens it:
 *  - JavaScript is off, so a page cannot run anything of its own;
 *  - file and content access are off, and network loads are blocked;
 *  - every navigation is refused;
 *  - the page lives at `https://preview.invalid`, and the only thing it can fetch is a file next to
 *    the document in the workspace, which Kotlin reads through the harness (4 MB a file, 32 MB in
 *    all, 10 s a file). Everything else gets an empty answer.
 *
 * The one addition is [zoomable], for the full-screen viewer: it lets the reader pinch the page
 * larger. Zooming is the web view's own and gives a page no new power.
 */
@Composable
internal fun SandboxedWebView(
    store: SessionStore,
    key: ComposerKey,
    documentPath: String,
    html: String,
    mime: String,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
) {
    val base = android.net.Uri.Builder().scheme("https").authority("preview.invalid")
        .path("/workspace/" + documentPath.replace('\\', '/').removePrefix("/")).build()
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                if (android.os.Build.VERSION.SDK_INT <= 30) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                if (zoomable) {
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
                webViewClient = object : WebViewClient() {
                    private var totalBytes = 0L
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        val denied = { WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                        val uri = request?.url ?: return denied()
                        if (uri.scheme != "https" || uri.host != "preview.invalid") return denied()
                        val relative = uri.path?.let { com.labteto.dshmobile.core.session.relativePreviewResource(base.path.orEmpty(), it) }
                            ?.takeIf { it.isNotBlank() } ?: return denied()
                        return try {
                            val bytes = runBlocking(Dispatchers.IO) {
                                withTimeout(10000) {
                                    val api = store.apiForHost(key.host) ?: return@withTimeout null
                                    val data = api.workspaceFileReadBytes(
                                        key.sessionId, relative, WorkspaceByteReadOptions(baseFile = documentPath),
                                    ).requireValue()
                                    if ((data.bytes ?: 0) > 4 * 1024 * 1024 || data.data.size > 4 * 1024 * 1024) return@withTimeout null
                                    data.data
                                }
                            } ?: return denied()
                            synchronized(this) { totalBytes += bytes.size; if (totalBytes > 32 * 1024 * 1024) return denied() }
                            val resourceMime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(relative.substringAfterLast('.')) ?: "application/octet-stream"
                            WebResourceResponse(resourceMime, "utf-8", bytes.inputStream())
                        } catch (_: Exception) {
                            denied()
                        }
                    }
                }
            }
        },
        update = { view ->
            // Load again only for a different document; a recomposition must not reset the page or its zoom.
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(base.toString(), html, mime, "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
