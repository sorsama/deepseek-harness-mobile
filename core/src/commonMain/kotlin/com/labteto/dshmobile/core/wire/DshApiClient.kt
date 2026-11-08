package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.AgentPresetDocument
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.DirectoryListing
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.FileUploadValue
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.GoalView
import com.labteto.dshmobile.core.wire.dto.HostCreateDirectoryValue
import com.labteto.dshmobile.core.wire.dto.HostOpenPathValue
import com.labteto.dshmobile.core.wire.dto.HostPickDirectoryValue
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.LlmProviderInfo
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.REMOTE_EVENT_RESULT_ENDPOINT
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.RemoteEventResult
import com.labteto.dshmobile.core.wire.dto.LegacySessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentValue
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelValue
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionCreateValue
import com.labteto.dshmobile.core.wire.dto.SessionFollowRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkValue
import com.labteto.dshmobile.core.wire.dto.SessionListRequest
import com.labteto.dshmobile.core.wire.dto.SessionListValue
import com.labteto.dshmobile.core.wire.dto.SessionPage
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionPromptValue
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameValue
import com.labteto.dshmobile.core.wire.dto.SessionSearchRequest
import com.labteto.dshmobile.core.wire.dto.SessionSearchValue
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelValue
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueRequest
import com.labteto.dshmobile.core.wire.dto.SessionUpdateQueueValue
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SkillListValue
import com.labteto.dshmobile.core.wire.dto.SubagentCatalog
import com.labteto.dshmobile.core.wire.dto.SubagentInterruptValue
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.SubagentPromptValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertSessionBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceOrderValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceValue
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/** The gateway's refusal of an argument object that does not match the host method's parameters. */
private const val ARGUMENTS_INVALID = "gateway/arguments-invalid"

/** The mux has no HTTP 404; the gateway reports an absent/withdrawn export in its envelope. */
fun RpcError.classifyCapability(): RpcError = when (code) {
    "gateway/invocation-unavailable", "gateway/definition-unavailable" -> copy(code = "capability-unavailable")
    else -> this
}

/** The streaming file-upload route (`packages/client/file-upload/src/protocol.ts`). */
const val FILE_UPLOAD_PATH: String = "/api/session/uploadFileBinary"

/**
 * Typed client for the harness unary wire protocol (v0.1.3-alpha.1).
 *
 * Every call is one `POST /api/<namespace>/<method>` carrying the unchanged RPC envelope with a
 * `{"args": {…}}` payload, and returns [RpcResult]: business failures arrive as HTTP 200 +
 * `ok: false` and come back as [RpcResult.Err]; carrier failures (non-2xx, transport, or decode)
 * are folded into `RpcResult.Err` with code `internal`.
 *
 * The envelope is the only thing 0.1.2 kept. Harness 0.1.2 deleted the API Proxy and moved every
 * operation onto the business service that owns it, so the flat `domain.method` vocabulary this
 * client used through 0.1.1 is gone; each method below names its owning Remote namespace.
 *
 * Two consequences are worth stating because they are not renames:
 *
 * - `args` keys are the host method's *parameter* names, and the gateway matches them exactly —
 *   it refuses a missing key as readily as an unexpected one. A parameter whose type is a lookup
 *   (an `Agent`) is named `<key>Id` on the wire. So several calls that took a request object
 *   through 0.1.1 now take flat named arguments, and the shapes below follow the host signatures
 *   rather than any convention of this client's.
 * - Streams are not here. `session/follow`, `session/control` and `workspace/follow` are stream
 *   Remotes and exist only on the mux — see [RemoteStreamMux]. This class covers the unary half.
 */
