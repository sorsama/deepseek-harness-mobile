package com.labteto.dshmobile.runtime.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.runtime.AndroidFsOps
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.FsTree
import com.labteto.dshmobile.runtime.GuestCommandRunner
import com.labteto.dshmobile.runtime.RuntimeState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** What a long project operation is. */
enum class JobKind { CLONE, IMPORT_FOLDER, IMPORT_ZIP, EXPORT_ZIP, SAVE_COPY }

/** Why a project operation did not finish. */
sealed interface JobFailure {
    data class Transfer(val problem: TransferProblem) : JobFailure
    data class Clone(val failure: GitClone.Failure, val lastLine: String?) : JobFailure

    /** The runtime is being installed, updated or removed, or is not installed. */
    data object RuntimeBusy : JobFailure
    data class Other(val detail: String?) : JobFailure
}

/**
 * The one long project operation, if any: running with its progress, or how it ended (kept until
 * dismissed, so a screen opened later still says what happened).
 */
sealed interface ProjectJob {
    val kind: JobKind
    val name: String

    data class Running(
        override val kind: JobKind,
        override val name: String,
        val fraction: Float?,
        val progress: TransferProgress? = null,
        val clonePhase: GitClone.Phase? = null,
    ) : ProjectJob

    data class Done(
        override val kind: JobKind,
        override val name: String,
        /** Links in a zip that would have pointed outside the project and were left out. */
        val skippedLinks: Int = 0,
        /** The zip to share (EXPORT_ZIP). */
        val shareUri: Uri? = null,
        val files: Int = 0,
    ) : ProjectJob

    data class Failed(override val kind: JobKind, override val name: String, val failure: JobFailure) : ProjectJob
    data class Cancelled(override val kind: JobKind, override val name: String) : ProjectJob
}

/**
 * Projects of the built-in harness: making them (empty, cloned, imported), getting them out
 * (zip, a copy to the phone), renaming and deleting them.
 *
 * App-scoped, so a clone or an import keeps going when the person leaves the screen; one long
 * operation at a time, in [job]. Everything long checks free space first, reports progress, can be
 * cancelled, and removes what it wrote when it does not finish: imports and clones are built in a
 * staging folder ([WorkspaceTree.RESERVED_PREFIX]) and renamed into place only when complete.
 *
 * The clone runs `git` in the runtime through [com.labteto.dshmobile.runtime.HarnessRuntimeController.runGuestCommand];
 * imports and exports are plain file copies on the app side, which need no runtime start at all.
 */
