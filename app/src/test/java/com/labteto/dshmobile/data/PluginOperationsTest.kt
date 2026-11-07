package com.labteto.dshmobile.data

import android.content.SharedPreferences
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.io.Source
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginOperationsTest {
    @Test fun `busy installs only block mutations on their own host`() = runTest {
        val transport = Transport().apply { gate = CompletableDeferred() }
        val owner = PluginOperations(backgroundScope, { DshApiClient(transport) })
        owner.install("host-a", "first", null); runCurrent()
        owner.changed("host-b", applied)
        assertEquals("host-b", owner.state.value.host)
        owner.install("host-b", "second", null); runCurrent()
        owner.install("host-a", "duplicate", null)
        owner.install("host-b", "duplicate", null); runCurrent()
        assertEquals(setOf("host-a", "host-b"), owner.pending.value.map { it.host }.toSet())
        assertEquals(2, transport.calls.size)
        assertTrue(owner.pending.value.all { it.busy })
        assertEquals(2, owner.pending.value.map { it.requestId }.toSet().size)
        transport.gate!!.complete(Unit); runCurrent()
        assertEquals(2, owner.pending.value.size)
        assertTrue(owner.pending.value.all { it.unknown && !it.busy })
    }

    private val applied = PluginChangeResult(true, "applied", "install", "fixture")
    private class Transport : RpcTransport {
        var failInstall = true
        var gate: CompletableDeferred<Unit>? = null
        var waitResult: JsonElement = JsonNull
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val request = WireJson.parseToJsonElement(body).jsonObject
            calls += path to request
            gate?.await()
            if (path.endsWith("/installBundle") && failInstall) throw RpcTransportException(0, "Lost receipt")
            val value = when {
                path.endsWith("/waitForInstall") -> waitResult
                path.endsWith("/cancelInstall") -> buildJsonObject { put("status", "cancelling") }
                else -> WireJson.parseToJsonElement("""{"changed":true,"application":"applied","stage":"install","target":"fixture"}""")
            }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":${request["rpcId"]},"result":{"ok":true,"value":$value}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: Source, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    /** Only the string persistence operations used by PluginOperations; no Android runtime needed. */
    private class Preferences {
        val values = mutableMapOf<String, String>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "apply" -> null
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        val store = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun `new installs and successful mutations retain old unknown requests after restart`() = runTest {
        val transport = Transport()
        val api = DshApiClient(transport)
        val preferences = Preferences()
        val owner = PluginOperations(backgroundScope, { api }, preferences.store)
        owner.install("host/team", "first", "https://registry.example"); runCurrent()
        val first = owner.pending.value.single()
        assertTrue(first.unknown)
        assertNull(owner.state.value.result)
        owner.changed("host/team", applied)
        assertEquals(listOf(first), owner.pending.value)
        transport.failInstall = false
        owner.install("host/team", "second", null); runCurrent()
        assertEquals(listOf(first), owner.pending.value)
        assertEquals("applied", owner.state.value.result?.application)
        val restored = PluginOperations(backgroundScope, { api }, preferences.store)
        assertEquals(first.requestId, restored.pending.value.single().requestId)
        assertEquals(first.host, restored.pending.value.single().host)
        assertEquals("first", restored.pending.value.single().subject)
        assertEquals("https://registry.example", restored.pending.value.single().registry)
        assertTrue(restored.pending.value.single().unknown)
    }

    @Test fun `null wait receipt stays unknown until explicit dismissal without an invented result`() = runTest {
        val transport = Transport()
        val api = DshApiClient(transport)
        val preferences = Preferences()
        preferences.values["host"] = "host/team"
        preferences.values["requestId"] = "old-install"
        val owner = PluginOperations(backgroundScope, { api }, preferences.store)
        owner.resume("host/team"); runCurrent()
        assertTrue(owner.pending.value.single().unknown)
        assertTrue(transport.calls.single().first.endsWith("/waitForInstall"))
        assertTrue(transport.calls.single().second.toString().contains("old-install"))
        owner.dismissUnknown("host/root", "old-install")
        assertEquals(1, owner.pending.value.size)
        owner.dismissUnknown("host/team", "old-install")
        assertTrue(owner.pending.value.isEmpty())
        assertNull(owner.state.value.result)
        assertFalse(owner.state.value.unknown)
        assertTrue(PluginOperations(backgroundScope, { api }, preferences.store).pending.value.isEmpty())
    }

    @Test fun `busy install guards mutations and dismissal while cancellation only updates progress`() = runTest {
        val transport = Transport()
        val api = DshApiClient(transport)
        val owner = PluginOperations(backgroundScope, { api })
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        owner.install("host", "first", null); runCurrent()
        val id = owner.pending.value.single().requestId!!
        owner.install("host", "second", null)
        owner.changed("host", applied)
        owner.dismissUnknown("host", id)
        assertEquals(1, owner.pending.value.size)
        assertEquals(1, transport.calls.size)
        assertNull(owner.state.value.result)
        gate.complete(Unit); runCurrent()
        transport.gate = null
        owner.cancel("host", id); runCurrent()
        assertTrue(owner.pending.value.single().unknown)
        assertEquals("cancelling", owner.pending.value.single().phase)
        assertNull(owner.state.value.result)
    }

    @Test fun `events and recovered results correlate by both host and request id`() = runTest {
        val transport = Transport()
        val api = DshApiClient(transport)
        val preferences = Preferences()
        preferences.values["pending"] = """[{"host":"root","requestId":"same"},{"host":"prefix","requestId":"same"}]"""
        val owner = PluginOperations(backgroundScope, { api }, preferences.store)
        owner.event("root", "plugin-manager/install-log", listOf(buildJsonObject { put("requestId", "same"); put("text", "root log") }))
        owner.event("prefix", "plugin-manager/install-log", listOf(buildJsonObject { put("requestId", "other"); put("text", "wrong log") }))
        assertEquals("root log", owner.pending.value.first { it.host == "root" }.log)
        assertEquals("", owner.pending.value.first { it.host == "prefix" }.log)
        transport.waitResult = WireJson.encodeToJsonElement(PluginChangeResult.serializer(), applied)
        owner.resume("root"); runCurrent()
        assertEquals("prefix", owner.pending.value.single().host)
        assertTrue(owner.pending.value.single().unknown)
        assertEquals("root", owner.state.value.host)
        assertEquals(applied, owner.state.value.result)
        assertEquals("prefix", PluginOperations(backgroundScope, { api }, preferences.store).pending.value.single().host)
    }
}
