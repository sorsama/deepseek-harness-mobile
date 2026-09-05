package com.labteto.dshmobile.core.wire

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typert Remote gateway shares the ordinary client-request envelope: `{"args": …}` is the
 * *payload*, not the body, and the envelope's `method` must equal the path. Posting a bare body —
 * which is what this client used to do — is rejected before it reaches a handler, so these tests
 * pin the shape rather than merely the happy path.
 */
class DshApiClientRemoteTest {

    private class RecordingTransport(
        private val responder: (path: String, body: String) -> RpcHttpResponse,
    ) : RpcTransport {
        var lastPath: String? = null
        var lastBody: String? = null
        var lastDownloadPath: String? = null
        var downloadBytes: ByteArray = ByteArray(0)
        var lastUploadPath: String? = null
        var lastUploadContentType: String? = null
        var lastUploadLength: Long = -1
        var lastUploadBytes: ByteArray = ByteArray(0)
        var uploadResponder: (() -> RpcHttpResponse)? = null

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            lastPath = path
            lastBody = body
            return responder(path, body)
        }

        override suspend fun <T> download(
            path: String,
            consume: (String?, String?, InputStream) -> T,
        ): T {
            lastDownloadPath = path
            return consume("application/zip", "attachment; filename=\"x.zip\"", ByteArrayInputStream(downloadBytes))
        }

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse {
            lastUploadPath = path
            lastUploadContentType = contentType
            lastUploadLength = contentLength
            lastUploadBytes = body.readBytes()
            onProgress?.invoke(lastUploadBytes.size.toLong())
            return uploadResponder?.invoke() ?: error("no upload responder")
        }
    }

    private fun client(transport: RpcTransport) = DshApiClient(transport = transport)

    private fun ok(rpcId: String, value: String) = RpcHttpResponse(
        status = 200,
        body = """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}""",
    )

    @Test
    fun `commands list posts a client-request envelope with agentId in args`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(
                rpcId,
                """[{"name":"permission","description":"Switch","input":{"hint":"<preset>"}},""" +
                    """{"name":"goal","description":"Set","input":{"hint":"<objective>","attachments":true}}]""",
            )
        }
        val result = client(transport).commandsList("session-1")

        assertEquals("/api/commands/list", transport.lastPath)
        val envelope = Json.parseToJsonElement(transport.lastBody!!).jsonObject
        assertEquals("client-request", envelope["type"]!!.jsonPrimitive.content)
        assertEquals("commands/list", envelope["method"]!!.jsonPrimitive.content)
        assertTrue(envelope["rpcId"]!!.jsonPrimitive.content.isNotBlank())

        // The gateway matches arg names against the descriptor exactly: the session-addressed
        // parameter is `agentId`, and an unexpected key fails the whole call.
        val args = envelope["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId"), args.keys)
        assertEquals("session-1", args["agentId"]!!.jsonPrimitive.content)

        val commands = (result as RpcResult.Ok).value
        assertEquals(2, commands.size)
        assertEquals("permission", commands.first().name)
        assertEquals("<preset>", commands.first().input?.hint)
        // `input.attachments` is only ever sent as true, so a descriptor without the key is a
        // command that takes none — which is every command but `/goal` and `/plan`.
        assertFalse(commands.first().acceptsAttachments)
        assertTrue(commands.last().acceptsAttachments)
    }

    @Test
    fun `a 0-1-2 catalog's images flag is not read as attachments`() = runTest {
        // The key was renamed upstream; a host still sending the old one declares nothing this
        // client understands, and the composer must refuse rather than send attachments a
        // 0.1.3 executor would not be told about.
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """[{"name":"goal","description":"Set","input":{"hint":"<objective>","images":true}}]""")
        }
        val commands = (client(transport).commandsList("session-1") as RpcResult.Ok).value
        assertFalse(commands.single().acceptsAttachments)
    }

    @Test
    fun `a command is always sent the three-argument shape`() = runTest {
        // The third argument is named after the host method's own parameter, because the gateway
        // matches args by parameter name and refuses a missing key as readily as an unexpected
        // one. 0.1.3 renamed it from `images` to `submittedAttachments` when files joined.
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, "{}")
        }
        client(transport).commandsExecute("session-2", "/compact")

        assertEquals("/api/commands/execute", transport.lastPath)
        val args = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId", "line", "submittedAttachments"), args.keys)
        assertEquals(0, args["submittedAttachments"]!!.jsonArray.size)
    }

    @Test
    fun `a command carries the composer's images and files with their discriminators`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, "{}")
        }
        client(transport).commandsExecute(
            "session-2",
            "/goal ship it",
            listOf(
                EncodedImageAttachment("image/png", "AAAA").asSubmit(),
                CommandSubmitAttachment.File(receiptId = "receipt-9"),
            ),
        )

        val args = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        val attachments = args["submittedAttachments"]!!.jsonArray
        val image = attachments[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", image["mediaType"]!!.jsonPrimitive.content)
        assertEquals("AAAA", image["data"]!!.jsonPrimitive.content)
        // explicitNulls = false: an absent display name is an absent key, not a null one.
        assertEquals(setOf("type", "mediaType", "data"), image.keys)
        val file = attachments[1].jsonObject
        assertEquals("file", file["type"]!!.jsonPrimitive.content)
        assertEquals("receipt-9", file["receiptId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 404 becomes capability-unavailable rather than a connection failure`() = runTest {
        val transport = RecordingTransport { _, _ ->
            throw RpcTransportException(404, "carrier returned HTTP 404")
        }
        val result = client(transport).commandsList("session-3")

        // A harness that composes no command registry answers 404. That is a missing optional
        // capability, not a broken link, and callers rely on the distinction to degrade the menu
        // instead of raising a failure banner over a healthy session.
        assertEquals("capability-unavailable", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `a 403 becomes forbidden`() = runTest {
        val transport = RecordingTransport { _, _ ->
            throw RpcTransportException(403, "harness trust fence rejected the request (HTTP 403)")
        }
        val result = client(transport).commandsList("session-4")
        assertEquals("forbidden", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `malformed command rows drop out instead of emptying the catalog`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """[{"name":"plan"},{"unexpected":true},{"name":"compact","future":42}]""")
        }
        val result = client(transport).commandsList("session-5")

        val commands = (result as RpcResult.Ok).value
        assertEquals(listOf("plan", "compact"), commands.map { it.name })
    }

    @Test
    fun `a prompt carries the host's required requestId`() = runTest {
        // Harness 0.1.2 made `requestId` a required field of the prompt request and validates the
        // whole `request` object against a strict codec, so a client that omits it cannot send a
        // message at all: the gateway answers `wire field "request" failed boundary validation`
        // before any agent sees the text. Nothing in this repo sent one until this test existed.
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """{"accepted":true}""")
        }
        val result = client(transport).sessionPrompt(
            SessionPromptRequest(
                requestId = "11111111-2222-3333-4444-555555555555",
                sessionId = "session-7",
                mode = "queue",
                content = listOf(PromptContentPart.Text("hello"), PromptContentPart.File("receipt-1")),
                clientTimeZone = "Asia/Bangkok",
            ),
        )

        assertEquals("/api/session/prompt", transport.lastPath)
        val request = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals(
            setOf("requestId", "sessionId", "mode", "content", "clientTimeZone"),
            request.keys,
        )
        assertEquals("11111111-2222-3333-4444-555555555555", request["requestId"]!!.jsonPrimitive.content)
        assertEquals("file", request["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue((result as RpcResult.Ok).value.accepted)
    }

    @Test
    fun `a subagent prompt carries one too`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """{"messageId":"m1"}""")
        }
        client(transport).subagentPrompt(
            SubagentPromptRequest(
                requestId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                parentSessionId = "parent-1",
                childSessionId = "child-1",
                content = listOf(PromptContentPart.Text("carry on")),
                clientTimeZone = "Asia/Bangkok",
            ),
        )

        val request = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", request["requestId"]!!.jsonPrimitive.content)
        // The child prompt shares the session prompt's identity vocabulary, so the discriminator
        // the host declares required rides along beside it rather than being assumed.
        assertEquals("continuable", request["mode"]!!.jsonPrimitive.content)
        // And the same prompt-part vocabulary: a text part carries its `type`.
        assertEquals("text", request["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `each message is minted its own identity`() {
        // Per message, not per call: two prompts must never claim to be the same message, or the
        // host would persist one identity on two of them.
        val ids = List(64) { newPromptRequestId() }
        assertEquals(64, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `session export streams from a GET with the session id in the query`() = runTest {
        val transport = RecordingTransport { _, _ -> error("export must not POST") }
        transport.downloadBytes = "PK-zip-bytes".toByteArray()

        val result = client(transport).sessionExport("session-6", includeDescendants = true) { _, _, body ->
            body.readBytes().decodeToString()
        }

        assertEquals(
            "/api/session.export?sessionId=session-6&includeDescendants=true",
            transport.lastDownloadPath,
        )
        assertEquals("PK-zip-bytes", (result as RpcResult.Ok).value)
        assertNull(transport.lastPath)
    }

    @Test
    fun `a binary upload streams octets to the upload route and reads a bare result`() = runTest {
        // Harness 0.1.3's one non-envelope write: the route answers 200 with `{ok, value}` and
        // no server-response wrapper, and the bytes go up verbatim rather than base64 in JSON.
        val transport = RecordingTransport { _, _ -> error("upload must not POST an envelope") }
        transport.uploadResponder = {
            RpcHttpResponse(
                200,
                """{"ok":true,"value":{"receiptId":"r-1","file":{"attachmentId":"sha256:abc","name":"notes.txt","bytes":5}}}""",
            )
        }
        var progressed = 0L
        val result = client(transport).uploadFileBinary(
            sessionId = "session-8",
            name = "my notes.txt",
            contentLength = 5,
            body = ByteArrayInputStream("hello".toByteArray()),
            onProgress = { progressed = it },
        )

        assertEquals("/api/session/uploadFileBinary?sessionId=session-8&name=my%20notes.txt", transport.lastUploadPath)
        assertEquals("application/octet-stream", transport.lastUploadContentType)
        assertEquals(5L, transport.lastUploadLength)
        assertEquals("hello", transport.lastUploadBytes.decodeToString())
        assertEquals(5L, progressed)
        assertEquals("r-1", (result as RpcResult.Ok).value.receiptId)
        assertEquals("notes.txt", result.value.file.name)
        assertNull(transport.lastPath)
    }

    @Test
    fun `an upload the host refuses comes back as its business error`() = runTest {
        val transport = RecordingTransport { _, _ -> error("not used") }
        transport.uploadResponder = {
            RpcHttpResponse(
                200,
                """{"ok":false,"error":{"code":"session/attachment-invalid","message":"too big","details":{"reason":"FILE_TOO_LARGE"}}}""",
            )
        }
        val result = client(transport).uploadFileBinary("session-8", null, 1, ByteArrayInputStream(ByteArray(1)))
        assertEquals("session/attachment-invalid", (result as RpcResult.Err).error.code)
        assertEquals("/api/session/uploadFileBinary?sessionId=session-8", transport.lastUploadPath)
    }

    @Test
    fun `an upload route nobody claims is a missing capability, not a broken link`() = runTest {
        // A relay that does not proxy the raw-byte route, or a deployment that composes no
        // file-upload service, answers 404 — the caller's cue to fall back to the Remote form.
        val transport = RecordingTransport { _, _ -> error("not used") }
        transport.uploadResponder = { throw RpcTransportException(404, "carrier returned HTTP 404") }
        val result = client(transport).uploadFileBinary("session-8", null, 1, ByteArrayInputStream(ByteArray(1)))
        assertEquals("capability-unavailable", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `the encoded upload is a Remote call addressed to the session`() = runTest {
        val transport = RecordingTransport { _, body ->
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            ok(rpcId, """{"receiptId":"r-2","file":{"attachmentId":"sha256:def","name":"a.bin","bytes":3}}""")
        }
        val result = client(transport).fileUploadEncoded("session-9", EncodedFileUploadRequest("AAAA", "a.bin"))

        assertEquals("/api/fileUploads/upload", transport.lastPath)
        val args = Json.parseToJsonElement(transport.lastBody!!)
            .jsonObject["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId", "request"), args.keys)
        assertEquals("session-9", args["agentId"]!!.jsonPrimitive.content)
        assertEquals(setOf("data", "name"), args["request"]!!.jsonObject.keys)
        assertEquals("r-2", (result as RpcResult.Ok).value.receiptId)
    }
}
