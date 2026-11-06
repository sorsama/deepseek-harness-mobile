package com.labteto.dshmobile.ui.screens.main.preview

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteRange
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteReadOptions
import com.labteto.dshmobile.core.wire.dto.WorkspaceFileRange
import com.labteto.dshmobile.core.wire.dto.WorkspaceFileStat
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.screens.main.requireValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The window a copy is streamed in; the same size the wire's byte range defaults to. */
private const val SAVE_WINDOW = 256 * 1024

/**
 * Whether a refusal from the host means "this file is bigger than I will send". The host does not
 * publish a dedicated code, so the wording of the code and message is all there is to go on.
 */
internal fun looksTooLarge(code: String, message: String?): Boolean {
    val text = (code + " " + message.orEmpty()).lowercase()
    return "too-large" in text || "too large" in text || "toolarge" in text || "exceeds" in text
}

/**
 * Brings one [PreviewTab] up to date from the host: what the file is, then the text or bytes its
 * kind needs. All of it is written into the tab, so the tab can be switched away from and back to
 * without losing its place, and the inline preview and the full viewer read the same state.
 *
 * Reads stay inside the caps ([MAX_PREVIEW_BYTES], [MAX_PREVIEW_TEXT]). A file over its cap is not
 * an error: the tab is marked [PreviewTab.tooLarge] and the body offers to save a copy, which
 * streams the file without holding it in memory.
 */
internal class PreviewLoader(
    private val store: SessionStore,
    private val key: ComposerKey,
    private val tab: PreviewTab,
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDirectory: () -> Unit,
) {
    /** Loads what is missing; with [more], the next page of a paged text file. */
    fun load(more: Boolean = false) {
        if (tab.busy) return
        tab.busy = true
        tab.error = null
        scope.launch {
            try {
                run(more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tab.error = e.message ?: context.getString(R.string.panel_failed)
            } finally {
                tab.busy = false
            }
        }
    }

    /** Forgets what was loaded and reads the file again; the stat change makes the body load it. */
    fun refresh() {
        tab.error = null
        tab.stat = null
        tab.text = null
        tab.bytes = null
    }

    private suspend fun run(more: Boolean) {
        val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
        val statResult = api.workspaceFileStat(key.sessionId, tab.path)
        // A reference guessed to be a file that names a folder (`.github`): browse it.
        if (statResult is RpcResult.Err && statResult.error.code == "workspace-file/not-regular-file" &&
            ((statResult.error.details as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "directory"
        ) {
            onDirectory()
            return
        }
        val stat = statResult.requireValue()
        val changed = stat.version != tab.stat?.version
        if (changed) {
            tab.text = null
            tab.bytes = null
            tab.head = null
            tab.nextLine = 1
            tab.eof = false
            tab.tooLarge = false
            tab.capped = false
            tab.detail = null
            // A type read from the bytes may be different for new bytes; one from the name is not.
            if (tab.type?.sniffed == true) tab.type = null
        }
        tab.stat = stat
        var type = tab.type ?: detectFileType(tab.path)
        if (type == null) {
            val head = readHead(api)
            tab.head = head
            type = detectFileType(tab.path, null, head) ?: FileType(PreviewKind.Binary, "bin", sniffed = true)
        }
        tab.type = type
        when (type.source) {
            PreviewSource.Bytes -> loadBytes(api, stat)
            PreviewSource.Head -> if (tab.head == null) tab.head = readHead(api)
            PreviewSource.Text -> if (type.needsWholeText) loadWhole(api, stat) else loadPage(api, stat, more, changed)
        }
    }

    private suspend fun readHead(api: DshApiClient): ByteArray =
        api.workspaceFileReadBytes(
            key.sessionId, tab.path, WorkspaceByteReadOptions(range = WorkspaceByteRange(0, HEAD_BYTES)),
        ).requireValue().data

    private suspend fun loadBytes(api: DshApiClient, stat: WorkspaceFileStat) {
        if (tab.bytes != null || tab.tooLarge) return
        if ((stat.bytes ?: 0) > MAX_PREVIEW_BYTES) {
            tab.tooLarge = true
            return
        }
        val result = api.workspaceFileReadBytes(key.sessionId, tab.path)
        if (result is RpcResult.Err && looksTooLarge(result.error.code, result.error.message)) {
            tab.tooLarge = true
            return
        }
        val data = result.requireValue()
        if (data.version != stat.version) error(context.getString(R.string.panel_changed))
        if (data.data.size > MAX_PREVIEW_BYTES) {
            tab.tooLarge = true
            return
        }
        tab.bytes = data.data
        tab.eof = true
    }

    /** A document that only means something whole (JSON, a notebook): one read, never a page at a time. */
    private suspend fun loadWhole(api: DshApiClient, stat: WorkspaceFileStat) {
        if (tab.text != null || tab.tooLarge) return
        if ((stat.bytes ?: 0) > MAX_PREVIEW_TEXT) {
            tab.tooLarge = true
            return
        }
        val result = api.workspaceFileReadBytes(key.sessionId, tab.path)
        if (result is RpcResult.Err && looksTooLarge(result.error.code, result.error.message)) {
            tab.tooLarge = true
            return
        }
        val data = result.requireValue()
        if (data.version != stat.version) error(context.getString(R.string.panel_changed))
        if (data.data.size > MAX_PREVIEW_TEXT) {
            tab.tooLarge = true
            return
        }
        tab.text = String(data.data, Charsets.UTF_8)
        tab.eof = true
    }

    private suspend fun loadPage(api: DshApiClient, stat: WorkspaceFileStat, more: Boolean, changed: Boolean) {
        if (tab.tooLarge || (tab.text != null && !more)) return
        val data = api.workspaceFileRead(key.sessionId, tab.path, WorkspaceFileRange(tab.nextLine)).requireValue()
        if (data.version != stat.version) error(context.getString(R.string.panel_changed))
        val current = tab.text.orEmpty()
        if (current.length + data.text.length > MAX_PREVIEW_TEXT) {
            // Nothing shown yet means the very first page is over the cap; otherwise stop growing.
            if (current.isEmpty()) tab.tooLarge = true else tab.capped = true
            return
        }
        tab.text = if (more && !changed) current + "\n" + data.text else data.text
        tab.nextLine = data.offset + data.lines
        tab.eof = data.eof || data.lines == 0
    }

    /**
     * Writes the file to [uri] (a document the person picked). Bytes already in the tab are written
     * as they are; otherwise the file is streamed from the host a window at a time, so a file over
     * the preview cap can still be saved. Returns whether it worked; a failed copy is deleted.
     */
    suspend fun saveTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        tab.saving = true
        try {
            val resolver = context.contentResolver
            val out = resolver.openOutputStream(uri, "wt") ?: error("no output stream")
            try {
                out.use { stream ->
                    val loaded = tab.bytes
                    if (loaded != null) stream.write(loaded) else streamTo(stream)
                }
                true
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            tab.saving = false
        }
    }

    private suspend fun streamTo(out: java.io.OutputStream) {
        val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
        val version = tab.stat?.version
        var offset = 0L
        while (true) {
            val data = api.workspaceFileReadBytes(
                key.sessionId, tab.path, WorkspaceByteReadOptions(range = WorkspaceByteRange(offset, SAVE_WINDOW)),
            ).requireValue()
            if (version != null && data.version != version) error(context.getString(R.string.panel_changed))
            if (data.data.isEmpty()) break
            out.write(data.data)
            offset += data.data.size
            if (data.eof) break
        }
    }
}
