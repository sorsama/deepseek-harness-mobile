package com.labteto.dshmobile.ui.screens.main.preview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.core.markdown.HtmlSanitizer
import com.labteto.dshmobile.core.session.PreviewLink
import com.labteto.dshmobile.core.session.classifyPreviewLink
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteReadOptions
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.requireValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * An HTML or SVG document, or a rendered Markdown file, in a locked-down web view.
 *
 * The sandbox is the same one the preview has always had, and nothing here loosens it:
 *  - JavaScript is off, so a page cannot run anything of its own;
 *  - file and content access are off, and network loads are blocked;
 *  - every navigation is refused, except a `#anchor` in the page itself, and a link goes to [onLink]
 *    (which may ask the reader first) instead of being followed;
 *  - the page lives at `https://preview.invalid`, and the only thing it can fetch is a file next to
 *    the document in the workspace, which Kotlin reads through the harness (4 MB a file, 32 MB in
 *    all, 10 s a file). Everything else gets an empty answer.
 *
 * Pictures from the web are the one thing a rendered Markdown file may ask for beyond that, and
 * only after the reader switches them on ([webImages]). Even then the web view never touches the
 * network itself: the page asks for `https://preview.invalid/__web/<encoded address>`, and this
 * code fetches that one HTTPS address with its own client, accepts an image answer of at most
 * 3 MB, and hands the bytes over.
 *
 * [zoomable] is for the full-screen viewer: it lets the reader pinch the page larger. Zooming is
 * the web view's own and gives a page no new power.
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
    onLink: ((String) -> Unit)? = null,
    webImages: Boolean = false,
    /** The colour behind the page while it loads and when it is shorter than the view. */
    backgroundColor: Int? = null,
) {
    val base = android.net.Uri.Builder().scheme("https").authority("preview.invalid")
        .path("/workspace/" + documentPath.replace('\\', '/').removePrefix("/")).build()
    val link by rememberUpdatedState(onLink)
    val showWebImages by rememberUpdatedState(webImages)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                if (android.os.Build.VERSION.SDK_INT <= 30) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                backgroundColor?.let { setBackgroundColor(it) }
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
                    private var webImageCount = 0
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        // A `#heading` in this page: let the view scroll itself. It is the same document, so
                        // nothing is loaded.
                        if (classifyPreviewLink(url, base.path.orEmpty()) == PreviewLink.InPage) return false
                        link?.invoke(url)
                        return true
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        val denied = { WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                        val uri = request?.url ?: return denied()
                        if (uri.scheme != "https" || uri.host != "preview.invalid") return denied()
                        val path = uri.path.orEmpty()
                        if (path.startsWith(HtmlSanitizer.WEB_IMAGE_PATH)) {
                            if (!showWebImages) return denied()
                            val allowed = synchronized(this) { ++webImageCount <= MAX_WEB_IMAGES }
                            val image = (if (allowed) fetchWebImage(path.removePrefix(HtmlSanitizer.WEB_IMAGE_PATH)) else null)
                                ?: return denied()
                            synchronized(this) { totalBytes += image.second.size; if (totalBytes > 32 * 1024 * 1024) return denied() }
                            return WebResourceResponse(image.first, null, image.second.inputStream())
                        }
                        val relative = path.let { com.labteto.dshmobile.core.session.relativePreviewResource(base.path.orEmpty(), it) }
                            .takeIf { it.isNotBlank() } ?: return denied()
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

private const val MAX_WEB_IMAGES = 60
private const val MAX_WEB_IMAGE_BYTES = 3 * 1024 * 1024

/** No cookies, no credentials, HTTPS only and never redirected to plain HTTP. */
private val webImageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
}

/**
 * The picture at the address a page asked for (base64url of the address, as [HtmlSanitizer] wrote
 * it), as its MIME type and bytes, or null when it is not an HTTPS address, not an image, too big
 * or not there.
 */
private fun fetchWebImage(encoded: String): Pair<String, ByteArray>? = try {
    val address = String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    val url = address.toHttpUrlOrNull()?.takeIf { it.isHttps }
    if (url == null) null else {
        webImageClient.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).execute().use { response ->
            val type = response.body?.contentType()
            if (!response.isSuccessful || type == null || type.type != "image") return@use null
            val body = response.body ?: return@use null
            val declared = body.contentLength()
            if (declared > MAX_WEB_IMAGE_BYTES) return@use null
            val bytes = body.source().let { source ->
                val buffer = okio.Buffer()
                var total = 0L
                while (total <= MAX_WEB_IMAGE_BYTES) {
                    val read = source.read(buffer, 8192)
                    if (read == -1L) break
                    total += read
                }
                if (total > MAX_WEB_IMAGE_BYTES) null else buffer.readByteArray()
            }
            bytes?.let { "${type.type}/${type.subtype}" to it }
        }
    }
} catch (_: Exception) {
    null
}
