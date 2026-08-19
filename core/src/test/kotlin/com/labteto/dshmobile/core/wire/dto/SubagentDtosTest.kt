package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentDtosTest {
    @Test
    fun `known list entries keep their kind through a JSON round trip`() {
        val entries = listOf(
            SubagentListEntry.ChildOneShot(
                id = "one-shot",
                activity = "idle",
                hasChildren = false,
                label = "Research",
            ),
            SubagentListEntry.ChildContinuable(
                id = "continuable",
                activity = "running",
                hasChildren = true,
                label = "Builder",
            ),
            SubagentListEntry.Diagnostic(
                id = "diagnostic",
                reason = "Transcript unavailable",
            ),
        )

        entries.forEach { entry ->
            val encoded = encodeToJsonElement(SubagentListEntrySerializer, entry)
            assertEquals(entry.kind, encoded.jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals(entry, decodeFromJsonElement(SubagentListEntrySerializer, encoded))
        }
    }

    @Test
    fun `unknown list entries remain lossless`() {
        val raw = buildJsonObject {
            put("kind", "future-kind")
            put("id", "future-entry")
            put("extra", true)
        }
        val decoded = decodeFromJsonElement(SubagentListEntrySerializer, raw)

        assertEquals(UnknownSubagentListEntry("future-kind", raw), decoded)
        assertEquals(raw, encodeToJsonElement(SubagentListEntrySerializer, decoded))
    }

    @Test
    fun `catalog round trip preserves every known entry subtype`() {
        val catalog = SubagentCatalog(
            entries = listOf(
                SubagentListEntry.ChildOneShot(
                    id = "one-shot",
                    activity = "idle",
                    hasChildren = false,
                    label = "Research",
                ),
                SubagentListEntry.ChildContinuable(
                    id = "continuable",
                    activity = "running",
                    hasChildren = true,
                    label = "Builder",
                ),
                SubagentListEntry.Diagnostic(
                    id = "diagnostic",
                    reason = "Transcript unavailable",
                ),
            ),
            parentAvailable = true,
        )

        val encoded = encodeToJsonElement(SubagentCatalog.serializer(), catalog)

        assertEquals(catalog, decodeFromJsonElement(SubagentCatalog.serializer(), encoded))
    }
}
