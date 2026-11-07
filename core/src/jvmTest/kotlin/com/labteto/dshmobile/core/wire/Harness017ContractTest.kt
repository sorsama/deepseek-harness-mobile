package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/**
 * Contract examples independently transcribed from the Remote signatures at `dsh-v0.1.7-rc.2`
 * (`477b4f4`), plus the 0.1.6 spellings the client still falls back to. These shapes are unchanged
 * at `dsh-v0.2.0-rc.1`.
 */
class Harness017ContractTest {
    private class Transport : RpcTransport {
        var path = ""
        var args: JsonObject = JsonObject(emptyMap())
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var value = "{}"
        /** When set, answers every call from here instead of [value]. */
        var answer: ((path: String, rpcId: JsonElement?) -> RpcHttpResponse)? = null
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            val request = Json.parseToJsonElement(body).jsonObject
            args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            calls += path to args
            answer?.let { return it(path, request["rpcId"]) }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":${request["rpcId"]},"result":{"ok":true,"value":$value}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    /** The body undici writes for `new Response(formData)`: blob parts carry a filename. */
    private fun multipart(rpcId: JsonElement?, value: String, bytes: ByteArray): RpcHttpResponse {
        val boundary = "----formdata-undici-0123456789"
        val metadata = """{"type":"server-response","rpcId":$rpcId,"result":{"ok":true,"value":$value},""" +
            """"attachments":[{"path":["data"],"codec":"bytes","part":"bytes-0"}]}"""
        val out = java.io.ByteArrayOutputStream()
        fun line(text: String) = out.write((text + "\r\n").toByteArray())
        line("--$boundary")
        line("""Content-Disposition: form-data; name="bytes-0"; filename="blob"""")
        line("Content-Type: application/octet-stream")
        line("")
        out.write(bytes); line("")
        line("--$boundary")
        line("""Content-Disposition: form-data; name="metadata"""")
        line("")
        line(metadata)
        out.write("--$boundary--\r\n".toByteArray())
        return RpcHttpResponse(200, "", "multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun refusal(rpcId: JsonElement?, code: String) = RpcHttpResponse(
        200,
        """{"type":"server-response","rpcId":$rpcId,"result":{"ok":false,"error":{"code":"$code","message":"refused","details":{}}}}""",
    )

    @Test fun `permission catalog and the rc2 roster decode without legacy fields`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"options":[{"value":"auto","name":"Auto review"}],"defaultOptions":[],"defaultPreset":"auto"}"""
        val result = api.permissionCatalog() as RpcResult.Ok
        assertEquals("auto", result.value.options.single().value)
        assertEquals("/api/permissionPresets/catalog", t.path)
        t.value = """{"presets":[{"id":"standard","isDefault":true}]}"""
        val roster = (api.agentPresetList() as RpcResult.Ok).value
        assertEquals("standard", roster.presets.single().id)
        assertTrue(roster.modeSelectionEnabled)
    }

    @Test fun `file operations use header lookup identity and explicit ranges`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"absolutePath":"/work/a.kt","version":"v1","offset":1,"text":"abc","lines":1,"eof":true}"""
        assertTrue(api.workspaceFileRead("cold-child", "a.kt") is RpcResult.Ok)
        assertEquals(setOf("workspaceFileScopeId", "path", "range"), t.args.keys)
        assertEquals("cold-child", t.args["workspaceFileScopeId"]?.jsonPrimitive?.content)
        assertEquals(1, t.args["range"]?.jsonObject?.get("offset")?.jsonPrimitive?.int)
    }

    @Test fun `readBytes sends options and reads its bytes from the multipart answer`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        val bytes = ByteArray(300) { it.toByte() }
        t.answer = { _, rpcId -> multipart(rpcId, """{"absolutePath":"/w/a.png","version":"v1","bytes":300,"offset":0,"data":null,"eof":true}""", bytes) }

        val whole = api.workspaceFileReadBytes("s", "a.png") as RpcResult.Ok
        assertEquals("/api/workspaceFiles/readBytes", t.path)
        assertEquals(setOf("workspaceFileScopeId", "path", "options"), t.args.keys)
        assertEquals(JsonObject(emptyMap()), t.args["options"])
        assertArrayEquals(bytes, whole.value.data)
        assertEquals(300L, whole.value.bytes)

        api.workspaceFileReadBytes("s", "img/b.png", WorkspaceByteReadOptions(baseFile = "docs/index.html"))
        assertEquals("img/b.png", t.args["path"]?.jsonPrimitive?.content)
        assertEquals("docs/index.html", t.args["options"]?.jsonObject?.get("baseFile")?.jsonPrimitive?.content)

        api.workspaceFileReadBytes("s", "a.png", WorkspaceByteReadOptions(range = WorkspaceByteRange(offset = 10, length = 20)))
        assertEquals(20, t.args["options"]?.jsonObject?.get("range")?.jsonObject?.get("length")?.jsonPrimitive?.int)
    }

