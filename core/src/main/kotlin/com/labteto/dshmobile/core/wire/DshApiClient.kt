package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.AgentPresetCopyRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetCopyValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetOpenDocumentRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetOpenDocumentValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetReadValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetRemoveRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetRemoveValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetSelectRequest
import com.labteto.dshmobile.core.wire.dto.AgentPresetSelectValue
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.CredentialsDescribeRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsDescribeValue
import com.labteto.dshmobile.core.wire.dto.CredentialsSetRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsSetValue
import com.labteto.dshmobile.core.wire.dto.CredentialsUnsetRequest
import com.labteto.dshmobile.core.wire.dto.CredentialsUnsetValue
import com.labteto.dshmobile.core.wire.dto.DirectoryListing
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.GoalClearRequest
import com.labteto.dshmobile.core.wire.dto.GoalClearValue
import com.labteto.dshmobile.core.wire.dto.GoalCompleteRequest
import com.labteto.dshmobile.core.wire.dto.GoalCompleteValue
import com.labteto.dshmobile.core.wire.dto.GoalCreateRequest
import com.labteto.dshmobile.core.wire.dto.GoalCreateValue
import com.labteto.dshmobile.core.wire.dto.GoalEditRequest
import com.labteto.dshmobile.core.wire.dto.GoalEditValue
import com.labteto.dshmobile.core.wire.dto.GoalPauseRequest
import com.labteto.dshmobile.core.wire.dto.GoalPauseValue
import com.labteto.dshmobile.core.wire.dto.GoalResumeRequest
import com.labteto.dshmobile.core.wire.dto.GoalResumeValue
import com.labteto.dshmobile.core.wire.dto.HostCreateDirectoryRequest
import com.labteto.dshmobile.core.wire.dto.HostCreateDirectoryValue
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.HostListDirectoryRequest
import com.labteto.dshmobile.core.wire.dto.HostOpenPathRequest
import com.labteto.dshmobile.core.wire.dto.HostOpenPathValue
import com.labteto.dshmobile.core.wire.dto.HostPickDirectoryValue
import com.labteto.dshmobile.core.wire.dto.LlmDiscoverModelsRequest
import com.labteto.dshmobile.core.wire.dto.LlmDiscoverModelsValue
import com.labteto.dshmobile.core.wire.dto.LlmModelsValue
import com.labteto.dshmobile.core.wire.dto.LlmProvidersValue
import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentValue
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelValue
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionCreateValue
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkValue
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRequest
import com.labteto.dshmobile.core.wire.dto.SessionHistoryValue
import com.labteto.dshmobile.core.wire.dto.SessionListRequest
import com.labteto.dshmobile.core.wire.dto.SessionListValue
import com.labteto.dshmobile.core.wire.dto.SessionModelsRequest
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
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
import com.labteto.dshmobile.core.wire.dto.SettingsMutateRequest
import com.labteto.dshmobile.core.wire.dto.SettingsMutateValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsOpenDocumentValue
import com.labteto.dshmobile.core.wire.dto.SettingsReplaceRequest
import com.labteto.dshmobile.core.wire.dto.SettingsReplaceValue
import com.labteto.dshmobile.core.wire.dto.SettingsUpdateRequest
import com.labteto.dshmobile.core.wire.dto.SettingsUpdateValue
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.SkillListValue
import com.labteto.dshmobile.core.wire.dto.SubagentCatalog
import com.labteto.dshmobile.core.wire.dto.SubagentHistoryRequest
import com.labteto.dshmobile.core.wire.dto.SubagentHistoryValue
import com.labteto.dshmobile.core.wire.dto.SubagentInterruptRequest
import com.labteto.dshmobile.core.wire.dto.SubagentInterruptValue
import com.labteto.dshmobile.core.wire.dto.SubagentListRequest
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.SubagentPromptValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertBeforeValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertSessionBeforeRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceInsertSessionBeforeValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceListValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameValue
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** Percent-encode one query-string value (session ids are opaque host-minted strings). */
private fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * Typed client for the harness unary + downlink wire protocol (v0.1.0-rc.8). Every unary method
 * maps to one `POST /api/<method>` (see [rpcMapPath] for the path table) and returns [RpcResult]:
 * business failures arrive as HTTP 200 + `ok: false` and come back as [RpcResult.Err]; carrier
 * failures (non-2xx, transport, or decode) are folded into `RpcResult.Err` with code `internal`.
 */
