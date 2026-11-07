package com.labteto.dshmobile.ui.screens.main.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.HapticTicker
import com.labteto.dshmobile.ui.components.rememberHapticTicker
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The largest zoom the buttons and the pinch reach. */
private const val MAX_ZOOM = 4f

/** Widest a page is rendered, in pixels, however far the reader has zoomed. */
private const val MAX_PAGE_PX = 3000

/** Most pixels one rendered page may hold (about 48 MB as ARGB). */
private const val MAX_PAGE_PIXELS = 12_000_000

/** Most pages whose size is read up front, so the list can reserve the right height for each. */
private const val MAX_SIZED_PAGES = 1000

/**
 * An open PDF. [PdfRenderer] allows one page open at a time, so every render takes [lock]; rendered
 * pages are kept in a small cache so scrolling back is instant. The temp file and the renderer are
 * released together by [close].
 */
internal class PdfSession private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Width over height of each page, for as many pages as were measured. */
    val aspects: List<Float>,
) {
    val pageCount: Int = renderer.pageCount
    private val lock = Mutex()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    /** Page [index] drawn [widthPx] wide, or null if it cannot be. Safe to call from any thread. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until pageCount) return@withLock null
            val key = "$index@$widthPx"
            cache.get(key)?.let { return@withLock it }
            try {
                renderer.openPage(index).use { page ->
                    var width = widthPx.coerceIn(1, MAX_PAGE_PX)
                    var height = (page.height * (width.toFloat() / page.width)).roundToInt().coerceAtLeast(1)
                    if (width.toLong() * height > MAX_PAGE_PIXELS) {
                        val shrink = Math.sqrt(MAX_PAGE_PIXELS.toDouble() / (width.toDouble() * height))
                        width = (width * shrink).toInt().coerceAtLeast(1)
                        height = (height * shrink).toInt().coerceAtLeast(1)
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // A PDF page is paper: white behind whatever the page draws.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    /** Releases the renderer and deletes the temp file, once any render in flight has finished. */
    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                closed = true
                cache.evictAll()
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                file.delete()
            }
        }
    }

    companion object {
        /** Writes [bytes] to a temp file and opens it. Throws if it is not a PDF this phone can read. */
        suspend fun open(context: Context, bytes: ByteArray): PdfSession {
            val file = PreviewTempFiles.write(context, bytes, "pdf")
            return withContext(Dispatchers.IO) {
                try {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = try {
                        PdfRenderer(descriptor)
                    } catch (e: Exception) {
                        descriptor.close()
                        throw e
                    }
                    val aspects = (0 until minOf(renderer.pageCount, MAX_SIZED_PAGES)).map { index ->
                        renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
                    }
                    PdfSession(file, descriptor, renderer, aspects)
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
        }
    }
}

/** What the PDF viewer and its controls share: the list position and the zoom. */
@Stable
internal class PdfViewState(val list: LazyListState) {
    var zoom by mutableFloatStateOf(1f)
    var pages by mutableIntStateOf(0)

    /** The tick stream for page changes and zoom snaps, set by the surface that shows the pages. */
    var ticker: HapticTicker? = null

    /** The page that fills most of the window (the first, until the list has been laid out). */
    val page: Int by derivedStateOf {
        val info = list.layoutInfo
        val viewportMid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        info.visibleItemsInfo.firstOrNull { viewportMid in it.offset..(it.offset + it.size) }?.index
            ?: list.firstVisibleItemIndex
    }

    fun zoomBy(factor: Float) {
        val before = zoom
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        // Arriving at either end of the zoom range is a snap: one firmer tick, once.
        if (zoom != before && (zoom <= 1f || zoom >= MAX_ZOOM)) ticker?.settle()
    }

    /** The double-tap: back to fit when zoomed in, otherwise to 2x. */
    fun toggleZoom() {
        zoom = if (zoom > 1.2f) 1f else 2f
        ticker?.settle()
    }
}

/** Reports a two-finger pinch as a factor, before the scrolling list underneath can take it. */
private fun Modifier.pinch(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    onZoom(factor)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private sealed interface PdfOpen {
    data object Loading : PdfOpen
    data object Failed : PdfOpen
    class Ready(val session: PdfSession) : PdfOpen
}

/**
 * A PDF as a column of pages that scrolls, with a page indicator over it. In the full viewer it also
 * zooms: pinch, double-tap, or the buttons that drive [state]. Pages render as they scroll into view.
 */
@Composable
internal fun PdfSurface(
    bytes: ByteArray,
    tab: PreviewTab,
    state: PdfViewState,
    surface: PreviewSurface,
    actions: PreviewActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var opened by remember(bytes) { mutableStateOf<PdfOpen>(PdfOpen.Loading) }
    LaunchedEffect(bytes) {
        val session = try {
            PdfSession.open(context, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // Opened just as this left the screen: nobody will close it later, so close it now.
        if (session != null && !isActive) session.close() else opened = session?.let { PdfOpen.Ready(it) } ?: PdfOpen.Failed
    }
    DisposableEffect(bytes) { onDispose { (opened as? PdfOpen.Ready)?.session?.close() } }
    when (val result = opened) {
        PdfOpen.Loading -> ImageSkeleton(modifier)
        PdfOpen.Failed -> NoticeCard(
            icon = PreviewIcons.File,
            title = stringResource(R.string.preview_cant_show_title),
            body = stringResource(R.string.panel_failed),
            modifier = modifier,
            action = { SaveCopyButton(actions) },
        )
        is PdfOpen.Ready -> PdfPages(result.session, tab, state, surface, actions, modifier)
    }
}

@Composable
private fun PdfPages(
    session: PdfSession,
    tab: PreviewTab,
    state: PdfViewState,
    surface: PreviewSurface,
    actions: PreviewActions,
    modifier: Modifier,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val pagesLabel = stringResource(R.string.preview_pdf_pages, session.pageCount)
    LaunchedEffect(session, pagesLabel) {
        state.pages = session.pageCount
        tab.detail = pagesLabel
    }
    val full = surface == PreviewSurface.Full
    val horizontal = rememberScrollState()
    // One tick for each page the reading position passes, and a firmer one when the scroll stops
    // on a different page than it started on. The ticker is remembered so its throttle holds
    // through a fast fling over many pages.
    val ticker = rememberHapticTicker()
    SideEffect { state.ticker = ticker }
    LaunchedEffect(state, ticker) {
        var lastPage = state.page
        var changedWhileScrolling = false
        launch {
            snapshotFlow { state.page }.collect { page ->
                if (page != lastPage) {
                    lastPage = page
                    changedWhileScrolling = true
                    ticker.cross()
                }
            }
        }
        snapshotFlow { state.list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && changedWhileScrolling) {
                changedWhileScrolling = false
                ticker.settle()
            }
        }
    }
    // Render at a width rounded to 64px so a pinch does not ask for a new bitmap every frame.
    val renderWidth = ((viewport.width - with(density) { (DsSpacing.small * 2).roundToPx() }) * state.zoom / 64f)
        .roundToInt().times(64).coerceAtLeast(64)
    val pageWidth = with(density) { ((viewport.width - (DsSpacing.small * 2).roundToPx()) * state.zoom).toDp() }
    PreviewFrame(
        modifier
            .onSizeChanged { viewport = it }
            .then(if (full) Modifier.pinch { state.zoomBy(it) } else Modifier)
            .pointerInput(full) {
                detectTapGestures(
                    onDoubleTap = { if (full) state.toggleZoom() },
                    onTap = { if (!full) actions.onOpenFull?.invoke() },
                )
            },
        framed = !full,
    ) {
        if (viewport.width > 0) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                LazyColumn(
                    Modifier.width(pageWidth + DsSpacing.small * 2).fillMaxHeight(),
                    state = state.list,
                    contentPadding = PaddingValues(DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    items(session.pageCount, key = { it }) { index ->
                        PdfPage(session, index, renderWidth)
                    }
                }
            }
        }
        if (session.pageCount > 0) {
            Text(
                "${state.page + 1} / ${session.pageCount}",
                style = DsType.mono12,
                color = colors.labelPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(DsSpacing.medium)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.bgLayer3)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.xsmall)
                    .semantics { contentDescription = "${state.page + 1} / ${session.pageCount}" },
            )
        }
    }
}

@Composable
private fun PdfPage(session: PdfSession, index: Int, widthPx: Int) {
    val colors = DsTheme.colors
    val aspect = session.aspects.getOrNull(index) ?: (1f / 1.414f)
    // The old bitmap stays up while a sharper one is drawn for a new zoom.
    var bitmap by remember(session, index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, index, widthPx) {
        session.render(index, widthPx)?.let { bitmap = it.asImageBitmap() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(DsShapes.chip)
            .then(if (bitmap == null) Modifier.skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip) else Modifier),
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
