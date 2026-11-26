package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.settings.SettingsFailure
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `ui-sync` row behind Settings > Add-ons and the phone gate: reads, writes, conflicts, refusals. */
@OptIn(ExperimentalCoroutinesApi::class)
class UiSyncSettingsTest {
    private val pet = "dsh-ui-sync-example-pet|shell.overlay|example-pet.pet"
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"
    private val items = Json.parseToJsonElement(
        """{
            "$pet": {"package":"dsh-ui-sync-example-pet","slot":"shell.overlay","cell":"example-pet.pet","title":"Cat","placement":"overlay","defaultTarget":"both","seen":"2026-11-23"},
            "$treat": {"package":"dsh-ui-sync-example-pet","slot":"conversation.input.right","cell":"example-pet.treat","title":"Treat button","placement":"input-right","defaultTarget":"both","seen":"2026-11-23"}
        }""",
    ).jsonObject

    /** A harness holding one `ui-sync` row; it answers each write with the new row, as `settings/mutate` does. */
    private inner class FakeHarness : UiSyncSettingsPort {
        var targets: Map<String, String> = emptyMap()
        var revision = 3L
        var companion = true
        var writable = true
        val mutations = mutableListOf<Pair<List<SettingsPathOpView>, Long?>>()
        var describes = 0
        /** Answers to hand out before the real behaviour, in order. */
        val scriptedMutations = ArrayDeque<RpcResult<SettingsNamespaceView>>()
        var describeFailure: RpcError? = null
        var gate: CompletableDeferred<Unit>? = null

        fun row() = SettingsNamespaceView(
            ns = UiSyncProtocol.NAMESPACE,
            value = buildJsonObject {
                put("targets", JsonObject(targets.mapValues { JsonPrimitive(it.value) }))
                put("items", items)
            },
            revision = revision,
        )

        override suspend fun describe(): RpcResult<SettingsDescribeValue> {
            describes++
            describeFailure?.let { return RpcResult.Err(it) }
            val rows = if (companion) listOf(SettingsNamespaceView(ns = "web"), row()) else listOf(SettingsNamespaceView(ns = "web"))
            return RpcResult.Ok(SettingsDescribeValue(writable = writable, hasDocument = true, namespaces = rows))
        }

        override suspend fun mutate(ops: List<SettingsPathOpView>, expectedRevision: Long?): RpcResult<SettingsNamespaceView> {
            mutations += ops to expectedRevision
            gate?.await()
            scriptedMutations.removeFirstOrNull()?.let { return it }
            if (expectedRevision != null && expectedRevision != revision) {
                return RpcResult.Err(RpcError("settings/conflict", "expected $expectedRevision, at $revision"))
            }
            val set = ops.single() as SettingsPathOpView.Set
            targets = targets + (set.path[1] to (set.value as JsonPrimitive).content)
            revision++
            return RpcResult.Ok(row())
        }
    }

    private fun TestScope.settings(harness: FakeHarness) = UiSyncSettings(this, harness)

