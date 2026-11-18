package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The form model built from the real describe rows of the five pages, and from shapes no real row has. */
class SettingsFieldsTest {

    private fun kinds(ns: String) = settingsFields(fixtureRow(ns)).associate { it.key to it.kind }

    @Test
    fun `agent loop is one whole number with its default in force`() {
        val field = field("agent-loop", "maxParallelToolCalls")
        val kind = assertIs<FieldKind.Number>(field.kind)
        assertTrue(kind.whole)
        assertEquals(1.0, kind.min)
        assertEquals(JsonPrimitive(10), field.value)
        assertEquals(JsonPrimitive(10), field.baseValue)
        assertFalse(field.overridden)
        assertEquals(FieldId("agent-loop", "maxParallelToolCalls"), field.id)
    }

    @Test
    fun `an override is the presence of the field in the user layer`() {
        val after = writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_SET)
        val field = settingsFields(after).single()
        assertEquals(JsonPrimitive(4), field.value)
        assertEquals(JsonPrimitive(10), field.baseValue)
        assertTrue(field.overridden)
        // And clearing it puts the default back and the badge away.
        val cleared = settingsFields(writeAnswer(SettingsFixtures.MUTATE_AGENT_LOOP_UNSET)).single()
        assertEquals(JsonPrimitive(10), cleared.value)
        assertFalse(cleared.overridden)
    }

    @Test
    fun `session log is one switch that starts on`() {
        val field = field("session-log-deepseek", "enabled")
        assertEquals(FieldKind.Toggle, field.kind)
        assertEquals(JsonPrimitive(true), field.value)
        val off = settingsFields(writeAnswer(SettingsFixtures.MUTATE_SESSION_LOG_OFF)).single()
        assertEquals(JsonPrimitive(false), off.value)
        assertTrue(off.overridden)
    }

    @Test
    fun `shell fields are numbers and paths with the limits as defaults`() {
        val kinds = kinds("pwsh-sandbox")
        assertEquals(listOf("cwd", "timeoutMs", "maxTimeoutMs", "maxOutputBytes", "maxSpillBytes", "graceMs", "pwshPath"), kinds.keys.toList())
        assertIs<FieldKind.Text>(kinds.getValue("cwd"))
        assertIs<FieldKind.Number>(kinds.getValue("timeoutMs"))
        assertEquals(JsonPrimitive(120000), field("pwsh-sandbox", "timeoutMs").value)
        assertEquals(JsonPrimitive(64000), field("pwsh-sandbox", "maxOutputBytes").value)
        // The number fields declare no step, so the page adds the whole-number rule; the schema alone does not.
        assertFalse((kinds.getValue("timeoutMs") as FieldKind.Number).whole)
    }

    @Test
    fun `the bash executor has the same limits without the PowerShell path`() {
        val keys = settingsFields(fixtureRow("bash-sandbox")).map { it.key }
        assertEquals(listOf("cwd", "timeoutMs", "maxTimeoutMs", "maxOutputBytes", "maxSpillBytes", "graceMs"), keys)
        assertEquals(JsonPrimitive(120000), field("bash-sandbox", "timeoutMs").value)
    }

    @Test
    fun `subagent limits are whole numbers with a floor and no ceiling`() {
        val depth = assertIs<FieldKind.Number>(field("subagent", "maxDepth").kind)
        assertEquals(0.0, depth.min)
        assertNull(depth.max)
        assertTrue(depth.whole)
        val width = assertIs<FieldKind.Number>(field("subagent", "maxActiveSubagents").kind)
        assertEquals(1.0, width.min)
        assertEquals(JsonPrimitive(8), field("subagent", "maxActiveSubagents").value)
    }

    @Test
    fun `the allowed model list is an array of objects the generic form shows but does not edit`() {
        val enabled = field("subagent-model-selection-settings", "enabled")
        assertEquals(FieldKind.Toggle, enabled.kind)
        assertEquals(FieldKind.ReadOnly("array"), field("subagent-model-selection-settings", "allowedModels").kind)
    }

    @Test
    fun `the web search key is a secret slot that is not set`() {
        val key = field("web-search-deepseek", "apiKey")
        assertEquals(FieldKind.Secret, key.kind)
        assertFalse(key.secretSet)
        assertNull(key.value)
        val ref = assertIs<FieldKind.Text>(field("web-search-deepseek", "apiKeyEnv").kind)
        assertTrue(ref.credentialRef)
        assertEquals(JsonPrimitive("DEEPSEEK_API_KEY"), field("web-search-deepseek", "apiKeyEnv").value)
    }

    @Test
    fun `web search endpoint and limit read as text and a number`() {
        // Messages search names no endpoint of its own: the field is empty until one is set.
        val endpoint = field("web-search-deepseek", "baseURL")
        assertIs<FieldKind.Text>(endpoint.kind)
        assertNull(endpoint.value)
        assertEquals(1.0, assertIs<FieldKind.Number>(field("web-search-deepseek", "maxUses").kind).min)
        // The Search API names one, and the field starts at it.
        assertEquals(JsonPrimitive("https://api.deepseek.com/v1/tools"), field("web-deepseek-api", "baseURL").value)
        val count = assertIs<FieldKind.Number>(field("web-deepseek-api", "count").kind)
        assertEquals(50.0, count.max)
    }

    @Test
    fun `an overridden web search row marks exactly the fields the user set`() {
        val after = writeAnswer(SettingsFixtures.MUTATE_WEB_SEARCH_SET)
        val byKey = settingsFields(after).associateBy { it.key }
        assertTrue(byKey.getValue("baseURL").overridden)
        assertTrue(byKey.getValue("maxUses").overridden)
        assertFalse(byKey.getValue("model").overridden)
        assertEquals(JsonPrimitive(7), byKey.getValue("maxUses").value)
        assertEquals(JsonPrimitive(5), byKey.getValue("maxUses").baseValue)
        // The answer still carries the secret slot, and never a value for it.
        assertFalse(byKey.getValue("apiKey").secretSet)
    }

    @Test
    fun `the provider selection is two plain strings`() {
        assertEquals(JsonPrimitive("deepseek-official"), field("web", "searchProvider").value)
        assertIs<FieldKind.Text>(field("web", "fetchProvider").kind)
    }

    @Test
    fun `a union of constants in a real namespace is a single choice`() {
        val fields = settingsFields(fixtureRow("ui-chat")).associateBy { it.key }
        val timing = assertIs<FieldKind.Choice>(fields.getValue("collapseTiming").kind)
        assertEquals(listOf("completion", "next-input"), timing.options.map { it.value.content })
        assertEquals(JsonPrimitive("completion"), fields.getValue("collapseTiming").value)
        val links = assertIs<FieldKind.Choice>(fields.getValue("linkOpening").kind)
        assertEquals(listOf("sidebar", "new-tab"), links.options.map { it.value.content })
        // The harness sends no words for an option, so the value is what there is to show.
        assertEquals(listOf("sidebar", "new-tab"), links.options.map { it.label })
        // A choice the harness has no value for yet is still a choice, with six to pick from.
        val view = assertIs<FieldKind.Choice>(fields.getValue("transcriptView").kind)
        assertEquals(6, view.options.size)
        assertNull(fields.getValue("transcriptView").value)
    }

    @Test
    fun `the permission namespace has one plain text field`() {
        val fields = settingsFields(fixtureRow("permission"))
        assertEquals(listOf("defaultPreset"), fields.map { it.key })
        assertIs<FieldKind.Text>(fields.single().kind)
        assertNull(fields.single().value)
    }

    @Test
    fun `a namespace whose schema declares no fields lists none`() {
        assertEquals(emptyList(), settingsFields(emptyRow("plugin-empty")))
    }

    @Test
    fun `the generic form covers every field type the app edits`() {
        val byKey = settingsFields(syntheticRow()).associateBy { it.key }
        // Hidden fields are never listed.
        assertFalse("secretHidden" in byKey)
        assertEquals(listOf("ratio", "label", "enabledFlag", "mode", "tags", "token", "nested", "weird", "pinned", "slug"), byKey.keys.toList())

        val ratio = assertIs<FieldKind.Number>(byKey.getValue("ratio").kind)
        assertEquals(0.0, ratio.min)
        assertEquals(1.0, ratio.max)
        assertEquals(0.5, ratio.step)
        assertFalse(ratio.whole)

        val label = assertIs<FieldKind.Text>(byKey.getValue("label").kind)
        assertEquals(2, label.minLength)
        assertEquals(8, label.maxLength)
        assertEquals("Shown name", byKey.getValue("label").description)
        assertTrue(byKey.getValue("label").overridden)
        assertFalse(byKey.getValue("ratio").overridden)

        assertEquals(FieldKind.Toggle, byKey.getValue("enabledFlag").kind)

        val mode = assertIs<FieldKind.Choice>(byKey.getValue("mode").kind)
        assertEquals(listOf("fast", "slow"), mode.options.map { it.value.content })
        assertEquals(listOf("Fast", "slow"), mode.options.map { it.label })

        val tags = assertIs<FieldKind.StringList>(byKey.getValue("tags").kind)
        assertEquals(3, tags.maxItems)

        assertEquals(FieldKind.Secret, byKey.getValue("token").kind)
        assertTrue(byKey.getValue("token").secretSet)

        assertIs<FieldKind.Text>(byKey.getValue("slug").kind)
    }

    @Test
    fun `types the app cannot edit are shown read only with their value instead of dropped`() {
        val byKey = settingsFields(syntheticRow()).associateBy { it.key }
        assertEquals(FieldKind.ReadOnly("object"), byKey.getValue("nested").kind)
        assertEquals(FieldKind.ReadOnly("tuple"), byKey.getValue("weird").kind)
        // A field the plugin marks disabled is displayed, not edited.
        assertEquals(FieldKind.ReadOnly("string"), byKey.getValue("pinned").kind)
        assertEquals(JsonPrimitive("fixed"), byKey.getValue("pinned").value)
        assertEquals(obj("a" to str("z")), byKey.getValue("nested").value)
    }

    @Test
    fun `a namespace whose schema cannot be read still lists every value as read only`() {
        val row = SettingsNamespaceView(
            ns = "plugin-y", schema = JsonNull, value = obj("a" to num(1), "b" to str("two")),
            user = obj("b" to str("two")), revision = 1,
        )
        val fields = settingsFields(row)
        assertEquals(listOf("a", "b"), fields.map { it.key })
        assertTrue(fields.all { it.kind == FieldKind.ReadOnly("unknown") })
        assertTrue(fields.single { it.key == "b" }.overridden)
    }

    @Test
    fun `a secret slot the schema does not mark is still a secret`() {
        val row = syntheticRow().copy(secrets = listOf(com.labteto.dshmobile.core.wire.dto.SettingsSecretView(listOf("label"), set = false)))
        val label = settingsFields(row).single { it.key == "label" }
        assertEquals(FieldKind.Secret, label.kind)
        assertFalse(label.secretSet)
    }

    @Test
    fun `names the app has no words for are made readable`() {
        assertEquals("Max parallel tool calls", humanizeSettingsName("maxParallelToolCalls"))
        assertEquals("Base URL", humanizeSettingsName("baseURL"))
        assertEquals("Api key env", humanizeSettingsName("apiKeyEnv"))
        assertEquals("Web search deepseek", humanizeSettingsName("web-search-deepseek"))
        assertEquals("Plugin x", humanizeSettingsName("plugin_x"))
        assertEquals("", humanizeSettingsName(""))
    }
}