@Singleton
class ProjectOperations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val host: BuiltInHarnessHost,
    private val connection: ConnectionManager,
    private val sessions: Provider<SessionStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller get() = host.controller
    private val fs = AndroidFsOps
    private val root: File get() = controller.layout.workspaces
    private val transfer = ProjectTransfer(fs)

    private val _job = MutableStateFlow<ProjectJob?>(null)
    val job: StateFlow<ProjectJob?> = _job.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    @Volatile
    private var running: Job? = null

    /** Started from the application: keeps the Files app's root in step with the install. */
    fun start() {
        scope.launch {
            WorkspacesProvider.syncEnabled(context, controller.supported)
            controller.state.map { it !is RuntimeState.NotInstalled && it !is RuntimeState.NotSupported }.distinctUntilChanged().collect {
                WorkspacesProvider.notifyRoots(context)
                if (it) refresh()
            }
        }
        scope.launch { cleanExports(maxAgeMs = 0) }
    }

    /** [onBuiltIn] as a state, for screens that show the project actions only there. */
    val onBuiltInState: StateFlow<Boolean> = combine(connection.state, controller.state) { _, _ -> onBuiltIn() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Whether the app is connected to the built-in harness right now (sessions can be opened in a project). */
    fun onBuiltIn(): Boolean {
        val state = controller.state.value as? RuntimeState.Running ?: return false
        val active = connection.state.value.host ?: return false
        return active.isLoopback && !active.isRelay && active.port == state.port && host.servedThisProcess(state.port)
    }

    /** Re-read the list; sizes follow one project at a time. */
    fun refresh() {
        scope.launch {
            val names = ProjectList.names(root, fs)
            val known = _projects.value.associateBy { it.name }
            _projects.value = names.map { n -> known[n.name]?.let { k -> k.copy(lastChange = maxOf(k.lastChange, n.lastChange)) } ?: n }
            for (p in names) {
                if (!isActive) break
                val measured = runCatching { ProjectList.measure(root, fs, p) }.getOrNull() ?: continue
                _projects.value = _projects.value.map { if (it.name == measured.name) measured else it }
            }
        }
    }

    fun dismiss() {
        if (_job.value !is ProjectJob.Running) _job.value = null
    }

    fun cancel() {
        running?.cancel()
    }

    val busy: Boolean get() = _job.value is ProjectJob.Running

    // ---- Making a project -------------------------------------------------------------------

    sealed interface CreateResult {
        data class Created(val project: ProjectInfo) : CreateResult
        data class Problem(val problem: ProjectList.NameProblem) : CreateResult
        data class Failed(val failure: JobFailure) : CreateResult
    }

    /** A new empty folder; [open] also opens a session in it when the app is on the built-in harness. */
    suspend fun createEmpty(name: String, open: Boolean): CreateResult = withContext(Dispatchers.IO) {
        if (!controller.state.value.installedNow()) return@withContext CreateResult.Failed(JobFailure.RuntimeBusy)
        root.mkdirs()
        ProjectList.nameProblem(root, fs, name)?.let { return@withContext CreateResult.Problem(it) }
        val clean = name.trim()
        val dir = File(root, clean)
        if (!dir.mkdir()) return@withContext CreateResult.Failed(JobFailure.Transfer(TransferProblem.NOT_WRITABLE))
        runCatching { fs.chmod(dir, WorkspaceTree.DIR_MODE) }
        WorkspacesProvider.notifyChanged(context)
        refresh()
        val info = ProjectInfo(clean, WorkspaceTree.GUEST_ROOT + "/" + clean, 0, dir.lastModified(), 0)
        if (open) openSession(info.guestPath)
        CreateResult.Created(info)
    }

    /** Clone [url] into a new project; [token] (for a private repository) is used for this clone only. */
    fun clone(url: GitClone.UrlCheck.Ok, branch: String, name: String, token: CharArray?, open: Boolean): Boolean =
        launchJob(JobKind.CLONE, name) { jobName ->
            requireSpace(available(), 0)
            val staging = stagingDir()
            val command = GitClone.command(url, branch, WorkspaceTree.GUEST_ROOT + "/" + staging.name, token)
            token?.fill('\u0000')
            var lastPercent = -1
            var lastPhase: GitClone.Phase? = null
            try {
                val outcome = controller.runGuestCommand(command, CLONE_TIMEOUT_MS) { line ->
                    val p = GitClone.parseProgress(line) ?: return@runGuestCommand
                    if (p.phase != lastPhase || (p.percent ?: -1) != lastPercent) {
                        lastPhase = p.phase
                        lastPercent = p.percent ?: -1
                        _job.value = ProjectJob.Running(JobKind.CLONE, jobName, GitClone.overallFraction(p), clonePhase = p.phase)
                    }
                } ?: return@launchJob ProjectJob.Failed(JobKind.CLONE, jobName, JobFailure.RuntimeBusy)
                when (outcome) {
                    is GuestCommandRunner.Outcome.Exited -> if (outcome.code == 0 && staging.isDirectory) {
                        val final = finish(staging, jobName)
                        if (open) openSession(WorkspaceTree.GUEST_ROOT + "/" + final)
                        ProjectJob.Done(JobKind.CLONE, final)
                    } else {
                        fs.removeQuietly(staging)
                        val failure = GitClone.classify(outcome.tail)
                        ProjectJob.Failed(JobKind.CLONE, jobName, JobFailure.Clone(failure, outcome.tail.lastOrNull { it.isNotBlank() && GitClone.parseProgress(it) == null }))
                    }
                    is GuestCommandRunner.Outcome.TimedOut -> {
                        fs.removeQuietly(staging)
                        ProjectJob.Failed(JobKind.CLONE, jobName, JobFailure.Clone(GitClone.Failure.TIMED_OUT, null))
                    }
                    is GuestCommandRunner.Outcome.LaunchFailed -> {
                        fs.removeQuietly(staging)
                        ProjectJob.Failed(JobKind.CLONE, jobName, JobFailure.Other(outcome.detail))
                    }
                    GuestCommandRunner.Outcome.NotInstalled -> ProjectJob.Failed(JobKind.CLONE, jobName, JobFailure.RuntimeBusy)
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { fs.removeQuietly(staging) }
                throw e
            }
        }

    /** Copy the folder the person picked ([treeUri], from the system picker) into a new project. */
    fun importFolder(treeUri: Uri, open: Boolean): Boolean {
        val source = SafSourceTree(context.contentResolver, treeUri)
        val name = source.rootName() ?: "project"
        return launchJob(JobKind.IMPORT_FOLDER, name) { jobName ->
            val staging = stagingDir()
            try {
                val total = transfer.scanSource(source, null) { !isActiveJob() }
                requireSpace(available(), total.second)
                _job.value = ProjectJob.Running(JobKind.IMPORT_FOLDER, jobName, 0f, TransferProgress(0, total.first, 0, total.second))
                val (files, _) = transfer.copyIn(source, null, staging, total, progress = { p ->
                    _job.value = ProjectJob.Running(JobKind.IMPORT_FOLDER, jobName, p.fraction, p)
                }) { !isActiveJob() }
                val final = finish(staging, jobName)
                if (open) openSession(WorkspaceTree.GUEST_ROOT + "/" + final)
                ProjectJob.Done(JobKind.IMPORT_FOLDER, final, files = files)
            } catch (e: Throwable) {
                withContext(NonCancellable) { fs.removeQuietly(staging) }
                throw e
            }
        }
    }

    /** Unpack the `.zip` the person picked into a new project. */
    fun importZip(uri: Uri, open: Boolean): Boolean {
        val resolver = context.contentResolver
        val (displayName, size) = queryOpenable(resolver, uri)
        val fallback = displayName?.removeSuffix(".zip")?.removeSuffix(".ZIP")?.takeIf { it.isNotBlank() } ?: "project"
        return launchJob(JobKind.IMPORT_ZIP, fallback) { jobName ->
            val temp = File(importsDir(), "import-${System.currentTimeMillis()}.zip")
            val staging = stagingDir()
            try {
                requireSpace(available(), size ?: 0)
                importsDir().mkdirs()
                (resolver.openInputStream(uri) ?: throw TransferException(TransferProblem.NOT_READABLE)).use { input ->
                    temp.outputStream().use { out -> copyCancellable(input, out) }
                }
                val importer = ZipImporter(fs)
                val plan = importer.plan(temp, fallback)
                requireSpace(available(), plan.totalBytes)
                val wanted = plan.suggestedName?.takeIf { fallback == "project" || displayName == null } ?: fallback
                val result = importer.extract(temp, plan, staging, progress = { p ->
                    _job.value = ProjectJob.Running(JobKind.IMPORT_ZIP, jobName, p.fraction, p)
                }) { !isActiveJob() }
                val final = finish(staging, wanted)
                if (open) openSession(WorkspaceTree.GUEST_ROOT + "/" + final)
                ProjectJob.Done(JobKind.IMPORT_ZIP, final, skippedLinks = result.skippedLinks, files = result.files)
            } catch (e: Throwable) {
                withContext(NonCancellable) { fs.removeQuietly(staging) }
                throw e
            } finally {
                temp.delete()
            }
        }
    }

    // ---- Getting results out ----------------------------------------------------------------

    /** Write [project] as a zip in the app's cache; [ProjectJob.Done.shareUri] is ready for the share sheet. */
    fun exportZip(project: String, filter: ExportFilter): Boolean = launchJob(JobKind.EXPORT_ZIP, project, uniqueName = false) { name ->
        val dir = projectDir(name)
        cleanExports(maxAgeMs = 60 * 60 * 1000L)
        val scan = transfer.scan(dir, filter) { !isActiveJob() }
        val exports = exportsDir().apply { mkdirs() }
        requireSpace(StatFs(exports.path).availableBytes, scan.bytes)
        val out = File(exports, "$name.zip")
        try {
            out.outputStream().use { stream ->
                transfer.writeZip(dir, name, filter, stream, scan, progress = { p ->
                    _job.value = ProjectJob.Running(JobKind.EXPORT_ZIP, name, p.fraction, p)
                }) { !isActiveJob() }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { out.delete() }
            throw e
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", out)
        ProjectJob.Done(JobKind.EXPORT_ZIP, name, shareUri = uri, files = scan.files)
    }

    /** Copy [project] into the folder the person picked ([treeUri]), as a folder of the same name. */
    fun saveCopy(project: String, filter: ExportFilter, treeUri: Uri): Boolean = launchJob(JobKind.SAVE_COPY, project, uniqueName = false) { name ->
        val dir = projectDir(name)
        val scan = transfer.scan(dir, filter) { !isActiveJob() }
        val sink = SafTreeSink(context.contentResolver, treeUri)
        try {
            transfer.copyOut(dir, name, filter, sink, scan, progress = { p ->
                _job.value = ProjectJob.Running(JobKind.SAVE_COPY, name, p.fraction, p)
            }) { !isActiveJob() }
        } catch (e: Throwable) {
            // A half copy in the person's own folder is theirs to see; it is removed, not left looking complete.
            withContext(NonCancellable) { sink.removeCreated() }
            throw e
        }
        ProjectJob.Done(JobKind.SAVE_COPY, name, files = scan.files)
    }

    // ---- Managing ---------------------------------------------------------------------------

    sealed interface ChangeResult {
        data object Done : ChangeResult
        data class Problem(val problem: ProjectList.NameProblem) : ChangeResult
        data object InUse : ChangeResult
        data object Failed : ChangeResult
    }

    suspend fun rename(project: String, newName: String): ChangeResult = withContext(Dispatchers.IO) {
        if (inUse(project)) return@withContext ChangeResult.InUse
        ProjectList.nameProblem(root, fs, newName, current = project)?.let { return@withContext ChangeResult.Problem(it) }
        val target = newName.trim()
        if (target == project) return@withContext ChangeResult.Done
        val ok = projectDirOrNull(project)?.renameTo(File(root, target)) == true
        WorkspacesProvider.notifyChanged(context)
        refresh()
        if (ok) moveRegistrations(WorkspaceTree.GUEST_ROOT + "/" + project, WorkspaceTree.GUEST_ROOT + "/" + target)
        if (ok) ChangeResult.Done else ChangeResult.Failed
    }

    suspend fun delete(project: String): ChangeResult = withContext(Dispatchers.IO) {
        if (inUse(project)) return@withContext ChangeResult.InUse
        val dir = projectDirOrNull(project) ?: return@withContext ChangeResult.Failed
        val ok = runCatching { FsTree.delete(fs, dir) }.isSuccess
        WorkspacesProvider.notifyChanged(context)
        refresh()
        if (ok) moveRegistrations(WorkspaceTree.GUEST_ROOT + "/" + project, null)
        if (ok) ChangeResult.Done else ChangeResult.Failed
    }

    /**
     * The harness's own workspace list names folders by path. After a project is deleted its
     * entries go (the folder is gone; their sessions stay, and say the folder is gone when used);
     * after a rename they are registered again under the new path. Only on the connected built-in
     * harness; quietly skipped otherwise.
     */
    private suspend fun moveRegistrations(oldPath: String, newPath: String?) {
        if (!onBuiltIn()) return
        runCatching {
            val store = sessions.get()
            val affected = store.workspaces.value.filter { it.path == oldPath || it.path.startsWith("$oldPath/") }
            for (workspace in affected) {
                store.deleteWorkspace(workspace.workspaceId)
                if (newPath != null) store.createWorkspace(newPath + workspace.path.removePrefix(oldPath))
            }
        }
    }

    /** Total bytes of all projects as last counted. */
    fun totalBytes(): Long? = _projects.value.takeIf { list -> list.all { it.sizeBytes != null } }?.sumOf { it.sizeBytes ?: 0 }

    /** Register [guestPath] as a workspace on the connected built-in harness and open a session in it. */
    suspend fun openSession(guestPath: String): Boolean {
        if (!onBuiltIn()) return false
        return runCatching { sessions.get().openWorkspaceSession(guestPath) }.getOrDefault(false)
    }

    // ---- Plumbing ---------------------------------------------------------------------------

    private fun inUse(project: String): Boolean = (_job.value as? ProjectJob.Running)?.name == project

    private fun launchJob(kind: JobKind, wanted: String, uniqueName: Boolean = true, block: suspend (name: String) -> ProjectJob): Boolean {
        synchronized(this) {
            if (_job.value is ProjectJob.Running) return false
            val name = if (uniqueName) ProjectList.uniqueName(root.also { it.mkdirs() }, fs, wanted) ?: "project" else wanted
            _job.value = ProjectJob.Running(kind, name, null)
            val started = android.os.SystemClock.elapsedRealtime()
            running = scope.launch(start = CoroutineStart.LAZY) {
                val end = try {
                    block(name)
                } catch (e: CancellationException) {
                    ProjectJob.Cancelled(kind, name)
                } catch (e: TransferCancelled) {
                    ProjectJob.Cancelled(kind, name)
                } catch (e: TransferException) {
                    ProjectJob.Failed(kind, name, JobFailure.Transfer(e.problem))
                } catch (e: IOException) {
                    Log.w(TAG, "${kind.name} failed: ${e.javaClass.simpleName}")
                    ProjectJob.Failed(kind, name, if (e.isNoSpace()) JobFailure.Transfer(TransferProblem.NO_SPACE) else JobFailure.Other(e.javaClass.simpleName))
                } catch (e: SecurityException) {
                    ProjectJob.Failed(kind, name, JobFailure.Transfer(TransferProblem.NOT_READABLE))
                } catch (e: IllegalArgumentException) {
                    ProjectJob.Failed(kind, name, JobFailure.Other(e.javaClass.simpleName))
                }
                _job.value = end
                // Debug builds only, for the device timings: what ran, how it ended, how long it took. No names, no paths.
                if (com.labteto.dshmobile.BuildConfig.DEBUG) {
                    val detail = when (end) {
                        is ProjectJob.Failed -> end.failure.toString().take(120)
                        is ProjectJob.Done -> "files=${end.files} skippedLinks=${end.skippedLinks}"
                        else -> ""
                    }
                    Log.i(TAG, "job ${kind.name} -> ${end.javaClass.simpleName} in ${android.os.SystemClock.elapsedRealtime() - started} ms $detail")
                }
                if (end is ProjectJob.Done || kind == JobKind.CLONE || kind == JobKind.IMPORT_FOLDER || kind == JobKind.IMPORT_ZIP) refresh()
            }
            running?.start()
            return true
        }
    }

    private fun isActiveJob(): Boolean = running?.isActive == true

    /** Rename a finished staging folder to its project name (unique at that moment); answers the name. */
    private fun finish(staging: File, wanted: String): String {
        val name = ProjectList.uniqueName(root, fs, wanted) ?: throw TransferException(TransferProblem.NOT_WRITABLE)
        if (!staging.renameTo(File(root, name))) throw TransferException(TransferProblem.NOT_WRITABLE)
        WorkspacesProvider.notifyChanged(context)
        return name
    }

    private fun stagingDir(): File {
        root.mkdirs()
        return File(root, WorkspaceTree.RESERVED_PREFIX + "incoming-" + System.currentTimeMillis())
    }

    private fun projectDirOrNull(name: String): File? {
        val id = WorkspaceTree.ROOT_ID + "/" + name
        val tree = WorkspaceTree(root, fs)
        val doc = tree.resolve(id) ?: return null
        return doc.file.takeIf { doc.kind == WorkspaceTree.Kind.DIRECTORY && tree.segmentsOf(id)?.size == 1 }
    }

    private fun projectDir(name: String): File = projectDirOrNull(name) ?: throw TransferException(TransferProblem.NOT_READABLE)

    private fun available(): Long = runCatching { StatFs(controller.layout.root.apply { mkdirs() }.path).availableBytes }.getOrDefault(0L)

    private fun importsDir() = File(controller.layout.cache, "imports")

    private fun exportsDir() = File(context.cacheDir, EXPORTS_DIR)

    /** Exports are for the share sheet only: removed once old, and all of them when the app starts. */
    private fun cleanExports(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        exportsDir().listFiles().orEmpty().filter { now - it.lastModified() >= maxAgeMs }.forEach { it.delete() }
        importsDir().listFiles().orEmpty().filter { now - it.lastModified() >= maxAgeMs && maxAgeMs == 0L }.forEach { it.delete() }
        // A staging folder from a run the app did not finish (the process was killed mid-import).
        if (maxAgeMs == 0L && _job.value !is ProjectJob.Running) {
            root.list().orEmpty().filter { it.startsWith(WorkspaceTree.RESERVED_PREFIX + "incoming-") }.forEach { fs.removeQuietly(File(root, it)) }
        }
    }

    private suspend fun copyCancellable(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (!isActiveJob()) throw TransferCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }

    private fun queryOpenable(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)

    private fun RuntimeState.installedNow(): Boolean = when (this) {
        is RuntimeState.Stopped, is RuntimeState.Running, is RuntimeState.Starting, is RuntimeState.Stopping -> true
        is RuntimeState.Failed -> installed != null
        else -> false
    }

    companion object {
        private const val TAG = "ProjectOperations"
        const val EXPORTS_DIR = "exports"
        const val CLONE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

/** A folder from `ACTION_OPEN_DOCUMENT_TREE`, read through the document tree it grants. */
private class SafSourceTree(private val resolver: ContentResolver, private val tree: Uri) : SourceTree {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    fun rootName(): String? = runCatching {
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    override fun children(parent: Any?): List<SourceTree.Node> {
        val id = parent as? String ?: rootId
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<SourceTree.Node>()
        (resolver.query(uri, columns, null, null, null) ?: throw TransferException(TransferProblem.NOT_READABLE)).use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                out += SourceTree.Node(childId, name, dir, size)
            }
        }
        return out
    }

    override fun open(node: SourceTree.Node): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, node.handle as String))
            ?: throw FileNotFoundException("could not open")
}

/** A folder from `ACTION_OPEN_DOCUMENT_TREE`, written through the document tree it grants. */
private class SafTreeSink(private val resolver: ContentResolver, private val tree: Uri) : TreeSink {
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private var top: Uri? = null

    override fun createFolder(parent: Any?, name: String): Any {
        val parentUri = parent as? Uri ?: rootUri
        val created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw TransferException(TransferProblem.NOT_WRITABLE)
        if (parent == null) top = created
        return created
    }

    override fun createFile(parent: Any?, name: String, mimeType: String): OutputStream {
        val parentUri = parent as? Uri ?: rootUri
        val created = DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw TransferException(TransferProblem.NOT_WRITABLE)
        return resolver.openOutputStream(created, "w") ?: throw TransferException(TransferProblem.NOT_WRITABLE)
    }

    fun removeCreated() {
        top?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    }
}
