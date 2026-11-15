package com.labteto.dshmobile.models

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.LlmProviderInfo
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.ModelSelection
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A harness in memory: the provider calls answer from its fields, and every call is recorded. */
private class FakeHarness : ModelsPort {
    val live = mutableListOf(LlmProviderInfo("deepseek-official", "DeepSeek"))
    val directory = mutableListOf(
        LlmConfigurableProvider("deepseek-official", "DeepSeek", "llm-deepseek", emptyList(), active = true),
        LlmConfigurableProvider("openai", "OpenAI", "llm-pi-ai", listOf("providers", "openai"), declared = false),
    )
    var deepseek: JsonObject = buildJsonObject { put("apiKeyEnv", "DEEPSEEK_API_KEY") }
    var piUser: JsonObject = JsonObject(emptyMap())
    var writable = true
    val stored = mutableMapOf<String, String>()
    val calls = mutableListOf<String>()
    /** Every string argument any call received, to prove where a key went. */
    val seenValues = mutableListOf<Pair<String, String>>()
    var refuseMutate: RpcError? = null
    var refuseSet: RpcError? = null
    var refuseDescribe: RpcError? = null
    var refuseProviders: RpcError? = null
    var discovered: RpcResult<List<LlmDiscoveredModel>> = RpcResult.Ok(emptyList())
    var lastDiscovery: LlmModelDiscoveryRequest? = null

    override suspend fun listProviders(): RpcResult<List<LlmProviderInfo>> {
        calls += "listProviders"
        return refuseProviders?.let { RpcResult.Err(it) } ?: RpcResult.Ok(live.toList())
    }

    override suspend fun listConfigurable() = RpcResult.Ok(directory.toList()).also { calls += "listConfigurable" }

    override suspend fun describeSettings(): RpcResult<SettingsDescribeValue> {
        calls += "describeSettings"
        val pi = SettingsNamespaceView(
            ns = "llm-pi-ai", schema = JsonObject(emptyMap()),
            value = buildJsonObject { put("providers", piUser) },
            base = JsonObject(emptyMap()), user = buildJsonObject { put("providers", piUser) },
            applies = "live", revision = 7,
        )
        val ds = SettingsNamespaceView(
            ns = "llm-deepseek", schema = JsonObject(emptyMap()), value = deepseek,
            base = JsonObject(emptyMap()), user = JsonObject(emptyMap()), applies = "live", revision = 3,
        )
        return RpcResult.Ok(SettingsDescribeValue(writable = writable, hasDocument = true, namespaces = listOf(ds, pi)))
    }

    override suspend fun describeCredentials(refs: List<String>): RpcResult<Map<String, CredentialInfo>> {
        calls += "describeCredentials:${refs.joinToString()}"
        refuseDescribe?.let { return RpcResult.Err(it) }
        return RpcResult.Ok(refs.associateWith { CredentialInfo(configured = it in stored, source = "store".takeIf { _ -> it in stored }, writable = true) })
    }

    override suspend fun modelCatalog() = RpcResult.Ok(ModelCatalog(default = ModelSelection("deepseek-official", "deepseek-v4-flash")))

