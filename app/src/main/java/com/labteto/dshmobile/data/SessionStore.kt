package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.automationCatalog
import com.labteto.dshmobile.core.wire.pluginBundles

import android.util.Base64
import android.util.Log
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.session.AssistantLiveState
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
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.core.wire.dto.ContentBlock
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileUploadValue
import com.labteto.dshmobile.core.wire.dto.GoalRef
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
import com.labteto.dshmobile.core.wire.dto.InboxProjection
import com.labteto.dshmobile.core.wire.dto.QueuedInboxItem
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventRejection
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef
import com.labteto.dshmobile.core.wire.dto.LegacySessionAttachmentRequest
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
import com.labteto.dshmobile.core.wire.dto.JobFollowFrame
import com.labteto.dshmobile.core.wire.dto.JobFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.JobFollowRequest
import com.labteto.dshmobile.core.wire.dto.JobListRequest
import com.labteto.dshmobile.core.wire.dto.SessionActivity
import com.labteto.dshmobile.core.wire.dto.WorkspaceSessionActiveDetails
import com.labteto.dshmobile.core.wire.dto.jobRowsOf
import com.labteto.dshmobile.core.wire.dto.subagentEntriesFromCatalog
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.USER_QUESTIONS_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertSessionBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import com.labteto.dshmobile.core.wire.dto.imageRejectionOf
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.newPromptRequestId
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.TimeZone
import com.labteto.dshmobile.core.wire.dto.PermissionCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Current CAS identity from a bare goal/ref or the harness's `{ goal: ... }` projection. */
internal fun goalRefFromProjection(value: JsonElement?): GoalRef? {
    val projection = value as? JsonObject ?: return null
    // An explicit null goal means cleared; never fall back to an envelope's unrelated fields.
    val goal = if ("goal" in projection) projection["goal"] else projection
    return goal?.let {
        runCatching { decodeFromJsonElement(GoalRef.serializer(), it) }.getOrNull()
    }?.takeIf { it.id.isNotBlank() && it.revision > 0 }
}

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
    val formatStatus: String? = null,
    /**
     * Durable identity when this row is a subagent child, else null.
     *
     * `origin` decides, not `parentSessionId`: a fork records a parent too, and a fork is an
     * ordinary session. See [subagentIdentityOf].
     */
    val subagent: SubagentIdentity? = null,
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
     * Taken. The wait behind the card is over, so the card goes with it — the host announces that
     * resolution to every *other* client and to this one never, so nothing else can take it away.
     */
    data object Accepted : QuestionOutcome

    /**
     * Refused by the host. `bad-response` means the payload did not match the request it
     * answered; [NOT_PENDING] means the wait had already settled; anything else is the code the
     * host sent, named rather than translated — a refusal this build has never heard of is still
     * worth showing, because the wait behind it stays open either way.
     */
    data class Refused(val reason: String) : QuestionOutcome

    /** The POST never completed, so nothing is known about the wait. */
    data object Unsent : QuestionOutcome
}

/** The refusal that means the request this answer addressed is already over. */
internal const val NOT_PENDING: String = "not-pending"

/**
 * Whether [outcome] means this client is done holding the request it answered.
 *
 * Two of the three do. [QuestionOutcome.Accepted] is the host taking the answer, and
 * [QuestionOutcome.Refused] with [NOT_PENDING] is the host saying the wait had already settled —
 * one is a card that did its job and the other a card that outlived its request, and neither has
 * anything left to send. Every other refusal leaves the host's wait open with the tool call behind
 * it still blocked, so the card has to stay: it is the only thing that can still answer. So does
 * [QuestionOutcome.Unsent], where nothing is known about the wait at all and taking the card away
 * would strand the session with no way to retry.
 *
 * File-level so the rule is testable without standing up the whole store, as [nextHasMore] is.
 */
