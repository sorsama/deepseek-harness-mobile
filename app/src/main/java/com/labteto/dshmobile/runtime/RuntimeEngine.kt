package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * The built-in runtime's state machine: install, update, start, stop, uninstall.
 *
 * Plain Kotlin; [Platform] is everything Android ([HarnessRuntimeController] implements it), so the
 * flows, including the harness-update trial and its rollback, run in JVM tests. One operation runs
 * at a time; a call made while another is in flight is refused (returns false) rather than queued,
 * like the Termux controller's.
 */
class RuntimeEngine(
    private val scope: CoroutineScope,
    private val layout: RuntimeLayout,
    private val installer: RuntimeInstaller,
    private val supervisor: HarnessSupervisor,
    private val fs: FsOps,
    private val platform: Platform,
    private val signIn: suspend (launchLine: String, port: Int) -> StartSignIn,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Paces the download and unpack progress states; see [ProgressPacer]. */
    private val progressPacer: ProgressPacer = ProgressPacer(),
) {
    /** What the engine needs from the device. */
    interface Platform {
        /** Non-null when this device cannot run the runtime at all. */
        val unsupported: RuntimeUnsupported?
        val nativeLibraryDir: File
        fun dnsServers(): List<String>
        val cpuCount: Int

        /** Whether 127.0.0.1:[port] can be bound right now. */
        fun portFree(port: Int): Boolean

        /** Something long-running is about to happen: make sure the foreground service is up. */
        fun keepAlive()

        /** "Fast start": whether the next start may use the runtime's module pack. Read at each start. */
        val modulePack: Boolean get() = true

        /**
         * What new sessions do when the agent wants to run a command, for a start of [record]. Read
         * once per start; the controller remembers it as what the running harness was started with.
         */
        fun commandPermission(record: InstalledRecord): CommandPermission = CommandPermission.DEFAULT

        /**
         * Extra guest variables for a start of [record]. Empty in release builds; debug builds use it
         * to make a release on trial fail its start, for the device test of the rollback.
         */
        fun guestEnvironment(record: InstalledRecord): Map<String, String> = emptyMap()
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AvailableRuntimeUpdate?>(null)
    val availableUpdate: StateFlow<AvailableRuntimeUpdate?> = _availableUpdate.asStateFlow()

    /**
     * How each update and "go back" ended, in order. Replays the last one, so a screen opened after
     * the fact still reads it; the host turns these into notifications and the control page's line.
     */
    private val _outcomes = MutableSharedFlow<UpdateOutcome>(replay = 1, extraBufferCapacity = 8)
    val outcomes: SharedFlow<UpdateOutcome> = _outcomes.asSharedFlow()

    private val transition = Mutex()
    @Volatile
    private var job: Job? = null

    /**
     * The operation in flight, claimed before it starts and released in the same step that publishes
     * its terminal state ([Op.finish]), release first. So whoever sees a terminal state can start
     * the next operation at once: a state that has been published is never refused as "still busy".
     * (The mutex above only serialises the bodies; it is released a moment after the last state.)
     */
    private val claim = AtomicReference<Op?>(null)

    private inner class Op {
        /**
         * Release the claim, then publish [terminal]. Dropped when the claim is no longer this
         * operation's: a stop that took over a start in flight owns the state from then on.
         */
        fun finish(terminal: RuntimeState) {
            if (claim.compareAndSet(this, null)) _state.value = terminal
        }
    }

    init {
        if (platform.unsupported == null) scope.launch { runCatching { installer.cleanOrphans() } }
    }

    /** Install the latest release, or update to it; resumes a broken-off download. */
    fun install(): Boolean = launchOp(allowWhen = { it is RuntimeState.NotInstalled || it is RuntimeState.Stopped || it is RuntimeState.Running || it is RuntimeState.Failed }, changesLayers = true) { op ->
        runInstall(op, goBack = false)
    }

    /**
     * The person's "go back to the previous version": install the release the installed manifest's
     * `previous` names, verified like any other, even though its sequence is lower. Only for an
     * installed runtime that has such a pointer.
     */
    fun goBack(): Boolean {
        if (installer.installed()?.previousRelease == null) return false
        return launchOp(allowWhen = { it is RuntimeState.Stopped || it is RuntimeState.Running || it is RuntimeState.Failed && it.installed != null }, changesLayers = true) { op ->
            runInstall(op, goBack = true)
        }
    }

    /** Abandon an install, an update or a start in flight. Downloads stay for a resume. */
    fun cancel() {
        val current = _state.value
        val running = job ?: return
        if (current is RuntimeState.Starting) {
            stop()
            return
        }
        if (current is RuntimeState.Downloading || current is RuntimeState.Verifying || current is RuntimeState.Unpacking || current is RuntimeState.Updating) {
            scope.launch { running.cancelAndJoin() }
        }
    }

    fun start(port: Int): Boolean = launchOp(allowWhen = { it is RuntimeState.Stopped || it is RuntimeState.Failed }) { op ->
        runStart(op, port, rolledBack = null)
    }

    /** Stop the harness; also abandons a start that has not reached readiness yet. */
    fun stop(): Boolean {
        val current = _state.value
        val port = when (current) {
            is RuntimeState.Running -> current.port
            is RuntimeState.Starting -> current.port
            else -> return false
        }
        val op = Op()
        // A running harness has no operation in flight, or the stop is refused; a start in flight is
        // taken over, so its own late writes are dropped.
        val claimed = if (current is RuntimeState.Starting) {
            claim.set(op)
            true
        } else {
            claim.compareAndSet(null, op)
        }
        if (!claimed) return false
        val starting = job.takeIf { current is RuntimeState.Starting }
        val installed = installer.installed()?.toInstalledRuntime()
        if (installed == null) {
            claim.compareAndSet(op, null)
            return false
        }
        _state.value = RuntimeState.Stopping(port, installed)
        job = scope.launch {
            try {
                starting?.cancelAndJoin()
                supervisor.stop()
                op.finish(RuntimeState.Stopped(installer.installed()?.toInstalledRuntime() ?: installed))
            } finally {
                claim.compareAndSet(op, null)
            }
        }
        return true
    }

    fun restart(): Boolean {
        val current = _state.value as? RuntimeState.Running ?: return false
        return launchOp(allowWhen = { it is RuntimeState.Running }) { op ->
            val installed = installer.installed()?.toInstalledRuntime() ?: return@launchOp
            _state.value = RuntimeState.Stopping(current.port, installed)
            supervisor.stop()
            runStart(op, current.port, rolledBack = null)
        }
    }

    /** Remove the runtime. [keepUserData] keeps the harness's `/root` and the workspaces. */
    fun uninstall(keepUserData: Boolean = false): Boolean =
        launchOp(allowWhen = { it is RuntimeState.Stopped || it is RuntimeState.Running || it is RuntimeState.Failed || it is RuntimeState.NotInstalled }, changesLayers = true) { op ->
            supervisor.stop()
            supervisor.cleanupStale()
            val terminal = try {
                installer.uninstall(keepUserData)
                RuntimeState.NotInstalled
            } catch (e: IOException) {
                RuntimeState.Failed(RuntimeFailure.Storage(e.message), installer.installed()?.toInstalledRuntime())
            }
            _availableUpdate.value = null
            op.finish(terminal)
        }

    /** Look for a newer release without installing it; the result is also in [availableUpdate]. */
    suspend fun checkForUpdate(): RuntimeInstaller.CheckResult {
        val result = installer.check()
        _availableUpdate.value = (result as? RuntimeInstaller.CheckResult.Available)?.toUpdate()
        return result
    }

    suspend fun storageUsage(): RuntimeStorageUsage = withContext(Dispatchers.IO) {
        val record = installer.installed()
        val layers = record?.layerDirs().orEmpty().sumOf { FsTree.size(fs, File(layout.root, it)) }
        RuntimeStorageUsage(
            layersBytes = layers,
            userDataBytes = FsTree.size(fs, layout.home) + FsTree.size(fs, layout.workspaces),
            cacheBytes = FsTree.size(fs, layout.downloads) + FsTree.size(fs, layout.prootTmp),
            logBytes = FsTree.size(fs, layout.logs),
        )
    }

    /**
     * Guest commands (a clone) running now. They use the installed layers, so an install, an
     * update, a go-back and an uninstall wait for none to be running: [beginGuestCommand] and
     * [launchOp] each publish their own flag before reading the other's, so at most one of the
     * two goes ahead.
     */
    private val guestCommands = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    private var layersChanging = false

    /**
     * Ask to run a guest command now. False while the runtime is being installed, updated or
     * removed, or when nothing is installed; true means [endGuestCommand] must follow.
     */
    fun beginGuestCommand(): Boolean {
        guestCommands.incrementAndGet()
        if (layersChanging || !_state.value.hasInstall() || _state.value.busy && _state.value !is RuntimeState.Starting && _state.value !is RuntimeState.Stopping) {
            guestCommands.decrementAndGet()
            return false
        }
        return true
    }

    fun endGuestCommand() {
        guestCommands.decrementAndGet()
    }

    private fun launchOp(allowWhen: (RuntimeState) -> Boolean, changesLayers: Boolean = false, block: suspend (Op) -> Unit): Boolean {
        if (platform.unsupported != null) return false
        val op = Op()
        if (!claim.compareAndSet(null, op)) return false
        val current = _state.value
        if (current.busy || !allowWhen(current)) {
            claim.compareAndSet(op, null)
            return false
        }
        if (changesLayers) {
            layersChanging = true
            if (guestCommands.get() > 0) {
                layersChanging = false
                claim.compareAndSet(op, null)
                return false
            }
        }
        job = scope.launch {
            try {
                transition.withLock { block(op) }
            } finally {
                if (changesLayers) layersChanging = false
                // Normally already released by [Op.finish]; this covers an operation that ends
                // without a terminal state (a restart with nothing installed, a failure not handled).
                claim.compareAndSet(op, null)
            }
        }
        return true
    }

    private suspend fun runInstall(op: Op, goBack: Boolean) {
        platform.keepAlive()
        progressPacer.reset()
        val before = installer.installed()
        val installed = before?.toInstalledRuntime()
        val wasRunning = (_state.value as? RuntimeState.Running)?.port
        var target: ReleaseRef? = null
        fun progress(phase: UpdatePhase, firstInstall: RuntimeState) {
            _state.value = if (installed == null) firstInstall else RuntimeState.Updating(phase, installed, target, goBack)
        }
        fun failed(failure: RuntimeFailure) {
            if (before != null) _outcomes.tryEmit(UpdateOutcome.Failed(before.ref(), target ?: before.ref(), failure, goBack))
        }
        try {
            progress(UpdatePhase.Checking, RuntimeState.Downloading(DownloadProgress(LAYER_BASE, 0, 2, 0, 0)))
            val result = if (goBack) installer.checkPrevious() else installer.check()
            when (val check = result) {
                is RuntimeInstaller.CheckResult.Refused -> {
                    failed(check.failure)
                    op.finish(afterFailure(check.failure, wasRunning))
                    return
                }
                is RuntimeInstaller.CheckResult.UpToDate -> {
                    if (!goBack) _availableUpdate.value = null
                    op.finish(restore(wasRunning))
                    return
                }
                is RuntimeInstaller.CheckResult.Available -> {
                    target = ReleaseRef(check.manifest.releaseId, check.manifest.harness.version, check.manifest.sequence)
                    var switchedPort: Int? = null
                    installer.apply(
                        check.manifest,
                        check.decision,
                        onEvent = { event ->
                            // Bytes and entries arrive far faster than anything can show them; only a
                            // paced share becomes a state (see ProgressPacer). A change of step always does.
                            when (event) {
                                InstallEvent.Checking -> Unit
                                is InstallEvent.Downloading -> {
                                    val p = event.progress
                                    if (progressPacer.admit("download:${p.layer}", last = p.bytesTotal > 0 && p.bytesDone >= p.bytesTotal)) {
                                        progress(UpdatePhase.Downloading(p), RuntimeState.Downloading(p))
                                    }
                                }
                                is InstallEvent.Verifying -> {
                                    progressPacer.admit("verify:${event.layer}", last = true)
                                    progress(UpdatePhase.Verifying(event.layer), RuntimeState.Verifying(event.layer))
                                }
                                is InstallEvent.Unpacking -> {
                                    val p = event.progress
                                    if (progressPacer.admit("unpack:${p.layer}", last = p.entriesTotal > 0 && p.entriesDone >= p.entriesTotal)) {
                                        progress(UpdatePhase.Unpacking(p), RuntimeState.Unpacking(p))
                                    }
                                }
                            }
                        },
                        beforeCommit = {
                            if (installed != null) _state.value = RuntimeState.Updating(UpdatePhase.Switching, installed, target, goBack)
                            if (supervisor.isRunning) {
                                switchedPort = wasRunning
                                supervisor.stop()
                            }
                        },
                    )
                    _availableUpdate.value = null
                    if (before != null) _outcomes.tryEmit(UpdateOutcome.Applied(before.ref(), target!!, wentBack = goBack))
                    val restartOn = switchedPort
                    if (restartOn != null) {
                        runStart(op, restartOn, rolledBack = null)
                    } else {
                        op.finish(RuntimeState.Stopped(installer.installed()!!.toInstalledRuntime()))
                    }
                }
            }
        } catch (e: CancellationException) {
            op.finish(restore(wasRunning))
            throw e
        } catch (e: InstallException) {
            failed(e.failure)
            op.finish(afterFailure(e.failure, wasRunning))
        } catch (e: DownloadException) {
            failed(e.failure)
            op.finish(afterFailure(e.failure, wasRunning))
        } catch (e: IOException) {
            failed(RuntimeFailure.Storage(e.message))
            op.finish(afterFailure(RuntimeFailure.Storage(e.message), wasRunning))
        }
    }

    /** After a failed or abandoned update, a harness that is still running stays reported as running. */
    private fun afterFailure(failure: RuntimeFailure, runningPort: Int?): RuntimeState {
        val running = _runningSnapshot
        if (runningPort != null && supervisor.isRunning && running != null) return running
        return RuntimeState.Failed(failure, installer.installed()?.toInstalledRuntime())
    }

    private fun restore(runningPort: Int?): RuntimeState {
        val running = _runningSnapshot
        if (runningPort != null && supervisor.isRunning && running != null) return running
        val record = installer.installed() ?: return RuntimeState.NotInstalled
        return RuntimeState.Stopped(record.toInstalledRuntime())
    }

    /** The last Running state, so an update that fails while the harness runs can put it back. */
    @Volatile
    private var _runningSnapshot: RuntimeState.Running? = null

    private suspend fun runStart(op: Op, port: Int, rolledBack: RolledBackUpdate?) {
        val record = installer.installed()
        if (record == null) {
            op.finish(RuntimeState.NotInstalled)
            return
        }
        val installed = record.toInstalledRuntime()
        _state.value = RuntimeState.Starting(port, installed)
        platform.keepAlive()

        val failure: RuntimeFailure = try {
            supervisor.cleanupStale()
            if (!platform.portFree(port)) {
                RuntimeFailure.PortInUse(port)
            } else {
                prepareRun(record)
                val command = ProotCommand.build(
                    ProotCommand.Inputs(
                        platform.nativeLibraryDir,
                        layout,
                        record,
                        port,
                        modulePack = platform.modulePack,
                        commandPermission = platform.commandPermission(record),
                        extraEnvironment = platform.guestEnvironment(record),
                    ),
                )
                when (val outcome = supervisor.start(command, layout.root, record.readinessPrefix, port, ::onExitAfterReady)) {
                    is HarnessSupervisor.StartOutcome.Ready -> {
                        val current = if (record.onTrial) installer.commitTrial() ?: record else record
                        val signedIn = signIn(outcome.launchLine, outcome.port)
                        val running = RuntimeState.Running(
                            port = outcome.port,
                            harnessVersion = current.harnessVersion,
                            sinceEpochMs = wallClock(),
                            signIn = signedIn,
                            installed = current.toInstalledRuntime(),
                            rolledBack = rolledBack,
                        )
                        _runningSnapshot = running
                        op.finish(running)
                        return
                    }
                    is HarnessSupervisor.StartOutcome.Exited -> RuntimeFailure.ExitedBeforeReady(outcome.exitCode, outcome.tail)
                    is HarnessSupervisor.StartOutcome.TimedOut -> RuntimeFailure.ReadyTimeout(outcome.tail)
                    is HarnessSupervisor.StartOutcome.LaunchFailed -> RuntimeFailure.LaunchFailed(outcome.detail)
                }
            }
        } catch (e: IOException) {
            RuntimeFailure.Storage(e.message)
        }

        if (record.onTrial && failure !is RuntimeFailure.PortInUse) {
            // The new release never became ready: put the previous one back and start that instead.
            val restored = installer.rollbackTrial()
            if (restored != null) _outcomes.tryEmit(UpdateOutcome.RolledBack(restored.ref(), record.ref(), failure))
            runStart(op, port, RolledBackUpdate(record.harnessVersion, failure, record.releaseId, record.sequence))
            return
        }
        op.finish(RuntimeState.Failed(failure, installed))
    }

    private fun onExitAfterReady(exitCode: Int, tail: String) {
        _runningSnapshot = null
        val installed = installer.installed()?.toInstalledRuntime()
        // Only a running harness can crash; a stop or an update switch has already moved the state on.
        if (_state.value is RuntimeState.Running) {
            _state.value = RuntimeState.Failed(RuntimeFailure.Crashed(exitCode, tail), installed)
        }
    }

    /** Files that change per start: DNS, the CPU count, the mount points and the home skeleton. */
    private fun prepareRun(record: InstalledRecord) {
        layout.run.mkdirs()
        layout.prootTmp.mkdirs()
        layout.workspaces.mkdirs()
        val rootfs = File(layout.root, record.base.dir)
        if (!layout.home.exists()) {
            layout.home.mkdirs()
            // The base's /root skeleton (.bashrc, .profile), once: after that /root is the user's.
            File(rootfs, "root").listFiles().orEmpty()
                .filter { fs.lstat(it) == FileKind.FILE }
                .forEach { it.copyTo(File(layout.home, it.name), overwrite = false) }
        }
        File(layout.run, ProotCommand.GENERATED_RESOLV).writeText(ProotCommand.resolvConf(platform.dnsServers()))
        val shippedStat = record.fakeProc.firstOrNull { it.target == "/proc/stat" }
            ?.let { File(rootfs, it.source.removePrefix("/")) }
            ?.takeIf { it.isFile }
            ?.readText()
        File(layout.run, ProotCommand.GENERATED_PROC_STAT).writeText(ProotCommand.procStat(shippedStat, platform.cpuCount))
    }

    private fun initialState(): RuntimeState {
        platform.unsupported?.let { return RuntimeState.NotSupported(it) }
        val record = installer.installed() ?: return RuntimeState.NotInstalled
        return RuntimeState.Stopped(record.toInstalledRuntime())
    }
}