    override suspend fun mutateSettings(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView> {
        calls += "mutate:$ns:${ops.joinToString { it.describe() }}@$expectedRevision"
        ops.forEach { if (it is SettingsPathOpView.Set) seenValues += "mutate" to it.value.toString() }
        refuseMutate?.let { return RpcResult.Err(it) }
        for (op in ops) when (op) {
            is SettingsPathOpView.Set -> if (ns == "llm-pi-ai") {
                val route = op.path[1]
                val current = (piUser[route] as? JsonObject) ?: JsonObject(emptyMap())
                val next = if (op.path.size == 2) op.value as JsonObject else JsonObject(current + (op.path[2] to op.value))
                piUser = JsonObject(piUser + (route to next))
                if (route !in live.map { it.id }) live += LlmProviderInfo(route, route)
            } else deepseek = JsonObject(deepseek + (op.path.last() to op.value))
            is SettingsPathOpView.Unset -> if (ns == "llm-pi-ai") {
                val route = op.path[1]
                if (op.path.size == 2) { piUser = JsonObject(piUser - route); live.removeAll { it.id == route } }
            } else deepseek = JsonObject(deepseek - op.path.last())
        }
        return RpcResult.Ok(SettingsNamespaceView(ns, JsonObject(emptyMap()), JsonObject(emptyMap()), applies = "live", revision = 8))
    }

    override suspend fun setCredential(ref: String, value: String): RpcResult<Unit> {
        calls += "set:$ref"
        seenValues += "set:$ref" to value
        refuseSet?.let { return RpcResult.Err(it) }
        stored[ref] = value
        return RpcResult.Ok(Unit)
    }

    override suspend fun unsetCredential(ref: String): RpcResult<Unit> {
        calls += "unset:$ref"
        stored.remove(ref)
        return RpcResult.Ok(Unit)
    }

    override suspend fun discoverModels(settingsNs: String, request: LlmModelDiscoveryRequest): RpcResult<List<LlmDiscoveredModel>> {
        calls += "discover:$settingsNs"
        lastDiscovery = request
        request.apiKey?.let { seenValues += "discover" to it }
        return discovered
    }
}

private fun SettingsPathOpView.describe(): String = when (this) {
    is SettingsPathOpView.Set -> "set ${path.joinToString(".")}=$value"
    is SettingsPathOpView.Unset -> "unset ${path.joinToString(".")}"
}

private const val FAKE_KEY = "sk-test-not-a-real-key-0000"

class ModelsControllerTest {
    private val logs = mutableListOf<String>()

    private fun TestScope.controller(harness: ModelsPort, cleartext: Boolean = false, changed: () -> Unit = {}) =
        ModelsController(harness, this, cleartext, log = { logs += it }, onChanged = changed).also {
            it.load()
            advanceUntilIdle()
        }

    // ---- the list --------------------------------------------------------------------------------

    @Test
    fun `a fresh harness lists DeepSeek as needing a key and OpenAI as one to add`() = runTest {
        val c = controller(FakeHarness())
        val dir = c.state.value.directory!!
        val deepseek = dir.rows.first { it.provider == "deepseek-official" }
        assertEquals(ProviderStatus.NeedsKey, deepseek.status)
        assertEquals("DEEPSEEK_API_KEY", deepseek.keyRef)
        assertEquals(listOf("deepseek-official"), dir.yours.map { it.provider })
        assertEquals(listOf("openai"), dir.addable.map { it.provider })
        assertEquals(ProviderStatus.NotSetUp, dir.addable.single().status)
        assertEquals(ModelReadiness.NoneUsable, dir.readiness())
    }

    @Test
    fun `a stored key makes the provider ready and the harness ready`() = runTest {
        val harness = FakeHarness().apply { stored["DEEPSEEK_API_KEY"] = "x" }
        val dir = controller(harness).state.value.directory!!
        assertEquals(ProviderStatus.Ready, dir.rows.first().status)
        assertEquals(ModelReadiness.Ready, dir.readiness())
    }

    @Test
    fun `unknown key state never claims that nothing is usable`() = runTest {
        val harness = FakeHarness().apply { refuseDescribe = RpcError("credentials/denied", "no") }
        val dir = controller(harness).state.value.directory!!
        assertEquals("no", dir.credentialError)
        assertEquals(ModelReadiness.Unknown, dir.readiness())
    }

    @Test
    fun `a harness without the calls is unsupported and a failure keeps its words`() = runTest {
        val old = FakeHarness().apply { refuseProviders = RpcError("capability-unavailable", "404") }
        assertEquals(ModelsPhase.Unsupported, controller(old).state.value.phase)
        val broken = FakeHarness().apply { refuseProviders = RpcError("internal", "socket closed") }
        assertEquals(ModelsPhase.Failed("socket closed"), controller(broken).state.value.phase)
        assertEquals(ModelReadiness.Unknown, (null as ProviderDirectory?).readiness())
    }

