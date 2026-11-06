package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.RemoteStreamException
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.JobFollowRequest
import com.labteto.dshmobile.core.wire.dto.JobListFrame
import com.labteto.dshmobile.core.wire.dto.JobListRequest
import com.labteto.dshmobile.core.wire.dto.GoalRef
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef
import com.labteto.dshmobile.core.wire.dto.MessageFeedbackDeleteRequest
import com.labteto.dshmobile.core.wire.dto.MessageFeedbackPutRequest
import com.labteto.dshmobile.core.wire.dto.SessionAttachmentRequest
import com.labteto.dshmobile.core.wire.dto.SessionCancelRequest
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionForkRequest
import com.labteto.dshmobile.core.wire.dto.SessionRenameRequest
import com.labteto.dshmobile.core.wire.dto.SessionSelectModelRequest
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import com.labteto.dshmobile.core.wire.dto.TerminalCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveSessionRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteRange
import com.labteto.dshmobile.core.wire.dto.WorkspaceByteReadOptions
import com.labteto.dshmobile.core.wire.dto.WorkspaceCreateRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceDeleteRequest
import com.labteto.dshmobile.core.wire.dto.WorkspaceRenameRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Every endpoint this client calls exists on a real harness, and takes the arguments it sends.
 *
 * This is the test the repository did not have and most needed. The gateway matches an args object
 * against the host method's **own parameter names**, exactly — a missing key is refused as readily
 * as an unexpected one — and it does that before any business logic runs. So a renamed parameter,
 * a dropped endpoint or a shape that moved is `gateway/arguments-invalid` or
 * `gateway/method-unavailable`, and both are findable by simply making the call. Until now the only
 * way that showed up was a user hitting it: the argument that carries attachments was renamed twice
 * in three releases, and each time the app found out in the field.
 *
 * What is asserted is deliberately narrow. Not "the call succeeded" — most of these are called with
 * identities that do not exist, on purpose — but "the harness understood the request well enough to
 * reach its own logic". A `session/not-found` is a **pass**: the gateway matched the descriptor,
 * decoded the request, resolved the lookup and only then found nothing. That is the whole contract
 * this client depends on.
 *
 * Nothing here mutates anything real. Calls that would (delete a workspace, unset a credential,
 * remove a preset) are made against identities that cannot exist, so they get as far as the
 * descriptor check and no further. The two that must be real — creating a workspace and a session —
 * happen in a disposable `DSH_HOME` that is deleted with the harness.
 */
class EndpointCatalogueConformanceTest {

    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

    /** Failures that mean the harness did not understand us, as opposed to disagreeing with us. */
    private val misunderstood = setOf(
        "gateway/arguments-invalid",
        "gateway/input-invalid",
        "gateway/method-unavailable",
        "gateway/service-unavailable",
        "gateway/signature-invalid",
        "gateway/ambiguous-endpoint",
        "gateway/binding-invalid",
        "gateway/definition-invalid",
        "gateway/invocation-unavailable",
        // The client mints this one from a 404: no route claimed the path at all.
        "capability-unavailable",
    )

    /*
     * `gateway/input-invalid` is in that set on purpose, and it earns its place.
     *
     * It fires when a `request` object passes the key check and then fails its codec — so it
     * catches a wrong *vocabulary* as well as a wrong shape. The first run of this test tripped it
     * by sending `rating = "up"`, which is not one of the two words the harness accepts. That was
     * a defect in the test rather than in the client, but the same signal is exactly what a client
     * sending a stale enum value would produce, and nothing else in the repository would notice.
     */

    private val failures = mutableListOf<String>()

    @Before
    fun boot() {
        assumeTrue("no built harness checkout; see HarnessProcess", HarnessProcess.available())
        harness = HarnessProcess.start()
        client = HarnessClient(harness)
    }

    @After
    fun stop() {
        if (this::client.isInitialized) client.close()
        if (this::harness.isInitialized) harness.close()
    }

    /**
     * Record one endpoint's verdict instead of failing at the first one.
     *
     * A single assertion per call would stop at the earliest break and hide the rest, and the
     * useful output of this test is the complete list — if a release renamed three parameters, the
     * person fixing it wants all three now.
     */
    private fun check(endpoint: String, result: RpcResult<*>) {
        val error: RpcError = when (result) {
            is RpcResult.Ok -> return
            is RpcResult.Err -> result.error
        }
        if (error.code in misunderstood) {
            failures += "$endpoint -> ${error.code}: ${error.message}"
        }
    }