    @Test fun `a 0_1_6 host gets the read spelled its way and base64 is decoded`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        val legacy = """{"absolutePath":"/w/a.png","version":"v1","bytes":3,"offset":0,"data":"AQID","eof":true}"""
        t.answer = { path, rpcId ->
            if (t.args.containsKey("options")) refusal(rpcId, "gateway/arguments-invalid")
            else RpcHttpResponse(200, """{"type":"server-response","rpcId":$rpcId,"result":{"ok":true,"value":$legacy}}""")
        }

        val whole = api.workspaceFileReadBytes("s", "a.png") as RpcResult.Ok
        assertEquals("/api/workspaceFiles/readAll", t.path)
        assertArrayEquals(byteArrayOf(1, 2, 3), whole.value.data)

        api.workspaceFileReadBytes("s", "b.png", WorkspaceByteReadOptions(baseFile = "index.html"))
        assertEquals("/api/workspaceFiles/readRelated", t.path)
        assertEquals("index.html", t.args["path"]?.jsonPrimitive?.content)
        assertEquals("b.png", t.args["relativePath"]?.jsonPrimitive?.content)

        api.workspaceFileReadBytes("s", "a.png", WorkspaceByteReadOptions(range = WorkspaceByteRange()))
        assertEquals("/api/workspaceFiles/readBytes", t.path)
        assertEquals(setOf("workspaceFileScopeId", "path", "range"), t.args.keys)
    }

    @Test fun `any other refusal is reported rather than retried`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.answer = { _, rpcId -> refusal(rpcId, "workspace-file/not-found") }
        val result = api.workspaceFileReadBytes("s", "gone.png") as RpcResult.Err
        assertEquals("workspace-file/not-found", result.error.code)
        assertEquals(1, t.calls.size)
    }

    @Test fun `archive omits stopActivity until asked, pins and kills take request objects`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"archivedSessionIds":["s"]}"""
        api.workspaceArchiveSession(WorkspaceArchiveSessionRequest("s"))
        assertEquals(setOf("sessionId"), t.args.getValue("request").jsonObject.keys)
        api.workspaceArchiveSession(WorkspaceArchiveSessionRequest("s", stopActivity = true))
        assertEquals(true, t.args.getValue("request").jsonObject["stopActivity"]?.jsonPrimitive?.boolean)

        t.value = """{"pinnedSessionIds":["s","t"]}"""
        val pinned = api.workspacePinSession("s") as RpcResult.Ok
        assertEquals("/api/workspace/pinSession", t.path)
        assertEquals("s", t.args.getValue("request").jsonObject["sessionId"]?.jsonPrimitive?.content)
        assertEquals(listOf("s", "t"), pinned.value.pinnedSessionIds)
        api.workspaceUnpinSession("s")
        assertEquals("/api/workspace/unpinSession", t.path)

        t.value = """{"outcome":"requested"}"""
        val killed = api.jobKill("s", "bash-1") as RpcResult.Ok
        assertEquals("/api/job/kill", t.path)
        assertEquals(setOf("sessionId", "jobId"), t.args.getValue("request").jsonObject.keys)
        assertEquals("requested", killed.value.outcome)
    }

    @Test fun `feedback creation sends explicit null version and preserves nested conflict`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"ok":false,"error":{"code":"version-conflict","current":null}}"""
        val result = api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "positive", null)) as RpcResult.Ok
        assertFalse(result.value.ok)
        assertEquals("version-conflict", result.value.error?.code)
        assertTrue(t.args.getValue("request").jsonObject.containsKey("ifVersion"))
        assertEquals(JsonNull, t.args.getValue("request").jsonObject["ifVersion"])
    }

    @Test fun `terminal writes carry controller identity and list uses session identity`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = "null"
        api.terminalWrite("s", "terminal-1", "controller-1", "pwd\r")
        assertEquals(setOf("agentId", "id", "attachmentId", "data"), t.args.keys)
        assertEquals("pwd\r", t.args["data"]?.jsonPrimitive?.content)
        t.value = "[]"; assertTrue(api.terminalList("s") is RpcResult.Ok)
        assertEquals(setOf("sessionId"), t.args.keys)
    }

    @Test fun `subagent messages encode delivery and multiple photos stay in one prompt`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"messageId":"m"}"""
        api.subagentPrompt(SubagentPromptRequest(requestId = "r", parentSessionId = "p", childSessionId = "c", delivery = "steer"))
        assertEquals("steer", t.args["request"]?.jsonObject?.get("delivery")?.jsonPrimitive?.content)
        t.value = """{"accepted":true}"""
        api.sessionPrompt(SessionPromptRequest(requestId = "r2", sessionId = "s", mode = "queue", content =
            listOf(PromptContentPart.Text("Site photos")) + List(3) { PromptContentPart.Image("image/png", "AA==") }))
        assertEquals("/api/session/prompt", t.path)
        assertEquals(4, t.args["request"]?.jsonObject?.get("content")?.jsonArray?.size)
    }
}
