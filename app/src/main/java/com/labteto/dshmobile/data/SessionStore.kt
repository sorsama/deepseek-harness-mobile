package com.labteto.dshmobile.data

import android.util.Base64
import android.util.Log
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcReceipt
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.ServerRequest
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetSelectRequest
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.CUSTOM_PRESET
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import com.labteto.dshmobile.core.wire.dto.imageRejectionOf
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.GoalClearRequest
import com.labteto.dshmobile.core.wire.dto.GoalCompleteRequest
import com.labteto.dshmobile.core.wire.dto.GoalCreateRequest
import com.labteto.dshmobile.core.wire.dto.GoalEditRequest
import com.labteto.dshmobile.core.wire.dto.GoalPauseRequest
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.GoalResumeRequest
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.HistoryEntry
import com.labteto.dshmobile.core.wire.dto.HostFrame
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.MuxFrame
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.PlanStateView
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.QUESTION_CANCELLED
import com.labteto.dshmobile.core.wire.dto.QueueAction
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRequest
import com.labteto.dshmobile.core.wire.dto.SessionModelsRequest
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionProjectionsBlock
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueRequest
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentHistoryRequest
import com.labteto.dshmobile.core.wire.dto.SubagentInterruptRequest
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.SubagentListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.ToolEventView
import com.labteto.dshmobile.core.wire.dto.UnknownHostFrame
import com.labteto.dshmobile.core.wire.dto.UnknownMuxFrame
import com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import java.io.OutputStream
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One renderable session list row (manual order, live). */
data class SessionRow(
    val sessionId: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val cwd: String?,
    val agentPreset: String?,
    val updatedAt: Long,
    val pendingInteraction: String?, // "approval" | "plan-review" | "question" | null
)

/** One renderable workspace row. */
data class WorkspaceRow(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    /**
     * `WorkspaceView.updatedAt` as epoch millis (0 when unparseable). This stamps the *registration
     * record* — a rename or a session being added — not conversation activity, so it is only a
     * tiebreak for recency ranking, never the primary key.
     */
    val updatedAtEpoch: Long = 0L,
)

/** What a slash command did, so the caller can report it without re-reading the wire. */
sealed interface CommandOutcome {
    /** The host executed the line; [text] is its settlement message, when it produced one. */
    data class Ok(val text: String?) : CommandOutcome

    /** The line named no registered command. */
    data class Unknown(val line: String) : CommandOutcome

    /** The command ran and reported a usage or state failure. */
    data class Failed(val message: String) : CommandOutcome
}

/** What the harness did with a prompt. */
sealed interface PromptOutcome {
    /** Accepted; the turn is the transcript's business now. */
    data object Ok : PromptOutcome

    /**
     * The host refused the images. Carried as its own case because it is a composer problem, not
     * a connection problem: raising the persistent connection banner for an image that is 200px
     * too wide tells the user their harness is broken when only their picture is.
     */
    data class Rejected(val rejection: ImageRejection, val reason: String?) : PromptOutcome

    /** Anything else; the connection banner already carries [message]. */
    data class Failed(val message: String) : PromptOutcome
}

/** What the harness did with an answer to a question request, or with a dismissal of one. */
sealed interface QuestionOutcome {
    /**
     * Taken. The panel leaves when the `question/resolved` frame lands rather than now — the
     * receipt only says the response was well-formed for the wait it addressed.
     */
    data object Accepted : QuestionOutcome

    /**
     * Refused. `bad-response` means the payload did not match the request it answered;
     * `not-pending` means the wait had already settled.
     */
    data class Refused(val reason: String) : QuestionOutcome

    /** The POST never completed, so nothing is known about the wait. */
    data object Unsent : QuestionOutcome
}

/** Wire workspace -> renderable row, parsing the ISO-8601 stamp once at the boundary. */
private fun WorkspaceView.toRow(): WorkspaceRow = WorkspaceRow(
    workspaceId = workspaceId,
    path = path,
    title = title,
    sessionIds = sessionIds,
    updatedAtEpoch = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
)

/** A pending sandbox/permission approval the user can answer (allow-once / reject). */
data class PendingApproval(
    val sessionId: String,
    val approvalId: String,
    val rpcId: String,
    val toolName: String,
    val reason: String?,
)

/** A pending ask_user_question batch (a plan review rides the same channel via its intent). */
data class PendingQuestions(
    val sessionId: String,
    val rpcId: String,
    val items: List<AskUserQuestionItem>,
)

/**
 * Whether more history remains after folding a backwards page.
 *
 * The load-bearing clause is [freshCount]: history paging is driven by scroll position, so a page
 * that added nothing new has to end the paging regardless of what the host claims. Believing a
 * `hasMore` that a `beforeSeq` query can no longer advance past leaves the scroll trigger firing
 * against the same page forever.
 *
 * File-level so it is testable without standing up the whole store.
 */
internal fun nextHasMore(freshCount: Int, hostHasMore: Boolean, overDelivered: Boolean): Boolean =
    freshCount > 0 && (hostHasMore || overDelivered)

/**
 * Single source of truth for the connected harness's live state. All public surface is
 * [StateFlow]; every RPC error becomes [connectionError] and never throws. The store survives
 * reconnects by re-baselining on the connection state transition and on `session/subscribed`.
 */