    /**
     * The same verdict for a stream Remote: open it, and wait for its first item or its end.
     *
     * A stream that does not understand its arguments fails its generation with the same gateway
     * codes a unary call gets, so the first signal is enough. A stream that stays open without
     * sending anything is a pass too: it was accepted, and some streams wait for news.
     */
    private suspend fun checkStream(endpoint: String, args: JsonObject): JsonElement? {
        val stream = client.mux.open(endpoint, args)
        return try {
            withTimeoutOrNull(STREAM_BUDGET_MS) { stream.receive() }
        } catch (e: RemoteStreamException) {
            if (e.error.code in misunderstood) failures += "$endpoint (stream) -> ${e.error.code}: ${e.error.message}"
            null
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `the harness understands every call this client makes`() = runBlocking {
        val missing = "00000000-0000-4000-8000-000000000000"
        val ref = GoalRef(id = missing, revision = 1)

        // Read-only, no arguments: these prove the endpoint is composed and reachable.
        check("session/list", client.api.sessionList())
        check("session/modelCatalog", client.api.sessionModelCatalog())
        check("session/canOpenWorkspacePath", client.api.sessionCanOpenWorkspacePath())
        check("agentPresets/list", client.api.agentPresetList())
        check("permissionPresets/catalog", client.api.permissionCatalog())
        check("pluginInventory/list", client.api.pluginInventoryList())
        check("settings/describe", client.api.settingsDescribe())
        check("llm/listProviders", client.api.llmListProviders())
        check("llm/listConfigurableProviders", client.api.llmListConfigurableProviders())
        check("directoryPicker/list", client.api.hostListDirectory())
        check("session/search", client.api.sessionSearch("conformance"))
        check("credentials/describe", client.api.credentialsDescribe(listOf("DEEPSEEK_API_KEY")))

        // Real creations, in a disposable home: a session id the rest of the calls can use.
        val workspace = client.api.workspaceCreate(WorkspaceCreateRequest(path = harness.workspacePath))
        check("workspace/create", workspace)
        val created = client.api.sessionCreate(SessionCreateRequest(cwd = harness.workspacePath))
        check("session/create", created)
        val sessionId = (created as? RpcResult.Ok)?.value?.sessionId
            ?: error("could not create a session to address the rest of the surface with")

        // Session-addressed reads.
        check("commands/list", client.api.commandsList(sessionId))
        check("skills/list", client.api.skillList(SkillListRequest(sessionId)))
        check("fileReferences/list", client.api.fileReferencesList(sessionId, "R"))
        check("messageFeedback/list", client.api.messageFeedbackList(sessionId))
        check("terminal/environment", client.api.terminalEnvironment(sessionId))
        check("terminal/shells", client.api.terminalShells(sessionId))
        check("terminal/list", client.api.terminalList(sessionId))
        check("workspaceFiles/list", client.api.workspaceFileList(sessionId, "."))
        check("workspaceFiles/stat", client.api.workspaceFileStat(sessionId, "."))
        check("workspaceFiles/read", client.api.workspaceFileRead(sessionId, "missing.txt"))
        // `readBytes` in each of the three shapes the file panel asks for: a whole file, a window,
        // and a file named relative to another (harness 0.1.7 folded `readAll`/`readRelated` in).
        check("workspaceFiles/readBytes", client.api.workspaceFileReadBytes(sessionId, "missing.txt"))
        check(
            "workspaceFiles/readBytes (range)",
            client.api.workspaceFileReadBytes(sessionId, "missing.txt", WorkspaceByteReadOptions(range = WorkspaceByteRange())),
        )
        check(
            "workspaceFiles/readBytes (baseFile)",
            client.api.workspaceFileReadBytes(sessionId, "b.png", WorkspaceByteReadOptions(baseFile = "a.html")),
        )

        // Session-addressed writes, aimed at things that do not exist.
        check("session/rename", client.api.sessionRename(SessionRenameRequest(sessionId, "conformance")))
        check("session/cancel", client.api.sessionCancel(SessionCancelRequest(sessionId)))
        check("session/fork", client.api.sessionFork(SessionForkRequest(missing)))
        check("session/fork (atSeq)", client.api.sessionFork(SessionForkRequest(missing, atSeq = 1)))
        check("job/kill", client.api.jobKill(sessionId, "bash-999"))
        // From 0.2.2 the site takes the whole durable image reference, not just its id; a made-up
        // reference is enough here, because this test only proves the arguments the client sends
        // are the ones the host method declares.
        check(
            "session/attachment",
            client.api.sessionAttachment(
                SessionAttachmentRequest(
                    sessionId,
                    ImageAttachmentRef(
                        attachmentId = missing,
                        mediaType = "image/png",
                        bytes = 1,
                        width = 1,
                        height = 1,
                    ),
                ),
            ),
        )
        check(
            "session/selectModel",
            client.api.sessionSelectModel(
                SessionSelectModelRequest(sessionId, provider = "deepseek", model = "no-such-model"),
            ),
        )
        check(
            "messageFeedback/put",
            client.api.messageFeedbackPut(
                MessageFeedbackPutRequest(sessionId, messageId = missing, rating = "positive", ifVersion = null),
            ),
        )
        check(
            "messageFeedback/delete",
            client.api.messageFeedbackDelete(
                MessageFeedbackDeleteRequest(sessionId, messageId = missing, ifVersion = "1"),
            ),
        )
        check("terminal/create", client.api.terminalCreate(sessionId, TerminalCreateRequest(id = missing, cols = 80, rows = 24)))
        // Input and resize ride the `terminal/attach` uplink from 0.2.1-alpha.3; see
        // TerminalAttachConformanceTest, which types into a real terminal.
        check("terminal/rename", client.api.terminalRename(sessionId, missing, "t"))
        check("terminal/close", client.api.terminalClose(sessionId, missing))

        // Goals: the whole verb set, against a ref that was never created.
        check("goals/create", client.api.goalCreate(sessionId, buildJsonObject { put("objective", "conformance") }))
        check("goals/edit", client.api.goalEdit(sessionId, ref, buildJsonObject { put("objective", "x") }))
        check("goals/pause", client.api.goalPause(sessionId, ref))
        check("goals/resume", client.api.goalResume(sessionId, ref))
        check("goals/complete", client.api.goalComplete(sessionId, ref))
        check("goals/clear", client.api.goalClear(sessionId, ref))

        // Presets and workspaces, aimed at identities that cannot exist.
        check("agentPresets/select", client.api.agentPresetSelect(sessionId, "no-such-preset"))
        check("agentPresets/read", client.api.agentPresetRead("no-such-preset"))
        check("workspace/rename", client.api.workspaceRename(WorkspaceRenameRequest(missing, "x")))
        check("workspace/archiveSession", client.api.workspaceArchiveSession(WorkspaceArchiveSessionRequest(missing)))
        check(
            "workspace/archiveSession (stopActivity)",
            client.api.workspaceArchiveSession(WorkspaceArchiveSessionRequest(missing, stopActivity = true)),
        )
        check("workspace/pinSession", client.api.workspacePinSession(missing))
        check("workspace/unpinSession", client.api.workspaceUnpinSession(missing))
        check("workspace/delete", client.api.workspaceDelete(WorkspaceDeleteRequest(missing)))
        check("workspace/unarchiveSession", client.api.workspaceUnarchiveSession(missing))
        check("credentials/set", client.api.credentialsSet("CONFORMANCE_ONLY", "value"))
        check("credentials/unset", client.api.credentialsUnset("CONFORMANCE_ONLY"))

        // The streams the app opens beyond `$events` and `session/control`, which have tests of
        // their own. `job/list` and the file watch are harness 0.1.7 shapes.
        client.mux.start()
        withTimeoutOrNull(STREAM_BUDGET_MS) { client.mux.awaitOpen() }
            ?: error("the mux did not open")
        val jobs = checkStream(
            "job/list",
            buildJsonObject { put("request", WireJson.encodeToJsonElement(JobListRequest.serializer(), JobListRequest(sessionId))) },
        )
        if (jobs != null) {
            val frame = WireJson.decodeFromJsonElement(JobListFrame.serializer(), jobs)
            assertEquals("job/list opens with a whole-set rows frame", "rows", frame.type)
        }
        checkStream(
            "job/follow",
            buildJsonObject {
                put("request", WireJson.encodeToJsonElement(JobFollowRequest.serializer(), JobFollowRequest("bash-999", sessionId)))
            },
        )
        checkStream(
            "workspaceFiles/changes",
            buildJsonObject {
                put("workspaceFileScopeId", JsonPrimitive(sessionId))
                put("path", JsonPrimitive("."))
            },
        )
        checkStream(
            "workspace/follow",
            JsonObject(emptyMap()),
        )
        checkStream("terminal/attach", client.api.terminalAttachArgs(sessionId, missing))

        assertEquals(
            "the harness did not understand these calls:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }

    /**
     * A whole file comes back as its exact bytes, which on harness 0.1.7 means through the
     * multipart answer rather than base64 in JSON. Every byte value is written so an encoding
     * mistake anywhere in the path (a charset, a text decode, a truncation at a zero byte) shows.
     */
    @Test
    fun `readBytes returns a file's exact bytes`() = runBlocking {
        val bytes = ByteArray(512) { (it % 256).toByte() }
        java.io.File(harness.workspacePath, "blob.bin").writeBytes(bytes)
        java.io.File(harness.workspacePath, "page.html").writeText("<img src=\"blob.bin\">")
        val created = client.api.sessionCreate(SessionCreateRequest(cwd = harness.workspacePath))
        val sessionId = (created as? RpcResult.Ok)?.value?.sessionId ?: error("no session: $created")

        val whole = client.api.workspaceFileReadBytes(sessionId, "blob.bin")
        assertTrue("whole-file read failed: $whole", whole is RpcResult.Ok)
        assertArrayEquals(bytes, (whole as RpcResult.Ok).value.data)
        assertTrue(whole.value.eof)

        val window = client.api.workspaceFileReadBytes(
            sessionId, "blob.bin", WorkspaceByteReadOptions(range = WorkspaceByteRange(offset = 256, length = 16)),
        )
        assertTrue("windowed read failed: $window", window is RpcResult.Ok)
        assertArrayEquals(bytes.copyOfRange(256, 272), (window as RpcResult.Ok).value.data)

        val related = client.api.workspaceFileReadBytes(sessionId, "blob.bin", WorkspaceByteReadOptions(baseFile = "page.html"))
        assertTrue("relative read failed: $related", related is RpcResult.Ok)
        assertArrayEquals(bytes, (related as RpcResult.Ok).value.data)
    }

    private companion object {
        const val STREAM_BUDGET_MS = 5_000L
    }
}
