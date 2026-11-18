package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.CredentialInfo
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller against a fake port that records every call it is handed. The refusals and the
 * answers it replays are the ones a real harness sent (see [SettingsFixtures]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessSettingsControllerTest {

    private class Mutation(val ns: String, val ops: List<SettingsPathOpView>, val revision: Long?)

    private class FakePort : HarnessSettingsPort {
        var describeCalls = 0
        val mutations = mutableListOf<Mutation>()
        val keyWrites = mutableListOf<Pair<String, String>>()
        val keyRemovals = mutableListOf<String>()
        val credentialAsks = mutableListOf<List<String>>()
        var catalogCalls = 0

        var onDescribe: suspend () -> RpcResult<SettingsDescribeValue> = { RpcResult.Ok(describedFixture()) }
        var onMutate: suspend (Mutation) -> RpcResult<SettingsNamespaceView> = { error("no scripted answer for ${it.ns}") }
        var onDescribeCredentials: suspend (List<String>) -> RpcResult<Map<String, CredentialInfo>> =
            { refs -> RpcResult.Ok(refs.associateWith { CredentialInfo(configured = true, source = "env", writable = true) }) }
        var onSetCredential: suspend (String, String) -> RpcResult<Unit> = { _, _ -> RpcResult.Ok(Unit) }
        var onUnsetCredential: suspend (String) -> RpcResult<Unit> = { RpcResult.Ok(Unit) }
        var onCatalog: suspend () -> RpcResult<ModelCatalog> = {
            RpcResult.Ok(WireJson.decodeFromJsonElement(ModelCatalog.serializer(), (fixtureResult(SettingsFixtures.MODEL_CATALOG) as RpcResult.Ok).value))
        }

        override suspend fun describe(): RpcResult<SettingsDescribeValue> {
            describeCalls++
            return onDescribe()
        }

        override suspend fun mutate(ns: String, ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView> {
            val mutation = Mutation(ns, ops, expectedRevision)
            mutations += mutation
            return onMutate(mutation)
        }

        override suspend fun describeCredentials(refs: List<String>): RpcResult<Map<String, CredentialInfo>> {
            credentialAsks += refs
            return onDescribeCredentials(refs)
        }

        override suspend fun setCredential(ref: String, value: String): RpcResult<Unit> {
            keyWrites += ref to value
            return onSetCredential(ref, value)
        }

        override suspend fun unsetCredential(ref: String): RpcResult<Unit> {
            keyRemovals += ref
            return onUnsetCredential(ref)
        }

        override suspend fun modelCatalog(): RpcResult<ModelCatalog> {
            catalogCalls++
            return onCatalog()
        }
    }

    private fun TestScope.loaded(port: FakePort, cleartext: Boolean = false): HarnessSettingsController {
        val controller = HarnessSettingsController(port, this, cleartext)
        controller.load()
        advanceUntilIdle()
        return controller
    }

    private fun HarnessSettingsController.row(ns: String) = state.value.namespaces.getValue(ns)

    private val loopId = FieldId("agent-loop", "maxParallelToolCalls")

    private fun err(code: String, message: String = "refused") = RpcResult.Err(RpcError(code, message))

    // ---------------------------------------------------------------------------------- loading

    @Test
    fun `a first read lists the pages and asks about the key once`() = runTest {
        val port = FakePort()
        val controller = HarnessSettingsController(port, this)
        assertEquals(SettingsPhase.Loading, controller.state.value.phase)
        controller.load()
        runCurrent()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(SettingsPhase.Ready, state.phase)
        assertEquals(10, state.namespaces.size)
        assertEquals(
            listOf(SettingsPage.WebSearch, SettingsPage.Shell, SettingsPage.Subagents, SettingsPage.AgentLoop, SettingsPage.SessionLog),
            state.pages,
        )
        // Both search sections name the same reference, so the harness is asked about it once.
        assertEquals(listOf(listOf("DEEPSEEK_API_KEY")), port.credentialAsks)
        assertEquals(CredentialInfo(configured = true, source = "env", writable = true), state.credentials["DEEPSEEK_API_KEY"])
    }

    @Test
    fun `a harness without the call is unsupported`() = runTest {
        val port = FakePort().apply { onDescribe = { err("gateway/invocation-unavailable") } }
        val controller = loaded(port)
        assertEquals(SettingsPhase.Unsupported, controller.state.value.phase)
        assertTrue(controller.state.value.pages.isEmpty())
    }

    @Test
    fun `a failed first read shows the failure and a retry recovers`() = runTest {
        val port = FakePort().apply { onDescribe = { err("internal", "socket closed") } }
        val controller = loaded(port)
        assertEquals(SettingsPhase.Failed(SettingsFailure.Unreachable("socket closed")), controller.state.value.phase)

        port.onDescribe = { RpcResult.Ok(describedFixture()) }
        controller.load()
        advanceUntilIdle()
        assertEquals(SettingsPhase.Ready, controller.state.value.phase)
    }

    @Test
    fun `a failed refresh keeps the rows already shown and says so`() = runTest {
        val port = FakePort()
        val controller = loaded(port)
        port.onDescribe = { err("internal", "timeout") }
        controller.load()
        advanceUntilIdle()
        assertEquals(SettingsPhase.Ready, controller.state.value.phase)
        assertEquals(10, controller.state.value.namespaces.size)
        assertEquals(SettingsNotice.RefreshFailed(SettingsFailure.Unreachable("timeout")), controller.state.value.notice)
        controller.dismissNotice()
        assertNull(controller.state.value.notice)
    }

    @Test
    fun `a thrown failure is a refusal and not a crash`() = runTest {
        val port = FakePort().apply { onDescribe = { throw IllegalStateException("boom") } }
        val controller = loaded(port)
        assertEquals(SettingsPhase.Failed(SettingsFailure.Unreachable("boom")), controller.state.value.phase)
    }

    @Test
    fun `a read only document is reported and refuses writes`() = runTest {
        val port = FakePort().apply { onDescribe = { RpcResult.Ok(describedFixture().copy(writable = false)) } }
        val controller = loaded(port)
        assertFalse(controller.state.value.writable)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(3))
        advanceUntilIdle()
        assertTrue(port.mutations.isEmpty())
    }

    // --------------------------------------------------------------------------------- writing

    @Test
    fun `a write is one revision fenced set and the answer replaces the row`() = runTest {
        val port = FakePort().apply { onMutate = { RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET)) } }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(4))
        advanceUntilIdle()

        val sent = port.mutations.single()
        assertEquals("agent-loop", sent.ns)
        assertEquals(listOf<SettingsPathOpView>(SettingsPathOpView.Set(listOf("maxParallelToolCalls"), JsonPrimitive(4))), sent.ops)
        assertEquals(0L, sent.revision)
        val after = settingsFields(controller.row("agent-loop")).single()
        assertEquals(JsonPrimitive(4), after.value)
        assertTrue(after.overridden)
        assertEquals(1L, controller.row("agent-loop").revision)
        assertTrue(controller.state.value.writing.isEmpty())
        assertTrue(controller.state.value.failures.isEmpty())
    }

    @Test
    fun `while a write is in flight the field shows what it will be`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onMutate = { gate.await(); RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET)) }
        }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(4))
        runCurrent()
        assertEquals(mapOf<FieldId, Any?>(loopId to JsonPrimitive(4)), controller.state.value.writing)
        // The row is still the harness's old one until it answers.
        assertEquals(JsonPrimitive(10), settingsFields(controller.row("agent-loop")).single().value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.writing.isEmpty())
    }

    @Test
    fun `a refused write restores the field and says why`() = runTest {
        val port = FakePort().apply { onMutate = { err("settings/rejected", "invalid config:\n  - \$.maxParallelToolCalls expected number >= 1 but got 0") } }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(0))
        advanceUntilIdle()

        val failure = controller.state.value.failures.getValue(loopId).failure
        assertIs<SettingsFailure.Rejected>(failure)
        assertTrue(failure.detail.contains("expected number >= 1"))
        // Nothing was accepted, so the row is the old one and nothing is shown as in flight.
        assertEquals(JsonPrimitive(10), settingsFields(controller.row("agent-loop")).single().value)
        assertEquals(0L, controller.row("agent-loop").revision)
        assertTrue(controller.state.value.writing.isEmpty())
    }

    @Test
    fun `a refusal the harness really sent is carried through unchanged`() = runTest {
        val port = FakePort().apply { onMutate = { RpcResult.Err(refusal(SettingsFixtures.MUTATE_AGENT_LOOP_INVALID)) } }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(0))
        advanceUntilIdle()
        assertIs<SettingsFailure.Rejected>(controller.state.value.failures.getValue(loopId).failure)
    }

    @Test
    fun `a conflict reads everything again and keeps what the harness holds`() = runTest {
        val port = FakePort()
        val controller = loaded(port)
        // Someone else saved first: the harness now holds 7 at revision 1.
        val newer = describedFixture().let { described ->
            described.copy(namespaces = described.namespaces.map { row ->
                if (row.ns == "agent-loop") writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET).copy(
                    value = obj("maxParallelToolCalls" to JsonPrimitive(7)),
                    user = obj("maxParallelToolCalls" to JsonPrimitive(7)),
                ) else row
            })
        }
        port.onMutate = { RpcResult.Err(refusal(SettingsFixtures.MUTATE_AGENT_LOOP_STALE)) }
        port.onDescribe = { RpcResult.Ok(newer) }
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(3))
        advanceUntilIdle()

        assertEquals(SettingsFailure.Conflict, controller.state.value.failures.getValue(loopId).failure)
        assertEquals(2, port.describeCalls)
        assertEquals(JsonPrimitive(7), settingsFields(controller.row("agent-loop")).single().value)
        assertEquals(1L, controller.row("agent-loop").revision)
    }

    @Test
    fun `writes to one namespace go one at a time and each uses the last answered revision`() = runTest {
        val first = CompletableDeferred<Unit>()
        val port = FakePort()
        var answered = 0
        port.onMutate = {
            answered++
            if (answered == 1) first.await()
            val base = writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET)
            RpcResult.Ok(base.copy(revision = answered.toLong()))
        }
        val controller = loaded(port)
        val field = field("agent-loop", "maxParallelToolCalls")
        controller.set(field, JsonPrimitive(4))
        controller.set(field, JsonPrimitive(5))
        runCurrent()
        // The second waits behind the first: only one call is out.
        assertEquals(1, port.mutations.size)
        first.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf<Long?>(0L, 1L), port.mutations.map { it.revision })
        assertEquals(2L, controller.row("agent-loop").revision)
    }

    @Test
    fun `resetting a field sends an unset and shows its default meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onMutate = { gate.await(); RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_UNSET)) }
        }
        val controller = loaded(port)
        controller.reset(field("agent-loop", "maxParallelToolCalls"))
        runCurrent()
        assertEquals(mapOf<FieldId, Any?>(loopId to null), controller.state.value.writing)
        assertEquals(listOf<SettingsPathOpView>(SettingsPathOpView.Unset(listOf("maxParallelToolCalls"))), port.mutations.single().ops)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(settingsFields(controller.row("agent-loop")).single().overridden)
    }

    @Test
    fun `several fields of one write are in flight and settle together`() = runTest {
        val port = FakePort().apply { onMutate = { RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)) } }
        val controller = loaded(port)
        val gate = CompletableDeferred<Unit>()
        port.onMutate = { gate.await(); RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)) }
        val ids = mapOf<FieldId, kotlinx.serialization.json.JsonElement?>(
            FieldId("web", "searchProvider") to JsonPrimitive("deepseek-search-api"),
            FieldId("web", "fetchProvider") to JsonPrimitive("deepseek-fetch-api"),
        )
        controller.write("web", WebSearchRules.switchOps(controller.row("web"), searchApi = true), ids)
        runCurrent()
        assertEquals(ids.keys, controller.state.value.writing.keys)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.writing.isEmpty())
        assertTrue(WebSearchRules.usesSearchApi(controller.row("web")))
    }

    @Test
    fun `a new write clears the old failure of the same field`() = runTest {
        val port = FakePort().apply { onMutate = { err("settings/rejected", "no") } }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(0))
        advanceUntilIdle()
        assertTrue(loopId in controller.state.value.failures)
        port.onMutate = { RpcResult.Ok(writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET)) }
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(4))
        runCurrent()
        assertFalse(loopId in controller.state.value.failures)
        advanceUntilIdle()
        controller.dismissFailure(loopId)
        assertTrue(controller.state.value.failures.isEmpty())
    }

    @Test
    fun `each failure carries a new serial so a screen can tell them apart`() = runTest {
        val port = FakePort().apply { onMutate = { err("settings/rejected", "no") } }
        val controller = loaded(port)
        val field = field("agent-loop", "maxParallelToolCalls")
        controller.set(field, JsonPrimitive(0))
        advanceUntilIdle()
        val first = controller.state.value.failures.getValue(loopId).serial
        controller.set(field, JsonPrimitive(0))
        advanceUntilIdle()
        assertTrue(controller.state.value.failures.getValue(loopId).serial > first)
    }

    @Test
    fun `a dropped connection during a write is unreachable and the row stays`() = runTest {
        val port = FakePort().apply { onMutate = { throw java.io.IOException("connection reset") } }
        val controller = loaded(port)
        controller.set(field("agent-loop", "maxParallelToolCalls"), JsonPrimitive(4))
        advanceUntilIdle()
        assertEquals(SettingsFailure.Unreachable("connection reset"), controller.state.value.failures.getValue(loopId).failure)
        assertEquals(JsonPrimitive(10), settingsFields(controller.row("agent-loop")).single().value)
    }

    // ---------------------------------------------------------------------------------- secrets

    @Test
    fun `a secret field value goes into the call and into nothing the controller keeps`() = runTest {
        val row = syntheticRow("plugin-x")
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onDescribe = { RpcResult.Ok(SettingsDescribeValue(writable = true, hasDocument = true, namespaces = listOf(row))) }
            onMutate = { gate.await(); err("settings/rejected", "bad value sk-secret-123 for token") }
        }
        val controller = loaded(port)
        val token = settingsFields(controller.row("plugin-x")).single { it.key == "token" }
        controller.set(token, JsonPrimitive("sk-secret-123"))
        runCurrent()
        // In flight: the screen is told the field is busy, never what is being written.
        assertEquals(mapOf<FieldId, Any?>(token.id to JsonNull), controller.state.value.writing)
        assertFalse(controller.state.value.toString().contains("sk-secret-123"))
        gate.complete(Unit)
        advanceUntilIdle()

        val failure = controller.state.value.failures.getValue(token.id).failure
        assertEquals(SettingsFailure.Rejected("bad value ••• for token"), failure)
        assertFalse(controller.state.value.toString().contains("sk-secret-123"))
        // The write itself did carry it.
        assertEquals(JsonPrimitive("sk-secret-123"), (port.mutations.single().ops.single() as SettingsPathOpView.Set).value)
    }

    @Test
    fun `saving a key stores it under the reference then reads the slot again`() = runTest {
        val port = FakePort()
        val controller = loaded(port)
        port.credentialAsks.clear()
        val gate = CompletableDeferred<Unit>()
        port.onSetCredential = { _, _ -> gate.await(); RpcResult.Ok(Unit) }
        port.onDescribeCredentials = { RpcResult.Ok(mapOf("DEEPSEEK_API_KEY" to CredentialInfo(configured = true, source = "file", writable = true))) }
        controller.saveKey("DEEPSEEK_API_KEY", "  sk-abc123  ")
        runCurrent()
        assertEquals(setOf("DEEPSEEK_API_KEY"), controller.state.value.credentialWork)
        // A second tap while the first is out does nothing.
        controller.saveKey("DEEPSEEK_API_KEY", "sk-other")
        runCurrent()
        assertEquals(1, port.keyWrites.size)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("DEEPSEEK_API_KEY" to "sk-abc123"), port.keyWrites)
        assertEquals(listOf(listOf("DEEPSEEK_API_KEY")), port.credentialAsks)
        val state = controller.state.value
        assertEquals("file", state.credentials.getValue("DEEPSEEK_API_KEY").source)
        assertTrue(state.credentialWork.isEmpty())
        assertEquals(SettingsNotice.KeySaved, state.notice)
        assertFalse(state.toString().contains("sk-abc123"))
    }

    @Test
    fun `a blank key is never sent`() = runTest {
        val port = FakePort()
        val controller = loaded(port)
        controller.saveKey("DEEPSEEK_API_KEY", "   ")
        advanceUntilIdle()
        assertTrue(port.keyWrites.isEmpty())
    }

    @Test
    fun `a refused key says why without echoing the key`() = runTest {
        val port = FakePort().apply { onSetCredential = { _, _ -> err("credentials/rejected", "key sk-abc123 is not accepted") } }
        val controller = loaded(port)
        controller.saveKey("DEEPSEEK_API_KEY", "sk-abc123")
        advanceUntilIdle()

        val failure = controller.state.value.credentialFailures.getValue("DEEPSEEK_API_KEY").failure
        assertEquals(SettingsFailure.Rejected("key ••• is not accepted"), failure)
        assertTrue(controller.state.value.credentialWork.isEmpty())
        assertNull(controller.state.value.notice)
        assertFalse(controller.state.value.toString().contains("sk-abc123"))
    }

    @Test
    fun `removing a key clears the slot and reads it again`() = runTest {
        val port = FakePort()
        val controller = loaded(port)
        port.credentialAsks.clear()
        port.onDescribeCredentials = { RpcResult.Ok(mapOf("DEEPSEEK_API_KEY" to CredentialInfo(configured = false, writable = true))) }
        controller.removeKey("DEEPSEEK_API_KEY")
        advanceUntilIdle()

        assertEquals(listOf("DEEPSEEK_API_KEY"), port.keyRemovals)
        assertFalse(controller.state.value.credentials.getValue("DEEPSEEK_API_KEY").configured)
        assertEquals(SettingsNotice.KeyRemoved, controller.state.value.notice)
    }

    @Test
    fun `a key from the environment reports that it cannot be written`() = runTest {
        val port = FakePort().apply {
            onDescribeCredentials = { RpcResult.Ok(mapOf("DEEPSEEK_API_KEY" to CredentialInfo(configured = true, source = "env", writable = false))) }
        }
        val controller = loaded(port)
        assertFalse(controller.state.value.credentials.getValue("DEEPSEEK_API_KEY").writable)
    }

    @Test
    fun `an unreadable credential answer leaves the slot unknown rather than wrong`() = runTest {
        val port = FakePort().apply { onDescribeCredentials = { err("internal", "down") } }
        val controller = loaded(port)
        assertTrue(controller.state.value.credentials.isEmpty())
        assertEquals(SettingsPhase.Ready, controller.state.value.phase)
    }

    @Test
    fun `the key is not asked about when the harness has no search page`() = runTest {
        val port = FakePort().apply {
            onDescribe = { RpcResult.Ok(SettingsDescribeValue(writable = true, hasDocument = true, namespaces = listOf(fixtureRow("agent-loop")))) }
        }
        loaded(port)
        assertTrue(port.credentialAsks.isEmpty())
    }

    @Test
    fun `a cleartext link is exposed so the screen can ask first`() = runTest {
        assertTrue(loaded(FakePort(), cleartext = true).cleartext)
        assertFalse(loaded(FakePort(), cleartext = false).cleartext)
    }

    // ----------------------------------------------------------------------------- the catalog

    @Test
    fun `the model catalog is read once while it is loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onCatalog = {
                gate.await()
                RpcResult.Ok(WireJson.decodeFromJsonElement(ModelCatalog.serializer(), (fixtureResult(SettingsFixtures.MODEL_CATALOG) as RpcResult.Ok).value))
            }
        }
        val controller = loaded(port)
        controller.loadCatalog()
        controller.loadCatalog()
        runCurrent()
        assertEquals(CatalogState.Loading, controller.state.value.catalog)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, port.catalogCalls)
        val ready = assertIs<CatalogState.Ready>(controller.state.value.catalog)
        assertEquals(2, ready.catalog.groups.single().models.size)
    }

    @Test
    fun `a catalog that cannot be read says so and can be tried again`() = runTest {
        val port = FakePort().apply { onCatalog = { err("internal", "no route") } }
        val controller = loaded(port)
        controller.loadCatalog()
        advanceUntilIdle()
        assertEquals(CatalogState.Failed(SettingsFailure.Unreachable("no route")), controller.state.value.catalog)

        port.onCatalog = { RpcResult.Ok(ModelCatalog(default = com.labteto.dshmobile.core.wire.dto.ModelSelection("p", "m"))) }
        controller.loadCatalog()
        advanceUntilIdle()
        assertIs<CatalogState.Ready>(controller.state.value.catalog)
        assertEquals(2, port.catalogCalls)
    }
}