internal fun settlesRequest(outcome: QuestionOutcome): Boolean = when (outcome) {
    is QuestionOutcome.Accepted -> true
    is QuestionOutcome.Refused -> outcome.reason == NOT_PENDING
    is QuestionOutcome.Unsent -> false
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

/** Projection key carrying the agent's pending input; the queue dock's source since 0.1.6-alpha.2. */
private const val INBOX_PROJECTION = "inbox"

/** Projection key carrying a session's agent preset; harness rows have no top-level field for it. */
private const val AGENT_PRESET_PROJECTION = "agentPreset"

/** Projection key carrying a parent's direct subagents; `subagents/list` was removed in 0.1.7. */
private const val SUBAGENT_CATALOG_PROJECTION = "subagentCatalog"

/**
 * What became of an archive request.
 *
 * [Busy] is harness 0.1.7's refusal to archive a session that still has work running; the caller
 * asks the person whether to stop that work, and archives again with `stopActivity` if they agree.
 */
sealed interface ArchiveOutcome {
    data object Archived : ArchiveOutcome
    data class Busy(val sessionId: String, val activity: List<SessionActivity>) : ArchiveOutcome
    data object Failed : ArchiveOutcome
}

/**
 * Single source of truth for the connected harness's live state. All public surface is
 * [StateFlow]; every RPC error becomes [connectionError] and never throws. The store survives
 * reconnects by re-baselining on the connection state transition and on `session/subscribed`.
 */
@Singleton
class SessionStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    private val connectionManager: ConnectionManager,
    private val hostsStore: HostsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val baselineMutex = Mutex()
    private val noCurrentGoalMessage = { context.getString(com.labteto.dshmobile.R.string.goal_none) }
    private val migrationRequiredMessage = { context.getString(com.labteto.dshmobile.R.string.alpha2_migration) }
    private val subagentReadOnlyMessage = { context.getString(com.labteto.dshmobile.R.string.subagents_readonly) }
    private val featureAvailability = FeatureAvailability { setConnectionError(it.message) }
    val automationAvailable = featureAvailability.state(HarnessFeature.AUTOMATION)
    val pluginManagementAvailable = featureAvailability.state(HarnessFeature.PLUGIN_MANAGEMENT)
    val timedQuestionsAvailable = featureAvailability.state(HarnessFeature.TIMED_QUESTIONS)
    val sessionReferencesAvailable = featureAvailability.state(HarnessFeature.SESSION_REFERENCES)
    val questionSessions = QuestionSessions(scope, { connectionManager.connectedApi },
        { connectionManager.generation?.mux }, { connectionManager.generation?.clientId },
        onCapability = { clientId, result ->
            if (clientId != null && clientId == connectionManager.generation?.clientId) {
                featureAvailability.observe(HarnessFeature.TIMED_QUESTIONS, featureAvailability.generation(), result)
            }
        },
        onUnavailable = { sid, eventId, questions -> handleQuestionRequested(eventId, sid, questions) })
    private val _hostEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, List<JsonElement>>>(extraBufferCapacity = 128)
    val hostEvents = _hostEvents as kotlinx.coroutines.flow.SharedFlow<Pair<String, List<JsonElement>>>

    val pluginOperations = PluginOperations(scope, ::apiForHost, context.getSharedPreferences("plugin-operation", android.content.Context.MODE_PRIVATE),
        observe = { featureObserver(HarnessFeature.PLUGIN_MANAGEMENT) },
        available = { pluginManagementAvailable.value != false })
    val connectionState = connectionManager.state
    val activeHostKey: String? get() = connectionManager.state.value.host?.let { "${it.baseUrl}|${it.id}" }
    internal val panels = com.labteto.dshmobile.ui.screens.main.PanelRepository()
    internal val composers = com.labteto.dshmobile.ui.screens.main.ComposerRepository(context.getSharedPreferences("composer-drafts", android.content.Context.MODE_PRIVATE))
    fun apiForHost(key: String?): DshApiClient? = if (key != null && key == activeHostKey) connectionManager.connectedApi else null
    fun muxForHost(key: String?) = if (key != null && key == activeHostKey) connectionManager.generation?.mux else null
    fun retryConnection() = connectionManager.reconnectIfNeeded()

    suspend fun <T> featureCall(feature: HarnessFeature, api: DshApiClient, call: suspend () -> RpcResult<T>): RpcResult<T> {
        val observe = featureObserver(feature)
        val result = call()
        observe(api, result)
        return result
    }

    private fun featureObserver(feature: HarnessFeature): (DshApiClient, RpcResult<*>) -> Unit {
        val epoch = featureAvailability.generation()
        val clientId = connectionManager.generation?.clientId
        return { api, result ->
            if (clientId != null && clientId == connectionManager.generation?.clientId && api === connectionManager.connectedApi) {
                featureAvailability.observe(feature, epoch, result)
            }
        }
    }

    private fun probeFeatures() {
        val api = connectionManager.connectedApi ?: return
        scope.launch { featureCall(HarnessFeature.AUTOMATION, api) { api.automationCatalog() } }
        scope.launch { featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginBundles() } }
    }
    private val permissionCatalog = MutableStateFlow<PermissionCatalog?>(null)
    private var permissionCatalogEpoch = 0L
    private val queuesBySession = MutableStateFlow<Map<String, List<QueueItem>>>(emptyMap())
    val sessionQueues: StateFlow<Map<String, List<QueueItem>>> = queuesBySession.asStateFlow()


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

    /**
     * Whether jobs can be stopped and followed from here: true once the harness has answered a
     * `job/list` stream (0.1.7). A 0.1.6 host reports jobs on `session/control` and offers neither.
     */
    private val _jobControls = MutableStateFlow(false)
    val jobControls: StateFlow<Boolean> = _jobControls.asStateFlow()

    /**
     * The registry-global pin set, most recently pinned first, or null when the harness has no
     * pinning (anything before 0.1.7) and the drawer should offer none.
     */
    private val _pinnedSessionIds = MutableStateFlow<List<String>?>(null)
    val pinnedSessionIds: StateFlow<List<String>?> = _pinnedSessionIds.asStateFlow()

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

    /**
     * The open session's identity when that session is a subagent child, else null.
     *
     * The chat needs it for two different reasons: the follow/page address has to travel through
     * the parent (harness 0.2.2 refuses a plain session address for an `origin: subagent` log),
     * and only a continuable child accepts a message at all.
     */
    private val _currentSubagent = MutableStateFlow<SubagentIdentity?>(null)
    val currentSubagent: StateFlow<SubagentIdentity?> = _currentSubagent.asStateFlow()

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

    /**
     * The in-flight permission switch. It ends on every path — see [PermissionSwitch] — so the
     * chip it drives can never be left disabled behind a skeleton.
     */
    private val permissionSwitch = PermissionSwitch(scope)

    /** The preset a switch is in flight for; null once it has settled, failed, timed out or been abandoned. */
    val pendingPermission: StateFlow<String?> = permissionSwitch.pending

    // ------------------------------------------------------------------ projection views
    // These are folds of `currentConversation.projections`, not separate fetches: the harness
    // already pushes every one of them on `session/projection` frames and in the history tail, so
    // deriving keeps them in lockstep with the transcript and adds no round trips. A null value
    // means the key is absent — the harness composes no such service — and callers hide the UI.

    val permissions: StateFlow<PermissionSelect?> = combine(
        projectionOf(PermissionSelect.serializer(), "permissions"), permissionCatalog,
    ) { selection, catalog ->
        // The projection carries its own options; the catalog RPC (permissionPresets/catalog) is a
        // newer host endpoint that 404s on older deployments. When it is absent, keep what the
        // projection already has rather than blanking it.
        selection?.copy(options = catalog?.options ?: selection.options)
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
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

    /** The newest agent preset seen per session, from either the list or the control stream. */
    private val presetBySession = HashMap<String, AgentPresetState>()
    private val workspaceRows = LinkedHashMap<String, WorkspaceRow>()
    private val workspaceOrder = ArrayList<String>()

    /**
     * Orders the reader asked for that the host has not confirmed yet, laid over what it last said.
     *
     * A move shows at once and is sent after; until the host answers, a stream frame carrying the old
     * order must not flick the list back, which is why these sit beside the host's rows rather than
     * replacing them. They are dropped when the answer lands (the host's own order is then the truth)
     * or when a move fails (which is the rollback).
     */
    private val pendingSessionOrder = HashMap<String, List<String>>()
    private var pendingWorkspaceOrder: List<String>? = null

    /** How many moves are waiting or in flight, per workspace (or [WORKSPACE_ORDER_KEY]). */
    private val movesInFlight = HashMap<String, Int>()

    /** Bumped when a move fails and the overlays are thrown away, so queued moves know to stand down. */
    private var moveEpoch = 0

    /** One move at a time reaches the host, in the order they were made. */
    private val moveMutex = Mutex()
    private var archived = emptySet<String>()
    private val pendingKinds = HashMap<String, MutableSet<String>>()
    private var modernQuestionKinds: Map<String, Set<String>> = emptyMap()

    // Pending Remote Event waterfalls this store can answer. Keyed by the frame's `eventId`,
    // which is both what an answer names and what a `cancel` frame withdraws — 0.1.2 mints no
    // separate approval id.
    private val approvalRequests = HashMap<String, ApprovalRequest>() // eventId -> request
    private val questionEvents = PendingQuestionRegistry()

    // Open-session fold state.
    private var currentId: String? = null
    /**
     * Identity of the open session when it is a subagent child, mirroring [currentSubagent].
     *
     * Held next to the fold because the address is built on every follow reconnect, and the
     * state flow is the UI's view of the same fact — one read from the wire is stored once.
     */
    private var openSubagent: SubagentIdentity? = null
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

    /**
     * The reply being written in the open session, as the follow stream's assistant frames show
     * it. Harness 0.1.3 logs no deltas, so this is the only source of a streaming preview; it is
     * folded after the durable window and retired by the settlement. Guarded by [lock].
     */
    private val liveAssistant = AssistantLiveState()

    /** The open session's live journal. Cancelled and replaced whenever the open session changes. */
    private var followJob: Job? = null
    private var childFollowJob: Job? = null

    /** Host-wide live control (queue, jobs, projections). One per connection generation. */
    private var controlJob: Job? = null

    /** The open session's `job/list` stream (harness 0.1.7). Replaced with the open session. */
    private var jobsJob: Job? = null

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
        scope.launch {
            questionSessions.cards.collect { cards ->
                val kinds = cards.pendingQuestionKinds()
                synchronized(lock) {
                    if (modernQuestionKinds != kinds) {
                        modernQuestionKinds = kinds
                        emitSessionsLocked()
                    }
                }
            }
        }
        observeConnection()
        observeEvents()
        observePermissionSettlement()
        observeRebuildTicks()
    }

    /**
     * Tell the permission switch what the harness's own projection says. The chip shows the target
     * immediately and stops pretending as soon as the truth arrives — whatever the truth is.
     */
    private fun observePermissionSettlement() {
        scope.launch {
            permissions.collect { select -> permissionSwitch.observe(select?.currentValue) }
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
                if (prev.host?.id != state.host?.id || prev.host?.baseUrl != state.host?.baseUrl ||
                    (prev.phase != state.phase && (prev.phase == ConnectionPhase.CONNECTED || state.phase == ConnectionPhase.CONNECTED))) {
                    featureAvailability.reset()
                }
                if (prev.host?.id != state.host?.id || prev.host?.baseUrl != state.host?.baseUrl) questionSessions.reset()
                else if (prev.phase == ConnectionPhase.CONNECTED && state.phase != ConnectionPhase.CONNECTED) questionSessions.disconnect()
                val initialConnect = !prev.hasConnected && state.hasConnected
                val reconnect = prev.hasConnected &&
                    prev.phase == ConnectionPhase.RECONNECTING &&
                    state.phase == ConnectionPhase.CONNECTED
                prev = state
                // A switch in flight when the socket drops can no longer be confirmed by a frame
                // on that socket, and the reconnect's baseline replaces the projection wholesale.
                if (state.phase != ConnectionPhase.CONNECTED) permissionSwitch.reset()
                if (initialConnect || reconnect) {
                    probeFeatures()
                    triggerBaseline()
                    activeHostKey?.let { pluginOperations.resume(it) }
                }
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
        // Preset watermarks are log positions as this host reported them. A harness restored from
        // an older snapshot can hand back the same session ids at lower positions, so the new
        // stream's baseline and the list read that follows start them over.
        synchronized(lock) { presetBySession.clear() }
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
        _jobControls.value = false
        // Before the list read: the workspace and control streams each open with their own
        // complete baseline, and the list is what their increments are applied on top of.
        startHostStreams()
        _hostInfo.value = connectionManager.generation?.description
        coroutineScope {
            // Host-scoped and needed before anything is tapped, but needed by nothing on the way to
            // the transcript: the chat bar names the session's preset as soon as it renders, and
            // the permission chip wants its catalog, and both can land while the session opens.
            // Awaiting them here put two full phone→relay→host round trips in front of the first
            // thing the reader actually looks at. Neither throws — each reports its own failure —
            // so nothing downstream has to know whether they have landed yet.
            launch { refreshAgentPresets() }
            launch { refreshPermissionCatalog() }
            // The list is the one read the landing session is chosen from, so it alone is awaited.
            refreshSessions()
            // On a reconnect `currentSessionId` is already set, so the resolver only ever runs on
            // the first connect of a process — no double-open, and reconnect keeps reopening what
            // was open.
            val sid = currentSessionId.value ?: resolveInitialSession() ?: return@coroutineScope
            openSession(sid)
        }
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
        val remembered = connectionManager.state.value.host?.let { hostsStore.lastSessionId(it) }
        val (rows, workspaces, archivedNow) = synchronized(lock) {
            Triple(
                sessionRows.values.toList(),
                workspaceOrder.mapNotNull { workspaceRows[it] },
                archived,
            )
        }
        return pickInitialSession(rows, workspaces, archivedNow, remembered)
    }

    /** Full endpoint identity: sessions behind different proxy roots belong to different hosts. */
    private fun hostKey(): String? =
        connectionManager.state.value.host?.baseUrl

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
        _hostEvents.tryEmit(event to args)
        pluginOperations.event(activeHostKey, event, args)
        fun str(i: Int) = args.getOrNull(i)?.jsonPrimitive?.contentOrNull
        when (event) {
            "api-session/added" -> args.firstOrNull()?.let { onSessionAdded(it) }
            "api-session/removed" -> str(0)?.let { onSessionRemoved(it) }
            "api-session/status" -> {
                val sid = str(0) ?: return
                val running = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull ?: false
                setRunning(sid, running)
            }
            "permission-presets/catalog-changed" -> scope.launch { refreshPermissionCatalog() }
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
            // The rest of the host's allowlist is either developer tooling (`cordis/*`), or a
            // change this client already learns from a projection, or a surface it does not
            // present — settings and credentials among them, which is why nothing here goes stale
            // when they change. Naming the unhandled one in the log is what makes the next gap
            // findable; dropping it silently is how `goal/activation-changed` went unnoticed.
            else -> log("unhandled emit $event")
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
                if (request.wait != null && timedQuestionsAvailable.value != false) questionSessions.requested(frame.agentId, frame.eventId, request)
                else handleQuestionRequested(frame.eventId, frame.agentId, request.questions)
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
     *
     * It is *not* the only way a request leaves: the host drops the answering client's delivery
     * before it cancels the rest, so this frame reaches every client except the one that acted.
     * That client settles its own card in [answerOutcome].
     */
    private fun handleWaterfallCancelled(eventId: String) {
        questionSessions.cancelled(eventId)
        forgetRequest(eventId)
    }

    /**
     * Drop one request this client is holding, whoever settled it.
     *
     * Idempotent by construction — every step is a remove or a null-if-matching — because the two
     * callers can both fire for one request: this client answers, forgets it here, and a `cancel`
     * for the same `eventId` may still arrive if the host had a second delivery open.
     */
    private fun forgetRequest(eventId: String) {
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
            questionEvents.sessionFor(eventId)
        } ?: return
        forgetQuestions(sessionId, eventId)
    }

    /**
     * Drop the question batch [sessionId] is holding — but only while it is still [eventId]'s, and
     * only the card drawn from that same request. [PendingQuestionRegistry.forget] holds the rule
     * and the reason for it; a null [eventId] is the corpse case it describes.
     */
    private fun forgetQuestions(sessionId: String, eventId: String?) {
        // Registry and card move together under the lock, so a replacement cannot land between
        // them and lose its card to this call.
        synchronized(lock) {
            if (!questionEvents.forget(sessionId, eventId)) return
            removePendingLocked(sessionId, "question")
            removePendingLocked(sessionId, "plan-review")
            emitSessionsLocked()
            val shown = _pendingQuestions.value
            if (shown?.sessionId == sessionId && (eventId == null || shown.rpcId == eventId)) {
                _pendingQuestions.value = null
            }
        }
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
                // A host that still computes queues wins for the sessions it names; every other
                // session's pending work is rebuilt from its own `inbox` projection.
                val legacy = frame.value.queues.mapValues { (_, items) -> items.map(::queuedInboxItemToQueueItem) }
                queuesBySession.value = inboxQueuesFrom(frame.value.projections) + legacy
                synchronized(lock) {
                    var changed = false
                    frame.value.projections.forEach { (sessionId, block) ->
                        val asOf = block["asOfSeq"]?.jsonPrimitive?.intOrNull ?: 0
                        (block["values"] as? JsonObject)?.get("userQuestions")?.let { questionSessions.projection(sessionId, asOf, it) }
                        (block["values"] as? JsonObject)?.get("inbox")?.let { questionSessions.inbox(sessionId, it) }
                        val value = (block["values"] as? JsonObject)?.get(AGENT_PRESET_PROJECTION)
                        if (foldAgentPresetLocked(sessionId, asOf, value)) changed = true
                    }
                    if (changed) emitSessionsLocked()
                }
                val sid = synchronized(lock) { currentId } ?: return
                frame.value.queues[sid]?.let { items -> applyQueue(sid, items) }
                frame.value.jobs[sid]?.let { jobs -> applyJobs(sid, jobs) }
                frame.value.projections[sid]?.let { block -> applyProjectionBaseline(sid, block) }
            }
            is SessionControlFrame.Queue -> applyQueue(frame.sessionId, frame.items)
            is SessionControlFrame.Jobs -> applyJobs(frame.sessionId, frame.jobs)
            is SessionControlFrame.Projection -> {
                // Harness 0.2.2 folds projections per committed event and names every key the
                // event touched in one frame; through 0.2.1 a frame carried exactly one key. The
                // decoder normalizes both to `changes`, so each entry applies with the semantics
                // this branch used to give "the key".
                for (change in frame.changes) {
                    if (change.key == "userQuestions") questionSessions.projection(frame.sessionId, frame.seq, change.value)
                    if (change.key == "inbox") questionSessions.inbox(frame.sessionId, change.value)
                    // The inbox projection is the queue now, and it arrives for every live session —
                    // not just the open one — so the chat list's per-session dock reads it here.
                    if (change.key == INBOX_PROJECTION) {
                        val items = InboxProjection.itemsFrom(change.value).map(::queuedInboxItemToQueueItem)
                        queuesBySession.value = queuesBySession.value + (frame.sessionId to items)
                    }
                }
                synchronized(lock) {
                    var presetChanged = false
                    for (change in frame.changes) {
                        // Another client (the web GUI) can pick a preset after our list read, and
                        // the list row is what the top-bar chip and the details pill draw from.
                        if (change.key == AGENT_PRESET_PROJECTION &&
                            foldAgentPresetLocked(frame.sessionId, frame.seq, change.value)
                        ) {
                            presetChanged = true
                        }
                    }
                    if (presetChanged) emitSessionsLocked()
                    if (frame.changes.isNotEmpty() && frame.sessionId == currentId) {
                        // One rebuild per frame rather than per changed key: the frame is the
                        // unit the host committed, and folding a long transcript is not free.
                        for (change in frame.changes) {
                            mergeProjectionLocked(change.key, frame.seq, change.value)
                        }
                        rebuildCurrentLocked()
                    }
                }
            }
            is SessionControlFrame.Unknown -> log("unknown control frame ${frame.type}")
        }
    }

    /**
     * Fold one `agentPreset` projection value seen at [seq] into the session's row. Returns whether
     * the row changed; the caller publishes. A session with no row yet keeps the value for the
     * list read that brings its row, rather than having a row invented here.
     */
    private fun foldAgentPresetLocked(sessionId: String, seq: Int, value: JsonElement?): Boolean {
        val incoming = agentPresetUpdate(seq, value) ?: return false
        val kept = newerAgentPreset(presetBySession[sessionId], incoming)
        presetBySession[sessionId] = kept
        val row = sessionRows[sessionId] ?: return false
        if (row.agentPreset == kept.preset) return false
        sessionRows[sessionId] = row.copy(agentPreset = kept.preset)
        return true
    }

    /**
     * The preset a list row should show: its own, unless the control stream already delivered one
     * from a later position in the session's log. A row without a projection block has no position
     * and yields to anything already seen.
     */
    private fun listedAgentPresetLocked(item: SessionSummary): String? {
        val kept = listedAgentPreset(
            held = presetBySession[item.sessionId],
            asOfSeq = item.projections?.asOfSeq ?: -1,
            topLevel = item.agentPreset,
            projected = item.projections?.values?.get(AGENT_PRESET_PROJECTION),
            cached = item.projections?.kind == "cached",
        ) ?: return null
        presetBySession[item.sessionId] = kept
        return kept.preset
    }

    /**
     * Rebuild every named session's pending queue from its projection baseline.
     *
     * The baseline's per-session block is `{asOfSeq, values}`; a session whose composition
     * publishes no inbox is simply absent, which reads as "nothing pending" rather than as an
     * error, exactly as an absent projection key does everywhere else.
     */
    private fun inboxQueuesFrom(projections: Map<String, JsonObject>): Map<String, List<QueueItem>> =
        projections.mapNotNull { (sessionId, block) ->
            val inbox = (block["values"] as? JsonObject)?.get(INBOX_PROJECTION) ?: return@mapNotNull null
            sessionId to InboxProjection.itemsFrom(inbox).map(::queuedInboxItemToQueueItem)
        }.toMap()

    private fun applyQueue(sessionId: String, items: List<QueuedInboxItem>) {
        queuesBySession.value = queuesBySession.value + (sessionId to items.map(::queuedInboxItemToQueueItem))
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
        if (block["kind"]?.jsonPrimitive?.contentOrNull in setOf("cached", "migration-required")) return
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
                _pinnedSessionIds.value = frame.pinnedSessionIds
                emitWorkspacesLocked()
            }
            is WorkspaceFollowFrame.Pinned -> _pinnedSessionIds.value = frame.pinnedSessionIds
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
            if (sessionId == currentId) {
                // The durable settlement and the transient rows say the same thing; the moment
                // the settlement lands the preview is redundant, and a fold that saw both would
                // show the reply twice.
                val data = envelope.data as? JsonObject
                liveAssistant.acceptDurable(
                    type = envelope.type,
                    turn = data?.get("turn")?.jsonPrimitive?.intOrNull,
                    step = data?.get("step")?.jsonPrimitive?.intOrNull,
                    seq = envelope.seq,
                    surfaceOp = envelope.surfaceOp,
                )
                appendCurrentEventLocked(envelope)
            }
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
        // Harness 0.1.7 sends a localized `displayReason` beside the raw one (an Auto review
        // denial, a sandbox escalation); the reader's own language wins, then English, then raw.
        val reason = request.reasonFor(java.util.Locale.getDefault().language)
        synchronized(lock) {
            approvalRequests[eventId] =
                ApprovalRequest(sessionId, eventId, request.toolName, reason)
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
            reason = reason,
        )
    }

    private fun handleQuestionRequested(
        eventId: String,
        sessionId: String,
        questions: List<AskUserQuestionItem>,
    ) {
        synchronized(lock) {
            questionEvents.install(sessionId, eventId)
            val kind = if (questions.any { it.intent is AskUserQuestionIntent.PlanReview }) {
                "plan-review"
            } else {
                "question"
            }
            addPendingLocked(sessionId, kind)
            emitSessionsLocked()
            // Inside the lock, with the registration it belongs to: [forgetQuestions] decides
            // whether to clear the card by reading that registration, so a request that installed
            // one but not yet the other could have its card taken by an answer to the request it
            // just replaced.
            _pendingQuestions.value = PendingQuestions(sessionId, eventId, questions)
        }
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
            val preset = listedAgentPresetLocked(item)
            // `session-added` may carry less than the list read did, so a row that already knows
            // its identity keeps it instead of being wiped by a frame without projections.
            val subagent = subagentIdentityOf(
                item.origin,
                item.parentSessionId,
                item.projections?.values?.get(SUBAGENT_IDENTITY_PROJECTION),
            ) ?: existing?.subagent
            val row = existing?.copy(
                title = title ?: existing.title,
                blank = item.blank,
                parentSessionId = item.parentSessionId,
                origin = item.origin,
                cwd = item.cwd,
                agentPreset = preset,
                formatStatus = item.formatStatus,
                subagent = subagent,
            ) ?: SessionRow(
                sessionId = item.sessionId,
                title = title,
                running = runningBySession[item.sessionId] ?: item.running,
                blank = item.blank,
                parentSessionId = item.parentSessionId,
                origin = item.origin,
                cwd = item.cwd,
                agentPreset = preset,
                updatedAt = item.updatedAt,
                pendingInteraction = null,
                formatStatus = item.formatStatus,
                subagent = subagent,
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
        questionSessions.removeSession(sessionId)
        synchronized(lock) {
            sessionRows.remove(sessionId)
            pendingKinds.remove(sessionId)
            runningBySession.remove(sessionId)
            presetBySession.remove(sessionId)
            questionEvents.discard(sessionId)
            emitSessionsLocked()
        }
    }

    private fun setRunning(sessionId: String, running: Boolean) {
        synchronized(lock) {
            runningBySession[sessionId] = running
            sessionRows[sessionId]?.let { if (it.running != running) sessionRows[sessionId] = it.copy(running = running) }
            if (sessionId == currentId) rebuildCurrentLocked()
            // A child's own status is what says whether it is running; the catalog only lists it.
            else publishCatalogSubagentsLocked()
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
            currentId?.let { sid ->
                if (key == "userQuestions") questionSessions.projection(sid, seq, value)
                if (key == "inbox") questionSessions.inbox(sid, value)
                if (key == SUBAGENT_IDENTITY_PROJECTION) refreshOpenSubagentLocked(sid)
            }
        }
    }

    /**
     * Fold the open child's own identity projection into the address state.
     *
     * The list row normally carries the mode already, but it is optional and may predate the
     * descriptor. The follow snapshot's projections are the child's own log, so this is where an
     * `unknown` mode becomes the real one — which is what lets the composer turn on for a
     * continuable child and stays read-only for a one-shot one.
     */
    private fun refreshOpenSubagentLocked(sessionId: String) {
        val row = sessionRows[sessionId]
        val origin = when {
            row?.origin != null -> row.origin
            // A row that arrived before the list read may not carry `origin` yet; the state we
            // already hold says this session was opened as a child, so keep refining it.
            openSubagent != null -> SUBAGENT_ORIGIN
            else -> null
        }
        val identity = subagentIdentityOf(
            origin = origin,
            parentSessionId = openSubagent?.parentSessionId ?: row?.parentSessionId,
            projection = currentProjections[SUBAGENT_IDENTITY_PROJECTION]?.value,
        ) ?: return
        if (identity != openSubagent) {
            openSubagent = identity
            _currentSubagent.value = identity
        }
    }

    private fun rebuildCurrentLocked() {
        val sid = currentId ?: return
        val events = currentEvents.toList()
        val snapshot = EventFold(sid).fold(events, liveAssistant.transientEnvelopes())
        val blank = if (events.isEmpty()) currentBlank else snapshot.blank
        val running = runningBySession[sid] ?: snapshot.running
        // The queue is the `inbox` projection wherever the host publishes one — it reaches us from
        // both the control stream and the follow snapshot, whichever is ahead. `currentQueue` is
        // the older `session/control` queue frame, kept for a host that still sends them.
        val inbox = currentProjections[INBOX_PROJECTION]?.value
        val queue = if (inbox != null) {
            InboxProjection.itemsFrom(inbox).map(::queuedInboxItemToQueueItem)
        } else {
            currentQueue
        }
        val merged = snapshot.copy(
            blank = blank,
            running = running,
            hasMore = currentHasMore,
            queue = queue,
            projections = currentProjections.mapValues { it.value.value },
        )
        questionSessions.restoreSettled(merged)
        _currentConversation.value = merged
        publishCatalogSubagentsLocked()
    }

    /**
     * The open session's subagents from its `subagentCatalog` projection (harness 0.1.7), with
     * each child's activity read off that child's own running state.
     *
     * Does nothing when the projection is absent, which is a 0.1.6 host: there [refreshSubagents]
     * reads `subagents/list` instead, and that answer must not be overwritten with an empty list.
     */
    private fun publishCatalogSubagentsLocked() {
        val rows = subagentEntriesFromCatalog(currentProjections[SUBAGENT_CATALOG_PROJECTION]?.value) {
            runningBySession[it] == true
        } ?: return
        if (_subagents.value != rows) _subagents.value = rows
    }

    private fun emitSessionsLocked() {
        val rows = sessionRows.values.map { row ->
            row.copy(pendingInteraction = pendingInteractionOf(
                pendingKinds[row.sessionId].orEmpty() + modernQuestionKinds[row.sessionId].orEmpty(),
            ))
        }
        _sessions.value = rows
    }

    private fun emitWorkspacesLocked() {
        val order = pendingWorkspaceOrder?.let { applySavedOrder(workspaceOrder, it) } ?: workspaceOrder
        val ordered = order.mapNotNull { workspaceRows[it] } +
            workspaceRows.values.filter { it.workspaceId !in order }
        _workspaces.value = ordered.map { row ->
            val pending = pendingSessionOrder[row.workspaceId] ?: return@map row
            row.copy(sessionIds = applySavedOrder(row.sessionIds, pending))
        }
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
                            agentPreset = listedAgentPresetLocked(item),
                            updatedAt = item.updatedAt,
                            pendingInteraction = null,
                            formatStatus = item.formatStatus,
                            subagent = subagentIdentityOf(
                                item.origin,
                                item.parentSessionId,
                                item.projections?.values?.get(SUBAGENT_IDENTITY_PROJECTION),
                            ),
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
        // A target the list read never saw — a search hit, or a session reference that raced the
        // `session-added` frame — has no row, and a child's creator is only in that row. Without it
        // the follow would fall back to the plain address this fix exists to avoid. One list read
        // closes that hole; an ordinary tap already has the row and skips the round trip.
        if (synchronized(lock) { sessionRows[sessionId] == null }) refreshSessions()
        val subagent = synchronized(lock) {
            // The list row is the only place the child's creator is known without reading the
            // child's own log, and the follow address is built before that read — so it has to be
            // settled here. A row whose `subagent` projection is absent still produces an identity
            // with mode `unknown`; the opening snapshot supplies the real mode for the composer.
            val resolved = openSubagentFor(sessionRows[sessionId])
            openSubagent = resolved
            _currentSubagent.value = resolved
            val same = currentId == sessionId
            currentId = sessionId
            _currentSessionId.value = sessionId
            currentEvents.clear()
            currentHasMore = false
            currentBlank = sessionRows[sessionId]?.blank ?: true
            currentProjections.clear()
            currentQueue = emptyList()
            liveAssistant.clear()
            // Opened again, even the same session: the follow snapshot replaces every projection,
            // so a switch started against the old ones has nothing left to be confirmed by.
            permissionSwitch.reset()
            if (!same) {
                _currentConversation.value = null
                _jobs.value = emptyList()
                _skills.value = emptyList()
                _models.value = null
                _subagents.value = emptyList()
                _subagentConversation.value = null
                _subagentMode.value = null
                _commands.value = emptyList()
            }
            resolved
        }
        startFollow(sessionId)
        startJobs(sessionId)
        // Everything past the follow stream furnishes the chrome around the transcript — the skill
        // and model pickers, the subagent list, the command catalog — and none of it is needed to
        // paint a single message. Run in series they stacked four round trips onto every session
        // tap, which is what made switching sessions feel like loading them. The follow stream is
        // already open by this point, so the transcript arrives while these are still in flight.
        //
        // `refreshSubagents` and `refreshCommands` read the open session from `currentId`, which
        // was set synchronously above, so they still target this session rather than a stale one.
        coroutineScope {
            // `skills/list` is an agent-scoped read that resolves the ordinary agent identity, and
            // a child's is owned by subagent routing; nothing in a child transcript needs it.
            if (subagent == null) launch { loadSkills(sessionId) }
            launch { loadModels(sessionId) }
            launch { refreshSubagents() }
            launch { refreshCommands() }
            // A child landing would be filtered on the next launch anyway (see InitialSession), so
            // remembering one here only displaces the session the app would otherwise return to.
            if (subagent == null) launch { rememberLastSession(sessionId) }
        }
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
     * The stream is opened with `assistantStream`, because since harness 0.1.3 that is the only
     * way to see a reply while it is written: the durable log holds one settlement per model
     * attempt and no deltas. The frames it adds are process-local presentation — never replayed,
     * never paged — and are folded after the durable window as a provisional message.
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
                        address = synchronized(lock) { openSessionAddressLocked(sessionId) },
                        maxMessages = HISTORY_PAGE_SIZE,
                        assistantStream = true,
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
                        is SessionFollowFrame.AssistantStream -> applyAssistantFrame(sessionId, frame)
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

    /**
     * The durable address naming the open session's log.
     *
     * Harness 0.2.2 validates it against the session header and refuses a plain session address for
     * a session whose `origin` is `subagent`; the child has to be named through its creator. The
     * subagent address has been the one protocol for child logs since 0.1.2, so older hosts read
     * the same request. Only the *open* session can be a child here: the subagent sheet builds its
     * own address from the catalog, which carries the real mode.
     */
    private fun openSessionAddressLocked(sessionId: String): SessionAddress =
        openSubagent
            ?.takeIf { currentId == sessionId }
            ?.let { sessionAddressFor(sessionId, it) }
            ?: SessionAddress.Session(sessionId = sessionId)

    /**
     * Mirror the open session's background jobs from `job/list` (harness 0.1.7).
     *
     * 0.1.7 took jobs off `session/control`; the stream answers with the complete set the session
     * can see on open and after every change. A 0.1.6 host refuses the endpoint, which only ends
     * this stream: its control-stream jobs frames still feed [jobs] through [applyJobs].
     */
    private fun startJobs(sessionId: String) {
        jobsJob?.cancel()
        val mux = connectionManager.generation?.mux ?: return
        val args = buildJsonObject {
            put("request", encodeToJsonElement(JobListRequest.serializer(), JobListRequest(sessionId)))
        }
        jobsJob = scope.launch {
            runCatching {
                mux.openStream("job/list", args).collect { item ->
                    val rows = jobRowsOf(item) ?: return@collect
                    _jobControls.value = true
                    applyJobs(sessionId, rows)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                log("job/list ended for $sessionId", failure)
            }
        }
    }

    /**
     * One job's retained output and then its settlement, from `job/follow` (harness 0.1.7).
     *
     * Cold: nothing is opened until the flow is collected, and cancelling the collector cancels
     * the stream. Frames this build cannot read are skipped.
     */
    fun followJob(jobId: String): kotlinx.coroutines.flow.Flow<JobFollowFrame> = kotlinx.coroutines.flow.flow {
        val sid = currentSessionId.value ?: return@flow
        val mux = connectionManager.generation?.mux ?: return@flow
        val args = buildJsonObject {
            put("request", encodeToJsonElement(JobFollowRequest.serializer(), JobFollowRequest(jobId = jobId, sessionId = sid)))
        }
        mux.openStream("job/follow", args).collect { item ->
            decodeOrNull(JobFollowFrameSerializer, item)?.let { emit(it) }
        }
    }

    /** Stop one background job of the open session. Failed when the harness refused. */
    suspend fun killJob(jobId: String, report: Boolean = true): ActionOutcome<Unit> {
        val sid = currentSessionId.value ?: return ActionOutcome.Failed()
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        return when (val r = api.jobKill(sid, jobId)) {
            is RpcResult.Ok -> ActionOutcome.Done(Unit)
            is RpcResult.Err -> when {
                // The row went away first; the next `job/list` frame shows that, so it is not a failure.
                r.error.code == "job/not-found" -> ActionOutcome.Done(Unit)
                else -> {
                    if (report) setConnectionError(r.error.message)
                    ActionOutcome.Failed(r.error.message)
                }
            }
        }
    }

    /** Install one complete opening window, replacing any previous one for this session. */
    private fun applyFollowSnapshot(sessionId: String, frame: SessionFollowFrame.Snapshot) {
        clearConnectionError()
        val envelopes = expandRecords(frame.records)
        val page = trimToUserPrompt(historyTail(envelopes))
        // `overDelivered` drives hasMore: the window held more than we are showing, so there is
        // more history to fetch either by trimming or by the tail window's own bound.
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
            // A reconnect mid-answer: the baseline carries the compact prefix this generation
            // missed, so the partial reply is on screen before the next live chunk arrives. A
            // host that predates the feature sends no baseline, and the preview simply waits for
            // the settlement.
            liveAssistant.seed(frame.assistantStream)
            rebuildCurrentLocked()
        }
    }

    /** One live event. */
    private fun applyFollowEntry(sessionId: String, record: SessionHistoryRecord) {
        for (envelope in expandRecords(listOf(record))) {
            handleSessionEvent(sessionId, envelope)
        }
    }

    /** One process-local assistant frame: the reply being written, a chunk at a time. */
    private fun applyAssistantFrame(sessionId: String, frame: SessionFollowFrame.AssistantStream) {
        val changed = synchronized(lock) {
            if (currentId != sessionId) return
            liveAssistant.accept(frame.frame) != AssistantLiveState.Change.NONE
        }
        if (changed) rebuildTicks.trySend(Unit)
    }

    /**
     * History records are plain events since harness 0.1.3, but a 0.1.2 host still packs runs of
     * consecutive assistant deltas into one record. Expanding those back into scalar events is
     * what keeps the journal's sequence numbers contiguous; see
     * [com.labteto.dshmobile.core.session.ChunkRows].
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
                address = synchronized(lock) { openSessionAddressLocked(sid) },
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
                    //
                    // Deliberately *not* anchored on a user prompt the way the opening window is:
                    // paging back asks for the run of events just before what is already loaded,
                    // and snapping to a prompt here would skip the tail of the previous turn —
                    // the reasoning and tool calls the reader scrolled up to see. The prompt
                    // anchor is a rule about where a cold open *starts*, not about what a
                    // backwards page contains.
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

    /**
     * Rename one session. [report] raises a refusal on the chat screen's banner; a caller drawn
     * over the chat, whose banner is hidden, passes false and says the failure itself.
     */
    suspend fun renameSession(sessionId: String, title: String, report: Boolean = true): ActionOutcome<Unit> {
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        return when (val r = api.sessionRename(SessionRenameRequest(sessionId, title))) {
            is RpcResult.Ok -> {
                setTitle(sessionId, r.value.title)
                ActionOutcome.Done(Unit)
            }
            is RpcResult.Err -> {
                if (report) setConnectionError(r.error.message)
                ActionOutcome.Failed(r.error.message)
            }
        }
    }

    /**
     * Fork one session, optionally cut at [atSeq]. Done carries the new session's id, so the caller
     * can offer to open it; the list is refreshed first so that id already has a row.
     */
    suspend fun forkSession(sessionId: String, atSeq: Long? = null, report: Boolean = true): ActionOutcome<String> {
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        return when (val r = api.sessionFork(SessionForkRequest(sessionId, atSeq?.toInt(), allowMigration = false))) {
            is RpcResult.Ok -> {
                refreshSessions()
                ActionOutcome.Done(r.value.sessionId)
            }
            is RpcResult.Err -> {
                val message = if (r.error.code == "session/migration-required") migrationRequiredMessage() else r.error.message
                if (report) setConnectionError(message)
                ActionOutcome.Failed(message)
            }
        }
    }

    /**
     * Archive one session.
     *
     * Harness 0.1.7 refuses a session that still has work running (its turn, a subagent, a job) as
     * `workspace/session-active`, naming the work, and that comes back as [ArchiveOutcome.Busy]
     * for the caller to confirm. Archiving again with [stopActivity] stops the work first.
     */
    suspend fun archiveSession(sessionId: String, stopActivity: Boolean = false, report: Boolean = true): ArchiveOutcome {
        val api = apiOrNull() ?: return ArchiveOutcome.Failed
        val request = WorkspaceArchiveSessionRequest(sessionId, stopActivity = stopActivity.takeIf { it })
        return when (val r = api.workspaceArchiveSession(request)) {
            is RpcResult.Ok -> {
                setArchived(r.value.archivedSessionIds)
                refreshSessions()
                ArchiveOutcome.Archived
            }
            is RpcResult.Err -> {
                if (r.error.code == SESSION_ACTIVE && !stopActivity) {
                    val details = runCatching {
                        decodeFromJsonElement(WorkspaceSessionActiveDetails.serializer(), r.error.details)
                    }.getOrNull()
                    ArchiveOutcome.Busy(sessionId, details?.activity.orEmpty())
                } else {
                    if (report) setConnectionError(r.error.message)
                    ArchiveOutcome.Failed
                }
            }
        }
    }

    /** Pin or unpin one session (harness 0.1.7); the answer is the complete pin set. */
    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        val api = apiOrNull() ?: return
        val result = if (pinned) api.workspacePinSession(sessionId) else api.workspaceUnpinSession(sessionId)
        when (result) {
            is RpcResult.Ok -> _pinnedSessionIds.value = result.value.pinnedSessionIds
            is RpcResult.Err -> setConnectionError(result.error.message)
        }
    }

    suspend fun prompt(text: String, mode: String, targetSessionId: String? = currentSessionId.value, targetHost: String? = activeHostKey) =
        promptContent(mode, listOf(PromptContentPart.Text(text)), targetSessionId, targetHost)

    /**
     * Prompt with attachments: raster images (bytes submitted base64, as the browser wire does)
     * and files already staged through [uploadFile], cited by receipt.
     *
     * All of them ride *one* call. `session/prompt` takes a list of content parts and the host
     * admits that list as a single batch, which is where its per-message image count and
     * aggregate-size limits live — sending one image per call, as this client used to, split one
     * message into several and meant those two limits could never fire at all. A file's bytes
     * never ride the prompt: the receipt names an upload the host already holds, and the host
     * refuses one it did not mint for this session.
     */
    suspend fun promptWithAttachments(
        text: String,
        mode: String,
        images: List<EncodedImageAttachment>,
        fileReceipts: List<String> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): PromptOutcome {
        val parts = mutableListOf<PromptContentPart>()
        if (text.isNotBlank()) parts.add(PromptContentPart.Text(text))
        images.mapTo(parts) { PromptContentPart.Image(it.mediaType, it.data, it.name) }
        fileReceipts.mapTo(parts) { PromptContentPart.File(it) }
        return promptContent(mode, parts, targetSessionId, targetHost)
    }

    /**
     * Stage one file for the open session and answer with its receipt.
     *
     * The bytes are streamed to the raw-byte route the way the web client does it, so a large
     * file never sits in memory as base64. A deployment or relay that does not serve that route
     * answers 404, which is a missing capability rather than a broken link; a file that fits
     * comfortably in an RPC body is then retried through the `fileUploads/upload` Remote, which
     * every 0.1.3 host composes beside the route. [open] is called once per attempt and must
     * answer a fresh stream positioned at the first byte.
     *
     * A refusal is the composer's problem, not the connection's, so nothing here raises the
     * connection banner: the chip that owns the file shows the failure and offers a retry.
     */
    suspend fun uploadFile(
        name: String,
        size: Long,
        open: () -> InputStream?,
        onProgress: (sent: Long) -> Unit = {},
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
    ): RpcResult<FileUploadValue> = withContext(Dispatchers.IO) {
        val sid = targetSessionId
            ?: return@withContext RpcResult.Err(RpcError("internal", "no open session"))
        val api = apiForHost(targetHost)
            ?: return@withContext RpcResult.Err(RpcError("internal", "not connected"))
        val stream = open()
            ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))
        val streamed = stream.use { api.uploadFileBinary(sid, name, size, it, onProgress) }
        if (streamed !is RpcResult.Err || streamed.error.code != "capability-unavailable") return@withContext streamed
        if (size !in 0..MAX_ENCODED_UPLOAD_BYTES) return@withContext streamed
        log("upload route unavailable; falling back to fileUploads/upload for ${size}B")
        val bytes = open()?.use { it.readBytes() }
            ?: return@withContext RpcResult.Err(RpcError("internal", "could not read the file"))
        api.fileUploadEncoded(
            sid,
            EncodedFileUploadRequest(data = Base64.encodeToString(bytes, Base64.NO_WRAP), name = name),
        ).also { onProgress(bytes.size.toLong()) }
    }

    private suspend fun promptContent(mode: String, content: List<PromptContentPart>, targetSessionId: String?, targetHost: String?): PromptOutcome {
        val sid = targetSessionId ?: return PromptOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return PromptOutcome.Failed("not connected")
        val safeMode = if (mode == "steer") "steer" else "queue"
        val zone = TimeZone.getDefault().id
        // `session/prompt` resolves the ordinary agent behind the session, and a child's is owned
        // by subagent routing — the host refuses it outright. Delivery goes through
        // `subagents/prompt`, whose request still names the creator and the exact mode.
        val target = promptTargetFor(
            synchronized(lock) {
                if (currentId == sid) openSubagent else sessionRows[sid]?.subagent
            },
        )
        when (target) {
            is PromptTarget.Ordinary -> Unit
            is PromptTarget.ReadOnly -> return PromptOutcome.Failed(subagentReadOnlyMessage())
            is PromptTarget.Child -> {
                // `subagents/prompt` has no file part at all — the delivery endpoint's content
                // union is text | image. Pre-empting that here is what the web client does too, and
                // it keeps the composer's staged files (a Rejected outcome restores them).
                if (content.any { it is PromptContentPart.File }) {
                    return PromptOutcome.Rejected(
                        ImageRejection.SUBAGENT_FILE_UNSUPPORTED,
                        "SUBAGENT_FILE_UNSUPPORTED",
                    )
                }
                val request = SubagentPromptRequest(
                    requestId = newPromptRequestId(),
                    delivery = safeMode,
                    parentSessionId = target.identity.parentSessionId,
                    childSessionId = sid,
                    mode = SUBAGENT_CONTINUABLE_MODE,
                    content = content,
                    clientTimeZone = zone,
                )
                return when (val r = api.subagentPrompt(request)) {
                    is RpcResult.Ok -> PromptOutcome.Ok
                    is RpcResult.Err -> when (r.error.code) {
                        // The host refused the image (a model without image input, a bad media
                        // type): the composer's problem, and it keeps the photo staged.
                        ATTACHMENT_INVALID, SUBAGENT_ATTACHMENT_INVALID -> {
                            val reason = (r.error.details as? JsonObject)
                                ?.get("reason")?.jsonPrimitive?.contentOrNull
                            PromptOutcome.Rejected(imageRejectionOf(reason.orEmpty()), reason)
                        }
                        else -> {
                            // The exact parent Agent not being live is the child's state, not a
                            // connection fault: report it in the composer instead of the banner.
                            if (r.error.code != SUBAGENT_PARENT_UNAVAILABLE) setConnectionError(r.error.message)
                            PromptOutcome.Failed(r.error.message)
                        }
                    }
                }
            }
        }
        val request = SessionPromptRequest(
            requestId = newPromptRequestId(),
            sessionId = sid,
            mode = safeMode,
            content = content,
            clientTimeZone = zone,
        )
        return when (val r = api.sessionPrompt(request)) {
            is RpcResult.Ok -> PromptOutcome.Ok
            is RpcResult.Err -> if (r.error.code == ATTACHMENT_INVALID) {
                // The host declined the attachments, not the connection. Report it where they are
                // so the composer can keep them and say which bound they crossed.
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
        // Stop on a child goes through `subagents/interruptByParent`, not `session/cancel`: the
        // latter resolves the ordinary agent behind the session and is refused for subagent
        // routing's identity. A child this client cannot continue has nothing to stop.
        val subagent = synchronized(lock) { if (currentId == sid) openSubagent else sessionRows[sid]?.subagent }
        if (subagent != null) {
            if (!subagent.continuable) return
            when (val r = api.subagentInterrupt(childSessionId = sid, parentSessionId = subagent.parentSessionId)) {
                is RpcResult.Ok -> Unit
                is RpcResult.Err -> setConnectionError(r.error.message)
            }
            return
        }
        when (val r = api.sessionCancel(SessionCancelRequest(sid))) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> setConnectionError(r.error.message)
        }
    }

    suspend fun updateQueue(itemId: String, action: String, contentText: String? = null, sessionId: String? = currentSessionId.value): Boolean =
        updateQueueOutcome(itemId, action, contentText, sessionId).succeeded

    /** [updateQueue] with the reason: a caller whose banner is hidden passes `report = false` and says it itself. */
    suspend fun updateQueueOutcome(
        itemId: String,
        action: String,
        contentText: String? = null,
        sessionId: String? = currentSessionId.value,
        report: Boolean = true,
    ): ActionOutcome<Unit> {
        if (action == "edit" && contentText.isNullOrBlank()) return ActionOutcome.Failed()
        val sid = sessionId ?: return ActionOutcome.Failed()
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        val queueAction: QueueAction = when (action) {
            "remove" -> QueueAction.Remove()
            "steer" -> QueueAction.Steer()
            else -> QueueAction.Edit(listOf(ContentBlock.Text(contentText.orEmpty())))
        }
        return when (val r = api.sessionUpdateQueue(SessionUpdateQueueRequest(sid, itemId, queueAction))) {
            is RpcResult.Ok -> ActionOutcome.Done(Unit)
            is RpcResult.Err -> {
                if (report) setConnectionError(r.error.message)
                ActionOutcome.Failed(r.error.message)
            }
        }
    }

    /**
     * Allows or refuses one pending approval.
     *
     * Reports its verdict in the same vocabulary a question answer does, and for the same reason:
     * an approval that the host would not take leaves the tool call behind it blocked, and a panel
     * that swallowed the refusal would sit there looking like a button that does nothing. A taken
     * one takes the panel with it — see [answerOutcome] for why no frame does that here.
     */
    suspend fun respondApproval(sessionId: String, approvalId: String, allow: Boolean): QuestionOutcome {
        val api = apiOrNull() ?: return QuestionOutcome.Unsent
        val request = synchronized(lock) { approvalRequests[approvalId] }
        if (request == null) {
            log("no pending approval for id $approvalId")
            // Nothing to answer with, so nothing can arrive to take the panel away either.
            if (_pendingApproval.value?.approvalId == approvalId) _pendingApproval.value = null
            return QuestionOutcome.Refused(NOT_PENDING)
        }
        val clientId = connectionManager.generation?.clientId
        if (clientId == null) {
            log("cannot answer approval $approvalId: no connection generation")
            return QuestionOutcome.Unsent
        }
        val outcome = if (allow) ApprovalOutcome.ALLOWED_ONCE else ApprovalOutcome.REJECTED
        // The waterfall's own return value *is* the outcome string, so this claims the request
        // with a bare value rather than the object 0.1.1 posted to /api/respond.
        val result = api.answerEvent(
            clientId = clientId,
            eventId = request.eventId,
            outcome = RemoteEventOutcome.Result(value = JsonPrimitive(outcome)),
        )
        return answerOutcome(result, "approval response", sessionId) { forgetRequest(request.eventId) }
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
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
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
        ) { forgetQuestions(sessionId, eventId) }
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
        val eventId = pendingQuestionEvent(sessionId) ?: return abandonQuestions(sessionId)
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
        ) { forgetQuestions(sessionId, eventId) }
    }

    private fun pendingQuestionEvent(sessionId: String): String? {
        val eventId = synchronized(lock) { questionEvents.eventFor(sessionId) }
        if (eventId == null) log("no pending question for session $sessionId")
        return eventId
    }

    /**
     * There is a card on screen for [sessionId] but no event left to address it to.
     *
     * A card in that state can never be answered — every path through here needs the `eventId` the
     * waterfall arrived with — so it is a corpse, and leaving it up would be the same dead end by a
     * shorter route. Reported as [NOT_PENDING] all the same: the wait, wherever it went, is not
     * this client's to settle any more.
     */
    private fun abandonQuestions(sessionId: String): QuestionOutcome {
        // Null, not an event: the corpse is defined by having no registration, and a request that
        // arrived in the meantime has one and must be left alone.
        forgetQuestions(sessionId, null)
        return QuestionOutcome.Refused(NOT_PENDING)
    }

    /**
     * Map one `$events/result` answer onto the store's outcome vocabulary, and run [forget] when
     * that answer ended the request behind it.
     *
     * [forget] is the card's only exit on this client, and it is a caller's lambda rather than an
     * `eventId` because the two kinds are held differently — an approval by its event alone, a
     * question by its session *and* its event, so that an answer cannot take away the card of the
     * request that replaced the one it answered. The web client has no equivalent because it never needs
     * one: its `PendingQuestion.answer()` resolves the waiting promise in the same process, so the
     * card's life ends with the call. Here the answer is a POST, and the host settles it by
     * *removing this client's delivery first* and then pushing `cancel` to the deliveries that
     * remain — so the client that acted is the only one the resolution is never announced to.
     * Waiting for a frame that cannot arrive is what left an answered card frozen on "Submitting…"
     * with no way out but a force-stop.
     *
     * A failure here is not retried: upstream fails the whole connection generation on it and
     * replays the pending request on the next one, so a retry would answer the same question
     * twice. Nor is a failing card taken away — see [settlesRequest].
     */
    private fun answerOutcome(
        result: RpcResult<JsonElement>,
        what: String,
        sessionId: String,
        forget: () -> Unit,
    ): QuestionOutcome {
        val outcome = when (result) {
            is RpcResult.Ok -> QuestionOutcome.Accepted
            is RpcResult.Err -> {
                log("$what failed for $sessionId: ${result.error.code}: ${result.error.message}")
                // The split is "did the host answer at all", not a list of codes. A carrier failure
                // carries a [TransportFailure] marker and nothing is known about the wait; anything
                // else reached the host and came back `ok:false`, so the refusal is reported with
                // the host's own code. Folding those into [QuestionOutcome.Unsent] is what made a
                // malformed envelope read as "could not reach the harness" and sent reporters to
                // debug their network for a protocol fault.
                if (TransportFailures.of(result.error) != null) {
                    QuestionOutcome.Unsent
                } else {
                    QuestionOutcome.Refused(result.error.code)
                }
            }
        }
        if (settlesRequest(outcome)) forget()
        return outcome
    }


    /**
     * Select a model, and optionally its reasoning effort. The picker sits in a sheet over the
     * chat, so it passes `report = false` and says a refusal inside the sheet itself.
     */
    suspend fun selectModel(
        provider: String,
        model: String,
        reasoningEffort: String? = null,
        report: Boolean = true,
    ): ActionOutcome<Unit> {
        val sid = currentSessionId.value ?: return ActionOutcome.Failed()
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        val request = SessionSelectModelRequest(sid, provider, model, reasoningEffort)
        return when (val r = api.sessionSelectModel(request)) {
            is RpcResult.Ok -> {
                loadModels(sid)
                ActionOutcome.Done(Unit)
            }
            is RpcResult.Err -> {
                if (report) setConnectionError(r.error.message)
                ActionOutcome.Failed(r.error.message)
            }
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

    /**
     * Read one durable image.
     *
     * Sends the whole reference (the 0.2.2 shape). A host that predates that shape rejects the
     * request as invalid input rather than as an attachment error, so that specific refusal is
     * retried once in the legacy id-only shape. Anything else — a missing session, a reference the
     * session log does not contain — is a real failure and surfaced as it came.
     */
    suspend fun fetchAttachment(
        attachment: ImageAttachmentRef,
        sessionId: String? = currentSessionId.value,
        host: String? = activeHostKey,
    ): ByteArray? {
        val sid = sessionId ?: return null
        val api = host?.let(::apiForHost) ?: return null
        val first = api.sessionAttachment(SessionAttachmentRequest(sid, attachment))
        val result = when {
            first is RpcResult.Ok -> first
            (first as RpcResult.Err).error.code.isArgumentShapeRefusal() ->
                api.sessionAttachmentLegacy(LegacySessionAttachmentRequest(sid, attachment.attachmentId))
            else -> first
        }
        return when (result) {
            is RpcResult.Ok -> runCatching { Base64.decode(result.value.data, Base64.DEFAULT) }.getOrNull()
            is RpcResult.Err -> {
                setConnectionError(result.error.message)
                null
            }
        }
    }

    /** Input-shape refusals: the host understood the call but not this request revision. */
    private fun String.isArgumentShapeRefusal(): Boolean = this == "gateway/input-invalid" ||
        this == "gateway/arguments-invalid"

    suspend fun listSkills() {
        val sid = currentSessionId.value ?: return
        loadSkills(sid)
    }

    /**
     * Bring the open session's subagent list up to date.
     *
     * From harness 0.1.7 the list is the session's `subagentCatalog` projection, which arrives with
     * the rest of its projections and is republished on every change; there is nothing to fetch.
     * A 0.1.6 host publishes no such projection and answers `subagents/list` instead. On 0.1.7 that
     * call is a 404, which only means the snapshot carrying the catalog has not landed yet.
     */
    suspend fun refreshSubagents() {
        val sid = currentSessionId.value ?: return
        val hasCatalog = synchronized(lock) {
            currentId == sid && currentProjections.containsKey(SUBAGENT_CATALOG_PROJECTION)
        }
        if (hasCatalog) {
            synchronized(lock) { publishCatalogSubagentsLocked() }
            return
        }
        val api = apiOrNull() ?: return
        when (val r = api.subagentList(sid)) {
            is RpcResult.Ok -> synchronized(lock) {
                if (currentId == sid && !currentProjections.containsKey(SUBAGENT_CATALOG_PROJECTION)) {
                    _subagents.value = r.value.entries
                }
            }
            is RpcResult.Err -> if (r.error.code != CAPABILITY_UNAVAILABLE) setConnectionError(r.error.message)
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

    suspend fun promptSubagent(childSessionId: String, text: String, delivery: String = "queue"): Boolean {
        val sid = currentSessionId.value ?: return false
        val api = apiOrNull() ?: return false
        val zone = TimeZone.getDefault().id
        val request = SubagentPromptRequest(
            requestId = newPromptRequestId(),
            parentSessionId = sid,
            childSessionId = childSessionId,
            mode = "continuable",
            delivery = delivery,
            content = listOf(PromptContentPart.Text(text)),
            clientTimeZone = zone,
        )
        return when (val r = api.subagentPrompt(request)) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> { setConnectionError(r.error.message); false }
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
        childFollowJob?.cancel()
        _subagentConversation.value = null
        val host = activeHostKey ?: return
        val mux = muxForHost(host) ?: return
        val request = SessionFollowRequest(
            address = SessionAddress.Subagent(parentSessionId = sid, childSessionId = childSessionId, mode = mode),
            maxMessages = HISTORY_PAGE_SIZE, assistantStream = true,
        )
        childFollowJob = scope.launch {
            val events = mutableListOf<SessionEventEnvelope>()
            val live = AssistantLiveState()
            var hasMore = false
            try {
                mux.openStream("session/follow", buildJsonObject {
                    put("request", encodeToJsonElement(SessionFollowRequest.serializer(), request))
                }).collect { item ->
                    if (activeHostKey != host || currentSessionId.value != sid) return@collect
                    when (val frame = decodeOrNull(SessionFollowFrameSerializer, item)) {
                        is SessionFollowFrame.Snapshot -> {
                            events.clear()
                            events.addAll(expandRecords(frame.records))
                            live.seed(frame.assistantStream)
                            hasMore = frame.hasMore
                        }
                        is SessionFollowFrame.Entry -> expandRecords(listOf(frame.record)).forEach { event ->
                            if (events.none { it.seq == event.seq }) events.add(event)
                            val data = event.data as? JsonObject
                            live.acceptDurable(event.type, data?.get("turn")?.jsonPrimitive?.intOrNull,
                                data?.get("step")?.jsonPrimitive?.intOrNull, event.seq, event.surfaceOp)
                        }
                        is SessionFollowFrame.AssistantStream -> live.accept(frame.frame)
                        null -> Unit
                    }
                    _subagentConversation.value = EventFold(childSessionId).fold(events.sortedBy { it.seq }, live.transientEnvelopes()).copy(hasMore = hasMore)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { setConnectionError(failure.message) }
        }
    }

    fun closeSubagentTranscript() { childFollowJob?.cancel(); childFollowJob = null }

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

    /**
     * Move [sessionId] to sit in front of [beforeSessionId] (null: last) within its own workspace.
     *
     * The list changes at once: the new order is laid over the host's, and then sent. When the host
     * confirms, its answer replaces the overlay; when it refuses, the overlay is dropped, which puts
     * every row back where the host has it, and the caller gets [MoveOutcome.Failed] to say so. The
     * harness can only reorder sessions inside one workspace, so the anchor must be a session of the
     * same one.
     *
     * Moves are sent one at a time in the order they were made. If one fails, those queued behind it
     * were made against an order that never happened, so they stand down ([MoveOutcome.Superseded])
     * rather than being sent. The work runs on the store's own scope: a drawer that closes mid-move
     * must not strand the overlay by cancelling the request that would clear it.
     */
    suspend fun moveSession(workspaceId: String, sessionId: String, beforeSessionId: String?): MoveOutcome {
        val api = apiOrNull() ?: return MoveOutcome.Failed(null)
        val epoch: Int
        synchronized(lock) {
            val row = workspaceRows[workspaceId] ?: return MoveOutcome.Failed(null)
            val base = pendingSessionOrder[workspaceId] ?: row.sessionIds
            val next = moveBefore(base, sessionId, beforeSessionId)
            if (next == base) return MoveOutcome.Unchanged
            pendingSessionOrder[workspaceId] = next
            movesInFlight.merge(workspaceId, 1, Int::plus)
            epoch = moveEpoch
            emitWorkspacesLocked()
        }
        return scope.async {
            moveMutex.withLock {
                if (epoch != moveEpoch) return@withLock MoveOutcome.Superseded
                when (val r = api.workspaceInsertSessionBefore(WorkspaceInsertSessionBeforeRequest(workspaceId, sessionId, beforeSessionId))) {
                    is RpcResult.Ok -> synchronized(lock) {
                        workspaceRows[workspaceId] = r.value.workspace.toRow()
                        settleMoveLocked(workspaceId)
                        MoveOutcome.Moved
                    }
                    is RpcResult.Err -> failMove(r.error.message)
                }
            }
        }.await()
    }

    /**
     * Move a whole workspace to sit in front of [beforeWorkspaceId] (null: last) in the registry
     * order. Applied, sent and rolled back exactly like [moveSession]; the host answers with the
     * complete order, which replaces ours.
     */
    suspend fun moveWorkspace(workspaceId: String, beforeWorkspaceId: String?): MoveOutcome {
        val api = apiOrNull() ?: return MoveOutcome.Failed(null)
        val epoch: Int
        synchronized(lock) {
            val base = pendingWorkspaceOrder ?: _workspaces.value.map { it.workspaceId }
            val next = moveBefore(base, workspaceId, beforeWorkspaceId)
            if (next == base) return MoveOutcome.Unchanged
            pendingWorkspaceOrder = next
            movesInFlight.merge(WORKSPACE_ORDER_KEY, 1, Int::plus)
            epoch = moveEpoch
            emitWorkspacesLocked()
        }
        return scope.async {
            moveMutex.withLock {
                if (epoch != moveEpoch) return@withLock MoveOutcome.Superseded
                when (val r = api.workspaceInsertBefore(WorkspaceInsertBeforeRequest(workspaceId, beforeWorkspaceId))) {
                    is RpcResult.Ok -> synchronized(lock) {
                        workspaceOrder.clear()
                        workspaceOrder.addAll(r.value.workspaceIds)
                        settleMoveLocked(WORKSPACE_ORDER_KEY)
                        MoveOutcome.Moved
                    }
                    is RpcResult.Err -> failMove(r.error.message)
                }
            }
        }.await()
    }

    /** One move of [key] is over and the host answered: once none are left, the host's order is the truth. */
    private fun settleMoveLocked(key: String) {
        val left = (movesInFlight[key] ?: 1) - 1
        if (left > 0) {
            movesInFlight[key] = left
        } else {
            movesInFlight.remove(key)
            if (key == WORKSPACE_ORDER_KEY) pendingWorkspaceOrder = null else pendingSessionOrder.remove(key)
        }
        emitWorkspacesLocked()
    }

    /** A move was refused: drop every overlay (the rollback) and tell the queue behind it to stand down. */
    private fun failMove(reason: String?): MoveOutcome = synchronized(lock) {
        moveEpoch++
        pendingSessionOrder.clear()
        pendingWorkspaceOrder = null
        movesInFlight.clear()
        emitWorkspacesLocked()
        MoveOutcome.Failed(reason)
    }

    suspend fun goalAction(action: String, objective: String? = null, report: Boolean = true): ActionOutcome<Unit> {
        val sid = currentSessionId.value ?: return ActionOutcome.Failed()
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        fun <T> settle(result: RpcResult<T>): ActionOutcome<Unit> = when (result) {
            is RpcResult.Ok -> ActionOutcome.Done(Unit)
            is RpcResult.Err -> {
                if (report) setConnectionError(result.error.message)
                ActionOutcome.Failed(result.error.message)
            }
        }
        return when (action) {
            "create" -> {
                val obj = objective
                if (obj.isNullOrBlank()) {
                    log("goal create requires an objective")
                    ActionOutcome.Failed()
                } else {
                    settle(api.goalCreate(sid, buildJsonObject { put("objective", JsonPrimitive(obj)) }))
                }
            }
            "edit", "pause", "resume", "complete", "clear" -> {
                val ref = synchronized(lock) { goalRefFromProjectionLocked() }
                if (ref == null) {
                    log("goal $action requires a current goal (no goal projection)")
                    val message = noCurrentGoalMessage()
                    if (report) setConnectionError(message)
                    ActionOutcome.Failed(message)
                } else {
                    when (action) {
                        "edit" -> settle(
                            api.goalEdit(
                                sid,
                                ref,
                                buildJsonObject {
                                    if (objective != null) put("objective", JsonPrimitive(objective))
                                },
                            ),
                        )
                        "pause" -> settle(api.goalPause(sid, ref))
                        "resume" -> settle(api.goalResume(sid, ref))
                        "complete" -> settle(api.goalComplete(sid, ref))
                        else -> settle(api.goalClear(sid, ref))
                    }
                }
            }
            else -> {
                log("unknown goal action $action")
                ActionOutcome.Failed()
            }
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
     * [attachments] must be empty unless the command's descriptor declares it takes them — see
     * `CommandDescriptor.acceptsAttachments`. A host that admits them but whose handler will not
     * use them (`/plan off`, `/goal pause`) answers with an ordinary error result, which is the
     * harness's own division of labour and not worth mirroring here.
     */
    suspend fun runCommand(
        line: String,
        attachments: List<CommandSubmitAttachment> = emptyList(),
        targetSessionId: String? = currentSessionId.value,
        targetHost: String? = activeHostKey,
        report: Boolean = true,
    ): CommandOutcome {
        val sid = targetSessionId ?: return CommandOutcome.Failed("no open session")
        val api = apiForHost(targetHost) ?: return CommandOutcome.Failed("not connected")
        return when (val r = api.commandsExecute(sid, line, attachments)) {
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
                // The attachments were refused, by the host or by the client's own guard. A
                // composer problem, so it must not raise the connection banner.
                ATTACHMENT_INVALID -> CommandOutcome.Failed(r.error.message)
                // No command gateway in this build (404) or the trust fence refused it (403).
                // Neither is a connection fault, so the menu retires rather than the session.
                "capability-unavailable", "forbidden" -> {
                    _commandsAvailable.value = false
                    _commands.value = emptyList()
                    log("commands/execute unavailable (${r.error.code}): ${r.error.message}")
                    CommandOutcome.Failed(r.error.message)
                }
                else -> {
                    if (report) setConnectionError(r.error.message)
                    CommandOutcome.Failed(r.error.message)
                }
            }
        }
    }

    /**
     * Switch the session's permission preset. The read side is the `permissions` projection, so
     * there is nothing to refresh — the harness pushes the new value back on a projection frame.
     *
     * Returns once the switch has ended, whichever way it ended (see [PermissionSwitchResult]).
     * The command is bound to the session and host the pick was made in, and runs on the store's
     * own scope: a caller that leaves composition while it is in flight stops waiting, and the
     * pending state still ends.
     */
    suspend fun setPermissionPreset(value: String): PermissionSwitchResult {
        if (value == CUSTOM_PRESET) {
            return PermissionSwitchResult.Failed(
                CommandOutcome.Failed("`$CUSTOM_PRESET` is a derived state, not a preset"),
            )
        }
        val sid = currentSessionId.value
        val host = activeHostKey
        return permissionSwitch.switchTo(value, permissions.value?.currentValue) {
            runCommand("/permission $value", targetSessionId = sid, targetHost = host)
        }
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
    suspend fun exportSessionTo(sink: OutputStream, includeDescendants: Boolean = false, report: Boolean = true): ActionOutcome<Unit> {
        val sid = currentSessionId.value ?: return ActionOutcome.Failed()
        val api = apiOrNull() ?: return ActionOutcome.Failed()
        val result = api.sessionExport(sid, includeDescendants) { _, _, body -> body.copyTo(sink) }
        return when (result) {
            is RpcResult.Ok -> ActionOutcome.Done(Unit)
            is RpcResult.Err -> {
                if (report) setConnectionError(result.error.message)
                ActionOutcome.Failed(result.error.message)
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
        return goalRefFromProjection(currentProjections["goal"]?.value)
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
    /**
     * Whether one envelope is a genuine user prompt rather than harness-injected context.
     *
     * The harness tags a real prompt with `source.kind` of `user`; everything else
     * (`agent-instructions`, `skill-invocation`, `goal`, …) is context. Deliberately stricter than
     * `UserMessageNode.isInjectedContext`: this decides where the transcript *starts*, and an
     * untagged message must not be mistaken for the reader's own last turn — so unlike the node
     * predicate, an absent tag is a "no" here rather than a "yes".
     *
     * `user-rpc` is not a `source.kind` value — it is a key in the harness's `MessageSourceMap`
     * whose variant carries `kind: 'user'` itself, so `"user"` covers both paths.
     */
    private fun isRealUserPrompt(e: SessionEventEnvelope): Boolean =
        e.type == "user/message" &&
            ((e.data as? JsonObject)?.get("source") as? JsonObject)
                ?.get("kind")?.jsonPrimitive?.contentOrNull == "user"

    /**
     * Drop everything strictly before the oldest real user prompt in [envelopes].
     *
     * The harness opens a window of a fixed number of *events*, and a tool-heavy turn is mostly
     * events — so a session opened cold used to start mid-tool-call-loop, with the message that
     * prompted the work somewhere above the fold. Anchoring on the reader's last prompt instead
     * makes the first screen read as a conversation. No prompt in the window means no anchor, and
     * the list is returned unchanged rather than emptied.
     */
    private fun trimToUserPrompt(envelopes: List<SessionEventEnvelope>): List<SessionEventEnvelope> {
        val sorted = envelopes.sortedBy { it.seq }
        val index = sorted.indexOfFirst { isRealUserPrompt(it) }
        return if (index < 0) sorted else sorted.subList(index, sorted.size)
    }

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
        is SubagentListEntry.ChildExternal -> entry.id
        is SubagentListEntry.ChildOneShot -> entry.id
        is SubagentListEntry.ChildContinuable -> entry.id
        is SubagentListEntry.Diagnostic -> entry.id
        is UnknownSubagentListEntry -> null
    }

    /**
     * Whether this connection's harness carries attachments on a slash command.
     *
     * Always true from harness 0.1.2: `commands/execute` declares the parameter unconditionally,
     * and the shape-derived capability check this used to perform depended on `host.describe`,
     * which no longer exists. Kept as a property so the composer's adjudication has one place to
     * consult if a future release makes it conditional again.
     */
    val commandAttachmentsSupported: Boolean get() = connectionManager.connectedApi != null

    suspend fun refreshPermissionCatalog() {
        val epoch = ++permissionCatalogEpoch
        val key = activeHostKey
        val api = apiForHost(key) ?: return
        // Not cleared before the read. Blanking first made the permission chip lose its options for
        // the length of a round trip on every baseline, which reads as the chip breaking rather
        // than as a refresh. The epoch and host guards below already stop a slow answer from
        // overwriting a newer host's catalog, which is what the clear was standing in for.
        val result = api.permissionCatalog()
        if (epoch == permissionCatalogEpoch && key == activeHostKey) {
            permissionCatalog.value = (result as? RpcResult.Ok)?.value
        }
    }

    suspend fun unarchiveSession(sessionId: String): Boolean {
        val key = activeHostKey
        val result = apiForHost(key)?.workspaceUnarchiveSession(sessionId) ?: return false
        if (key != activeHostKey) return false
        return when (result) {
            is RpcResult.Ok -> { setArchived(result.value.archivedSessionIds); refreshSessions(); true }
            is RpcResult.Err -> { setConnectionError(result.error.message); false }
        }
    }

    private fun apiOrNull(): DshApiClient? {
        val api = connectionManager.connectedApi
        if (api == null) log("not connected: ignoring request")
        return api
    }

    private fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "SessionStore"

        /**
         * The host's refusal of a prompt's or command's attachments (harness 0.1.3; it was
         * `attachment-error` through 0.1.2). Every business code is namespaced now.
         */
        const val ATTACHMENT_INVALID = "session/attachment-invalid"

        /** Harness 0.1.7's refusal to archive a session with running work; see [archiveSession]. */
        const val SESSION_ACTIVE = "workspace/session-active"

        /** [movesInFlight]'s key for a reorder of the workspaces themselves rather than of one's sessions. */
        private const val WORKSPACE_ORDER_KEY = "\u0000workspaces"

        /** Minted by the client from a 404: no route claimed the endpoint. */
        const val CAPABILITY_UNAVAILABLE = "capability-unavailable"

        /** Largest file the base64 Remote fallback will carry; anything bigger needs the route. */
        const val MAX_ENCODED_UPLOAD_BYTES = 20L * 1024 * 1024
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
