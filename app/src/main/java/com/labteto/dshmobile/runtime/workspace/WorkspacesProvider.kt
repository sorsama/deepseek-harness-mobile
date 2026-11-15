package com.labteto.dshmobile.runtime.workspace

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.webkit.MimeTypeMap
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.AndroidFsOps
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The built-in harness's `workspaces` folder in the system Files app and in every app's file
 * picker, as a storage location named after the app.
 *
 * Only that folder: not the runtime's root file system and not the harness home (`/root`, where
 * the API keys and session history live). Every rule about what an id may name is in
 * [WorkspaceTree]; this class is the Android side: cursors, file descriptors and change
 * notifications.
 *
 * Who can reach it: the manifest protects it with `MANAGE_DOCUMENTS`, a permission only the
 * system's document UI holds, so no app can query it directly. Another app gets a document only
 * when the person picks it in the system picker (a per-document grant), or a whole folder when the
 * person grants a tree. See docs/SECURITY.md.
 *
 * - **Streaming:** a file is opened as a real file descriptor, so a large file is never read into
 *   memory and seeking works. Before a descriptor is handed out its real path (from
 *   `/proc/self/fd`) is checked to be inside the folder, once more after [WorkspaceTree.resolve].
 * - **Change notifications:** listings carry a notification URI, writes through the provider
 *   notify it, and a folder someone is looking at is watched with a [FileObserver], so files the
 *   agent writes appear in an open Files window without a refresh.
 * - **Harness stopped:** nothing here needs the harness or the runtime to be running; it reads the
 *   folder directly.
 * - Enabled only on devices that can run the runtime ([syncEnabled]), and the root is listed only
 *   once something is installed (or the folder was kept when the runtime was removed).
 */
class WorkspacesProvider : DocumentsProvider() {

