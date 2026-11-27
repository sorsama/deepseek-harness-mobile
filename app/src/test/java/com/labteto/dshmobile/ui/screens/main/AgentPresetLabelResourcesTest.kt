package com.labteto.dshmobile.ui.screens.main

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android copies of the shipped presets' names and descriptions, in every locale. The rules that pick
 * them (`builtInPresetStrings`) are tested in sharedUi's AgentPresetLabelsTest.
 */
class AgentPresetLabelResourcesTest {
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

    /**
     * The strings wherever they live now: the Android resources, or the shared UI's files once only shared
     * code reads them (tools/shared-strings/sync.mjs --prune). SharedResourcesTest holds a pair identical.
     */
    private fun strings(locale: String): Map<String, String> {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val android = File(repo, "app/src/main/res/$locale").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        val files = android + File(repo, "sharedUi/src/commonMain/composeResources/$locale/strings.xml")
        val found = mutableMapOf<String, String>()
        for (file in files) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }
                .filter { it.attributes.getNamedItem("name").nodeValue in keys }
                .forEach { found.getOrPut(it.attributes.getNamedItem("name").nodeValue) { it.textContent } }
        }
        return keys.filter { it in found }.associateWith { found.getValue(it) }
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