    @Test
    fun `the account route shows only while its sign-in serves models`() = runTest {
        val harness = FakeHarness().apply {
            directory.add(0, LlmConfigurableProvider("deepseek-account", "DeepSeek Account", "llm-deepseek-account", emptyList()))
            live += LlmProviderInfo("deepseek-account", "DeepSeek Account")
        }
        val dir = controller(harness).state.value.directory!!
        assertFalse(dir.rows.any { it.isAccount })
        assertFalse(harness.calls.any { it.startsWith("describeCredentials") && "DEEPSEEK_ACCOUNT" in it })
    }

    // ---- saving ----------------------------------------------------------------------------------

    @Test
    fun `saving a DeepSeek key stores it under the profile reference and nothing else`() = runTest {
        val harness = FakeHarness()
        var changes = 0
        val c = controller(harness) { changes++ }
        c.open("deepseek-official")
        assertTrue(c.canSave("  $FAKE_KEY "))
        c.save("  $FAKE_KEY ")
        advanceUntilIdle()
        assertEquals(FAKE_KEY, harness.stored["DEEPSEEK_API_KEY"])
        assertFalse(harness.calls.any { it.startsWith("mutate") })
        assertNull(c.state.value.editor)
        assertEquals(ModelsNotice.Saved("DeepSeek", restartNeeded = false), c.state.value.notice)
        assertEquals(ProviderStatus.Ready, c.state.value.directory!!.rows.first().status)
        assertEquals(1, changes)
    }

    @Test
    fun `a custom DeepSeek endpoint is written as one baseURL op fenced on the revision`() = runTest {
        val harness = FakeHarness()
        val c = controller(harness)
        c.open("deepseek-official")
        c.setBaseUrl("http://10.0.2.2:8765/anthropic")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        assertTrue(harness.calls.contains("mutate:llm-deepseek:set baseURL=\"http://10.0.2.2:8765/anthropic\"@3"))
        // The profile write lands before the key, as the harness page orders them.
        assertTrue(harness.calls.indexOfFirst { it.startsWith("mutate") } < harness.calls.indexOf("set:DEEPSEEK_API_KEY"))
        assertEquals("http://10.0.2.2:8765/anthropic", c.state.value.directory!!.rows.first().baseUrl)
    }

    @Test
    fun `clearing the endpoint unsets it`() = runTest {
        val harness = FakeHarness().apply { deepseek = buildJsonObject { put("apiKeyEnv", "DEEPSEEK_API_KEY"); put("baseURL", "http://x") } }
        val c = controller(harness)
        c.open("deepseek-official")
        assertEquals("http://x", c.state.value.editor!!.baseUrlDraft)
        c.setBaseUrl("  ")
        assertTrue(c.canSave(""))
        c.save("")
        advanceUntilIdle()
        assertTrue(harness.calls.any { it == "mutate:llm-deepseek:unset baseURL@3" })
    }

    @Test
    fun `adding a catalog provider names the derived reference and stores the key under it`() = runTest {
        val harness = FakeHarness()
        val c = controller(harness)
        c.open("openai")
        assertTrue(c.state.value.editor!!.adding)
        c.save(FAKE_KEY)
        advanceUntilIdle()
        assertTrue(harness.calls.contains("mutate:llm-pi-ai:set providers.openai.apiKeyEnv=\"OPENAI_API_KEY\"@7"))
        assertEquals(FAKE_KEY, harness.stored["OPENAI_API_KEY"])
        val openai = c.state.value.directory!!.rows.first { it.provider == "openai" }
        assertEquals(ProviderStatus.Ready, openai.status)
        assertTrue(openai.removable)
    }

