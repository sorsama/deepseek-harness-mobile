package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schema reader, against the schemas a real harness sent (see [SettingsFixtures]) and against
 * shapes it never has.
 */
class SettingsSchemaTest {

    private fun schemaOf(ns: String): SchemaObject =
        assertIs<SchemaObject>(parseSettingsSchema(fixtureRow(ns).schema))

    @Test
    fun `the real describe payload decodes into rows with their revision and page policy`() {
        val described = describedFixture()
        assertTrue(described.writable)
        assertTrue(described.hasDocument)
        assertEquals(10, described.namespaces.size)
        assertEquals(0L, described.namespaces.first().revision)
        assertEquals("live", described.namespaces.first().applies)
        // The harness says which namespaces another surface owns; the model and agent defaults are not for a generic page.
        assertFalse(described.namespaces.single { it.ns == "agent-default-model" }.autoGenerate)
        assertTrue(described.namespaces.single { it.ns == "agent-loop" }.autoGenerate)
    }

    @Test
    fun `a row without a schema or a revision still decodes`() {
        val row = WireJson.decodeFromString(SettingsNamespaceView.serializer(), """{"ns":"x","value":{"a":1}}""")
        assertEquals("x", row.ns)
        assertEquals(JsonNull, row.schema)
        assertEquals(0L, row.revision)
        assertTrue(row.autoGenerate)
        assertNull(parseSettingsSchema(row.schema))
    }

    @Test
    fun `the agent loop schema is one whole number of at least one`() {
        val number = assertIs<SchemaNumber>(schemaOf("agent-loop").fields.getValue("maxParallelToolCalls"))
        assertEquals(1.0, number.meta.min)
        assertEquals(1.0, number.meta.step)
        assertEquals(10, (number.meta.default as JsonPrimitive).content.toInt())
        assertNull(number.meta.max)
    }

    @Test
    fun `the largest safe integer is read as no upper bound`() {
        val depth = assertIs<SchemaNumber>(schemaOf("subagent").fields.getValue("maxDepth"))
        assertEquals(0.0, depth.meta.min)
        assertNull(depth.meta.max)
        val active = assertIs<SchemaNumber>(schemaOf("subagent").fields.getValue("maxActiveSubagents"))
        assertEquals(1.0, active.meta.min)
    }

    @Test
    fun `a limit the plugin does declare is kept`() {
        val count = assertIs<SchemaNumber>(schemaOf("web-deepseek-api").fields.getValue("count"))
        assertEquals(1.0, count.meta.min)
        assertEquals(50.0, count.meta.max)
    }

    @Test
    fun `secret and credential reference roles are read`() {
        val fields = schemaOf("web-search-deepseek").fields
        assertEquals("secret", fields.getValue("apiKey").meta.role)
        assertEquals("credential-ref", fields.getValue("apiKeyEnv").meta.role)
        assertEquals("DEEPSEEK_API_KEY", (fields.getValue("apiKeyEnv").meta.default as JsonPrimitive).content)
    }

    @Test
    fun `fields keep the order the plugin declared them in`() {
        assertEquals(
            listOf("cwd", "timeoutMs", "maxTimeoutMs", "maxOutputBytes", "maxSpillBytes", "graceMs", "pwshPath"),
            schemaOf("pwsh-sandbox").fields.keys.toList(),
        )
    }

    @Test
    fun `an array of objects is an array whose items are objects`() {
        val allowed = assertIs<SchemaArray>(schemaOf("subagent-model-selection-settings").fields.getValue("allowedModels"))
        val item = assertIs<SchemaObject>(assertNotNull(allowed.inner))
        assertEquals(listOf("provider", "model"), item.fields.keys.toList())
        assertTrue(item.fields.getValue("provider").meta.required)
    }

    @Test
    fun `nothing that is not a schema parses as one`() {
        assertNull(parseSettingsSchema(null))
        assertNull(parseSettingsSchema(JsonNull))
        assertNull(parseSettingsSchema(JsonPrimitive("schema")))
        assertNull(parseSettingsSchema(JsonArray(emptyList())))
        assertNull(parseSettingsSchema(obj("uid" to num(1))))
    }

    @Test
    fun `a reference to a node that is not there yields no schema rather than a crash`() {
        assertNull(parseSettingsSchema(obj("uid" to num(9), "refs" to obj())))
    }

    @Test
    fun `a bare node without a graph is read as itself`() {
        val node = assertIs<SchemaNumber>(parseSettingsSchema(obj("type" to str("number"), "meta" to obj("min" to num(2)))))
        assertEquals(2.0, node.meta.min)
    }

    @Test
    fun `a cycle in the graph stops at the repeat`() {
        val graph = obj(
            "uid" to num(1),
            "refs" to obj(
                "1" to obj("type" to str("object"), "dict" to obj("again" to num(1))),
            ),
        )
        val root = assertIs<SchemaObject>(parseSettingsSchema(graph))
        val again = assertIs<SchemaOther>(root.fields.getValue("again"))
        assertEquals("recursive", again.type)
    }

    @Test
    fun `a type this app has never heard of becomes an opaque node`() {
        val graph = obj("uid" to num(1), "refs" to obj("1" to obj("type" to str("quantum"), "meta" to obj())))
        val node = assertIs<SchemaOther>(parseSettingsSchema(graph))
        assertEquals("quantum", node.type)
    }

    @Test
    fun `a description may be a string or a locale map`() {
        val plain = parseSettingsSchema(obj("type" to str("string"), "meta" to obj("description" to str("Plain"))))
        assertEquals("Plain", plain?.meta?.description)
        val mapped = parseSettingsSchema(obj("type" to str("string"), "meta" to obj("description" to obj("en" to str("English"), "zh" to str("中文")))))
        assertEquals("English", mapped?.meta?.description)
        val only = parseSettingsSchema(obj("type" to str("string"), "meta" to obj("description" to obj("zh" to str("中文")))))
        assertEquals("中文", only?.meta?.description)
    }

    @Test
    fun `the described value decodes as the typed value it is`() {
        val value = WireJson.decodeFromString(SettingsDescribeValue.serializer(), SettingsFixtures.DESCRIBE)
        assertEquals(setOf("session-log-deepseek", "pwsh-sandbox", "subagent", "web", "web-search-deepseek", "web-deepseek-api", "agent-loop", "subagent-model-selection-settings"),
            value.namespaces.filter { it.autoGenerate }.map { it.ns }.toSet())
    }
}
