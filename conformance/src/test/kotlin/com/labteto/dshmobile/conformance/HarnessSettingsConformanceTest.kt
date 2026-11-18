package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.settings.FieldKind
import com.labteto.dshmobile.core.settings.SettingsFailure
import com.labteto.dshmobile.core.settings.SettingsNamespaces
import com.labteto.dshmobile.core.settings.SettingsPage
import com.labteto.dshmobile.core.settings.settingsFields
import com.labteto.dshmobile.core.settings.settingsPages
import com.labteto.dshmobile.core.settings.toSettingsFailure
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Harness settings pages rely on, against a real harness.
 *
 * The pages are driven by the schema `settings/describe` sends, so a unit test over a recorded
 * answer can only say the reader agrees with the day it was recorded. This boots the harness and
 * reads what it says now: the pages it offers, the kind of each field they edit, and one real write
 * with its refusals. Every namespace is optional (a harness composed without a plugin has no page
 * for it), so a case that needs one is skipped when the harness does not serve it.
 *
 * Nothing here is left behind: the harness runs in a throwaway `DSH_HOME`, and the one field written
 * is cleared again.
 */
class HarnessSettingsConformanceTest {

    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

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

    private fun describe() = runBlocking {
        when (val result = client.api.settingsDescribe()) {
            is RpcResult.Ok -> result.value
            is RpcResult.Err -> error("settings/describe failed: ${result.error}")
        }
    }

    @Test
    fun `the harness describes settings the client can read into pages`() {
        val described = describe()
        assertTrue("a harness that describes nothing has nothing to test", described.namespaces.isNotEmpty())
        val pages = settingsPages(described)
        // Whatever the composition, the harness's own list is read without losing a row or a field.
        described.namespaces.forEach { row ->
            assertTrue("${row.ns} has a revision", row.revision >= 0)
            // A schema this reader cannot parse would have every field come back read only as "unknown".
            val fields = settingsFields(row)
            assertTrue("${row.ns} schema was not read", fields.none { it.kind == FieldKind.ReadOnly("unknown") })
        }
        // The pages are exactly the ones whose namespaces it serves.
        val served = described.namespaces.map { it.ns }.toSet()
        assertEquals(SettingsNamespaces.AGENT_LOOP in served, SettingsPage.AgentLoop in pages)
        assertEquals(SettingsNamespaces.SESSION_LOG in served, SettingsPage.SessionLog in pages)
        assertEquals(SettingsNamespaces.WEB_SEARCH in served, SettingsPage.WebSearch in pages)
    }

    @Test
    fun `the fields the five pages edit have the kinds the pages draw them as`() {
        val rows = describe().namespaces.associateBy { it.ns }
        fun kindOf(ns: String, key: String): FieldKind? = rows[ns]?.let { row -> settingsFields(row).firstOrNull { it.key == key }?.kind }

        kindOf(SettingsNamespaces.AGENT_LOOP, "maxParallelToolCalls")?.let { assertTrue(it is FieldKind.Number) }
        kindOf(SettingsNamespaces.SESSION_LOG, "enabled")?.let { assertEquals(FieldKind.Toggle, it) }
        kindOf(SettingsNamespaces.SUBAGENT, "maxDepth")?.let { assertTrue(it is FieldKind.Number) }
        kindOf(SettingsNamespaces.SUBAGENT, "maxActiveSubagents")?.let { assertTrue(it is FieldKind.Number) }
        kindOf(SettingsNamespaces.SUBAGENT_MODELS, "enabled")?.let { assertEquals(FieldKind.Toggle, it) }
        listOf(SettingsNamespaces.BASH, SettingsNamespaces.PWSH).forEach { ns ->
            kindOf(ns, "timeoutMs")?.let { assertTrue("$ns timeoutMs", it is FieldKind.Number) }
            kindOf(ns, "maxOutputBytes")?.let { assertTrue("$ns maxOutputBytes", it is FieldKind.Number) }
        }
        kindOf(SettingsNamespaces.WEB_SEARCH, "baseURL")?.let { assertTrue(it is FieldKind.Text) }
        kindOf(SettingsNamespaces.WEB_SEARCH, "maxUses")?.let { assertTrue(it is FieldKind.Number) }
        kindOf(SettingsNamespaces.WEB_API, "count")?.let { assertTrue(it is FieldKind.Number) }
        // The web search key is a slot the wire never fills: the page writes it through credentials.
        kindOf(SettingsNamespaces.WEB_SEARCH, "apiKey")?.let { assertEquals(FieldKind.Secret, it) }
    }

    @Test
    fun `a write is fenced on the revision and the answer is the new row`() = runBlocking {
        val before = describe().namespaces.firstOrNull { it.ns == SettingsNamespaces.AGENT_LOOP }
        assumeTrue("this harness serves no agent-loop namespace", before != null)
        val field = settingsFields(before!!).single { it.key == "maxParallelToolCalls" }
        assertFalse("a fresh harness has no override", field.overridden)

        // Set it, fenced on the revision just read.
        val set = client.api.settingsMutate(
            SettingsNamespaces.AGENT_LOOP,
            listOf(SettingsPathOpView.Set(listOf("maxParallelToolCalls"), JsonPrimitive(4))),
            before.revision.toInt(),
        )
        val after = (set as? RpcResult.Ok)?.value ?: error("settings/mutate failed: $set")
        val changed = settingsFields(after).single { it.key == "maxParallelToolCalls" }
        assertEquals(JsonPrimitive(4), changed.value)
        assertTrue("the user layer now carries the field", changed.overridden)
        assertTrue("the revision moved", after.revision > before.revision)

        // The same revision again is stale: that is the conflict the client reads again on.
        val stale = client.api.settingsMutate(
            SettingsNamespaces.AGENT_LOOP,
            listOf(SettingsPathOpView.Set(listOf("maxParallelToolCalls"), JsonPrimitive(3))),
            before.revision.toInt(),
        )
        assertEquals(SettingsFailure.Conflict, (stale as RpcResult.Err).error.toSettingsFailure())

        // A value the schema refuses is a rejection that names why, and changes nothing.
        val refused = client.api.settingsMutate(
            SettingsNamespaces.AGENT_LOOP,
            listOf(SettingsPathOpView.Set(listOf("maxParallelToolCalls"), JsonPrimitive(0))),
            after.revision.toInt(),
        )
        assertTrue((refused as RpcResult.Err).error.toSettingsFailure() is SettingsFailure.Rejected)

        // Clearing it goes back to the deployment's value and takes the override away.
        val unset = client.api.settingsMutate(
            SettingsNamespaces.AGENT_LOOP,
            listOf(SettingsPathOpView.Unset(listOf("maxParallelToolCalls"))),
        )
        val cleared = settingsFields((unset as? RpcResult.Ok)?.value ?: error("unset failed: $unset")).single { it.key == "maxParallelToolCalls" }
        assertFalse(cleared.overridden)
        assertEquals(field.value, cleared.value)
    }

    @Test
    fun `a credential slot answers whether it is set and is never given back`() = runBlocking {
        val served = describe().namespaces.map { it.ns }
        assumeTrue("no web search namespace", SettingsNamespaces.WEB_SEARCH in served)
        val ref = SettingsNamespaces.DEFAULT_KEY_REF
        val info = (client.api.credentialsDescribe(listOf(ref)) as? RpcResult.Ok)?.value?.get(ref)
            ?: error("credentials/describe did not answer for $ref")
        // Only whether and from where: the type has no field that could carry the value.
        assertTrue(info.configured || info.source == null)
    }
}