@Singleton
class SessionStore @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val hostsStore: HostsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val baselineMutex = Mutex()

    /** Coalesces transcript rebuilds during a stream; see [observeRebuildTicks]. */
    private val rebuildTicks = Channel<Unit>(Channel.CONFLATED)

    // ------------------------------------------------------------------ public StateFlows
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceRow>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceRow>> = _workspaces.asStateFlow()

    private val _archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessionIds: StateFlow<Set<String>> = _archivedSessionIds.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val searchResults: StateFlow<List<Pair<String, String>>> = _searchResults.asStateFlow()

    private val _contentSearchAvailable = MutableStateFlow(true)

    /**
     * Whether this harness will answer `session.search`.
     *
     * Assumed true and latched false by the first refusal — see [search]. Reset on connect, because
     * it is a fact about the harness on the other end, not about the app.
     */
    val contentSearchAvailable: StateFlow<Boolean> = _contentSearchAvailable.asStateFlow()

    private val _currentConversation = MutableStateFlow<ConversationSnapshot?>(null)
    val currentConversation: StateFlow<ConversationSnapshot?> = _currentConversation.asStateFlow()

    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    private val _models = MutableStateFlow<SessionModelsValue?>(null)
    val models: StateFlow<SessionModelsValue?> = _models.asStateFlow()

    private val _hostInfo = MutableStateFlow<HostDescription?>(null)
    val hostInfo: StateFlow<HostDescription?> = _hostInfo.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _toolViews = MutableStateFlow<Map<Long, ToolEventView>>(emptyMap())
    val toolViews: StateFlow<Map<Long, ToolEventView>> = _toolViews.asStateFlow()

    /** A backwards page is in flight; the transcript shows a spinner and suppresses re-entry. */
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * The last backwards page failed.
     *
     * Paging is driven by scroll position, and `snapshotFlow` only emits distinct values — with the
     * index unchanged after a failure nothing re-fires until the reader scrolls again. So the retry
     * has to be an affordance rather than an automatic repeat.
     */
    private val _loadOlderFailed = MutableStateFlow(false)
    val loadOlderFailed: StateFlow<Boolean> = _loadOlderFailed.asStateFlow()

    private val _subagents = MutableStateFlow<List<SubagentListEntry>>(emptyList())
    val subagents: StateFlow<List<SubagentListEntry>> = _subagents.asStateFlow()

    private val _subagentConversation = MutableStateFlow<ConversationSnapshot?>(null)
    val subagentConversation: StateFlow<ConversationSnapshot?> = _subagentConversation.asStateFlow()

    private val _subagentMode = MutableStateFlow<String?>(null)
    val subagentMode: StateFlow<String?> = _subagentMode.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<PendingQuestions?>(null)
    val pendingQuestions: StateFlow<PendingQuestions?> = _pendingQuestions.asStateFlow()

    private val _commands = MutableStateFlow<List<CommandDescriptor>>(emptyList())
    val commands: StateFlow<List<CommandDescriptor>> = _commands.asStateFlow()

    /** False once the harness has told us it has no command registry; the menu degrades, not errors. */
    private val _commandsAvailable = MutableStateFlow(true)
    val commandsAvailable: StateFlow<Boolean> = _commandsAvailable.asStateFlow()

    private val _agentPresets = MutableStateFlow<AgentPresetListValue?>(null)
    val agentPresets: StateFlow<AgentPresetListValue?> = _agentPresets.asStateFlow()

    private val _plugins = MutableStateFlow<PluginInventorySnapshot?>(null)

    /** The host's plugin inventory, or null when this deployment does not expose one. */
    val plugins: StateFlow<PluginInventorySnapshot?> = _plugins.asStateFlow()

    /** The preset a switch is in flight for, cleared when the projection reports it as effective. */
    private val _pendingPermission = MutableStateFlow<String?>(null)
    val pendingPermission: StateFlow<String?> = _pendingPermission.asStateFlow()

    // ------------------------------------------------------------------ projection views
    // These are folds of `currentConversation.projections`, not separate fetches: the harness
    // already pushes every one of them on `session/projection` frames and in the history tail, so
    // deriving keeps them in lockstep with the transcript and adds no round trips. A null value
    // means the key is absent — the harness composes no such service — and callers hide the UI.

    val permissions: StateFlow<PermissionSelect?> = projectionOf(PermissionSelect.serializer(), "permissions")
    val sessionStats: StateFlow<SessionStatsView?> = projectionOf(SessionStatsView.serializer(), "sessionStats")
    val tokenUsage: StateFlow<TokenUsageView?> = projectionOf(TokenUsageView.serializer(), "tokenUsage")
    val contextPressure: StateFlow<ContextPressureView?> =
        projectionOf(ContextPressureView.serializer(), "contextPressure")
    val contextBreakdown: StateFlow<ContextBreakdownView?> =
        projectionOf(ContextBreakdownView.serializer(), "contextBreakdown")
    val imageLimits: StateFlow<ImageLimitsView?> = projectionOf(ImageLimitsView.serializer(), "imageLimits")
    val planState: StateFlow<PlanStateView?> = projectionOf(PlanStateView.serializer(), "plan")

    /** One projection key, decoded leniently: unknown or malformed payloads read as absent. */
    private fun <T> projectionOf(serializer: KSerializer<T>, key: String): StateFlow<T?> =
        currentConversation
            .map { conversation ->
                conversation?.projections?.get(key)?.let { element ->
                    runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // ------------------------------------------------------------------ internal state (guarded by `lock`)
    private val sessionRows = LinkedHashMap<String, SessionRow>()
    private val runningBySession = HashMap<String, Boolean>()
    private val titleBySession = HashMap<String, String>()
    private val workspaceRows = LinkedHashMap<String, WorkspaceRow>()
    private val workspaceOrder = ArrayList<String>()
    private var archived = emptySet<String>()
    private val pendingKinds = HashMap<String, MutableSet<String>>()

    // Pending server-initiated requests the store can answer.
    private val approvalRequests = HashMap<String, ApprovalRequest>() // approvalId -> request
    private val questionRpcBySession = HashMap<String, String>() // sessionId -> rpcId

    // Open-session fold state.
    private var currentId: String? = null
    private val currentEvents = ArrayList<SessionEventEnvelope>()
    private var currentHasMore = false
    private var currentBlank = true
    private val currentProjections = HashMap<String, ProjectionValue>()
    private var currentQueue = emptyList<QueueItem>()
    private val toolViewsBySeq = HashMap<Long, ToolEventView>()

    private data class ApprovalRequest(
        val sessionId: String,
        val approvalId: String,
        val rpcId: String,
        val toolName: String,
        val reason: String?,
    )
    private data class ProjectionValue(val seq: Int, val value: JsonElement)

    init {
        observeConnection()
        observeFrames()
        observePermissionSettlement()
        observeRebuildTicks()
    }

    /**
     * Clear the optimistic permission value once the harness's own projection agrees with it. The
     * chip shows the target immediately and stops pretending as soon as the truth arrives.
     */
    private fun observePermissionSettlement() {
        scope.launch {
            permissions.collect { select ->
                val pending = _pendingPermission.value ?: return@collect
                if (select?.currentValue == pending) _pendingPermission.value = null
            }
        }
    }

    /**
     * Drives [rebuildCurrentLocked] for the live event stream, at most once per
     * [REBUILD_INTERVAL_MS].
     *
     * A rebuild re-folds the whole transcript, so its cost is proportional to the length of the
     * session. Running one per event made streaming quadratic: a turn arrives as a long run of
     * `assistant/chunk` deltas, and each delta was re-folding every event before it and publishing
     * a fresh snapshot for the transcript to recompose against. On a session of any size that
     * allocated hundreds of megabytes a second and eventually exhausted the heap.
     *
     * The channel is conflated because a rebuild is idempotent and reads whatever state exists when
     * it runs: a burst of deltas collapses into one rebuild, and no delta can be lost by it — the
     * event is already in `currentEvents` before the tick is sent. The interval is a display frame
     * rather than a debounce, so the tail of a stream still lands promptly.
     */
    private fun observeRebuildTicks() {
        scope.launch {
            for (tick in rebuildTicks) {
                synchronized(lock) { rebuildCurrentLocked() }
                delay(REBUILD_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------ connection lifecycle
    private fun observeConnection() {
        scope.launch {
            var prev = connectionManager.state.value
            connectionManager.state.collect { state ->
                val initialConnect = !prev.hasConnected && state.hasConnected
                val reconnect = prev.hasConnected &&
                    prev.phase == ConnectionPhase.RECONNECTING &&
                    state.phase == ConnectionPhase.CONNECTED
                prev = state
                if (initialConnect || reconnect) triggerBaseline()
            }
        }
    }

    private fun observeFrames() {
        scope.launch {
            connectionManager.muxFrames.collect { handleMuxFrame(it) }
        }
        scope.launch {
            connectionManager.hostFrames.collect { handleHostFrame(it) }
        }
    }

    private fun triggerBaseline() {
        scope.launch {
            if (!baselineMutex.tryLock()) return@launch
            try {
                baseline()
            } catch (e: Exception) {
                log("baseline failed", e)
            } finally {
                baselineMutex.unlock()
            }
        }
    }

    private suspend fun baseline() {
        // Whether content search works is a fact about the harness we just reached, so a fresh
        // connection re-earns the answer rather than inheriting the previous host's.
        _contentSearchAvailable.value = true
        refreshSessions()
        // Host-scoped and needed before anything is tapped: the chat bar names the session's preset
        // as soon as it renders, and without the roster it could only show the raw wire id.
        refreshAgentPresets()
        // On a reconnect `currentSessionId` is already set, so the resolver only ever runs on the
        // first connect of a process — no double-open, and reconnect keeps reopening what was open.
        val sid = currentSessionId.value ?: resolveInitialSession() ?: return
        openSession(sid)
    }

    /**
     * Which session to land on when the app has just connected and nothing is open.
     *
     * Mirrors the harness's own startup policy: the session you were last in, else the most
     * recently active workspace's newest session, else simply the newest session. Ranking is by
     * session `updatedAt` — `workspace.updatedAt` stamps the registration record (a rename, a
     * session being added), and `workspace.list` order is the manual display order, so neither
     * tracks conversation activity.
     *
     * Returns null when there is nothing worth opening, which leaves the empty hero on screen.
     */
    private suspend fun resolveInitialSession(): String? {
        val remembered = hostKey()?.let { hostsStore.lastSessionId(it) }
        val (rows, workspaces, archivedNow) = synchronized(lock) {
            Triple(
                sessionRows.values.toList(),
                workspaceOrder.mapNotNull { workspaceRows[it] },
                archived,
            )
        }
        return pickInitialSession(rows, workspaces, archivedNow, remembered)
    }

    /** `"host:port"` for the connected harness — session ids are only meaningful within one host. */
    private fun hostKey(): String? =
        connectionManager.state.value.host?.let { "${it.host}:${it.port}" }

    // ------------------------------------------------------------------ frame handlers
    private fun handleMuxFrame(frame: ServerRequest) {
        val mux = parseMuxFrame(frame.payload) ?: return
        when (mux) {
            is MuxFrame.SessionEventFrame -> handleSessionEvent(mux.sessionId, mux.event, mux.view)
            is MuxFrame.SessionSubscribed -> {
                val sid = mux.sessionId
                scope.launch {
                    if (sid == currentSessionId.value) openSession(sid)
                }
            }
            is MuxFrame.ApprovalRequested -> handleApprovalRequested(frame.rpcId, mux)
            is MuxFrame.ApprovalResolved -> handleApprovalResolved(mux)
            is MuxFrame.QuestionRequested -> handleQuestionRequested(frame.rpcId, mux)
            is MuxFrame.QuestionResolved -> handleQuestionResolved(mux)
            is MuxFrame.SessionQueue -> handleSessionQueue(mux)
            is MuxFrame.SessionJobs -> handleSessionJobs(mux)
            is MuxFrame.SessionProjection -> handleSessionProjection(mux)
            is MuxFrame.StreamError -> log("mux stream/error ${mux.error.code}: ${mux.error.message}")
            is UnknownMuxFrame -> log("unknown mux frame ${mux.type}")
        }
    }

    private fun handleHostFrame(frame: ServerRequest) {
        val host = parseHostFrame(frame.payload) ?: return
        when (host) {
            is HostFrame.SessionAdded -> onSessionAdded(host)
            is HostFrame.SessionRemoved -> onSessionRemoved(host.sessionId)
            is HostFrame.SessionStatus -> setRunning(host.sessionId, host.running)
            is HostFrame.AgentError -> setConnectionError(host.message)
            is HostFrame.WorkspaceChanged -> upsertWorkspace(host.workspace)
            is HostFrame.WorkspaceRemoved -> removeWorkspace(host.workspaceId)
            is HostFrame.WorkspaceOrderChanged -> setWorkspaceOrder(host.workspaceIds)
            is HostFrame.ArchivedSessionsChanged -> setArchived(host.archivedSessionIds)
            is HostFrame.RemoteEvent -> onRemoteEvent(host.event)
            is HostFrame.StreamError -> log("host stream/error ${host.error.code}: ${host.error.message}")
            is UnknownHostFrame -> log("unknown host frame ${host.type}")
        }
    }

    /**
     * One allowlisted host event forwarded verbatim. Only the two that invalidate cached catalogs
     * are acted on: a registry change, and a preset switch (which changes what an agent resolves
     * without registering anything globally, so the registry-wide signal never fires for it).
     */
    private fun onRemoteEvent(event: String) {
        when (event) {
            "commands/change" -> scope.launch { refreshCommands() }
            "agent-preset/selected" -> scope.launch {
                refreshAgentPresets()
                refreshCommands()
            }
            else -> Unit
        }
    }

    private fun handleSessionEvent(sessionId: String, event: SessionEvent, view: ToolEventView?) {
        val envelope = sessionEventToEnvelope(event)
        when (event.type) {
            "turn/start" -> {
                setRunning(sessionId, true)
                setBlank(sessionId, false)
            }
            "turn/end" -> setRunning(sessionId, false)
            "user/message" -> setBlank(sessionId, false)
            "session/title" -> {
                val title = envelope.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull
                if (title != null) setTitle(sessionId, title)
            }
        }
        synchronized(lock) {
            if (sessionId == currentId) {
                appendCurrentEventLocked(envelope)
                if (view != null) {
                    toolViewsBySeq[event.seq.toLong()] = view
                    _toolViews.value = toolViewsBySeq.toMap()
                }
            }
        }
    }

    private fun handleApprovalRequested(rpcId: String, frame: MuxFrame.ApprovalRequested) {
        synchronized(lock) {
            approvalRequests[frame.approvalId] =
                ApprovalRequest(frame.sessionId, frame.approvalId, rpcId, frame.toolName, frame.reason)
            addPendingLocked(frame.sessionId, "approval")
            emitSessionsLocked()
        }
        _pendingApproval.value = PendingApproval(
            sessionId = frame.sessionId,
            approvalId = frame.approvalId,
            rpcId = rpcId,
            toolName = frame.toolName,
            reason = frame.reason,
        )
    }

    private fun handleApprovalResolved(frame: MuxFrame.ApprovalResolved) {
        synchronized(lock) {
            approvalRequests.remove(frame.approvalId)
            removePendingLocked(frame.sessionId, "approval")
            emitSessionsLocked()
        }
        if (_pendingApproval.value?.approvalId == frame.approvalId) _pendingApproval.value = null
    }

    private fun handleQuestionRequested(rpcId: String, frame: MuxFrame.QuestionRequested) {
        synchronized(lock) {
            questionRpcBySession[frame.sessionId] = rpcId
            val kind = if (frame.questions.any { it.intent is AskUserQuestionIntent.PlanReview }) {
                "plan-review"
            } else {
                "question"
            }
            addPendingLocked(frame.sessionId, kind)
            emitSessionsLocked()
        }
        _pendingQuestions.value = PendingQuestions(frame.sessionId, rpcId, frame.questions)
    }

    private fun handleQuestionResolved(frame: MuxFrame.QuestionResolved) {
        synchronized(lock) {
            questionRpcBySession.remove(frame.sessionId)
            removePendingLocked(frame.sessionId, "question")
            removePendingLocked(frame.sessionId, "plan-review")
            emitSessionsLocked()
        }
        if (_pendingQuestions.value?.sessionId == frame.sessionId) _pendingQuestions.value = null
    }

    private fun handleSessionQueue(frame: MuxFrame.SessionQueue) {
        synchronized(lock) {
            if (frame.sessionId == currentId) {
                currentQueue = frame.items.map { queuedInboxItemToQueueItem(it) }
                rebuildCurrentLocked()
            }
        }
    }

    private fun handleSessionJobs(frame: MuxFrame.SessionJobs) {
        synchronized(lock) {
            if (frame.sessionId == currentId) {
                _jobs.value = frame.jobs
            }
        }
    }

    private fun handleSessionProjection(frame: MuxFrame.SessionProjection) {
        synchronized(lock) {
            if (frame.sessionId == currentId) {
                mergeProjectionLocked(frame.key, frame.seq, frame.value)
                rebuildCurrentLocked()
            }
        }
    }

    // ------------------------------------------------------------------ host frame state updates
    private fun onSessionAdded(frame: HostFrame.SessionAdded) {
        synchronized(lock) {
            val existing = sessionRows[frame.sessionId]
            val title = titleBySession[frame.sessionId]
            val row = existing?.copy(
                title = title ?: existing.title,
                blank = frame.blank,
                parentSessionId = frame.parentSessionId,
                origin = frame.origin,
                cwd = frame.cwd,
                agentPreset = frame.agentPreset,
            ) ?: SessionRow(
                sessionId = frame.sessionId,
                title = title,
                running = runningBySession[frame.sessionId] ?: false,
                blank = frame.blank,
                parentSessionId = frame.parentSessionId,
                origin = frame.origin,
                cwd = frame.cwd,
                agentPreset = frame.agentPreset,
                updatedAt = System.currentTimeMillis(),
                pendingInteraction = null,
            )
            if (existing == null) {
                // New sessions appear at the front (most recent first).
                val copy = LinkedHashMap<String, SessionRow>(sessionRows.size + 1)
                copy[frame.sessionId] = row
                copy.putAll(sessionRows)
                sessionRows.clear()
                sessionRows.putAll(copy)
            } else {
                sessionRows[frame.sessionId] = row
            }
            emitSessionsLocked()
        }
    }

    private fun onSessionRemoved(sessionId: String) {
        synchronized(lock) {
            sessionRows.remove(sessionId)
            pendingKinds.remove(sessionId)
            runningBySession.remove(sessionId)
            questionRpcBySession.remove(sessionId)
            emitSessionsLocked()
        }
    }

    private fun setRunning(sessionId: String, running: Boolean) {
        synchronized(lock) {
            runningBySession[sessionId] = running
            sessionRows[sessionId]?.let { if (it.running != running) sessionRows[sessionId] = it.copy(running = running) }
            if (sessionId == currentId) rebuildCurrentLocked()
            emitSessionsLocked()
        }
    }

    private fun setBlank(sessionId: String, blank: Boolean) {
        synchronized(lock) {
            sessionRows[sessionId]?.let { if (it.blank != blank) sessionRows[sessionId] = it.copy(blank = blank) }
            if (sessionId == currentId) currentBlank = blank
            emitSessionsLocked()
        }
    }

    private fun setTitle(sessionId: String, title: String) {
        synchronized(lock) {
            titleBySession[sessionId] = title
            sessionRows[sessionId]?.let { if (it.title != title) sessionRows[sessionId] = it.copy(title = title) }
            emitSessionsLocked()
        }
    }

    private fun upsertWorkspace(workspace: WorkspaceView) {
        synchronized(lock) {
            val row = workspace.toRow()
            if (!workspaceRows.containsKey(workspace.workspaceId)) workspaceOrder.add(workspace.workspaceId)
            workspaceRows[workspace.workspaceId] = row
            emitWorkspacesLocked()
        }
    }

    private fun removeWorkspace(workspaceId: String) {
        synchronized(lock) {
            workspaceRows.remove(workspaceId)
            workspaceOrder.remove(workspaceId)
            emitWorkspacesLocked()
        }
    }

    private fun setWorkspaceOrder(ids: List<String>) {
        synchronized(lock) {
            workspaceOrder.clear()
            workspaceOrder.addAll(ids)
            emitWorkspacesLocked()
        }
    }

    private fun setArchived(ids: List<String>) {
        synchronized(lock) {
            archived = ids.toSet()
            _archivedSessionIds.value = archived
        }
    }

    private fun setConnectionError(message: String?) {
        _connectionError.value = message
    }

    /**
     * Drop a stale failure banner once something works again.
     *
     * Errors used to be set and never cleared, so one transient failure — a session that was still
     * cold when the app opened it, say — left a red banner across the whole session for the rest of
     * the run, long after the thing it described had resolved.
     */
    private fun clearConnectionError() {
        if (_connectionError.value != null) _connectionError.value = null
    }

    // ------------------------------------------------------------------ open-session fold
    /**
     * Fold one freshly-streamed event into the open session.
     *
     * The common case by far is a strictly-increasing append, which is why it is checked first:
     * the scan-and-sort below is O(n log n) and used to run for every delta of every turn. Out of
     * order or repeated sequence numbers still take the slow path, which is what makes a
     * re-delivery after a reconnect land in the right place.
     *
     * The rebuild is requested rather than performed — see [observeRebuildTicks].
     */
    private fun appendCurrentEventLocked(envelope: SessionEventEnvelope) {
        val lastSeq = currentEvents.lastOrNull()?.seq
        if (lastSeq == null || envelope.seq > lastSeq) {
            currentEvents.add(envelope)
        } else {
            val idx = currentEvents.indexOfFirst { it.seq == envelope.seq }
            if (idx >= 0) {
                currentEvents[idx] = envelope
            } else {
                currentEvents.add(envelope)
                currentEvents.sortBy { it.seq }
            }
        }
        rebuildTicks.trySend(Unit)
    }

    private fun mergeProjectionLocked(key: String, seq: Int, value: JsonElement) {
        val existing = currentProjections[key]
        if (existing == null || seq >= existing.seq) {
            currentProjections[key] = ProjectionValue(seq, value)
        }
    }

    private fun rebuildCurrentLocked() {
        val sid = currentId ?: return
        val events = currentEvents.toList()
        val snapshot = EventFold(sid).fold(events)
        val blank = if (events.isEmpty()) currentBlank else snapshot.blank
        val running = runningBySession[sid] ?: snapshot.running
        val merged = snapshot.copy(
            blank = blank,
            running = running,
            hasMore = currentHasMore,
            queue = currentQueue,
            projections = currentProjections.mapValues { it.value.value },
        )
        _currentConversation.value = merged
    }

    private fun emitSessionsLocked() {
        val rows = sessionRows.values.map { row ->
            row.copy(pendingInteraction = pendingInteractionOf(pendingKinds[row.sessionId]))
        }
        _sessions.value = rows
    }

    private fun emitWorkspacesLocked() {
        val ordered = workspaceOrder.mapNotNull { workspaceRows[it] } +
            workspaceRows.values.filter { it.workspaceId !in workspaceOrder }
        _workspaces.value = ordered
    }

    private fun pendingInteractionOf(kinds: Set<String>?): String? {
        if (kinds.isNullOrEmpty()) return null
        return when {
            "question" in kinds -> "question"
            "plan-review" in kinds -> "plan-review"
            "approval" in kinds -> "approval"
            else -> null
        }
    }

    private fun addPendingLocked(sessionId: String, kind: String) {
        pendingKinds.getOrPut(sessionId) { LinkedHashSet() }.add(kind)
    }

    private fun removePendingLocked(sessionId: String, kind: String) {
        pendingKinds[sessionId]?.remove(kind)
        if (pendingKinds[sessionId].isNullOrEmpty()) pendingKinds.remove(sessionId)
    }

    private fun extractTitle(block: SessionProjectionsBlock?): String? {
        val value = block?.values?.get("title") ?: return null
        return when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["title"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    // ------------------------------------------------------------------ public RPC surface
    suspend fun refreshSessions() {
        val api = apiOrNull() ?: return
        when (val r = api.sessionList(null)) {
            is RpcResult.Ok -> {
                clearConnectionError()
                synchronized(lock) {
                    sessionRows.clear()
                    for (item in r.value.items) {
                        val title = titleBySession[item.sessionId]
                            ?: extractTitle(item.projections)?.also { titleBySession[item.sessionId] = it }
                        runningBySession.putIfAbsent(item.sessionId, item.running)
                        sessionRows[item.sessionId] = SessionRow(
                            sessionId = item.sessionId,
                            title = title,
                            running = runningBySession[item.sessionId] ?: item.running,
                            blank = item.blank,
                            parentSessionId = item.parentSessionId,
                            origin = item.origin,
                            cwd = item.cwd,
                            agentPreset = item.agentPreset,
                            updatedAt = item.updatedAt,
                            pendingInteraction = null,
                        )
                    }
                    emitSessionsLocked()
                }
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
        refreshWorkspaces()
        when (val r = api.hostDescribe()) {
            is RpcResult.Ok -> _hostInfo.value = r.value
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    private suspend fun refreshWorkspaces() {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceList()) {
            is RpcResult.Ok -> {
                synchronized(lock) {
                    workspaceRows.clear()
                    workspaceOrder.clear()
                    for (w in r.value.items) {
                        workspaceRows[w.workspaceId] = w.toRow()
                        workspaceOrder.add(w.workspaceId)
                    }
                    archived = r.value.archivedSessionIds.toSet()
                    _archivedSessionIds.value = archived
                    emitWorkspacesLocked()
                }
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun openSession(sessionId: String) = withContext(Dispatchers.Default) {
        val api = apiOrNull() ?: return@withContext
        _loadOlderFailed.value = false
        synchronized(lock) {
            val same = currentId == sessionId
            currentId = sessionId
            _currentSessionId.value = sessionId
            currentEvents.clear()
            currentHasMore = false
            currentBlank = sessionRows[sessionId]?.blank ?: true
            currentProjections.clear()
            currentQueue = emptyList()
            toolViewsBySeq.clear()
            _toolViews.value = emptyMap()
            if (!same) {
                _currentConversation.value = null
                _jobs.value = emptyList()
                _skills.value = emptyList()
                _models.value = null
                _subagents.value = emptyList()
                _subagentConversation.value = null
                _subagentMode.value = null
                _commands.value = emptyList()
                _pendingPermission.value = null
            }
        }
        when (val r = api.sessionHistory(SessionHistoryRequest(sessionId, null, HISTORY_PAGE_SIZE))) {
            is RpcResult.Ok -> {
                clearConnectionError()
                val page = historyTail(r.value.events)
                val overDelivered = r.value.events.size > page.size
                val envelopes = ArrayList<SessionEventEnvelope>(page.size)
                val views = HashMap<Long, ToolEventView>()
                for (entry in page) {
                    envelopes.add(sessionEventToEnvelope(entry.event))
                    entry.view?.let { views[entry.event.seq.toLong()] = it }
                }
                synchronized(lock) {
                    if (currentId != sessionId) return@synchronized
                    currentEvents.clear()
                    currentEvents.addAll(envelopes)
                    currentEvents.sortBy { it.seq }
                    currentHasMore = r.value.hasMore || overDelivered
                    toolViewsBySeq.putAll(views)
                    _toolViews.value = toolViewsBySeq.toMap()
                    r.value.projections?.let { block ->
                        block.values.forEach { (key, value) ->
                            currentProjections[key] = ProjectionValue(block.asOfSeq, value)
                        }
                    }
                    rebuildCurrentLocked()
                }
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
        loadSkills(sessionId)
        loadModels(sessionId)
        refreshSubagents()
        refreshCommands()
        rememberLastSession(sessionId)
    }

    /** Persist the landing session for this harness; a write failure is not worth surfacing. */
    private suspend fun rememberLastSession(sessionId: String) {
        val key = hostKey() ?: return
        runCatching { hostsStore.setLastSessionId(key, sessionId) }
            .onFailure { log("could not remember last session", it) }
    }

    /**
     * Page one screen further back.
     *
     * Called from the transcript's scroll position, so it has to be safe to call repeatedly: the
     * in-flight flag collapses a burst of scroll emissions into one request, and a page that adds
     * nothing new ends the paging rather than leaving `hasMore` set for the trigger to fire on
     * again.
     */
    suspend fun loadOlder() = withContext(Dispatchers.Default) {
        val sid = currentSessionId.value ?: return@withContext
        val api = apiOrNull() ?: return@withContext
        if (!_loadingOlder.compareAndSet(expect = false, update = true)) return@withContext
        try {
            val oldestSeq = synchronized(lock) { currentEvents.firstOrNull()?.seq }
            when (val r = api.sessionHistory(SessionHistoryRequest(sid, oldestSeq?.toInt(), HISTORY_PAGE_SIZE))) {
                is RpcResult.Ok -> {
                    clearConnectionError()
                    _loadOlderFailed.value = false
                    // Same guard as the initial page, so paging backwards stays bounded instead of
                    // pulling the whole log at once.
                    val page = historyTail(r.value.events)
                    val overDelivered = r.value.events.size > page.size
                    val envelopes = ArrayList<SessionEventEnvelope>(page.size)
                    val views = HashMap<Long, ToolEventView>()
                    for (entry in page) {
                        envelopes.add(sessionEventToEnvelope(entry.event))
                        entry.view?.let { views[entry.event.seq.toLong()] = it }
                    }
                    synchronized(lock) {
                        if (currentId != sid) return@synchronized
                        val existingSeqs = currentEvents.mapTo(HashSet()) { it.seq }
                        val fresh = envelopes.filter { it.seq !in existingSeqs }
                        if (fresh.isNotEmpty()) {
                            currentEvents.addAll(fresh)
                            currentEvents.sortBy { it.seq }
                        }
                        views.forEach { (seq, view) -> toolViewsBySeq[seq] = view }
                        _toolViews.value = toolViewsBySeq.toMap()
                        currentHasMore = nextHasMore(fresh.size, r.value.hasMore, overDelivered)
                        rebuildCurrentLocked()
                    }
                }
                // Not a connection fault: the session is healthy and the tail still streams, so this
                // offers a retry in the transcript rather than raising a connection banner over it.
                is RpcResult.Err -> _loadOlderFailed.value = true
            }
        } finally {
            _loadingOlder.value = false
        }
    }

    suspend fun createSession(cwd: String? = null, workspaceId: String? = null) {
        // Reuse the workspace's existing blank session instead of leaving another empty one behind
        // — the harness's own New Session does this, and it is why its list stays clean.
        if (workspaceId != null) {
            val reusable = synchronized(lock) {
                workspaceRows[workspaceId]?.sessionIds
                    ?.mapNotNull { sessionRows[it] }
                    ?.firstOrNull { it.blank && it.sessionId !in archived && it.origin != "subagent" }
                    ?.sessionId
            }
            if (reusable != null) {
                openSession(reusable)
                return
            }
        }
        val api = apiOrNull() ?: return
        when (val r = api.sessionCreate(SessionCreateRequest(workspaceId = workspaceId, cwd = cwd))) {
            is RpcResult.Ok -> {
                refreshSessions()
                openSession(r.value.sessionId)
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val api = apiOrNull() ?: return
        when (val r = api.sessionRename(SessionRenameRequest(sessionId, title))) {
            is RpcResult.Ok -> setTitle(sessionId, r.value.title)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun forkSession(sessionId: String, atSeq: Long? = null) {
        val api = apiOrNull() ?: return
        when (val r = api.sessionFork(SessionForkRequest(sessionId, atSeq?.toInt()))) {
            is RpcResult.Ok -> refreshSessions()
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun archiveSession(sessionId: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceArchiveSession(WorkspaceArchiveSessionRequest(sessionId))) {
            is RpcResult.Ok -> {
                setArchived(r.value.archivedSessionIds)
                refreshSessions()
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun prompt(text: String, mode: String) =
        promptContent(mode, listOf(PromptContentPart.Text(text)))

    /**
     * Prompt with attached raster images (bytes submitted base64, as the browser wire does).
     *
     * All of them ride *one* call. `session.prompt` takes a list of content parts and the host
     * admits that list as a single batch, which is where its per-message image count and
     * aggregate-size limits live — sending one image per call, as this client used to, split one
     * message into several and meant those two limits could never fire at all.
     */
    suspend fun promptWithImages(
        text: String,
        mode: String,
        images: List<EncodedImageAttachment>,
    ): PromptOutcome {
        val parts = mutableListOf<PromptContentPart>()
        if (text.isNotBlank()) parts.add(PromptContentPart.Text(text))
        images.mapTo(parts) { PromptContentPart.Image(it.mediaType, it.data, it.name) }
        return promptContent(mode, parts)
    }

    private suspend fun promptContent(mode: String, content: List<PromptContentPart>): PromptOutcome {
        val sid = currentSessionId.value ?: return PromptOutcome.Failed("no open session")
        val api = apiOrNull() ?: return PromptOutcome.Failed("not connected")
        val safeMode = if (mode == "steer") "steer" else "queue"
        val zone = TimeZone.getDefault().id
        val request = SessionPromptRequest(
            sessionId = sid,
            mode = safeMode,
            content = content,
            clientTimeZone = zone,
        )
        return when (val r = api.sessionPrompt(request)) {
            is RpcResult.Ok -> PromptOutcome.Ok
            is RpcResult.Err -> if (r.error.code == "attachment-error") {
                // The host declined the pictures, not the connection. Report it where the pictures
                // are so the composer can keep them and say which bound they crossed.
                val reason = (r.error.details as? JsonObject)
                    ?.get("reason")?.jsonPrimitive?.contentOrNull
                PromptOutcome.Rejected(imageRejectionOf(reason.orEmpty()), reason)
            } else {
                setConnectionError(r.error.message)
                PromptOutcome.Failed(r.error.message)
            }
        }
    }

    suspend fun cancelTurn() {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.sessionCancel(SessionCancelRequest(sid))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun updateQueue(itemId: String, action: String, contentText: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val queueAction: QueueAction = when (action) {
            "remove" -> QueueAction.Remove()
            "steer" -> QueueAction.Steer()
            else -> QueueAction.Edit(listOf(ContentBlock.Text(contentText.orEmpty())))
        }
        when (val r = api.sessionUpdateQueue(SessionUpdateQueueRequest(sid, itemId, queueAction))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun respondApproval(sessionId: String, approvalId: String, allow: Boolean) {
        val api = apiOrNull() ?: return
        val request = synchronized(lock) { approvalRequests[approvalId] }
        if (request == null) {
            log("no pending approval for id $approvalId")
            return
        }
        val outcome = if (allow) "allowed-once" else "rejected"
        val value = buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            put("approvalId", JsonPrimitive(approvalId))
            put("outcome", JsonPrimitive(outcome))
        }
        val receipt = api.respond(request.rpcId, value)
        if (receipt == null) log("approval response not acknowledged for $approvalId")
    }

    /**
     * Answers a pending question batch.
     *
     * The payload is serialized from a typed DTO rather than assembled by hand, and that is the
     * whole point of the type: `custom` belongs to the answer *item*, and the host's schema strips
     * keys it does not recognise instead of objecting to them. A `custom` written one level out
     * therefore reached the wire, was accepted, and simply never reached the model — the user's
     * typed answer deleted in transit with nothing to show for it.
     */
    suspend fun answerQuestions(sessionId: String, answer: AskUserQuestionAnswer): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val rpcId = pendingQuestionRpc(sessionId) ?: return QuestionOutcome.Refused("not-pending")
        val value = buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            put("answer", encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer))
        }
        return receiptOutcome(api.respond(rpcId, value), "question response", sessionId)
    }

    /**
     * Dismisses a pending question batch instead of answering it.
     *
     * Answering every item with an empty selection is a perfectly valid *answer*, and the model
     * reads it as "no preference". A dismissal fails the wait instead, and the host then settles
     * the tool call as cancelled. The code has to be exactly `cancelled`; the proxy refuses an
     * `ok:false` carrying any other.
     */
    suspend fun dismissQuestions(sessionId: String): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val rpcId = pendingQuestionRpc(sessionId) ?: return QuestionOutcome.Refused("not-pending")
        return receiptOutcome(
            api.respondError(rpcId, QUESTION_CANCELLED),
            "question dismissal",
            sessionId,
        )
    }

    private fun pendingQuestionRpc(sessionId: String): String? {
        val rpcId = synchronized(lock) { questionRpcBySession[sessionId] }
        if (rpcId == null) log("no pending question for session $sessionId")
        return rpcId
    }

    private fun receiptOutcome(
        receipt: RpcReceipt?,
        what: String,
        sessionId: String,
    ): QuestionOutcome = when {
        receipt == null -> {
            log("$what not acknowledged for $sessionId")
            QuestionOutcome.Unsent
        }
        receipt.accepted -> QuestionOutcome.Accepted
        else -> {
            log("$what refused for $sessionId: ${receipt.reason}")
            QuestionOutcome.Refused(receipt.reason ?: "refused")
        }
    }

    suspend fun selectModel(provider: String, model: String, reasoningEffort: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val request = SessionSelectModelRequest(sid, provider, model, reasoningEffort)
        when (val r = api.sessionSelectModel(request)) {
            is RpcResult.Ok -> loadModels(sid)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    /**
     * Full-text search across message content.
     *
     * This is the *optional* half of search, and most deployments do not have it: the shipped
     * `session-query-sqlite` row is configured `openAt: never`, which keeps exact reads, titles and
     * lineage traces working while `session.search` fails outright. So a failure here is a normal
     * condition, not a fault — it is latched into [contentSearchAvailable], never raised as a
     * connection error, and never retried for the life of the connection. The drawer's own title
     * and workspace filtering is unaffected and remains the primary way to find a session, exactly
     * as it is in the harness's web sidebar under the same configuration.
     */
    suspend fun search(query: String) {
        val trimmed = query.trim()
        // The host schema is query.trim().min(1).max(500); a blank or overlong query is an
        // invalid payload, so never send one — a blank query just clears the result set.
        if (trimmed.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        if (!_contentSearchAvailable.value) return
        val api = apiOrNull() ?: run {
            // Disconnected: stale hits would otherwise sit under a query that never ran.
            _searchResults.value = emptyList()
            return
        }
        val bounded = trimmed.take(SESSION_SEARCH_QUERY_MAX_CHARS)
        when (val r = api.sessionSearch(bounded)) {
            is RpcResult.Ok -> _searchResults.value = r.value.items.map { it.sessionId to it.snippet }
            is RpcResult.Err -> {
                _contentSearchAvailable.value = false
                _searchResults.value = emptyList()
            }
        }
    }

    suspend fun fetchAttachment(attachmentId: String): ByteArray? {
        val sid = currentSessionId.value ?: return null
        val api = apiOrNull() ?: return null
        return when (val r = api.sessionAttachment(SessionAttachmentRequest(sid, attachmentId))) {
            is RpcResult.Ok -> runCatching { Base64.decode(r.value.data, Base64.DEFAULT) }.getOrNull()
            is RpcResult.Err -> {
                setConnectionError(r.error.message)
                null
            }
        }
    }

    suspend fun listSkills() {
        val sid = currentSessionId.value ?: return
        loadSkills(sid)
    }

    suspend fun refreshSubagents() {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.subagentList(SubagentListRequest(sid))) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid) _subagents.value = r.value.entries
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun interruptSubagent(childSessionId: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.subagentInterrupt(SubagentInterruptRequest(parentSessionId = sid, childSessionId = childSessionId))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun promptSubagent(childSessionId: String, text: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val zone = TimeZone.getDefault().id
        val request = SubagentPromptRequest(
            parentSessionId = sid,
            childSessionId = childSessionId,
            mode = "continuable",
            content = listOf(ContentBlock.Text(text)),
            clientTimeZone = zone,
        )
        when (val r = api.subagentPrompt(request)) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun openSubagentTranscript(childSessionId: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val entry = _subagents.value.firstOrNull { subagentEntryId(it) == childSessionId }
        val mode = when (entry) {
            is SubagentListEntry.ChildOneShot -> "one-shot"
            is SubagentListEntry.ChildContinuable -> "continuable"
            else -> null
        }
        _subagentMode.value = mode
        if (mode == null) {
            _subagentConversation.value = null
            log("subagent $childSessionId has no readable transcript mode")
            return
        }
        val request = SubagentHistoryRequest(sid, childSessionId, mode, null, HISTORY_PAGE_SIZE)
        when (val r = api.subagentHistory(request)) {
            is RpcResult.Ok -> {
                val envelopes = r.value.events.mapNotNull { sessionEventToEnvelope(it.event) }
                val snapshot = EventFold(childSessionId).fold(envelopes).copy(
                    hasMore = r.value.hasMore,
                    projections = r.value.projections?.values ?: emptyMap(),
                )
                _subagentConversation.value = snapshot
            }
            is RpcResult.Err -> {
                _subagentConversation.value = null
                setConnectionError(r.error.message)
            }
        }
    }

    suspend fun createWorkspace(path: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceCreate(WorkspaceCreateRequest(path))) {
            is RpcResult.Ok -> refreshWorkspaces()
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun renameWorkspace(id: String, title: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceRename(WorkspaceRenameRequest(id, title))) {
            is RpcResult.Ok -> refreshWorkspaces()
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun deleteWorkspace(id: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceDelete(WorkspaceDeleteRequest(id))) {
            is RpcResult.Ok -> refreshWorkspaces()
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun goalAction(action: String, objective: String? = null) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (action) {
            "create" -> {
                val obj = objective
                if (obj.isNullOrBlank()) {
                    log("goal create requires an objective")
                    return
                }
                handleResult(api.goalCreate(GoalCreateRequest(sid, obj)))
            }
            "edit", "pause", "resume", "complete", "clear" -> {
                val ref = synchronized(lock) { goalRefFromProjectionLocked() }
                if (ref == null) {
                    log("goal $action requires a current goal (no goal projection)")
                    return
                }
                when (action) {
                    "edit" -> handleResult(api.goalEdit(GoalEditRequest(sid, ref, objective)))
                    "pause" -> handleResult(api.goalPause(GoalPauseRequest(sid, ref)))
                    "resume" -> handleResult(api.goalResume(GoalResumeRequest(sid, ref)))
                    "complete" -> handleResult(api.goalComplete(GoalCompleteRequest(sid, ref)))
                    "clear" -> handleResult(api.goalClear(GoalClearRequest(sid, ref)))
                }
            }
            else -> log("unknown goal action $action")
        }
    }

    /**
     * Reload the session's slash-command catalog.
     *
     * A harness with no command registry answers 404 and a LAN-refused method answers 403; neither
     * is a connection fault, so this degrades the menu to its static fallback rather than raising a
     * failure banner on an otherwise healthy session.
     */
    suspend fun refreshCommands() {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.commandsList(sid)) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid) {
                    _commands.value = r.value
                    _commandsAvailable.value = true
                }
            }
            is RpcResult.Err -> {
                _commandsAvailable.value = false
                _commands.value = emptyList()
                log("commands/list unavailable (${r.error.code}): ${r.error.message}")
            }
        }
    }

    /**
     * Run one complete slash-command line, optionally carrying the composer's images.
     *
     * The typert remote is the *only* command write path: `session.prompt` does not inspect its
     * content, so a leading-slash prompt reaches the model as ordinary user text (this store used
     * to send commands that way, which is why picking a permission preset made the agent shell out
     * to figure out what `/permission` meant). See `docs/PROTOCOL.md`.
     *
     * The remote answers `undefined` when the line parses to no registered command, and the wire
     * codec folds an absent `value` slot into an empty object — so the discriminator is the
     * presence of `commandId`, not the emptiness of the value.
     *
     * [images] must be empty unless the command's descriptor declares it takes them and the host
     * carries them at all — see `DshApiClient.acceptsCommandImages`. A host that admits them but
     * whose handler will not use them (`/plan off`, `/goal pause`) answers with an ordinary error
     * result, which is the harness's own division of labour and not worth mirroring here.
     */
    suspend fun runCommand(
        line: String,
        images: List<EncodedImageAttachment> = emptyList(),
    ): CommandOutcome {
        val sid = currentSessionId.value ?: return CommandOutcome.Failed("no open session")
        val api = apiOrNull() ?: return CommandOutcome.Failed("not connected")
        return when (val r = api.commandsExecute(sid, line, images)) {
            is RpcResult.Ok -> {
                val execution = r.value as? JsonObject
                val commandId = execution?.get("commandId")
                if (commandId == null || commandId is JsonNull) {
                    CommandOutcome.Unknown(line)
                } else {
                    val result = execution["result"] as? JsonObject
                    val text = (result?.get("text") as? JsonPrimitive)?.contentOrNull
                    if ((result?.get("kind") as? JsonPrimitive)?.contentOrNull == "error") {
                        CommandOutcome.Failed(text ?: "command failed")
                    } else {
                        CommandOutcome.Ok(text)
                    }
                }
            }
            is RpcResult.Err -> when (r.error.code) {
                // The images were refused, by the host or by the client's own guard. A composer
                // problem, so it must not raise the connection banner.
                "attachment-error" -> CommandOutcome.Failed(r.error.message)
                // No command gateway in this build (404) or the trust fence refused it (403).
                // Neither is a connection fault, so the menu retires rather than the session.
                "capability-unavailable", "forbidden" -> {
                    _commandsAvailable.value = false
                    _commands.value = emptyList()
                    log("commands/execute unavailable (${r.error.code}): ${r.error.message}")
                    CommandOutcome.Failed(r.error.message)
                }
                else -> {
                    setConnectionError(r.error.message)
                    CommandOutcome.Failed(r.error.message)
                }
            }
        }
    }

    /**
     * Switch the session's permission preset. The read side is the `permissions` projection, so
     * there is nothing to refresh — the harness pushes the new value back on a projection frame.
     */
    suspend fun setPermissionPreset(value: String): CommandOutcome {
        if (value == CUSTOM_PRESET) {
            return CommandOutcome.Failed("`$CUSTOM_PRESET` is a derived state, not a preset")
        }
        _pendingPermission.value = value
        val outcome = runCommand("/permission $value")
        if (outcome !is CommandOutcome.Ok) _pendingPermission.value = null
        return outcome
    }

    /**
     * Reload the host's plugin inventory.
     *
     * Host-scoped and read-only — the harness offers no way to change it from here. A deployment
     * that does not compose `@deepseek-ai/dsh-host-plugin-inventory` answers 404, which leaves the
     * flow null and takes the settings section off the screen: absence of the capability, not a
     * failure to report.
     */
    suspend fun refreshPlugins() {
        val api = apiOrNull() ?: return
        when (val r = api.pluginInventoryList()) {
            is RpcResult.Ok -> _plugins.value = r.value
            is RpcResult.Err -> {
                _plugins.value = null
                log("pluginInventory/list unavailable (${r.error.code}): ${r.error.message}")
            }
        }
    }

    /** Reload the agent-preset roster (host-scoped, so it survives session switches). */
    suspend fun refreshAgentPresets() {
        val api = apiOrNull() ?: return
        when (val r = api.agentPresetList()) {
            is RpcResult.Ok -> _agentPresets.value = r.value
            is RpcResult.Err -> log("agentPreset.list unavailable (${r.error.code}): ${r.error.message}")
        }
    }

    /**
     * Pin an agent preset onto the open session. The harness only allows this while the session is
     * blank; on a started session it answers `agent-preset-locked`, which surfaces as a normal error.
     */
    suspend fun selectAgentPreset(agentPreset: String): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        return when (val r = api.agentPresetSelect(AgentPresetSelectRequest(sid, agentPreset))) {
            is RpcResult.Ok -> {
                refreshSessions()
                true
            }
            is RpcResult.Err -> {
                setConnectionError(r.error.message)
                false
            }
        }
    }

    /**
     * Stream the open session's log ZIP into [sink]. The caller owns [sink] and should close it;
     * the harness answers this as a plain attachment download, not an RPC.
     */
    suspend fun exportSessionTo(sink: OutputStream, includeDescendants: Boolean = false): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        val result = api.sessionExport(sid, includeDescendants) { _, _, body -> body.copyTo(sink) }
        return when (result) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> {
                setConnectionError(result.error.message)
                false
            }
        }
    }

    suspend fun exportSessionUrl(): String? {
        val sid = currentSessionId.value ?: return null
        val host = connectionManager.state.value.host ?: return null
        return "${host.baseUrl}/api/session.export?sessionId=$sid"
    }

    /** True while [sessionId] is the session currently open in the foreground. */
    fun isSessionOpen(sessionId: String): Boolean = currentSessionId.value == sessionId

    // ------------------------------------------------------------------ internal helpers
    private fun goalRefFromProjectionLocked(): GoalRef? {
        val value = currentProjections["goal"]?.value ?: return null
        return runCatching {
            val snapshot = decodeFromJsonElement(GoalSnapshot.serializer(), value)
            GoalRef(snapshot.id, snapshot.revision)
        }.getOrElse {
            runCatching { decodeFromJsonElement(GoalRef.serializer(), value) }.getOrNull()
        }
    }

    private suspend fun loadSkills(sessionId: String) {
        val api = apiOrNull() ?: return
        when (val r = api.skillList(SkillListRequest(sessionId))) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sessionId) _skills.value = r.value.skills
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    private suspend fun loadModels(sessionId: String) {
        val api = apiOrNull() ?: return
        when (val r = api.sessionModels(SessionModelsRequest(sessionId))) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sessionId) _models.value = r.value
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    /**
     * The tail slice of a history page the host over-delivered.
     *
     * `maxMessages` is a bound on *messages*, and not every harness build honours it — one was
     * observed answering a 60-message request with ~29k events (several MB), which folds slowly
     * enough to stall the first paint. Trimming is not as simple as keeping the last N events
     * though: a single assistant message can be hundreds of `assistant/chunk` deltas, so a fixed
     * event count yields a page with almost nothing readable in it. This walks back until it has
     * [HISTORY_PAGE_SIZE] actual messages, with a hard event ceiling so a pathological log still
     * cannot stall the fold. Anything trimmed is reported as `hasMore`, which is what
     * "Load older" is for.
     */
    private fun historyTail(entries: List<HistoryEntry>): List<HistoryEntry> {
        if (entries.size <= MAX_PAGE_EVENTS) return entries
        var messages = 0
        var index = entries.lastIndex
        while (index > 0 && entries.size - index < MAX_PAGE_EVENTS) {
            if (entries[index].event.type in SURFACE_EVENT_TYPES) {
                messages++
                if (messages >= HISTORY_PAGE_SIZE) break
            }
            index--
        }
        return entries.subList(index.coerceAtLeast(0), entries.size)
    }

    private fun subagentEntryId(entry: SubagentListEntry): String? = when (entry) {
        is SubagentListEntry.ChildOneShot -> entry.id
        is SubagentListEntry.ChildContinuable -> entry.id
        is SubagentListEntry.Diagnostic -> entry.id
        is UnknownSubagentListEntry -> null
    }

    /**
     * Whether this connection's harness carries images on a slash command (harness 0.1.0-rc.8).
     *
     * Read at submit time rather than observed: the value is latched during the handshake, long
     * before any command can be dispatched, and it never changes within a connection.
     */
    val commandImagesSupported: Boolean get() = connectionManager.connectedApi?.acceptsCommandImages == true

    private fun apiOrNull(): DshApiClient? {
        val api = connectionManager.connectedApi
        if (api == null) log("not connected — ignoring request")
        return api
    }

    private fun <T> handleResult(result: RpcResult<T>) {
        when (result) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(result.error.message)
        }
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "SessionStore"
        const val HISTORY_PAGE_SIZE = 60

        /** Ceiling on events folded per page, whatever the host sends. */
        const val MAX_PAGE_EVENTS = 4_000

        /** The event types that produce a visible message; everything else frames them. */
        val SURFACE_EVENT_TYPES = setOf("user/message", "assistant/message", "tool/result")

        /** Host-side wire bound for `session.search` (SESSION_SEARCH_QUERY_MAX_CHARS). */
        const val SESSION_SEARCH_QUERY_MAX_CHARS = 500

        /**
         * Floor on the gap between transcript rebuilds while a turn streams.
         *
         * One display frame. Nothing is gained by republishing a transcript faster than it can be
         * drawn, and the deltas of a single turn arrive far faster than that.
         */
        const val REBUILD_INTERVAL_MS = 50L
    }
}