    @Test
    fun `adding a catalog provider without a key turns on its own sign-in path`() = runTest {
        val harness = FakeHarness()
        val c = controller(harness)
        c.open("openai")
        assertTrue(c.canSave(""))
        c.save("")
        advanceUntilIdle()
        assertTrue(harness.calls.contains("mutate:llm-pi-ai:set providers.openai={}@7"))
        assertTrue(harness.calls.none { it.startsWith("set:") })
    }

    @Test
    fun `nothing to save means the button stays off`() = runTest {
        val c = controller(FakeHarness())
        c.open("deepseek-official")
        assertFalse(c.canSave(""))
        assertFalse(c.canSave("   "))
        assertFalse(c.canSave("DEEPSEEK_API_KEY=sk-1"))
        assertFalse(c.canSave("\"sk-quoted\""))
        assertFalse(c.canSave("sk with space"))
        c.setBaseUrl("ftp://nope")
        assertFalse(c.canSave(FAKE_KEY))
    }

    @Test
    fun `a stale revision says someone else changed it and keeps the editor open`() = runTest {
        val harness = FakeHarness().apply { refuseMutate = RpcError("settings/conflict", "revision 3 is stale") }
        val c = controller(harness)
        c.open("deepseek-official")
        c.setBaseUrl("http://10.0.2.2:8765/anthropic")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        assertEquals(EditorResult.Conflict, c.state.value.editor!!.result)
        assertNull(c.state.value.editor!!.work)
        // The key is not stored after a refused profile write.
        assertTrue(harness.stored.isEmpty())
    }

    @Test
    fun `a refused key write shows the harness words with the key masked`() = runTest {
        val harness = FakeHarness().apply { refuseSet = RpcError("credentials/refused", "cannot store $FAKE_KEY: provider is read-only") }
        val c = controller(harness)
        c.open("deepseek-official")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        val result = assertIs<EditorResult.Failed>(c.state.value.editor!!.result)
        assertEquals("cannot store ••••: provider is read-only", result.message)
    }

    @Test
    fun `a read-only settings document still takes a key but not an endpoint`() = runTest {
        val c = controller(FakeHarness().apply { writable = false })
        c.open("deepseek-official")
        assertTrue(c.canSave(FAKE_KEY))
        c.setBaseUrl("http://10.0.2.2:8765/anthropic")
        assertFalse(c.canSave(FAKE_KEY))
    }

    @Test
    fun `a key from the harness environment cannot be replaced from here`() = runTest {
        val harness = object : ModelsPort by FakeHarness() {
            override suspend fun describeCredentials(refs: List<String>) =
                RpcResult.Ok(refs.associateWith { CredentialInfo(configured = true, source = "env", writable = false) })
        }
        val c = controller(harness)
        c.open("deepseek-official")
        assertFalse(c.canSave(FAKE_KEY))
        c.removeKey()
        advanceUntilIdle()
        assertNull(c.state.value.editor!!.work)
    }

    // ---- removing --------------------------------------------------------------------------------

    @Test
    fun `removing a key unsets it and the provider needs a key again`() = runTest {
        val harness = FakeHarness().apply { stored["DEEPSEEK_API_KEY"] = "x" }
        val c = controller(harness)
        c.open("deepseek-official")
        c.removeKey()
        advanceUntilIdle()
        assertTrue("unset:DEEPSEEK_API_KEY" in harness.calls)
        assertEquals(ModelsNotice.KeyRemoved("DeepSeek"), c.state.value.notice)
        assertEquals(ProviderStatus.NeedsKey, c.state.value.directory!!.rows.first().status)
        assertEquals(ModelReadiness.NoneUsable, c.state.value.directory.readiness())
    }