    private lateinit var tree: WorkspaceTree
    private lateinit var rootCanonical: String
    private val watchers = LinkedHashMap<String, DirWatcher>(16, 0.75f, true)
    private val handlerThread by lazy { HandlerThread("workspaces-provider").apply { start() } }
    private val handler by lazy { Handler(handlerThread.looper) }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        tree = WorkspaceTree(workspacesDir(ctx), AndroidFsOps, MimeTypes { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) })
        rootCanonical = runCatching { tree.root.canonicalPath }.getOrDefault(tree.root.path)
        return true
    }

    // ---- Roots --------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = context!!
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildRootsUri(authority(ctx)))
        if (!rootVisible(ctx)) return cursor
        tree.ensureRoot()
        val available = runCatching { StatFs(tree.root.path).availableBytes }.getOrDefault(0L)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceTree.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, WorkspaceTree.ROOT_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, ctx.getString(R.string.projects_provider_summary))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, available)
        }
        return cursor
    }

    // ---- Reading ------------------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val entry = tree.entry(documentId) ?: throw FileNotFoundException("no such document")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { addRow(it, entry) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = tree.resolve(parentDocumentId) ?: throw FileNotFoundException("no such folder")
        if (parent.kind != WorkspaceTree.Kind.DIRECTORY) throw FileNotFoundException("not a folder")
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        tree.list(parentDocumentId).forEach { addRow(cursor, it) }
        val ctx = context!!
        cursor.setNotificationUri(ctx.contentResolver, childrenUri(ctx, parentDocumentId))
        watch(parentDocumentId, parent.file)
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        tree.search(query).forEach { addRow(cursor, it) }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        tree.recent().forEach { addRow(cursor, it) }
        return cursor
    }

    override fun getDocumentType(documentId: String): String =
        tree.entry(documentId)?.mimeType ?: throw FileNotFoundException("no such document")

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = tree.isChild(parentDocumentId, documentId)

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val path = tree.pathTo(childDocumentId) ?: throw FileNotFoundException("no such document")
        val from = parentDocumentId?.let { parent -> path.indexOf(parent).takeIf { it >= 0 } ?: throw FileNotFoundException("not below that folder") } ?: 0
        return DocumentsContract.Path(if (parentDocumentId == null) WorkspaceTree.ROOT_ID else null, path.drop(from))
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val doc = tree.resolve(documentId) ?: throw FileNotFoundException("no such document")
        if (doc.kind != WorkspaceTree.Kind.FILE) throw FileNotFoundException("a folder has no content")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        val writing = mode.contains('w') || mode.contains('a')
        val pfd = if (writing) {
            val parentId = tree.parentId(documentId)
            ParcelFileDescriptor.open(doc.content, accessMode, handler) { parentId?.let { notifyChildren(it) } }
        } else {
            ParcelFileDescriptor.open(doc.content, accessMode)
        }
        // The last word on where this descriptor points: the kernel's, after open.
        val real = try {
            Os.readlink("/proc/self/fd/${pfd.fd}")
        } catch (e: ErrnoException) {
            null
        }
        if (real == null || !WorkspaceTree.isInside(rootCanonical, real)) {
            runCatching { pfd.close() }
            throw FileNotFoundException("no such document")
        }
        return pfd
    }

    // ---- Changing -----------------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val directory = mimeType == Document.MIME_TYPE_DIR
        var name = displayName
        if (!directory && !name.contains('.')) {
            tree.mime.extensionFor(mimeType) { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { name = "$name.$it" }
        }
        val created = guard { tree.create(parentDocumentId, name, directory) }
        notifyChildren(parentDocumentId)
        return created.id
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val renamed = guard { tree.rename(documentId, displayName) }
        tree.parentId(documentId)?.let { notifyChildren(it) }
        if (renamed.id == documentId) return null
        revokeDocumentPermission(documentId)
        return renamed.id
    }

    override fun deleteDocument(documentId: String) {
        val parent = tree.parentId(documentId)
        guard { tree.delete(documentId) }
        revokeDocumentPermission(documentId)
        parent?.let { notifyChildren(it) }
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val copied = guard { tree.copy(sourceDocumentId, targetParentDocumentId) }
        notifyChildren(targetParentDocumentId)
        return copied.id
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        if (tree.parentId(sourceDocumentId) != sourceParentDocumentId) throw FileNotFoundException("not in that folder")
        val moved = guard { tree.move(sourceDocumentId, targetParentDocumentId) }
        revokeDocumentPermission(sourceDocumentId)
        notifyChildren(sourceParentDocumentId)
        notifyChildren(targetParentDocumentId)
        return moved.id
    }

    /** A failure as the framework expects one: FileNotFound for a missing document, IllegalState otherwise. */
    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException(e.message, e)
    } catch (e: IOException) {
        Log.w(TAG, "document operation failed: ${e.message}")
        throw IllegalStateException(e.message, e)
    }

    // ---- Rows and notifications ---------------------------------------------------------------

    private fun addRow(cursor: MatrixCursor, entry: WorkspaceTree.Entry) {
        val isRoot = entry.id == WorkspaceTree.ROOT_ID
        var flags = 0
        if (entry.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (!isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, entry.id)
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) context?.getString(R.string.app_name) ?: entry.name else entry.name)
            add(Document.COLUMN_MIME_TYPE, entry.mimeType)
            add(Document.COLUMN_SIZE, if (entry.isDirectory) null else entry.size)
            add(Document.COLUMN_LAST_MODIFIED, entry.lastModified)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun notifyChildren(documentId: String) {
        val ctx = context ?: return
        ctx.contentResolver.notifyChange(childrenUri(ctx, documentId), null, false)
    }

    /**
     * Watch the folder [id] while a listing of it may be on screen: the last [MAX_WATCHERS]
     * folders listed. Changes are gathered for a moment, so an agent writing many files sends one
     * notification instead of hundreds.
     */
    private fun watch(id: String, dir: File) = synchronized(watchers) {
        if (watchers[id] != null) return@synchronized
        val watcher = DirWatcher(dir.path) { schedule(id) }
        watchers[id] = watcher
        watcher.startWatching()
        while (watchers.size > MAX_WATCHERS) {
            val eldest = watchers.entries.first()
            eldest.value.stopWatching()
            watchers.remove(eldest.key)
        }
    }

    private val pending = HashSet<String>()

    private fun schedule(id: String) {
        synchronized(pending) {
            if (!pending.add(id)) return
        }
        handler.postDelayed({
            synchronized(pending) { pending.remove(id) }
            notifyChildren(id)
        }, NOTIFY_DELAY_MS)
    }

    @Suppress("DEPRECATION") // FileObserver(File) is API 29; the String form works everywhere.
    private class DirWatcher(path: String, private val onChange: () -> Unit) : FileObserver(path, MASK) {
        override fun onEvent(event: Int, path: String?) {
            if (event and MASK != 0) onChange()
        }

        companion object {
            const val MASK = CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF
        }
    }

    companion object {
        private const val TAG = "WorkspacesProvider"
        private const val MAX_WATCHERS = 16
        private const val NOTIFY_DELAY_MS = 300L

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context): String = context.packageName + ".workspaces"

        /** The folder this provider shows: `noBackupFilesDir/runtime/workspaces` ([com.labteto.dshmobile.runtime.RuntimeLayout]). */
        fun workspacesDir(context: Context): File = File(File(context.noBackupFilesDir, "runtime"), "workspaces")

        private fun rootVisible(context: Context): Boolean =
            File(File(context.noBackupFilesDir, "runtime"), "installed.json").isFile || workspacesDir(context).isDirectory

        fun childrenUri(context: Context, documentId: String): Uri =
            DocumentsContract.buildChildDocumentsUri(authority(context), documentId)

        fun documentUri(context: Context, documentId: String): Uri =
            DocumentsContract.buildDocumentUri(authority(context), documentId)

        /** Something outside the provider (an import, a rename on the Projects page) changed [documentId]'s folder. */
        fun notifyChanged(context: Context, folderId: String = WorkspaceTree.ROOT_ID) {
            context.contentResolver.notifyChange(childrenUri(context, folderId), null, false)
        }

        /** The runtime was installed or removed: the root may have appeared or gone. */
        fun notifyRoots(context: Context) {
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null, false)
        }

        /** Turn the provider on where the runtime can run and off elsewhere (it starts disabled in the manifest). */
        fun syncEnabled(context: Context, supported: Boolean) {
            val component = ComponentName(context, WorkspacesProvider::class.java)
            val pm = context.packageManager
            val want = if (supported) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != want) {
                pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
