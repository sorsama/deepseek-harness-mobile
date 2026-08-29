package com.labteto.dshmobile.data

import android.util.Base64
import android.util.Log
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.APPROVAL_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.ApprovalOutcome
import com.labteto.dshmobile.core.wire.dto.ApprovalRequestEvent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionIntent
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionRequestEvent
import com.labteto.dshmobile.core.wire.dto.CUSTOM_PRESET
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.PlanStateView
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.QUESTION_CANCELLED
import com.labteto.dshmobile.core.wire.dto.QueueAction
import com.labteto.dshmobile.core.wire.dto.ModelSelectionProjection
import kotlinx.coroutines.flow.combine
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventRejection
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionControlFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionFollowRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import com.labteto.dshmobile.core.wire.dto.SessionProjectionsBlock
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueRequest
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.USER_QUESTIONS_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import com.labteto.dshmobile.core.wire.dto.imageRejectionOf
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.newPromptRequestId
import java.io.OutputStream
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    private val _models = MutableStateFlow<ModelCatalog?>(null)

    /** The host generation's routable model catalog, before the session's own selection is joined in. */
    val modelCatalog: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _hostInfo = MutableStateFlow<HostDescription?>(null)
    val hostInfo: StateFlow<HostDescription?> = _hostInfo.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()


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

    /** This session's durable model choice; the catalog alone no longer carries one. */
    val modelSelection: StateFlow<ModelSelectionProjection?> =
        projectionOf(ModelSelectionProjection.serializer(), "modelSelection")

    /**
     * The model surface every screen renders: the session's effective selection over the host's
     * catalog.
     *
     * A join rather than a wire value, because 0.1.2 answers the two halves separately — the
     * catalog belongs to the host generation and the selection to the session. `next` wins over
     * `lastUsed` (it is the choice that has not been spent yet), and the deployment default
     * stands in before a session has either.
     */
    val models: StateFlow<SessionModelsValue?> =
        combine(_models, modelSelection) { catalog, selection ->
            if (catalog == null) return@combine null
            val current = selection?.next ?: selection?.lastUsed ?: catalog.default
            SessionModelsValue(
                current = current,
                // `routableProviders` lists what can serve a request at all; whether *this*
                // session can start a turn is whether its own provider is in that list.
                routable = current.provider in catalog.routableProviders,
                groups = catalog.groups,
                failures = catalog.failures,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

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

    // Pending Remote Event waterfalls this store can answer. Keyed by the frame's `eventId`,
    // which is both what an answer names and what a `cancel` frame withdraws — 0.1.2 mints no
    // separate approval id.
    private val approvalRequests = HashMap<String, ApprovalRequest>() // eventId -> request
    private val questionEventBySession = HashMap<String, String>() // sessionId -> eventId

    // Open-session fold state.
    private var currentId: String? = null
    private val currentEvents = ArrayList<SessionEventEnvelope>()
    private var currentHasMore = false
    private var currentBlank = true
    private val currentProjections = HashMap<String, ProjectionValue>()
    private var currentQueue = emptyList<QueueItem>()

    /**
     * The open session's follow cursor: the log cut its current stream generation opened at.
     *
     * `session/page` will not answer without it. Paging is pinned to the same cut the live tail
     * started from, which is what lets an older page and the streaming tail be joined without a
     * gap — so a page requested before the snapshot arrives has nothing to send and is skipped.
     */
    private var followCursor: Int? = null

    /** The open session's live journal. Cancelled and replaced whenever the open session changes. */
    private var followJob: Job? = null

    /** Host-wide live control (queue, jobs, projections). One per connection generation. */
    private var controlJob: Job? = null

    /** Workspace registry stream. One per connection generation. */
    private var workspaceJob: Job? = null

    private data class ApprovalRequest(
        val sessionId: String,
        val eventId: String,
        val toolName: String,
        val reason: String?,
    )
    private data class ProjectionValue(val seq: Int, val value: JsonElement)

    init {
        observeConnection()
        observeEvents()
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

    private fun observeEvents() {
        scope.launch {
            connectionManager.eventFrames.collect { handleEventFrame(it) }
        }
    }

    /**
     * Open the two host-wide streams for this connection generation.
     *
     * Both replace things that used to arrive unbidden on the all-session mux, and both open with
     * a complete baseline — which is the point: a reconnect replaces the mirror wholesale rather
     * than leaving whatever the old generation last said. They are cancelled and reopened with
     * the generation, because a stream's items are only meaningful within the socket that carries
     * them.
     */
    private fun startHostStreams() {
        val mux = connectionManager.generation?.mux ?: return
        controlJob?.cancel()
        controlJob = scope.launch {
            runCatching {
                mux.openStream("session/control").collect { item ->
                    decodeOrNull(SessionControlFrameSerializer, item)?.let { handleControlFrame(it) }
                }
            }.onFailure { log("session/control ended", it) }
        }
        workspaceJob?.cancel()
        workspaceJob = scope.launch {
            runCatching {
                mux.openStream("workspace/follow").collect { item ->
                    decodeOrNull(WorkspaceFollowFrameSerializer, item)?.let { handleWorkspaceFrame(it) }
                }
            }.onFailure { log("workspace/follow ended", it) }
        }
    }

    /** Decode one stream item, or null when it does not match the expected frame union. */
    private fun <T> decodeOrNull(serializer: kotlinx.serialization.KSerializer<T>, item: JsonElement): T? =
        runCatching { decodeFromJsonElement(serializer, item) }.getOrNull()

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
        // Before the list read: the workspace and control streams each open with their own
        // complete baseline, and the list is what their increments are applied on top of.
        startHostStreams()
        _hostInfo.value = connectionManager.generation?.description
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

    // ------------------------------------------------------------------ host event frames
    /**
     * One frame of the host's `$events` stream.
     *
     * This is the whole of what arrives unbidden in 0.1.2. Session events are not here — they
     * belong to a per-session `session/follow` stream — and neither is queue, job or projection
     * state, which belongs to `session/control`. What is left is notifications and the two
     * agent-scoped waterfalls.
     */
    private fun handleEventFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Emit -> handleNotification(frame.event, frame.args)
            is RemoteEventFrame.Waterfall -> handleWaterfall(frame)
            is RemoteEventFrame.Cancel -> handleWaterfallCancelled(frame.eventId)
            // Consumed by the connection loop's handshake; it never forwards one.
            is RemoteEventFrame.Ready -> Unit
            is RemoteEventFrame.Unknown -> log("unknown host event frame ${frame.type}")
        }
    }

    /**
     * One ordinary host notification.
     *
     * Arguments are positional — the host forwards the Cordis listener's own argument list — so
     * these read by index rather than by key. None of them is replayed after a reconnect, which
     * is why every one of them is either repairable from the session list baseline or purely
     * advisory.
     */
    private fun handleNotification(event: String, args: List<JsonElement>) {
        fun str(i: Int) = args.getOrNull(i)?.jsonPrimitive?.contentOrNull
        when (event) {
            "api-session/added" -> args.firstOrNull()?.let { onSessionAdded(it) }
            "api-session/removed" -> str(0)?.let { onSessionRemoved(it) }
            "api-session/status" -> {
                val sid = str(0) ?: return
                val running = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull ?: false
                setRunning(sid, running)
            }
            "api-session/activity" -> {
                // Only reorders the list; the durable value is the session's own projection, so a
                // missed one is corrected by the next list read rather than lost.
                val sid = str(0) ?: return
                val updatedAt = args.getOrNull(1)?.jsonPrimitive?.longOrNull ?: return
                setUpdatedAt(sid, updatedAt)
            }
            "api-session/error" -> setConnectionError(str(1))
            "commands/change" -> scope.launch { refreshCommands() }
            "agent-preset/selected" -> scope.launch {
                refreshAgentPresets()
                refreshCommands()
            }
            else -> Unit
        }
    }

    /** One pending agent-scoped request awaiting this client's answer. */
    private fun handleWaterfall(frame: RemoteEventFrame.Waterfall) {
        when (frame.event) {
            APPROVAL_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(ApprovalRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                handleApprovalRequested(frame.eventId, frame.agentId, request)
            }
            USER_QUESTIONS_REQUEST_EVENT -> {
                val request = runCatching {
                    decodeFromJsonElement(AskUserQuestionRequestEvent.serializer(), frame.request)
                }.getOrNull() ?: return
                handleQuestionRequested(frame.eventId, frame.agentId, request.questions)
            }
            else -> log("unhandled waterfall ${frame.event}")
        }
    }

    /**
     * A pending request was withdrawn: another client answered it, or the host's caller cancelled.
     *
     * Replaces the `approval/resolved` and `question/resolved` frames, and covers both — an
     * `eventId` identifies the request without saying which kind it was, so both registries are
     * checked.
     */
    private fun handleWaterfallCancelled(eventId: String) {
        val approval = synchronized(lock) { approvalRequests.remove(eventId) }
        if (approval != null) {
            synchronized(lock) {
                removePendingLocked(approval.sessionId, "approval")
                emitSessionsLocked()
            }
            if (_pendingApproval.value?.approvalId == eventId) _pendingApproval.value = null
            return
        }
        val sessionId = synchronized(lock) {
            questionEventBySession.entries.firstOrNull { it.value == eventId }?.key
        } ?: return
        synchronized(lock) {
            questionEventBySession.remove(sessionId)
            removePendingLocked(sessionId, "question")
            removePendingLocked(sessionId, "plan-review")
            emitSessionsLocked()
        }
        if (_pendingQuestions.value?.sessionId == sessionId) _pendingQuestions.value = null
    }

    // ------------------------------------------------------------------ control stream
    /**
     * One frame of the host-wide live-control stream.
     *
     * Queue and job values are complete replacements applied last-wins, never deltas, so an
     * empty value is a real "nothing pending" rather than an absent update.
     */
    private fun handleControlFrame(frame: SessionControlFrame) {
        when (frame) {
            is SessionControlFrame.Baseline -> {
                val sid = synchronized(lock) { currentId } ?: return
                frame.value.queues[sid]?.let { items -> applyQueue(sid, items) }
                frame.value.jobs[sid]?.let { jobs -> applyJobs(sid, jobs) }
                frame.value.projections[sid]?.let { block -> applyProjectionBaseline(sid, block) }
            }
            is SessionControlFrame.Queue -> applyQueue(frame.sessionId, frame.items)
            is SessionControlFrame.Jobs -> applyJobs(frame.sessionId, frame.jobs)
            is SessionControlFrame.Projection -> synchronized(lock) {
                if (frame.sessionId == currentId) {
                    mergeProjectionLocked(frame.key, frame.seq, frame.value)
                    rebuildCurrentLocked()
                }
            }
            is SessionControlFrame.Unknown -> log("unknown control frame ${frame.type}")
        }
    }

    private fun applyQueue(sessionId: String, items: List<QueuedInboxItem>) {
        synchronized(lock) {
            if (sessionId == currentId) {
                currentQueue = items.map { queuedInboxItemToQueueItem(it) }
                rebuildCurrentLocked()
            }
        }
    }

    private fun applyJobs(sessionId: String, jobs: List<JobView>) {
        synchronized(lock) {
            if (sessionId == currentId) _jobs.value = jobs
        }
    }

    /**
     * Merge a projection baseline for one session.
     *
     * The tail page's baseline and the control stream's are produced independently, so neither is
     * authoritative on its own; [mergeProjectionLocked] keeps whichever carries the higher
     * watermark.
     */
    private fun applyProjectionBaseline(sessionId: String, block: JsonObject) {
        synchronized(lock) {
            if (sessionId != currentId) return@synchronized
            val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
            (block["values"] as? JsonObject)?.forEach { (key, value) ->
                mergeProjectionLocked(key, asOf, value)
            }
            rebuildCurrentLocked()
        }
    }

    // ------------------------------------------------------------------ workspace stream
    /**
     * One frame of the workspace registry stream.
     *
     * The `order` frame is complete and authoritative; display order is never inferred from the
     * arrival order of upserts, which is what makes the list converge after a reconnect baseline.
     */
    private fun handleWorkspaceFrame(frame: WorkspaceFollowFrame) {
        when (frame) {
            is WorkspaceFollowFrame.Baseline -> synchronized(lock) {
                workspaceRows.clear()
                workspaceOrder.clear()
                for (w in frame.workspaces) workspaceRows[w.workspaceId] = w.toRow()
                workspaceOrder.addAll(frame.workspaceIds.ifEmpty { frame.workspaces.map { it.workspaceId } })
                archived = frame.archivedSessionIds.toSet()
                _archivedSessionIds.value = archived
                emitWorkspacesLocked()
            }
            is WorkspaceFollowFrame.Upsert -> upsertWorkspace(frame.workspace)
            is WorkspaceFollowFrame.Remove -> removeWorkspace(frame.workspaceId)
            is WorkspaceFollowFrame.Order -> setWorkspaceOrder(frame.workspaceIds)
            is WorkspaceFollowFrame.Archived -> setArchived(frame.archivedSessionIds)
            is WorkspaceFollowFrame.Unknown -> log("unknown workspace frame ${frame.type}")
        }
    }

    /**
     * One event from the open session's follow stream.
     *
     * Through 0.1.1 this arrived for every session at once on the mux, which is how the store
     * kept list state for sessions nobody had opened. 0.1.2 has no such stream: an event is only
     * seen for the session actually being followed, and everything else about the list comes from
     * a notification or a list read.
     */
    private fun handleSessionEvent(sessionId: String, envelope: SessionEventEnvelope) {
        when (envelope.type) {
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
        // Completion notifications used to be classified from the all-session mux. That stream is
        // gone, so the session that owns the event forwards it to whoever is watching for one.
        notificationSink?.invoke(sessionId, envelope)
        synchronized(lock) {
            if (sessionId == currentId) appendCurrentEventLocked(envelope)
        }
    }

    /**
     * Where session events go for completion notifications.
     *
     * A hook rather than a direct dependency: the notification observer already depends on this
     * store, and 0.1.2 leaves no all-session stream for it to read instead.
     */
    @Volatile
    var notificationSink: ((String, SessionEventEnvelope) -> Unit)? = null

    private fun handleApprovalRequested(eventId: String, sessionId: String, request: ApprovalRequestEvent) {
        synchronized(lock) {
            approvalRequests[eventId] =
                ApprovalRequest(sessionId, eventId, request.toolName, request.reason)
            addPendingLocked(sessionId, "approval")
            emitSessionsLocked()
        }
        _pendingApproval.value = PendingApproval(
            sessionId = sessionId,
            // The event id is the approval id now: 0.1.2 correlates a pending request by the
            // frame's own `eventId` and mints nothing separate.
            approvalId = eventId,
            rpcId = eventId,
            toolName = request.toolName,
            reason = request.reason,
        )
    }

    private fun handleQuestionRequested(
        eventId: String,
        sessionId: String,
        questions: List<AskUserQuestionItem>,
    ) {
        synchronized(lock) {
            questionEventBySession[sessionId] = eventId
            val kind = if (questions.any { it.intent is AskUserQuestionIntent.PlanReview }) {
                "plan-review"
            } else {
                "question"
            }
            addPendingLocked(sessionId, kind)
            emitSessionsLocked()
        }
        _pendingQuestions.value = PendingQuestions(sessionId, eventId, questions)
    }

    // ------------------------------------------------------------------ session list state updates
    /**
     * One session became visible to list consumers.
     *
     * The notification carries the whole list row rather than the loose fields the old
     * `host/session-added` frame did, so this decodes a summary and folds it in.
     */
    private fun onSessionAdded(summary: JsonElement) {
        val item = runCatching {
            decodeFromJsonElement(SessionSummary.serializer(), summary)
        }.getOrNull() ?: return
        onSessionAdded(item)
    }

    private fun onSessionAdded(item: SessionSummary) {
        synchronized(lock) {
            val existing = sessionRows[item.sessionId]
            val title = titleBySession[item.sessionId]
            val row = existing?.copy(
                title = title ?: existing.title,
                blank = item.blank,
                parentSessionId = item.parentSessionId,
                origin = item.origin,
                cwd = item.cwd,
                agentPreset = item.agentPreset,
            ) ?: SessionRow(
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
            if (existing == null) {
                // New sessions appear at the front (most recent first).
                val copy = LinkedHashMap<String, SessionRow>(sessionRows.size + 1)
                copy[item.sessionId] = row
                copy.putAll(sessionRows)
                sessionRows.clear()
                sessionRows.putAll(copy)
            } else {
                sessionRows[item.sessionId] = row
            }
            emitSessionsLocked()
        }
    }

    private fun onSessionRemoved(sessionId: String) {
        synchronized(lock) {
            sessionRows.remove(sessionId)
            pendingKinds.remove(sessionId)
            runningBySession.remove(sessionId)
            questionEventBySession.remove(sessionId)
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

    /** Reorder one session on a durable user message, without touching anything else about it. */
    private fun setUpdatedAt(sessionId: String, updatedAt: Long) {
        synchronized(lock) {
            val row = sessionRows[sessionId] ?: return@synchronized
            sessionRows[sessionId] = row.copy(updatedAt = updatedAt)
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
    }

    /**
     * Apply one workspace mutation's own answer immediately.
     *
     * `workspace.list` no longer exists; the registry is a stream, and a mutation answers with the
     * value it produced. Applying it here keeps the UI responsive without waiting for the stream
     * to commit, and the stream's next frame — which is authoritative — corrects anything this
     * guessed. Deleting is the one case that must not be optimistic in reverse: a delayed upsert
     * could otherwise resurrect a row, which is why removal goes through the same path as the
     * stream's own.
     */
    private fun applyWorkspaceValue(value: WorkspaceValue) = upsertWorkspace(value.workspace)

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
        startFollow(sessionId)
        loadSkills(sessionId)
        loadModels(sessionId)
        refreshSubagents()
        refreshCommands()
        rememberLastSession(sessionId)
    }

    /**
     * Open the live journal for one session, replacing whatever was open.
     *
     * There is no separate history read any more. `session/follow` opens with a complete snapshot
     * carrying the first page, its projections, and the log cut the generation opened at; every
     * later item is one live event. A reconnect re-opens the stream and sends another complete
     * snapshot, so the window is replaced wholesale rather than patched — which is why the
     * snapshot handler clears the buffer instead of merging into it.
     *
     * Following does not resume a stopped agent: the host publishes a cold session's prepared
     * snapshot immediately and promotes it in the background, so opening a transcript is an
     * observation rather than an execution.
     */
    private fun startFollow(sessionId: String) {
        followJob?.cancel()
        followCursor = null
        val mux = connectionManager.generation?.mux
        if (mux == null) {
            log("cannot follow $sessionId: no connection generation")
            return
        }
        val args = buildJsonObject {
            put(
                "request",
                encodeToJsonElement(
                    SessionFollowRequest.serializer(),
                    SessionFollowRequest(
                        address = SessionAddress.Session(sessionId = sessionId),
                        maxMessages = HISTORY_PAGE_SIZE,
                    ),
                ),
            )
        }
        followJob = scope.launch {
            runCatching {
                mux.openStream("session/follow", args).collect { item ->
                    when (val frame = decodeOrNull(SessionFollowFrameSerializer, item)) {
                        is SessionFollowFrame.Snapshot -> applyFollowSnapshot(sessionId, frame)
                        is SessionFollowFrame.Entry -> applyFollowEntry(sessionId, frame.record)
                        null -> log("undecodable session/follow frame")
                    }
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                log("session/follow ended for $sessionId", failure)
                setConnectionError(failure.message)
            }
        }
    }

    /** Install one complete opening window, replacing any previous one for this session. */
    private fun applyFollowSnapshot(sessionId: String, frame: SessionFollowFrame.Snapshot) {
        clearConnectionError()
        val envelopes = expandRecords(frame.records)
        val page = historyTail(envelopes)
        val overDelivered = envelopes.size > page.size
        synchronized(lock) {
            if (currentId != sessionId) return@synchronized
            followCursor = frame.cursor
            currentEvents.clear()
            currentEvents.addAll(page)
            currentEvents.sortBy { it.seq }
            currentHasMore = frame.hasMore || overDelivered
            val asOf = frame.projections["asOfSeq"]?.jsonPrimitive?.intOrNull ?: frame.cursor
            (frame.projections["values"] as? JsonObject)?.forEach { (key, value) ->
                mergeProjectionLocked(key, asOf, value)
            }
            rebuildCurrentLocked()
        }
    }

    /** One live event. Always scalar — packing applies to history pages only. */
    private fun applyFollowEntry(sessionId: String, record: SessionHistoryRecord) {
        for (envelope in expandRecords(listOf(record))) {
            handleSessionEvent(sessionId, envelope)
        }
    }

    /**
     * Flatten history records into the envelopes the fold consumes.
     *
     * A packed run expands into one event per member; see
     * [com.labteto.dshmobile.core.session.ChunkRows] for why expansion rather than folding.
     */
    private fun expandRecords(records: List<SessionHistoryRecord>): List<SessionEventEnvelope> =
        ChunkRows.expandAll(records).map { wireEventToEnvelope(it) }

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
            val (oldestSeq, cursor) = synchronized(lock) {
                currentEvents.firstOrNull()?.seq to followCursor
            }
            // A page is pinned to the follow generation's log cut, and there is no page without
            // one. Before the opening snapshot lands there is nothing to pin to, so this waits
            // for the next scroll rather than guessing a cut the host would reject.
            if (cursor == null) {
                log("cannot page $sid: no follow cursor yet")
                return@withContext
            }
            val request = SessionPageRequest(
                address = SessionAddress.Session(sessionId = sid),
                throughSeq = cursor,
                beforeSeq = oldestSeq?.toInt(),
                maxMessages = HISTORY_PAGE_SIZE,
            )
            when (val r = api.sessionPage(request)) {
                is RpcResult.Ok -> {
                    clearConnectionError()
                    _loadOlderFailed.value = false
                    // Same guard as the opening window, so paging backwards stays bounded instead
                    // of pulling the whole log at once.
                    val envelopes = expandRecords(r.value.records)
                    val page = historyTail(envelopes)
                    val overDelivered = envelopes.size > page.size
                    synchronized(lock) {
                        if (currentId != sid) return@synchronized
                        val existingSeqs = currentEvents.mapTo(HashSet()) { it.seq }
                        val fresh = page.filter { it.seq !in existingSeqs }
                        if (fresh.isNotEmpty()) {
                            currentEvents.addAll(fresh)
                            currentEvents.sortBy { it.seq }
                        }
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
            requestId = newPromptRequestId(),
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
        val clientId = connectionManager.generation?.clientId
        if (clientId == null) {
            log("cannot answer approval $approvalId: no connection generation")
            return
        }
        val outcome = if (allow) ApprovalOutcome.ALLOWED_ONCE else ApprovalOutcome.REJECTED
        // The waterfall's own return value *is* the outcome string, so this claims the request
        // with a bare value rather than the object 0.1.1 posted to /api/respond.
        val result = api.answerEvent(
            clientId = clientId,
            eventId = request.eventId,
            outcome = RemoteEventOutcome.Result(value = JsonPrimitive(outcome)),
        )
        if (result is RpcResult.Err) log("approval response failed for $approvalId: ${result.error.message}")
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
        val eventId = pendingQuestionEvent(sessionId) ?: return QuestionOutcome.Refused("not-pending")
        val clientId = connectionManager.generation?.clientId ?: return QuestionOutcome.Unsent
        // The waterfall returns the answer object itself; there is no envelope around it now.
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Result(
                    value = encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer),
                ),
            ),
            "question response",
            sessionId,
        )
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
        val eventId = pendingQuestionEvent(sessionId) ?: return QuestionOutcome.Refused("not-pending")
        val clientId = connectionManager.generation?.clientId ?: return QuestionOutcome.Unsent
        // A rejection, not an empty answer, and not `next`: `next` would delegate to the host's
        // own later listeners, which is a different thing from the user closing the prompt.
        return answerOutcome(
            api.answerEvent(
                clientId = clientId,
                eventId = eventId,
                outcome = RemoteEventOutcome.Rejected(
                    error = RemoteEventRejection(
                        name = "UserQuestionError",
                        message = QUESTION_CANCELLED.message,
                        code = QUESTION_CANCELLED.code,
                    ),
                ),
            ),
            "question dismissal",
            sessionId,
        )
    }

    private fun pendingQuestionEvent(sessionId: String): String? {
        val eventId = synchronized(lock) { questionEventBySession[sessionId] }
        if (eventId == null) log("no pending question for session $sessionId")
        return eventId
    }

    /**
     * Map one `$events/result` answer onto the store's outcome vocabulary.
     *
     * A failure here is not retried: upstream fails the whole connection generation on it and
     * replays the pending request on the next one, so a retry would answer the same question
     * twice.
     */
    private fun answerOutcome(
        result: RpcResult<JsonElement>,
        what: String,
        sessionId: String,
    ): QuestionOutcome = when (result) {
        is RpcResult.Ok -> QuestionOutcome.Accepted
        is RpcResult.Err -> {
            log("$what failed for $sessionId: ${result.error.message}")
            if (result.error.code == "not-pending") {
                QuestionOutcome.Refused("not-pending")
            } else {
                QuestionOutcome.Unsent
            }
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
        when (val r = api.subagentList(sid)) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid) _subagents.value = r.value.entries
            }
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun interruptSubagent(childSessionId: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        when (val r = api.subagentInterrupt(childSessionId = childSessionId, parentSessionId = sid)) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun promptSubagent(childSessionId: String, text: String) {
        val sid = currentSessionId.value ?: return
        val api = apiOrNull() ?: return
        val zone = TimeZone.getDefault().id
        val request = SubagentPromptRequest(
            requestId = newPromptRequestId(),
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
        // `subagents/history` is gone: one address protocol covers ordinary sessions and direct
        // children alike, so a child transcript is an ordinary page read against a subagent
        // address. It needs a follow cursor like any other page, and this surface has no stream of
        // its own — so it reads at the parent's current cut, which is the same log the child's
        // events are sequenced in.
        val cursor = synchronized(lock) { followCursor }
        if (cursor == null) {
            _subagentConversation.value = null
            log("cannot read subagent $childSessionId: no follow cursor yet")
            return
        }
        val request = SessionPageRequest(
            address = SessionAddress.Subagent(
                parentSessionId = sid,
                childSessionId = childSessionId,
                mode = mode,
            ),
            throughSeq = cursor,
            maxMessages = HISTORY_PAGE_SIZE,
        )
        when (val r = api.sessionPage(request)) {
            is RpcResult.Ok -> {
                val envelopes = expandRecords(r.value.records)
                _subagentConversation.value = EventFold(childSessionId).fold(envelopes)
                    .copy(hasMore = r.value.hasMore)
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
            is RpcResult.Ok -> upsertWorkspace(r.value.workspace)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun renameWorkspace(id: String, title: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceRename(WorkspaceRenameRequest(id, title))) {
            is RpcResult.Ok -> applyWorkspaceValue(r.value)
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun deleteWorkspace(id: String) {
        val api = apiOrNull() ?: return
        when (val r = api.workspaceDelete(WorkspaceDeleteRequest(id))) {
            is RpcResult.Ok -> removeWorkspace(id)
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
                handleResult(
                    api.goalCreate(sid, buildJsonObject { put("objective", JsonPrimitive(obj)) }),
                )
            }
            "edit", "pause", "resume", "complete", "clear" -> {
                val ref = synchronized(lock) { goalRefFromProjectionLocked() }
                if (ref == null) {
                    log("goal $action requires a current goal (no goal projection)")
                    return
                }
                when (action) {
                    "edit" -> handleResult(
                        api.goalEdit(
                            sid,
                            ref,
                            buildJsonObject {
                                if (objective != null) put("objective", JsonPrimitive(objective))
                            },
                        ),
                    )
                    "pause" -> handleResult(api.goalPause(sid, ref))
                    "resume" -> handleResult(api.goalResume(sid, ref))
                    "complete" -> handleResult(api.goalComplete(sid, ref))
                    "clear" -> handleResult(api.goalClear(sid, ref))
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
        return when (val r = api.agentPresetSelect(sid, agentPreset)) {
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
        // Host-scoped now, not session-scoped: `session/modelCatalog` describes the generation's
        // routable models, and the session's own current selection comes from its projections.
        when (val r = api.sessionModelCatalog()) {
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
    private fun historyTail(entries: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
        if (entries.size <= MAX_PAGE_EVENTS) return entries
        var messages = 0
        var index = entries.lastIndex
        while (index > 0 && entries.size - index < MAX_PAGE_EVENTS) {
            if (entries[index].type in SURFACE_EVENT_TYPES) {
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
     * Whether this connection's harness carries images on a slash command.
     *
     * Always true from harness 0.1.2: `commands/execute` declares the `images` parameter
     * unconditionally, and the shape-derived capability check this used to perform depended on
     * `host.describe`, which no longer exists. Kept as a property so the composer's adjudication
     * has one place to consult if a future release makes it conditional again.
     */
    val commandImagesSupported: Boolean get() = connectionManager.connectedApi != null

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
