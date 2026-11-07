package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.session.effectiveWorkingDirectory
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Examples transcribed from the tagged session-controller, working-directory and plugin contracts. */
class Harness021Alpha2ContractTest {
    private val fixture = WireJson.parseToJsonElement(javaClass.getResource("/protocol/d743267.json")!!.readText()).jsonObject

    @Test fun `migration hints have no usable sequence and legacy baselines still decode`() {
        assertEquals("d743267388641bc76f17c45ce8b4c231aed1d32c", fixture.getValue("upstreamCommit").jsonPrimitive.content)
        val migration = WireJson.decodeFromJsonElement<SessionProjectionsValue>(fixture.getValue("migration"))
        assertTrue(migration.requiresMigration)
        assertNull(migration.asOfSeq)
        assertNull(migration.sequenced)
        assertEquals(JsonPrimitive("Older session"), migration.values["title"])
        val current = WireJson.decodeFromJsonElement<SessionProjectionsValue>(fixture.getValue("sequenced"))
        assertEquals(7, current.sequenced?.asOfSeq)
        val legacy = WireJson.decodeFromString<SessionProjectionsValue>("""{"asOfSeq":-1,"values":{}}""")
        assertEquals(-1, legacy.sequenced?.asOfSeq)
        val cold = WireJson.decodeFromJsonElement<SessionSummary>(fixture.getValue("coldSession"))
        assertEquals("migration-required", cold.formatStatus)
        assertEquals("cached", cold.projections?.kind)
    }

    @Test fun `worktree projection overrides original directory without changing it`() {
        val current = WireJson.decodeFromJsonElement<SessionProjectionsValue>(fixture.getValue("sequenced"))
        assertEquals("/work/tree", effectiveWorkingDirectory(current.values, "/work/original"))
        assertEquals("/work/original", effectiveWorkingDirectory(mapOf("workingDirectory" to JsonNull), "/work/original"))
        assertEquals("/work/original", effectiveWorkingDirectory(emptyMap(), "/work/original"))
    }

    @Test fun `external executions retain their identity instead of becoming unknown diagnostics`() {
        val entry = subagentEntriesFromCatalog(fixture.getValue("externalCatalog")) { false }!!.single()
        assertTrue(entry is SubagentListEntry.ChildExternal)
        entry as SubagentListEntry.ChildExternal
        assertEquals("external", entry.mode)
        assertEquals("Codex", entry.label)
    }

    @Test fun `official bundles expose exact install targets and preserve selection options`() {
        val bundle = WireJson.decodeFromJsonElement<PluginBundle>(fixture.getValue("officialBundle"))
        assertTrue(bundle.official)
        assertEquals("missing", bundle.availability)
        assertEquals("0.2.1-alpha.2", bundle.installTarget?.version)
        val options = WireJson.encodeToJsonElement(PluginInstallOptions(enabled = true, requestId = "install", saveExact = true)).jsonObject
        assertEquals(JsonPrimitive(true), options["saveExact"])
        assertEquals(JsonPrimitive(true), options["enabled"])
        val fork = WireJson.encodeToJsonElement(SessionForkRequest("cold", allowMigration = false)).jsonObject
        assertEquals(JsonPrimitive(false), fork["allowMigration"])
    }
}