    @Test
    fun `removing an added provider drops its managed key first then its profile`() = runTest {
        val harness = FakeHarness()
        val c = controller(harness)
        c.open("openai")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        c.open("openai")
        c.removeProvider()
        advanceUntilIdle()
        val unsetKey = harness.calls.indexOf("unset:OPENAI_API_KEY")
        val unsetProfile = harness.calls.indexOfFirst { it == "mutate:llm-pi-ai:unset providers.openai@null" }
        assertTrue(unsetKey in 0 until unsetProfile)
        assertEquals(ModelsNotice.ProviderRemoved("OpenAI"), c.state.value.notice)
        assertEquals(listOf("openai"), c.state.value.directory!!.addable.map { it.provider })
    }

    // ---- checking --------------------------------------------------------------------------------

    @Test
    fun `the check is offered only where the harness really asks the endpoint`() = runTest {
        val harness = FakeHarness().apply {
            piUser = buildJsonObject { putJsonObject("gateway") { put("api", "openai-completions"); put("baseURL", "http://gw:4000/v1") } }
            directory += LlmConfigurableProvider("gateway", "Gateway", "llm-pi-ai", listOf("providers", "gateway"), declared = true)
            live += LlmProviderInfo("gateway", "Gateway")
        }
        val c = controller(harness)
        c.open("deepseek-official")
        assertFalse(c.canCheck())
        c.close()
        c.open("gateway")
        assertTrue(c.canCheck())
        harness.discovered = RpcResult.Ok(listOf(LlmDiscoveredModel("m1"), LlmDiscoveredModel("m2")))
        c.check(FAKE_KEY)
        advanceUntilIdle()
        assertEquals(EditorResult.Checked(2), c.state.value.editor!!.result)
        // No route named, so the harness interrogates the endpoint instead of its own catalog.
        assertEquals(LlmModelDiscoveryRequest(provider = null, baseURL = "http://gw:4000/v1", api = "openai-completions", apiKey = FAKE_KEY), harness.lastDiscovery)
        harness.discovered = RpcResult.Err(RpcError("llm/model-discovery-rejected", "401 from http://gw:4000/v1"))
        c.check(FAKE_KEY)
        advanceUntilIdle()
        assertEquals(EditorResult.Failed("401 from http://gw:4000/v1"), c.state.value.editor!!.result)
        c.setBaseUrl("")
        assertFalse(c.canCheck())
    }

    // ---- plain HTTP ------------------------------------------------------------------------------

    @Test
    fun `the plain-HTTP rule warns for every non-loopback cleartext link`() {
        assertTrue(keyTravelsInClear(useTls = false, host = "192.168.1.20", isLoopback = false))
        assertTrue(keyTravelsInClear(useTls = false, host = "10.0.2.2", isLoopback = false))
        assertTrue(keyTravelsInClear(useTls = false, host = "relay.example", isLoopback = false))
        assertFalse(keyTravelsInClear(useTls = true, host = "192.168.1.20", isLoopback = false))
        assertFalse(keyTravelsInClear(useTls = false, host = "127.0.0.1", isLoopback = true))
        assertFalse(keyTravelsInClear(useTls = false, host = "127.0.0.1", isLoopback = false))
        assertFalse(keyTravelsInClear(useTls = false, host = "localhost", isLoopback = false))
        assertFalse(keyTravelsInClear(useTls = false, host = "[::1]", isLoopback = false))
        assertTrue(keyTravelsInClear(useTls = false, host = "127.0.0.1.example", isLoopback = false))
    }

    @Test
    fun `a key over plain HTTP waits for a yes and is sent only after it`() = runTest {
        val harness = FakeHarness()
        val c = controller(harness, cleartext = true)
        c.open("deepseek-official")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        assertEquals(PendingSend.Save, c.state.value.editor!!.askCleartext)
        assertTrue(harness.calls.none { it.startsWith("set:") })
        c.answerCleartext(send = false, key = FAKE_KEY)
        advanceUntilIdle()
        assertTrue(harness.calls.none { it.startsWith("set:") })
        c.save(FAKE_KEY)
        c.answerCleartext(send = true, key = FAKE_KEY)
        advanceUntilIdle()
        assertEquals(FAKE_KEY, harness.stored["DEEPSEEK_API_KEY"])
        // Asked once per visit: the next key goes without a second question.
        c.open("openai")
        c.save(FAKE_KEY)
        advanceUntilIdle()
        assertNull(c.state.value.editor?.askCleartext)
        assertEquals(FAKE_KEY, harness.stored["OPENAI_API_KEY"])
    }

