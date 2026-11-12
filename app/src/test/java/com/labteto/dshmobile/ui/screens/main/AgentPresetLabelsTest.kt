package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the agent-preset roster turns into localized copy.
 *
 * The regression this guards: harness 0.2.1 sends `agentPresets/list` rows as
 * `{id, order, isDefault}` — no `trust`, no `name`, no `description`. The old rule only translated
 * `trust == SYSTEM` rows, so every shipped preset arrived UNKNOWN and the picker, the top-bar chip
 * and the session details panel all showed raw ids with no descriptions.
 */
class AgentPresetLabelsTest {

    private fun entry(
        id: String,
        trust: AgentPresetTrust = AgentPresetTrust.UNKNOWN,
        name: String? = null,
        description: String? = null,
    ) = AgentPresetEntry(id = id, trust = trust, isDefault = false, name = name, description = description)

    // ----- the roster harness 0.2.1 actually sends -----

    @Test
    fun `the shipped four resolve with no trust field and no host copy`() {
        // Exactly the rows the live host answered: id + order + isDefault, nothing else.
        val standard = builtInPresetStrings(entry("standard"))
        assertEquals(R.string.preset_standard_name, standard?.first)
        assertEquals(R.string.preset_standard_desc, standard?.second)

        val ptc = builtInPresetStrings(entry("ptc"))
        assertEquals(R.string.preset_code_name, ptc?.first)
        assertEquals(R.string.preset_code_desc, ptc?.second)

        val minimal = builtInPresetStrings(entry("minimal"))
        assertEquals(R.string.preset_minimal_name, minimal?.first)
        assertEquals(R.string.preset_minimal_desc, minimal?.second)

        val cordis = builtInPresetStrings(entry("cordis"))
        assertEquals(R.string.preset_cordis_name, cordis?.first)
        assertEquals(R.string.preset_cordis_desc, cordis?.second)
    }

    @Test
    fun `the older code id stays an alias for ptc`() {
        assertEquals(builtInPresetStrings("ptc"), builtInPresetStrings("code"))
    }

    @Test
    fun `an explicit system tier still resolves`() {
        assertEquals(R.string.preset_standard_name, builtInPresetStrings(entry("standard", AgentPresetTrust.SYSTEM))?.first)
        assertEquals(R.string.preset_code_name, builtInPresetStrings(entry("ptc", AgentPresetTrust.SYSTEM))?.first)
    }

    // ----- what must not be claimed as shipped -----

    @Test
    fun `an authored preset keeps its own row`() {
        // Someone's own preset is never translated, whatever it is called.
        assertNull(builtInPresetStrings(entry("my-preset", AgentPresetTrust.USER, "标准模式")))
        assertNull(builtInPresetStrings(entry("standard", AgentPresetTrust.USER, "My standard")))
    }

    @Test
    fun `an unknown id has no translation to reach for`() {
        assertNull(builtInPresetStrings("my-org-preset"))
        assertNull(builtInPresetStrings("custom"))
        assertNull(builtInPresetStrings(""))
        assertNull(builtInPresetStrings(entry("my-org-preset", AgentPresetTrust.UNKNOWN, "Anything")))
    }

    @Test
    fun `a custom preset that borrows a shipped name is not misclassified`() {
        // Same display name, different id: still the deployment's own row.
        assertNull(builtInPresetStrings(entry("team-preset", AgentPresetTrust.UNKNOWN, "Standard mode")))
        assertNull(builtInPresetStrings(entry("team-preset", AgentPresetTrust.SYSTEM, "Standard mode")))
    }

    // ----- locale resources -----

    private val locales = listOf(
        "values", "values-ar", "values-bn", "values-es", "values-fr", "values-hi",
        "values-pt", "values-ru", "values-th", "values-ur", "values-zh-rCN",
    )

    private val keys = listOf(
        "preset_standard_name", "preset_standard_desc",
        "preset_code_name", "preset_code_desc",
        "preset_minimal_name", "preset_minimal_desc",
        "preset_cordis_name", "preset_cordis_desc",
    )

    private fun strings(locale: String): Map<String, String> {
        val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "$locale/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("name").nodeValue in keys }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun `every supported locale carries all eight preset strings`() {
        for (locale in locales) {
            val have = strings(locale)
            assertEquals(locale, keys, have.keys.toList())
            for (k in keys) assertTrue("$locale/$k is blank", have.getValue(k).isNotBlank())
        }
    }

    @Test
    fun `simplified chinese names the four presets the web UI way`() {
        val zh = strings("values-zh-rCN")
        assertEquals("标准模式", zh.getValue("preset_standard_name"))
        assertEquals("PTC 模式", zh.getValue("preset_code_name"))
        assertEquals("极简模式", zh.getValue("preset_minimal_name"))
        assertEquals("创造模式", zh.getValue("preset_cordis_name"))
    }

    @Test
    fun `english keeps the shipped names`() {
        val en = strings("values")
        assertEquals("Standard mode", en.getValue("preset_standard_name"))
        assertEquals("PTC mode", en.getValue("preset_code_name"))
        assertEquals("Minimal mode", en.getValue("preset_minimal_name"))
        assertEquals("Creator mode", en.getValue("preset_cordis_name"))
    }

    @Test
    fun `no locale carries a raw id as its preset copy`() {
        // The bug looked like these strings were missing; they were there, unreachable.
        for (locale in locales) {
            val have = strings(locale)
            for (k in keys) {
                val value = have.getValue(k)
                assertNotNull("$locale/$k is empty", value.takeIf { it.isNotBlank() })
                assertTrue(
                    "$locale/$k still reads like a wire id: $value",
                    !value.matches(Regex("^(standard|ptc|code|minimal|cordis)$")),
                )
            }
        }
    }
}