    @Test
    fun `a read gives the items and whether anything shows on the phone`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        assertNull(settings.state.value.phoneItems)
        assertIs<RpcResult.Ok<*>>(settings.reload())
        val state = settings.state.value
        assertEquals(UiSyncSettingsPhase.Ready, state.phase)
        assertEquals(2, state.store.items.size)
        assertEquals(true, state.phoneItems)
    }

    @Test
    fun `a harness without the companion reads as missing and nothing to show`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { companion = false }
        val settings = settings(harness)
        settings.reload()
        assertEquals(UiSyncSettingsPhase.Missing, settings.state.value.phase)
        assertEquals(false, settings.state.value.phoneItems)
    }

    @Test
    fun `a first read that fails says so and leaves the gate unknown`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { describeFailure = RpcError("forbidden", "relay refused") }
        val settings = settings(harness)
        settings.reload()
        assertIs<UiSyncSettingsPhase.Failed>(settings.state.value.phase)
        assertNull(settings.state.value.phoneItems)
    }

    @Test
    fun `a later read that fails keeps the row and says so once`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        harness.describeFailure = RpcError("internal", "dropped")
        settings.reload()
        assertEquals(UiSyncSettingsPhase.Ready, settings.state.value.phase)
        assertIs<SettingsFailure.Unreachable>(settings.state.value.refreshFailed)
        settings.dismissRefreshFailed()
        assertNull(settings.state.value.refreshFailed)
    }

    @Test
    fun `a pick is written to targets fenced on the revision and the answer replaces the row`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        settings.setTarget(pet, UiSyncTarget.DESKTOP)
        advanceUntilIdle()
        val (ops, revision) = harness.mutations.single()
        assertEquals(listOf(SettingsPathOpView.Set(listOf("targets", pet), JsonPrimitive("desktop"))), ops)
        assertEquals(3L, revision)
        val state = settings.state.value
        assertTrue(state.writing.isEmpty())
        assertEquals(4L, state.row?.revision)
        assertEquals(UiSyncTarget.DESKTOP, state.targetOf(pet))
        // The treat button still shows on the phone, so the page is still worth loading.
        assertEquals(true, state.phoneItems)
        settings.setTarget(treat, UiSyncTarget.OFF)
        advanceUntilIdle()
        assertEquals(false, settings.state.value.phoneItems)
    }

    @Test
    fun `the pick shows while it is on its way`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { gate = CompletableDeferred() }
        val settings = settings(harness)
        settings.reload()
        settings.setTarget(pet, UiSyncTarget.PHONE)
        assertEquals(UiSyncTarget.PHONE, settings.state.value.writing[pet])
        assertEquals(UiSyncTarget.PHONE, settings.state.value.targetOf(pet))
        harness.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(settings.state.value.writing.isEmpty())
        assertEquals(UiSyncTarget.PHONE, settings.state.value.targetOf(pet))
    }

    @Test
    fun `a conflict reads again and retries once on the new revision`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        // A page recorded its items meanwhile: the harness is two revisions on.
        harness.revision = 5
        settings.setTarget(pet, UiSyncTarget.DESKTOP)
        advanceUntilIdle()
        assertEquals(listOf(3L, 5L), harness.mutations.map { it.second })
        assertEquals(2, harness.describes)
        assertEquals(UiSyncTarget.DESKTOP, settings.state.value.targetOf(pet))
        assertTrue(settings.state.value.failures.isEmpty())
    }

    @Test
    fun `a second conflict is shown under the item and the row is read again`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        val conflict = RpcResult.Err(RpcError("settings/conflict", "again"))
        harness.scriptedMutations += conflict
        harness.scriptedMutations += conflict
        settings.setTarget(pet, UiSyncTarget.OFF)
        advanceUntilIdle()
        assertEquals(2, harness.mutations.size)
        val state = settings.state.value
        assertEquals(SettingsFailure.Conflict, state.failures[pet]?.failure)
        assertTrue(state.writing.isEmpty())
        // Back to what the harness holds.
        assertEquals(UiSyncTarget.BOTH, state.targetOf(pet))
        assertEquals(3, harness.describes)
    }

    @Test
    fun `a refusal is shown inline without a retry`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        harness.scriptedMutations += RpcResult.Err(RpcError("settings/rejected", "invalid config"))
        settings.setTarget(pet, UiSyncTarget.PHONE)
        advanceUntilIdle()
        assertEquals(1, harness.mutations.size)
        assertIs<SettingsFailure.Rejected>(settings.state.value.failures[pet]?.failure)
        assertEquals(UiSyncTarget.BOTH, settings.state.value.targetOf(pet))
        // Picking again clears it.
        settings.setTarget(pet, UiSyncTarget.PHONE)
        advanceUntilIdle()
        assertNull(settings.state.value.failures[pet])
        assertEquals(UiSyncTarget.PHONE, settings.state.value.targetOf(pet))
    }

    @Test
    fun `nothing is written to a read only harness or for the value already held`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { writable = false }
        val settings = settings(harness)
        settings.reload()
        settings.setTarget(pet, UiSyncTarget.OFF)
        advanceUntilIdle()
        assertTrue(harness.mutations.isEmpty())
        harness.writable = true
        settings.reload()
        settings.setTarget(pet, UiSyncTarget.BOTH)
        advanceUntilIdle()
        assertTrue(harness.mutations.isEmpty())
    }

    @Test
    fun `a newer pick for the same item replaces one still waiting`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { gate = CompletableDeferred() }
        val settings = settings(harness)
        settings.reload()
        settings.setTarget(treat, UiSyncTarget.OFF)
        settings.setTarget(pet, UiSyncTarget.PHONE)
        settings.setTarget(pet, UiSyncTarget.DESKTOP)
        harness.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf("off", "desktop"),
            harness.mutations.map { ((it.first.single() as SettingsPathOpView.Set).value as JsonPrimitive).content },
        )
        assertEquals(UiSyncTarget.DESKTOP, settings.state.value.targetOf(pet))
    }

    @Test
    fun `document updates read again only for a newer revision of ui-sync`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness()
        val settings = settings(harness)
        settings.reload()
        assertEquals(1, harness.describes)
        settings.onDocumentUpdated("web", 99)
        settings.onDocumentUpdated(UiSyncProtocol.NAMESPACE, 3)
        advanceUntilIdle()
        assertEquals(1, harness.describes)
        harness.targets = mapOf(pet to "desktop")
        harness.revision = 4
        settings.onDocumentUpdated(UiSyncProtocol.NAMESPACE, 4)
        advanceUntilIdle()
        assertEquals(2, harness.describes)
        assertEquals(UiSyncTarget.DESKTOP, settings.state.value.targetOf(pet))
    }

    @Test
    fun `a reset forgets the old harness and drops its late answers`() = runTest(UnconfinedTestDispatcher()) {
        val harness = FakeHarness().apply { gate = CompletableDeferred() }
        val settings = settings(harness)
        settings.reload()
        settings.setTarget(pet, UiSyncTarget.OFF)
        settings.reset()
        harness.gate!!.complete(Unit)
        advanceUntilIdle()
        val state = settings.state.value
        assertEquals(UiSyncSettingsPhase.Idle, state.phase)
        assertNull(state.row)
        assertNull(state.phoneItems)
    }
}