    @Test
    fun `an endpoint change alone over plain HTTP is not asked about`() = runTest {
        val c = controller(FakeHarness(), cleartext = true)
        c.open("deepseek-official")
        c.setBaseUrl("http://10.0.2.2:8765/anthropic")
        c.save("")
        advanceUntilIdle()
        assertNull(c.state.value.editor)
    }

    // ---- where the key goes ----------------------------------------------------------------------

    @Test
    fun `the key reaches only the key call and never the state or a log line`() = runTest {
        val harness = FakeHarness().apply { refuseSet = RpcError("x", "echo $FAKE_KEY") }
        val states = mutableListOf<String>()
        val c = controller(harness)
        c.open("openai")
        states += c.state.value.toString()
        c.save(FAKE_KEY)
        advanceUntilIdle()
        states += c.state.value.toString()
        harness.refuseSet = null
        c.save(FAKE_KEY)
        advanceUntilIdle()
        states += c.state.value.toString()
        c.open("openai")
        c.removeProvider()
        advanceUntilIdle()
        states += c.state.value.toString()

        assertEquals(listOf("set:OPENAI_API_KEY"), harness.seenValues.filter { it.second.contains(FAKE_KEY) }.map { it.first }.distinct())
        assertTrue(states.none { FAKE_KEY in it }, "a key leaked into published state")
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { FAKE_KEY in it }, "a key leaked into a log line")
        assertTrue(harness.calls.none { FAKE_KEY in it })
    }

    @Test
    fun `key rules match the harness page`() {
        assertNull(keyProblemOf(""))
        assertNull(keyProblemOf("  sk-abc  "))
        assertNull(keyProblemOf("ABCD=="))
        assertEquals(KeyProblem.Blank, keyProblemOf("   "))
        assertEquals(KeyProblem.Format, keyProblemOf("OPENAI_API_KEY=sk-abc"))
        assertEquals(KeyProblem.Format, keyProblemOf("'sk-abc'"))
        assertEquals(KeyProblem.Format, keyProblemOf("sk abc"))
        assertEquals(KeyProblem.Format, keyProblemOf("sk-é"))
        assertEquals("MINIMAX_CN_API_KEY", deriveKeyRef("minimax-cn"))
        assertEquals("DEEPSEEK_OFFICIAL_API_KEY", deriveKeyRef("deepseek-official"))
        assertTrue(baseUrlLooksValid(""))
        assertTrue(baseUrlLooksValid("https://api.example.com/v1"))
        assertFalse(baseUrlLooksValid("api.example.com"))
        assertFalse(baseUrlLooksValid("http:// spaced"))
    }

    @Test
    fun `json paths read only what is there`() {
        val value: JsonElement = buildJsonObject { putJsonObject("providers") { putJsonObject("a") { put("apiKeyEnv", "A_KEY"); put("blank", " ") } } }
        assertEquals("A_KEY", value.at(listOf("providers", "a")).string("apiKeyEnv"))
        assertNull(value.at(listOf("providers", "a")).string("blank"))
        assertNull(value.at(listOf("providers", "b")))
        assertTrue(value.has(listOf("providers", "a")))
        assertFalse(JsonNull.has(listOf("providers")))
        assertFalse((null as JsonElement?).has(emptyList()))
        assertEquals(JsonPrimitive("A_KEY"), value.at(listOf("providers", "a", "apiKeyEnv")))
    }
}