class DshApiClient(
    private val transport: RpcTransport,
    private val wsFactory: (path: String, sink: WsDownlinkSink) -> WsDownlink,
) {

    /**
     * Whether this host's `commands/execute` takes an `images` argument (harness 0.1.0-rc.8).
     *
     * Set once per connection generation by the handshake — see [ConnectionLoop] — from the
     * *shape* of `host.describe` rather than its `version`, because a version string is the one
     * thing this client has never been willing to branch on (`docs/COMPATIBILITY.md`). It is
     * volatile because the handshake and the callers run on different threads.
     */
    @Volatile
    var acceptsCommandImages: Boolean = false
        private set

    // ------------------------------------------------------------------ unary machinery

    /** POST one unary call with a raw [JsonElement] payload and decode the typed value. */
    private suspend fun <T> unary(method: String, payload: JsonElement, value: KSerializer<T>): RpcResult<T> {
        val request = ClientRequest(rpcId = newRpcId(), method = method, payload = payload)
        return try {
            val response = transport.post("/api/$method", encodeEnvelope(request))
            val envelope = decodeServerResponse(response.body)
            when (val result = envelope.result) {
                is RpcResult.Ok -> try {
                    RpcResult.Ok(decodeFromJsonElement(value, result.value))
                } catch (e: SerializationException) {
                    RpcResult.Err(notAHarness("response value decode failed: ${e.message}"))
                }
                is RpcResult.Err -> result
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
     * service is simply not composed — and a 403 is the documented loopback-only refusal. Neither
     * is a connection fault, and reporting them as one would put a failure banner on a healthy
     * session.
     */
    private fun transportError(e: RpcTransportException): RpcError = RpcError(
        code = when (e.status) {
            404 -> "capability-unavailable"
            403 -> "forbidden"
            else -> "internal"
        },
        message = e.message ?: "transport error",
        // The `code` above answers "can this build do that"; the details answer "why did the wire
        // fail", which is what a connect screen needs to tell a firewall from a loopback bind.
        details = TransportFailures.details(TransportFailures.classify(e), e.status),
    )

    /** POST one unary call with a typed @Serializable request payload. */
    private suspend inline fun <reified R, reified T> call(method: String, request: R): RpcResult<T> =
        unary(method, encodeToJsonElement(serializer<R>(), request), serializer<T>())

    /** POST one unary call with the literal empty payload `{}`. */
    private suspend inline fun <reified T> callEmpty(method: String): RpcResult<T> =
        unary(method, JsonObject(emptyMap()), serializer<T>())

    // ------------------------------------------------------------------ host

    /**
     * host.describe — one-shot host snapshot, and the point where [acceptsCommandImages] is
     * latched. Every path that reaches this client's commands describes first (the handshake's
     * last step, `ConnectionLoop.openGeneration`), so latching here rather than at one call site
     * means no caller can dispatch a command against an undecided shape.
     */
    suspend fun hostDescribe(): RpcResult<HostDescription> =
        callEmpty<HostDescription>("host.describe").also { result ->
            if (result is RpcResult.Ok) acceptsCommandImages = result.value.home != null
        }

    /** host.pickDirectory — open the OS directory picker; `path` is null when the user cancelled. */
    suspend fun hostPickDirectory(): RpcResult<HostPickDirectoryValue> = callEmpty("host.pickDirectory")

    /** host.listDirectory — list one directory level; an absent path lists the home directory. */
    suspend fun hostListDirectory(path: String? = null): RpcResult<DirectoryListing> =
        call("host.listDirectory", HostListDirectoryRequest(path))

    /** host.createDirectory — create one child directory under an existing parent. */
    suspend fun hostCreateDirectory(path: String, name: String): RpcResult<HostCreateDirectoryValue> =
        call("host.createDirectory", HostCreateDirectoryRequest(path, name))

    /** host.openPath — open a filesystem path with the OS default application. */
    suspend fun hostOpenPath(path: String): RpcResult<HostOpenPathValue> =
        call("host.openPath", HostOpenPathRequest(path))

    // ------------------------------------------------------------------ sessions

    /** session.list — lists persisted sessions (updatedAt descending). */
    suspend fun sessionList(cursor: String? = null): RpcResult<SessionListValue> =
        call("session.list", SessionListRequest(cursor))

    /** session.search — searches the user/assistant/steering surface across visible sessions. */
    suspend fun sessionSearch(query: String): RpcResult<SessionSearchValue> =
        call("session.search", SessionSearchRequest(query))

    /** session.create — creates a real session and its idle agent. */
    suspend fun sessionCreate(request: SessionCreateRequest): RpcResult<SessionCreateValue> =
        call("session.create", request)

    /** session.history — reads a window of history events (tail page carries projections). */
    suspend fun sessionHistory(request: SessionHistoryRequest): RpcResult<SessionHistoryValue> =
        call("session.history", request)

    /** session.models — reads a fresh advisory model directory. */
    suspend fun sessionModels(request: SessionModelsRequest): RpcResult<SessionModelsValue> =
        call("session.models", request)

    /** session.selectModel — selects the complete model selection for this session. */
    suspend fun sessionSelectModel(request: SessionSelectModelRequest): RpcResult<SessionSelectModelValue> =
        call("session.selectModel", request)

    /** session.rename — renames a session (pins the title against automatic regeneration). */
    suspend fun sessionRename(request: SessionRenameRequest): RpcResult<SessionRenameValue> =
        call("session.rename", request)

    /** session.fork — forks a new session from a completed-turn prefix of the source. */
    suspend fun sessionFork(request: SessionForkRequest): RpcResult<SessionForkValue> =
        call("session.fork", request)

    /** session.prompt — sends text and temporary image bytes to an ordinary session agent. */
    suspend fun sessionPrompt(request: SessionPromptRequest): RpcResult<SessionPromptValue> =
        call("session.prompt", request)

    /** session.attachment — reads one durable image after session-log reference proof. */
    suspend fun sessionAttachment(request: SessionAttachmentRequest): RpcResult<SessionAttachmentValue> =
        call("session.attachment", request)

    /** session.updateQueue — edits, removes, or strictly steers one pending queued occurrence. */
    suspend fun sessionUpdateQueue(request: SessionUpdateQueueRequest): RpcResult<SessionUpdateQueueValue> =
        call("session.updateQueue", request)

    /** session.cancel — stops an ordinary session's active turn, preserving pending inbox work. */
    suspend fun sessionCancel(request: SessionCancelRequest): RpcResult<SessionCancelValue> =
        call("session.cancel", request)

    // ------------------------------------------------------------------ subagents

    /** subagent.list — lists direct session-backed children without loading either side. */
    suspend fun subagentList(request: SubagentListRequest): RpcResult<SubagentCatalog> =
        call("subagent.list", request)

    /** subagent.history — reads one healthy catalog child's transcript. */
    suspend fun subagentHistory(request: SubagentHistoryRequest): RpcResult<SubagentHistoryValue> =
        call("subagent.history", request)

    /** subagent.prompt — delivers human content to a continuable child's inbox. */
    suspend fun subagentPrompt(request: SubagentPromptRequest): RpcResult<SubagentPromptValue> =
        call("subagent.prompt", request)

    /** subagent.interrupt — interrupts a live continuable child's current turn. */
    suspend fun subagentInterrupt(request: SubagentInterruptRequest): RpcResult<SubagentInterruptValue> =
        call("subagent.interrupt", request)

    // ------------------------------------------------------------------ workspaces

    /** workspace.list — lists all workspaces plus the registry-global archive set. */
    suspend fun workspaceList(): RpcResult<WorkspaceListValue> = callEmpty("workspace.list")

    /** workspace.create — creates (or idempotently resolves) a workspace over an existing directory. */
    suspend fun workspaceCreate(request: WorkspaceCreateRequest): RpcResult<WorkspaceCreateValue> =
        call("workspace.create", request)

    /** workspace.rename — renames a workspace. */
    suspend fun workspaceRename(request: WorkspaceRenameRequest): RpcResult<WorkspaceRenameValue> =
        call("workspace.rename", request)

    /** workspace.delete — removes one workspace registration (never touches directory or logs). */
    suspend fun workspaceDelete(request: WorkspaceDeleteRequest): RpcResult<WorkspaceDeleteValue> =
        call("workspace.delete", request)

    /** workspace.insertBefore — moves one workspace within the registry display order. */
    suspend fun workspaceInsertBefore(request: WorkspaceInsertBeforeRequest): RpcResult<WorkspaceInsertBeforeValue> =
        call("workspace.insertBefore", request)

    /** workspace.insertSessionBefore — moves an accounted session within its workspace's order. */
    suspend fun workspaceInsertSessionBefore(request: WorkspaceInsertSessionBeforeRequest): RpcResult<WorkspaceInsertSessionBeforeValue> =
        call("workspace.insertSessionBefore", request)

    /** workspace.archiveSession — adds one session to the registry-global archive set. */
    suspend fun workspaceArchiveSession(request: WorkspaceArchiveSessionRequest): RpcResult<WorkspaceArchiveSessionValue> =
        call("workspace.archiveSession", request)

    // ------------------------------------------------------------------ skills

    /** skill.list — lists the user-invocable skill catalog for the session's project. */
    suspend fun skillList(request: SkillListRequest): RpcResult<SkillListValue> =
        call("skill.list", request)

    // ------------------------------------------------------------------ agent presets

    /** agentPreset.list — lists the agent-preset roster. */
    suspend fun agentPresetList(): RpcResult<AgentPresetListValue> = callEmpty("agentPreset.list")

    /** agentPreset.select — selects the agent preset for a session (blank sessions only). */
    suspend fun agentPresetSelect(request: AgentPresetSelectRequest): RpcResult<AgentPresetSelectValue> =
        call("agentPreset.select", request)

    /** agentPreset.read — reads one preset's source document. */
    suspend fun agentPresetRead(request: AgentPresetReadRequest): RpcResult<AgentPresetReadValue> =
        call("agentPreset.read", request)

    /** agentPreset.copy — copies one preset into a new user preset. */
    suspend fun agentPresetCopy(request: AgentPresetCopyRequest): RpcResult<AgentPresetCopyValue> =
        call("agentPreset.copy", request)

    /** agentPreset.openDocument — opens a preset's document with the OS default application. */
    suspend fun agentPresetOpenDocument(request: AgentPresetOpenDocumentRequest): RpcResult<AgentPresetOpenDocumentValue> =
        call("agentPreset.openDocument", request)

    /** agentPreset.remove — removes a user preset. */
    suspend fun agentPresetRemove(request: AgentPresetRemoveRequest): RpcResult<AgentPresetRemoveValue> =
        call("agentPreset.remove", request)

    // ------------------------------------------------------------------ goals

    /** goal.create — create and arm a goal. */
    suspend fun goalCreate(request: GoalCreateRequest): RpcResult<GoalCreateValue> =
        call("goal.create", request)

    /** goal.edit — edit objective and/or round cap without changing phase. */
    suspend fun goalEdit(request: GoalEditRequest): RpcResult<GoalEditValue> =
        call("goal.edit", request)

    /** goal.pause — pause an active goal and disarm automatic continuation. */
    suspend fun goalPause(request: GoalPauseRequest): RpcResult<GoalPauseValue> =
        call("goal.pause", request)

    /** goal.resume — resume and arm a stopped goal. */
    suspend fun goalResume(request: GoalResumeRequest): RpcResult<GoalResumeValue> =
        call("goal.resume", request)

    /** goal.complete — mark a current non-complete goal complete and disarm it. */
    suspend fun goalComplete(request: GoalCompleteRequest): RpcResult<GoalCompleteValue> =
        call("goal.complete", request)

    /** goal.clear — clear the current goal while retaining a durable tombstone and history. */
    suspend fun goalClear(request: GoalClearRequest): RpcResult<GoalClearValue> =
        call("goal.clear", request)

    // ------------------------------------------------------------------ settings

    /** settings.describe — describes the configuration-plane namespaces. */
    suspend fun settingsDescribe(): RpcResult<SettingsDescribeValue> = callEmpty("settings.describe")

    /** settings.openDocument — opens the settings document with the OS default application. */
    suspend fun settingsOpenDocument(): RpcResult<SettingsOpenDocumentValue> = callEmpty("settings.openDocument")

    /** settings.update — patches one namespace's effective value (CAS on expectedRevision). */
    suspend fun settingsUpdate(request: SettingsUpdateRequest): RpcResult<SettingsUpdateValue> =
        call("settings.update", request)

    /** settings.replace — replaces one namespace's section wholesale (CAS on expectedRevision). */
    suspend fun settingsReplace(request: SettingsReplaceRequest): RpcResult<SettingsReplaceValue> =
        call("settings.replace", request)

    /** settings.mutate — applies path-addressed ops to one namespace (CAS on expectedRevision). */
    suspend fun settingsMutate(request: SettingsMutateRequest): RpcResult<SettingsMutateValue> =
        call("settings.mutate", request)

    // ------------------------------------------------------------------ credentials

    /** credentials.describe — describes credential slots by reference name. */
    suspend fun credentialsDescribe(request: CredentialsDescribeRequest): RpcResult<CredentialsDescribeValue> =
        call("credentials.describe", request)

    /** credentials.set — writes one credential value (the one direction a value crosses this wire). */
    suspend fun credentialsSet(request: CredentialsSetRequest): RpcResult<CredentialsSetValue> =
        call("credentials.set", request)

    /** credentials.unset — clears one credential slot. */
    suspend fun credentialsUnset(request: CredentialsUnsetRequest): RpcResult<CredentialsUnsetValue> =
        call("credentials.unset", request)

    // ------------------------------------------------------------------ llm

    /** llm.providers — lists configurable provider routes. */
    suspend fun llmProviders(): RpcResult<LlmProvidersValue> = callEmpty("llm.providers")

    /** llm.models — lists the model catalog per provider group. */
    suspend fun llmModels(): RpcResult<LlmModelsValue> = callEmpty("llm.models")

    /** llm.discoverModels — interrogates a draft provider endpoint for its model listing. */
    suspend fun llmDiscoverModels(request: LlmDiscoverModelsRequest): RpcResult<LlmDiscoverModelsValue> =
        call("llm.discoverModels", request)

    // ------------------------------------------------------------------ server-initiated responses / remotes / downlinks

    /**
     * Answers a server-initiated request (approval/question requested) by POSTing a
     * client-response to /api/respond. Returns the carrier receipt, or null when the carrier
     * failed (the server never acknowledged).
     */
    suspend fun respond(rpcId: String, value: JsonElement): RpcReceipt? {
        val envelope = ClientResponse(rpcId = rpcId, result = RpcResult.Ok(value))
        return try {
            val response = transport.post("/api/respond", encodeClientResponse(envelope))
            decodeFromString<RpcReceipt>(response.body)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Rejects a server-initiated request rather than answering it, by POSTing a client-response
     * whose result is `ok: false`.
     *
     * A dismissal is not an empty answer. Closing a question with `{selected: []}` for every item
     * is a valid *answer* the model reads as "no preference"; the harness's own client instead
     * fails the wait, and the host then resolves the tool call as cancelled. Only the `cancelled`
     * code is accepted here — the proxy answers `bad-response` to any other.
     */
    suspend fun respondError(rpcId: String, error: RpcError): RpcReceipt? {
        val envelope = ClientResponse(rpcId = rpcId, result = RpcResult.Err(error))
        return try {
            val response = transport.post("/api/respond", encodeClientResponse(envelope))
            decodeFromString<RpcReceipt>(response.body)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Invokes a typert "Remote" gateway method: `POST /api/<namespace>/<method>`.
     *
     * The gateway shares the ordinary envelope — `{"args": …}` is the *payload*, not the body — and
     * asserts that the envelope's `method` equals the path, so this delegates to [unary] with the
     * slash-separated method name rather than posting a bare body. Args are a named object whose
     * keys must match the remote descriptor exactly; a session-addressed method takes `agentId`.
     */
    suspend fun remote(namespace: String, method: String, args: JsonElement): RpcResult<JsonElement> =
        unary(
            method = "$namespace/$method",
            payload = buildJsonObject { put("args", args) },
            value = JsonElement.serializer(),
        )

    /**
     * commands/list — the session's slash-command catalog.
     *
     * Rows are decoded individually so one unfamiliar entry drops out instead of emptying the menu.
     * A harness without a command registry answers 404, which surfaces as `capability-unavailable`.
     */
    suspend fun commandsList(sessionId: String): RpcResult<List<CommandDescriptor>> {
        val args = buildJsonObject { put("agentId", JsonPrimitive(sessionId)) }
        return when (val result = remote("commands", "list", args)) {
            is RpcResult.Ok -> RpcResult.Ok(
                (result.value as? JsonArray).orEmpty().mapNotNull { row ->
                    runCatching { decodeFromJsonElement(CommandDescriptor.serializer(), row) }.getOrNull()
                },
            )
            is RpcResult.Err -> result
        }
    }

    /**
     * commands/execute — runs one complete slash-command line against the session's agent.
     *
     * [sessionPrompt] executes a leading-slash line the same way, so a caller that cannot reach the
     * gateway still has a working write path.
     *
     * The `images` argument arrived in harness 0.1.0-rc.8 and is *required* there. The gateway
     * matches an args object against the remote's declared parameters and refuses both a missing
     * and an unexpected key, so this is the one call in the client whose shape cannot be written
     * once for every host: it follows [acceptsCommandImages], which the handshake sets from the
     * shape of `host.describe` rather than from any version string.
     *
     * @param images base64-encoded composer images, in submission order; must be empty when
     *   [acceptsCommandImages] is false, because an rc.7 host has nowhere to put them.
     */
    suspend fun commandsExecute(
        sessionId: String,
        line: String,
        images: List<EncodedImageAttachment> = emptyList(),
    ): RpcResult<JsonElement> {
        // Refuse rather than drop. Adjudication should have stopped this already, but every other
        // caller of this method reaches it directly, and silently sending a command without the
        // images the user attached to it is the one outcome nobody could diagnose from the screen.
        if (images.isNotEmpty() && !acceptsCommandImages) {
            return RpcResult.Err(
                RpcError(
                    code = "capability-unavailable",
                    message = "this harness does not carry image attachments on a command",
                ),
            )
        }
        return remote(
            "commands",
            "execute",
            buildJsonObject {
                put("agentId", JsonPrimitive(sessionId))
                put("line", JsonPrimitive(line))
                if (acceptsCommandImages) {
                    put("images", encodeToJsonElement(ListSerializer(EncodedImageAttachment.serializer()), images))
                }
            },
        )
    }

    /**
     * pluginInventory/list — the host's composed-plugin inventory (read-only).
     *
     * Rows are decoded individually, as in [commandsList]: a future `fiberPhase` this build has
     * never heard of should cost that one row, not the whole list. A deployment that does not
     * compose the inventory plugin answers 404, which surfaces as `capability-unavailable` and is
     * the caller's cue to hide the section rather than report a failure.
     */
    suspend fun pluginInventoryList(): RpcResult<PluginInventorySnapshot> =
        when (val result = remote("pluginInventory", "list", JsonObject(emptyMap()))) {
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

    /**
     * session.export — streams the session-log ZIP.
     *
     * This is the harness's one non-envelope read channel: a plain `GET` answered with an
     * attachment, not an RPC. [consume] receives the live stream and must not retain it.
     */
    suspend fun <T> sessionExport(
        sessionId: String,
        includeDescendants: Boolean = false,
        consume: (contentType: String?, contentDisposition: String?, body: InputStream) -> T,
    ): RpcResult<T> = try {
        val query = "sessionId=${encodeQueryComponent(sessionId)}" +
            if (includeDescendants) "&includeDescendants=true" else ""
        RpcResult.Ok(transport.download("/api/session.export?$query", consume))
    } catch (e: RpcTransportException) {
        RpcResult.Err(transportError(e))
    } catch (e: IOException) {
        RpcResult.Err(RpcError("internal", e.message ?: "download failed", JsonObject(emptyMap())))
    }

    /**
     * Opens one downlink stream. `mux = true` opens `/api/events.mux`; `mux = false` opens
     * `/api/events.host`. The returned [WsDownlink] must be [WsDownlink.start]ed by the caller.
     */
    fun openEvents(mux: Boolean, sink: WsDownlinkSink): WsDownlink =
        wsFactory(if (mux) "/api/events.mux" else "/api/events.host", sink)
}