class DshApiClient(
    private val transport: RpcTransport,
) {

    /** Exact Fetch routes share the envelope, but do NOT use Remote's args wrapper. */
    suspend fun subscriptionStatus(): RpcResult<SubscriptionRoster> =
        unary("subscriptions-auth.status", JsonObject(emptyMap()), SubscriptionRoster.serializer())

    suspend fun subscriptionUsage(provider: String, account: String): RpcResult<ProviderQuota> =
        unary("subscriptions-auth.usage", args {
            put("provider", JsonPrimitive(provider)); put("account", JsonPrimitive(account)); put("force", JsonPrimitive(false))
        }, ProviderQuota.serializer())

    suspend fun accountBalance(client: AccountUiMetadata): RpcResult<AccountWallet?> =
        call("account/getBalance", args { put("client", encodeToJsonElement(AccountUiMetadata.serializer(), client)) })

    // ------------------------------------------------------------------ unary machinery

    /** POST one unary call with a raw [JsonElement] payload and decode the typed value. */
    private suspend fun <T> unary(endpoint: String, payload: JsonElement, value: KSerializer<T>): RpcResult<T> =
        unaryWith(endpoint, payload) { json, _ -> decodeFromJsonElement(value, json) }

    /**
     * POST one unary call and hand the result value, plus any raw-byte attachments, to [decode].
     *
     * Attachments exist only on harness 0.1.7's multipart answers (see [RpcMultipart]); for every
     * JSON answer the map is empty.
     */
    private suspend fun <T> unaryWith(
        endpoint: String,
        payload: JsonElement,
        decode: (value: JsonElement, attachments: Map<List<String>, ByteArray>) -> T,
    ): RpcResult<T> {
        val request = ClientRequest(rpcId = newRpcId(), method = endpoint, payload = payload)
        return try {
            val response = transport.post("/api/$endpoint", encodeEnvelope(request))
            val multipart = response.multipart?.let { RpcMultipart.decode(response.contentType.orEmpty(), it) }
            val envelope = decodeServerResponse(multipart?.envelope?.toString() ?: response.body)
            when (val result = envelope.result) {
                is RpcResult.Ok -> try {
                    RpcResult.Ok(decode(result.value, multipart?.attachments.orEmpty()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Deliberately broad. A value of the wrong shape is not guaranteed to fail as
                    // a SerializationException — decoding an object where a primitive is declared
                    // can surface as an IndexOutOfBoundsException from inside the decoder — and
                    // this method's whole contract is that a caller gets an RpcResult rather than
                    // an exception. Letting one escape from here turns "that is not a harness"
                    // into a crash on the connect screen.
                    RpcResult.Err(notAHarness("response value decode failed: ${e.message}"))
                }
                is RpcResult.Err -> RpcResult.Err(result.error.classifyCapability())
            }
        } catch (e: RpcTransportException) {
            RpcResult.Err(transportError(e))
        } catch (e: SerializationException) {
            RpcResult.Err(notAHarness("response envelope decode failed: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            RpcResult.Err(notAHarness(e.message ?: "invalid response"))
        }
    }

    /**
     * Invoke one Remote endpoint with an explicit named-argument object.
     *
     * [args] keys must match the host method's parameter names exactly; see the class comment.
     */
    suspend fun <T> call(endpoint: String, args: JsonObject, value: KSerializer<T>): RpcResult<T> =
        unary(endpoint, buildJsonObject { put("args", args) }, value)

    /** Invoke one Remote endpoint and decode its value by inferred type. */
    private suspend inline fun <reified T> call(endpoint: String, args: JsonObject): RpcResult<T> =
        call(endpoint, args, serializer<T>())

    /** Invoke one Remote endpoint that takes no arguments. */
    private suspend inline fun <reified T> callEmpty(endpoint: String): RpcResult<T> =
        call(endpoint, JsonObject(emptyMap()), serializer<T>())

    /** Invoke one Remote endpoint whose sole parameter is named `request`. */
    private suspend inline fun <reified R, reified T> callRequest(endpoint: String, request: R): RpcResult<T> =
        call(
            endpoint,
            buildJsonObject { put("request", encodeToJsonElement(serializer<R>(), request)) },
            serializer<T>(),
        )

    /** Build a named-argument object. */
    private inline fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(build)

    /**
     * A 2xx answer this client could not read as the harness protocol.
     *
     * Anything else listening on the port — a dev server, a router admin page — lands here rather
     * than in [transportError], so the marker has to be set here too or a probe against the wrong
     * port reports a generic failure instead of "that is not a harness".
     */
    private fun notAHarness(message: String): RpcError = RpcError(
        code = "internal",
        message = message,
        details = TransportFailures.details(TransportFailure.NOT_A_HARNESS),
    )

    /**
     * Classify a carrier failure so callers can tell "this harness does not have that capability"
     * apart from "the connection is broken". A 404 means no route claimed the path — an optional
     * service is simply not composed — and 401/403 are the two authentication refusals. None is a
     * connection fault, and reporting one as such would put a failure banner on a healthy session.
     *
     * 403 is the Host/Origin fence; 401 means the harness has no browser session for this client.
     * Through 0.1.1 a 403 also covered the loopback-only method tier, which 0.1.2 deleted: there
     * is now one uniform authenticated surface, so a refusal here is about the caller rather than
     * about which method was called.
     */
    private fun transportError(e: RpcTransportException): RpcError = RpcError(
        code = when (e.status) {
            404 -> "capability-unavailable"
            401 -> "unauthenticated"
            403 -> "forbidden"
            else -> "internal"
        },
        message = e.message ?: "transport error",
        details = TransportFailures.details(
            TransportFailures.classify(e),
            e.status,
            e.retryAfterSeconds,
        ),
    )

    /** Decode a bare (non-object) remote value, e.g. a `string | null` or a `boolean`. */
    private fun scalarString(value: JsonElement): String? =
        if (value is JsonNull) null else value.jsonPrimitive.contentOrNull

    // ------------------------------------------------------------------ host / directory picker

    /**
     * `directoryPicker/pick` — open the OS directory chooser.
     *
     * Answers a bare `string | null` rather than an object; null is the operator cancelling.
     */
    suspend fun hostPickDirectory(): RpcResult<HostPickDirectoryValue> =
        when (val result = callEmpty<JsonElement>("directoryPicker/pick")) {
            is RpcResult.Ok -> RpcResult.Ok(HostPickDirectoryValue(scalarString(result.value)))
            is RpcResult.Err -> result
        }

    /** `directoryPicker/list` — list one directory level; an absent path lists the home directory. */
    suspend fun hostListDirectory(path: String? = null): RpcResult<DirectoryListing> =
        call("directoryPicker/list", args { if (path != null) put("path", JsonPrimitive(path)) })

    /** `directoryPicker/createDirectory` — create one child directory; answers the created path. */
    suspend fun hostCreateDirectory(path: String, name: String): RpcResult<HostCreateDirectoryValue> =
        when (
            val result = call<JsonElement>(
                "directoryPicker/createDirectory",
                args {
                    put("path", JsonPrimitive(path))
                    put("name", JsonPrimitive(name))
                },
            )
        ) {
            is RpcResult.Ok -> RpcResult.Ok(HostCreateDirectoryValue(scalarString(result.value).orEmpty()))
            is RpcResult.Err -> result
        }

    /**
     * `session/openWorkspacePath` — open a path with the OS default application.
     *
     * This replaces `host.openPath`, and the session is not decoration: the host resolves a
     * relative path against that session's workspace and refuses a browser-chosen absolute
     * target, so there is no longer a way to ask the host to open an arbitrary file.
     */
    suspend fun sessionOpenWorkspacePath(sessionId: String, path: String): RpcResult<HostOpenPathValue> =
        callRequest(
            "session/openWorkspacePath",
            com.labteto.dshmobile.core.wire.dto.SessionOpenWorkspacePathRequest(sessionId, path),
        )

    /** `session/canOpenWorkspacePath` — whether this deployment can reach a native desktop. */
    suspend fun sessionCanOpenWorkspacePath(): RpcResult<Boolean> =
        call("session/canOpenWorkspacePath", JsonObject(emptyMap()), Boolean.serializer())

    // ------------------------------------------------------------------ sessions

    /**
     * `session/list` — lists persisted sessions.
     *
     * The argument really is named `_request`: the host method ignores it, and the wire name is
     * the parameter identifier verbatim. It is sent anyway because the gateway refuses a missing
     * declared key as readily as an unexpected one.
     */
    suspend fun sessionList(cursor: String? = null): RpcResult<SessionListValue> =
        call(
            "session/list",
            args { put("_request", encodeToJsonElement(SessionListRequest.serializer(), SessionListRequest(cursor))) },
        )

    /** `session/search` — searches the user/assistant/steering surface across visible sessions. */
    suspend fun sessionSearch(query: String): RpcResult<SessionSearchValue> =
        callRequest("session/search", SessionSearchRequest(query))

    /** `session/create` — creates a real session and its idle agent. */
    suspend fun sessionCreate(request: SessionCreateRequest): RpcResult<SessionCreateValue> =
        callRequest("session/create", request)

    /**
     * `session/page` — one message-aligned backwards page of history.
     *
     * [SessionPageRequest.throughSeq] is mandatory and must come from the matching
     * `session/follow` generation's opening cursor: it pins the read to the same log cut, which
     * is what makes a page and the live tail joinable. There is no way to page without following
     * first, which is the substantive change from `session.history`.
     */
    suspend fun sessionPage(request: SessionPageRequest): RpcResult<SessionPage> =
        callRequest("session/page", request)

    /** `session/modelCatalog` — every currently routable model, grouped by provider. */
    suspend fun sessionModelCatalog(): RpcResult<ModelCatalog> = callEmpty("session/modelCatalog")

    /** `session/selectModel` — selects the complete model selection for this session. */
    suspend fun sessionSelectModel(request: SessionSelectModelRequest): RpcResult<SessionSelectModelValue> =
        callRequest("session/selectModel", request)

    /** `session/rename` — renames a session (pins the title against automatic regeneration). */
    suspend fun sessionRename(request: SessionRenameRequest): RpcResult<SessionRenameValue> =
        callRequest("session/rename", request)

    /** `session/fork` — forks a new session from a completed-turn prefix of the source. */
    suspend fun sessionFork(request: SessionForkRequest): RpcResult<SessionForkValue> =
        callRequest("session/fork", request)

    /** Read projections without activating or migrating a cold session. */
    suspend fun sessionProjections(sessionId: String): RpcResult<SessionProjectionsValue?> =
        callRequest("session/projections", SessionProjectionsRequest(sessionId))

    /**
     * `session/prompt` — sends text, temporary image bytes and staged file receipts to an
     * ordinary session agent.
     */
    suspend fun sessionPrompt(request: SessionPromptRequest): RpcResult<SessionPromptValue> =
        callRequest("session/prompt", request)

    /** `session/attachment` — reads one durable image after session-log reference proof. */
    suspend fun sessionAttachment(request: SessionAttachmentRequest): RpcResult<SessionAttachmentValue> =
        callRequest("session/attachment", request)

    /**
     * `session/attachment` in the pre-0.2.2 shape: the site takes only the attachment id.
     *
     * Used as a fallback when a host rejects the reference-shaped request as invalid input, so an
     * app that has been updated ahead of its harness still renders images on the older wire.
     */
    suspend fun sessionAttachmentLegacy(request: LegacySessionAttachmentRequest): RpcResult<SessionAttachmentValue> =
        callRequest("session/attachment", request)

    /** `session/updateQueue` — edits, removes, or strictly steers one pending queued occurrence. */
    suspend fun sessionUpdateQueue(request: SessionUpdateQueueRequest): RpcResult<SessionUpdateQueueValue> =
        callRequest("session/updateQueue", request)

    /** `session/cancel` — stops an ordinary session's active turn, preserving pending inbox work. */
    suspend fun sessionCancel(request: SessionCancelRequest): RpcResult<SessionCancelValue> =
        callRequest("session/cancel", request)

    // ------------------------------------------------------------------ subagents

    /**
     * `subagents/list` — lists direct session-backed children without loading either side.
     *
     * Takes the parent id as a bare argument rather than a request object.
     *
     * Harness 0.1.7 removed it: membership is the parent's `subagentCatalog` projection, which
     * arrives with the rest of the session's projections. Kept as the fallback for a 0.1.6 host,
     * whose projection map has no such key.
     */
    suspend fun subagentList(parentSessionId: String): RpcResult<SubagentCatalog> =
        call("subagents/list", args { put("parentSessionId", JsonPrimitive(parentSessionId)) })

    /** `subagents/prompt` — delivers human content to a continuable child's inbox. */
    suspend fun subagentPrompt(request: SubagentPromptRequest): RpcResult<SubagentPromptValue> =
        callRequest("subagents/prompt", request)

    /**
     * `subagents/interruptByParent` — interrupts a live continuable child's current turn.
     *
     * The parent id is the authority being claimed, not a routing hint: the host refuses the call
     * when the address does not own the live target.
     */
    suspend fun subagentInterrupt(
        childSessionId: String,
        parentSessionId: String,
    ): RpcResult<SubagentInterruptValue> =
        call(
            "subagents/interruptByParent",
            args {
                put("childSessionId", JsonPrimitive(childSessionId))
                put("parentSessionId", JsonPrimitive(parentSessionId))
                // The host declares this discriminator required and accepts only this value.
                put("mode", JsonPrimitive("continuable"))
            },
        )

    // ------------------------------------------------------------------ workspaces

    /** `workspace/create` — creates (or idempotently resolves) a workspace over a directory. */
    suspend fun workspaceCreate(request: WorkspaceCreateRequest): RpcResult<WorkspaceCreateValue> =
        callRequest("workspace/create", request)

    /** `workspace/rename` — renames a workspace. */
    suspend fun workspaceRename(request: WorkspaceRenameRequest): RpcResult<WorkspaceValue> =
        callRequest("workspace/rename", request)

    /** `workspace/delete` — removes one registration (never touches directory or logs). */
    suspend fun workspaceDelete(request: WorkspaceDeleteRequest): RpcResult<WorkspaceDeleteValue> =
        callRequest("workspace/delete", request)

    /** `workspace/insertBefore` — moves one workspace within the registry display order. */
    suspend fun workspaceInsertBefore(request: WorkspaceInsertBeforeRequest): RpcResult<WorkspaceOrderValue> =
        callRequest("workspace/insertBefore", request)

    /** `workspace/insertSessionBefore` — moves an accounted session within its workspace's order. */
    suspend fun workspaceInsertSessionBefore(
        request: WorkspaceInsertSessionBeforeRequest,
    ): RpcResult<WorkspaceValue> = callRequest("workspace/insertSessionBefore", request)

    /**
     * `workspace/archiveSession` — adds one session to the registry-global archive set.
     *
     * From harness 0.1.7 a session with running work (its turn, a subagent, a job) is refused as
     * `workspace/session-active` unless the request sets `stopActivity`, which stops that work
     * first. The flag is omitted when unset, so an older host that does not declare it is
     * unaffected.
     */
    suspend fun workspaceArchiveSession(
        request: WorkspaceArchiveSessionRequest,
    ): RpcResult<WorkspaceArchiveValue> = callRequest("workspace/archiveSession", request)

    /** `workspace/pinSession` (harness 0.1.7) — answers the complete pin set, most recent first. */
    suspend fun workspacePinSession(sessionId: String): RpcResult<WorkspacePinValue> =
        callRequest("workspace/pinSession", WorkspacePinSessionRequest(sessionId))

    /** `workspace/unpinSession` (harness 0.1.7) — answers the complete pin set after removal. */
    suspend fun workspaceUnpinSession(sessionId: String): RpcResult<WorkspacePinValue> =
        callRequest("workspace/unpinSession", WorkspacePinSessionRequest(sessionId))

    // ------------------------------------------------------------------ jobs

    /**
     * `job/kill` (harness 0.1.7) — stop one background job on a person's behalf.
     *
     * The session is the fence the host reads the job through, so it must be one whose job list
     * carries the row; `job/not-found` means the row has already gone.
     */
    suspend fun jobKill(sessionId: String, jobId: String): RpcResult<JobKillValue> =
        callRequest("job/kill", JobKillRequest(sessionId, jobId))

    // ------------------------------------------------------------------ skills

    /** `skills/list` — lists the user-invocable skill catalog for the session's project. */
    suspend fun skillList(request: SkillListRequest): RpcResult<SkillListValue> =
        callRequest("skills/list", request)

    // ------------------------------------------------------------------ agent presets

    /** `agentPresets/list` — lists the agent-preset roster. */
    suspend fun agentPresetList(): RpcResult<AgentPresetListValue> = callEmpty("agentPresets/list")

    /** `agentPresets/select` — selects the agent preset for a session (blank sessions only). */
    suspend fun agentPresetSelect(sessionId: String, agentPreset: String): RpcResult<String> =
        call(
            "agentPresets/select",
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("agentPreset", JsonPrimitive(agentPreset))
            },
            String.serializer(),
        )

    /** `agentPresets/read` — reads one preset's source document. */
    suspend fun agentPresetRead(agentPreset: String): RpcResult<AgentPresetDocument> =
        call("agentPresets/read", args { put("agentPreset", JsonPrimitive(agentPreset)) })

    // Harness 0.1.7 declares presets in profile YAML: `agentPresets/copy`, `deletePreset` and the
    // `settings/*AgentPresetDirectory` pair are gone, and this client never offered authoring.

    // ------------------------------------------------------------------ goals

    /** `goals/create` — create and arm a goal. */
    suspend fun goalCreate(sessionId: String, request: JsonElement): RpcResult<JsonElement> =
        call(
            "goals/create",
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("request", request)
            },
        )

    /** `goals/edit` — edit objective and/or round cap without changing phase. */
    suspend fun goalEdit(sessionId: String, ref: GoalRef, request: JsonElement): RpcResult<GoalView> =
        call(
            "goals/edit",
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("ref", encodeToJsonElement(GoalRef.serializer(), ref))
                put("request", request)
            },
        )

    /** `goals/pause` — pause an active goal and disarm automatic continuation. */
    suspend fun goalPause(sessionId: String, ref: GoalRef): RpcResult<GoalView> =
        goalControl("goals/pause", sessionId, ref)

    /** `goals/resume` — resume and arm a stopped goal. */
    suspend fun goalResume(sessionId: String, ref: GoalRef): RpcResult<GoalView> =
        goalControl("goals/resume", sessionId, ref)

    /** `goals/complete` — mark a current non-complete goal complete and disarm it. */
    suspend fun goalComplete(sessionId: String, ref: GoalRef): RpcResult<GoalView> =
        goalControl("goals/complete", sessionId, ref)

    /** `goals/clear` — clear the current goal, retaining a durable tombstone and history. */
    suspend fun goalClear(sessionId: String, ref: GoalRef): RpcResult<GoalRef> =
        call(
            "goals/clear",
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("ref", encodeToJsonElement(GoalRef.serializer(), ref))
            },
        )

    /** The three goal verbs that take exactly an agent and a ref and answer the updated view. */
    private suspend fun goalControl(endpoint: String, sessionId: String, ref: GoalRef): RpcResult<GoalView> =
        call(
            endpoint,
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("ref", encodeToJsonElement(GoalRef.serializer(), ref))
            },
        )

    // ------------------------------------------------------------------ settings

    /** `settings/describe` — describes the configuration-plane namespaces. */
    suspend fun settingsDescribe(): RpcResult<SettingsDescribeValue> = callEmpty("settings/describe")

    /** `settings/openSettingsDocument` — opens the settings document on the host desktop. */
    suspend fun settingsOpenDocument(): RpcResult<JsonElement> = callEmpty("settings/openSettingsDocument")

    /**
     * `settings/update` — patches one namespace's effective value (CAS on `expectedRevision`).
     *
     * Flat arguments now: 0.1.1 wrapped these three in a request object.
     */
    suspend fun settingsUpdate(
        ns: String,
        patch: JsonObject,
        expectedRevision: Int? = null,
    ): RpcResult<SettingsNamespaceView> =
        call(
            "settings/update",
            args {
                put("ns", JsonPrimitive(ns))
                put("patch", patch)
                if (expectedRevision != null) put("expectedRevision", JsonPrimitive(expectedRevision))
            },
        )

    /** `settings/replace` — replaces one namespace's section wholesale (CAS on `expectedRevision`). */
    suspend fun settingsReplace(
        ns: String,
        section: JsonObject,
        expectedRevision: Int? = null,
    ): RpcResult<SettingsNamespaceView> =
        call(
            "settings/replace",
            args {
                put("ns", JsonPrimitive(ns))
                put("section", section)
                if (expectedRevision != null) put("expectedRevision", JsonPrimitive(expectedRevision))
            },
        )

    /** `settings/mutate` — applies path-addressed ops to one namespace (CAS on `expectedRevision`). */
    suspend fun settingsMutate(
        ns: String,
        ops: List<SettingsPathOpView>,
        expectedRevision: Int? = null,
    ): RpcResult<SettingsNamespaceView> =
        call(
            "settings/mutate",
            args {
                put("ns", JsonPrimitive(ns))
                put("ops", encodeToJsonElement(ListSerializer(SettingsPathOpView.serializer()), ops))
                if (expectedRevision != null) put("expectedRevision", JsonPrimitive(expectedRevision))
            },
        )

    // ------------------------------------------------------------------ credentials

    /**
     * `credentials/describe` — describes credential slots by reference name.
     *
     * Takes a bare `refs` array and answers a map keyed by reference, where 0.1.1 exchanged
     * request and value objects.
     */
    suspend fun credentialsDescribe(refs: List<String>): RpcResult<Map<String, CredentialInfo>> =
        call(
            "credentials/describe",
            args { put("refs", encodeToJsonElement(ListSerializer(String.serializer()), refs)) },
        )

    /** `credentials/set` — writes one credential value (the one direction a value crosses this wire). */
    suspend fun credentialsSet(ref: String, value: String): RpcResult<JsonElement> =
        call(
            "credentials/set",
            args {
                put("ref", JsonPrimitive(ref))
                put("value", JsonPrimitive(value))
            },
        )

    /** `credentials/unset` — clears one credential slot. */
    suspend fun credentialsUnset(ref: String): RpcResult<JsonElement> =
        call("credentials/unset", args { put("ref", JsonPrimitive(ref)) })

    // ------------------------------------------------------------------ llm

    /**
     * `llm/listProviders` — the live provider routes.
     *
     * 0.1.1's `llm.providers` answered live and configurable rows together; they are two calls
     * now, and the client joins them.
     */
    suspend fun llmListProviders(): RpcResult<List<LlmProviderInfo>> =
        call("llm/listProviders", JsonObject(emptyMap()), ListSerializer(LlmProviderInfo.serializer()))

    /** `llm/listConfigurableProviders` — the routes an operator may configure. */
    suspend fun llmListConfigurableProviders(): RpcResult<List<LlmConfigurableProvider>> =
        call(
            "llm/listConfigurableProviders",
            JsonObject(emptyMap()),
            ListSerializer(LlmConfigurableProvider.serializer()),
        )

    /** `llm/discoverModels` — interrogates a draft provider endpoint for its model listing. */
    suspend fun llmDiscoverModels(
        settingsNs: String,
        request: LlmModelDiscoveryRequest,
    ): RpcResult<List<LlmDiscoveredModel>> =
        call(
            "llm/discoverModels",
            args {
                put("settingsNs", JsonPrimitive(settingsNs))
                put("request", encodeToJsonElement(LlmModelDiscoveryRequest.serializer(), request))
            },
            ListSerializer(LlmDiscoveredModel.serializer()),
        )

    // ------------------------------------------------------------------ commands / inventory

    /**
     * `commands/list` — the session's slash-command catalog.
     *
     * Rows are decoded individually so one unfamiliar entry drops out instead of emptying the menu.
     * A harness without a command registry answers 404, which surfaces as `capability-unavailable`.
     */
    suspend fun commandsList(sessionId: String): RpcResult<List<CommandDescriptor>> =
        when (val result = call<JsonElement>("commands/list", args { put("agentId", JsonPrimitive(sessionId)) })) {
            is RpcResult.Ok -> RpcResult.Ok(
                (result.value as? JsonArray).orEmpty().mapNotNull { row ->
                    runCatching { decodeFromJsonElement(CommandDescriptor.serializer(), row) }.getOrNull()
                },
            )
            is RpcResult.Err -> result
        }

    /**
     * `commands/execute` — runs one complete slash-command line against the session's agent.
     *
     * [sessionPrompt] does not execute a leading-slash line — it hands it to the model as text —
     * so this is the only command write path; the composer adjudicates first.
     *
     * The third argument is named after the host method's own parameter, `submittedAttachments`,
     * because the gateway matches args by parameter name. Through 0.1.2 it was `images` and took
     * bare image objects; 0.1.3 renamed it when files joined, and every member now carries a
     * `type`. It is sent unconditionally, empty or not, because the gateway refuses a missing key
     * as readily as an unexpected one.
     */
    suspend fun commandsExecute(
        sessionId: String,
        line: String,
        attachments: List<CommandSubmitAttachment> = emptyList(),
    ): RpcResult<JsonElement> = call(
        "commands/execute",
        args {
            put("agentId", JsonPrimitive(sessionId))
            put("line", JsonPrimitive(line))
            put(
                "submittedAttachments",
                encodeToJsonElement(ListSerializer(CommandSubmitAttachment.serializer()), attachments),
            )
        },
    )

    suspend fun permissionCatalog(): RpcResult<PermissionCatalog> = callEmpty("permissionPresets/catalog")

    suspend fun workspaceUnarchiveSession(sessionId: String): RpcResult<WorkspaceArchiveValue> =
        callRequest("workspace/unarchiveSession", WorkspaceUnarchiveSessionRequest(sessionId))

    private fun fileArgs(sessionId: String, path: String) = args {
        put("workspaceFileScopeId", JsonPrimitive(sessionId)); put("path", JsonPrimitive(path))
    }
    suspend fun workspaceFileList(sessionId: String, path: String): RpcResult<WorkspaceDirectoryListing> =
        call("workspaceFiles/list", fileArgs(sessionId, path))
    suspend fun workspaceFileStat(sessionId: String, path: String): RpcResult<WorkspaceFileStat> =
        call("workspaceFiles/stat", fileArgs(sessionId, path))
    suspend fun workspaceFileRead(sessionId: String, path: String, range: WorkspaceFileRange = WorkspaceFileRange()): RpcResult<WorkspaceFileText> =
        call("workspaceFiles/read", JsonObject(fileArgs(sessionId, path) + ("range" to encodeToJsonElement(WorkspaceFileRange.serializer(), range))))

    /**
     * `workspaceFiles/readBytes` — a file's raw bytes: the whole file, one byte window, or a file
     * named relative to another.
     *
     * Harness 0.1.7 folded `readAll` and `readRelated` into this call's `options` and began
     * answering it as multipart, the bytes riding as their own part rather than as base64 in the
     * JSON (see [RpcMultipart]). A 0.1.6 host refuses `options` as an unknown argument, and then
     * the same read is made the way that host spelled it, so the caller gets the same value from
     * either.
     */
    suspend fun workspaceFileReadBytes(
        sessionId: String,
        path: String,
        options: WorkspaceByteReadOptions = WorkspaceByteReadOptions(),
    ): RpcResult<WorkspaceFileContent> {
        val args = JsonObject(
            fileArgs(sessionId, path) + ("options" to encodeToJsonElement(WorkspaceByteReadOptions.serializer(), options)),
        )
        val result = unaryWith("workspaceFiles/readBytes", buildJsonObject { put("args", args) }, ::fileContentOf)
        if (result !is RpcResult.Err || result.error.code != ARGUMENTS_INVALID) return result
        // A 0.1.6 host: one endpoint per read shape, and base64 data in a JSON answer.
        val baseFile = options.baseFile
        val range = options.range
        val (endpoint, legacy) = when {
            baseFile != null -> "workspaceFiles/readRelated" to JsonObject(
                fileArgs(sessionId, baseFile) + ("relativePath" to JsonPrimitive(path)),
            )
            range == null -> "workspaceFiles/readAll" to fileArgs(sessionId, path)
            else -> "workspaceFiles/readBytes" to JsonObject(
                fileArgs(sessionId, path) + ("range" to encodeToJsonElement(WorkspaceByteRange.serializer(), range)),
            )
        }
        return unaryWith(endpoint, buildJsonObject { put("args", legacy) }, ::fileContentOf)
    }

    /** A `readBytes` value from either host: the bytes from their part, or from base64 `data`. */
    private fun fileContentOf(value: JsonElement, attachments: Map<List<String>, ByteArray>): WorkspaceFileContent {
        val meta = decodeFromJsonElement(WorkspaceFileBytesMeta.serializer(), value)
        val data = attachments[listOf("data")]
            ?: ((value as? JsonObject)?.get("data") as? JsonPrimitive)?.takeIf { it.isString }
                ?.let { decodeBase64(it.content) }
            ?: ByteArray(0)
        return WorkspaceFileContent(meta.absolutePath, meta.version, meta.bytes, meta.offset, meta.eof, data)
    }

    private fun terminalArgs(sessionId: String, id: String? = null, attachmentId: String? = null) = args {
        put("agentId", JsonPrimitive(sessionId))
        id?.let { put("id", JsonPrimitive(it)) }
        attachmentId?.let { put("attachmentId", JsonPrimitive(it)) }
    }
    suspend fun terminalEnvironment(sessionId: String): RpcResult<TerminalEnvironment> = call("terminal/environment", terminalArgs(sessionId))
    suspend fun terminalShells(sessionId: String): RpcResult<List<TerminalShell>> = call("terminal/shells", terminalArgs(sessionId))
    suspend fun terminalList(sessionId: String): RpcResult<List<WebTerminalInfo>> = call("terminal/list", args { put("sessionId", JsonPrimitive(sessionId)) })
    suspend fun terminalCreate(sessionId: String, request: TerminalCreateRequest): RpcResult<WebTerminalInfo> =
        call("terminal/create", JsonObject(terminalArgs(sessionId) + ("request" to encodeToJsonElement(TerminalCreateRequest.serializer(), request))))
    /** Arguments for the duplex `terminal/attach` stream (harness 0.2.1-alpha.3 and later). */
    fun terminalAttachArgs(sessionId: String, id: String): JsonObject = JsonObject(terminalArgs(sessionId, id))
    /** Arguments for the pre-alpha.3 `terminal/follow` stream, kept for older hosts. */
    fun terminalFollowArgs(sessionId: String, id: String, attachmentId: String): JsonObject =
        JsonObject(terminalArgs(sessionId, id, attachmentId))
    /** Pre-alpha.3 only: duplex hosts take input on the `terminal/attach` uplink and 404 this. */
    suspend fun terminalWrite(sessionId: String, id: String, attachmentId: String, data: String): RpcResult<JsonElement> =
        call("terminal/write", JsonObject(terminalArgs(sessionId, id, attachmentId) + ("data" to JsonPrimitive(data))))
    /** Pre-alpha.3 only, like [terminalWrite]. */
    suspend fun terminalResize(sessionId: String, id: String, attachmentId: String, cols: Int, rows: Int): RpcResult<JsonElement> =
        call("terminal/resize", JsonObject(terminalArgs(sessionId, id, attachmentId) + mapOf("cols" to JsonPrimitive(cols), "rows" to JsonPrimitive(rows))))
    suspend fun terminalRename(sessionId: String, id: String, title: String): RpcResult<JsonElement> =
        call("terminal/rename", JsonObject(terminalArgs(sessionId, id) + ("title" to JsonPrimitive(title))))
    suspend fun terminalClose(sessionId: String, id: String): RpcResult<JsonElement> = call("terminal/close", terminalArgs(sessionId, id))
    suspend fun messageFeedbackList(sessionId: String): RpcResult<MessageFeedbackResult<MessageFeedbackListValue>> =
        callRequest("messageFeedback/list", MessageFeedbackListRequest(sessionId))
    suspend fun messageFeedbackPut(request: MessageFeedbackPutRequest): RpcResult<MessageFeedbackResult<MessageFeedbackItem>> =
        call("messageFeedback/put", args {
            put("request", JsonObject((encodeToJsonElement(MessageFeedbackPutRequest.serializer(), request) as JsonObject) +
                ("ifVersion" to (request.ifVersion?.let(::JsonPrimitive) ?: JsonNull))))
        })
    suspend fun messageFeedbackDelete(request: MessageFeedbackDeleteRequest): RpcResult<MessageFeedbackResult<MessageFeedbackDeleteValue>> =
        callRequest("messageFeedback/delete", request)

    // ------------------------------------------------------------------ file uploads

    /**
     * `fileUploads/upload` — stage one file for a session from canonical base64 bytes.
     *
     * The Remote form of the upload, which the web client uses only as a fallback for exact
     * bytes; the streaming route ([uploadFileBinary]) is the ordinary path. It exists here for
     * the same reason: a deployment or relay that does not forward the raw-byte route still has
     * an envelope path to the same receipt. Bounded by the RPC body cap (300 MiB after base64),
     * and by what a phone will hold in memory, so callers keep it to small files.
     */
    suspend fun fileUploadEncoded(
        sessionId: String,
        request: EncodedFileUploadRequest,
    ): RpcResult<FileUploadValue> = call(
        "fileUploads/upload",
        args {
            put("agentId", JsonPrimitive(sessionId))
            put("request", encodeToJsonElement(EncodedFileUploadRequest.serializer(), request))
        },
    )

    /**
     * `POST /api/session/uploadFileBinary` — stage one file for a session by streaming its bytes.
     *
     * Harness 0.1.3's one non-envelope write route: an `application/octet-stream` body with the
     * session id and optional display name in the query, answered with HTTP 200 and a bare
     * `{ok, value|error}` result rather than a response envelope. The host writes the bytes
     * verbatim and answers with a receipt that a later `session/prompt` or `commands/execute`
     * cites by [FileUploadValue.receiptId]; the bytes themselves never ride a prompt.
     *
     * A carrier 404 means no route claimed the path — a deployment that composes no file-upload
     * service, or a relay that does not proxy it — and surfaces as `capability-unavailable` so a
     * caller can fall back to [fileUploadEncoded].
     */
    suspend fun uploadFileBinary(
        sessionId: String,
        name: String?,
        contentLength: Long,
        body: Source,
        onProgress: ((sent: Long) -> Unit)? = null,
    ): RpcResult<FileUploadValue> {
        val query = "sessionId=${encodeQueryComponent(sessionId)}" +
            if (name != null) "&name=${encodeQueryComponent(name)}" else ""
        return try {
            val response = transport.upload(
                "$FILE_UPLOAD_PATH?$query",
                "application/octet-stream",
                contentLength,
                body,
                onProgress,
            )
            when (val result = decodeFromJsonElement(RpcResultJsonSerializer, WireJson.parseToJsonElement(response.body))) {
                is RpcResult.Ok -> try {
                    RpcResult.Ok(decodeFromJsonElement(FileUploadValue.serializer(), result.value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RpcResult.Err(notAHarness("upload value decode failed: ${e.message}"))
                }
                is RpcResult.Err -> result
            }
        } catch (e: RpcTransportException) {
            RpcResult.Err(transportError(e))
        } catch (e: SerializationException) {
            RpcResult.Err(notAHarness("upload result decode failed: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            RpcResult.Err(notAHarness(e.message ?: "invalid upload result"))
        }
    }

    /**
     * `pluginInventory/list` — the host's composed-plugin inventory (read-only).
     *
     * Rows are decoded individually, as in [commandsList]: a future `fiberPhase` this build has
     * never heard of should cost that one row, not the whole list. A deployment that does not
     * compose the inventory plugin answers 404, which surfaces as `capability-unavailable` and is
     * the caller's cue to hide the section rather than report a failure.
     */
    suspend fun pluginInventoryList(): RpcResult<PluginInventorySnapshot> =
        when (val result = callEmpty<JsonElement>("pluginInventory/list")) {
            is RpcResult.Ok -> {
                val entries = (result.value as? JsonObject)?.get("entries") as? JsonArray
                RpcResult.Ok(
                    PluginInventorySnapshot(
                        entries = entries.orEmpty().mapNotNull { row ->
                            runCatching {
                                decodeFromJsonElement(PluginInventoryEntry.serializer(), row)
                            }.getOrNull()
                        },
                    ),
                )
            }
            is RpcResult.Err -> result
        }

    /** `fileReferences/list` — file-reference completion candidates for a composer mention. */
    suspend fun fileReferencesList(sessionId: String, query: String): RpcResult<JsonElement> =
        call(
            "fileReferences/list",
            args {
                put("agentId", JsonPrimitive(sessionId))
                put("query", JsonPrimitive(query))
            },
        )

    // ------------------------------------------------------------------ remote events

    /**
     * Answer one pending Remote Event waterfall.
     *
     * This is what replaced `POST /api/respond`. The reply is bound to a generation by
     * [clientId] — from the current `$events` ready frame — and to one pending request by
     * [eventId]; the host refuses a reply carrying a retired generation, so an answer typed
     * before a reconnect cannot resolve a request the host has since replayed.
     *
     * A failure here is deliberately not retried: upstream fails the whole connection generation
     * on it and replays the pending event on the next one, so a client-side retry queue would
     * answer the same request twice.
     *
     * It goes through [call] like every other unary, and that is load-bearing rather than tidy:
     * the gateway reads `$events/result` as a Remote with one named parameter, so the three
     * fields ride inside `args` and a bare `{clientId, eventId, outcome}` payload is refused
     * outright — "Remote event result requires exactly one plain-object args field". Through
     * 0.11.2 this posted that bare object, so every answer and every approval failed on the
     * wire while the app reported it as a connection fault.
     */
    suspend fun answerEvent(
        clientId: String,
        eventId: String,
        outcome: RemoteEventOutcome,
    ): RpcResult<JsonElement> = call(
        REMOTE_EVENT_RESULT_ENDPOINT,
        encodeToJsonElement(
            RemoteEventResult.serializer(),
            RemoteEventResult(clientId = clientId, eventId = eventId, outcome = outcome),
        ).jsonObject,
        JsonElement.serializer(),
    )

    // ------------------------------------------------------------------ downloads

    /**
     * `session.export` — streams the session-log ZIP.
     *
     * The harness's one non-envelope read channel, and the one route that kept its 0.1.1 shape:
     * a plain `GET` answered with an attachment, registered as an exact fetch route rather than
     * as a Remote, because a browser download manager consumes a streamed response rather than a
     * JSON result. [consume] receives the live stream and must not retain it.
     */
    suspend fun <T> sessionExport(
        sessionId: String,
        includeDescendants: Boolean = false,
        consume: (contentType: String?, contentDisposition: String?, body: Source) -> T,
    ): RpcResult<T> = try {
        val query = "sessionId=${encodeQueryComponent(sessionId)}" +
            if (includeDescendants) "&includeDescendants=true" else ""
        RpcResult.Ok(transport.download("/api/session.export?$query", consume))
    } catch (e: RpcTransportException) {
        RpcResult.Err(transportError(e))
    } catch (e: IOException) {
        RpcResult.Err(RpcError("internal", e.message ?: "download failed", JsonObject(emptyMap())))
    }
}
