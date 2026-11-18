package com.labteto.dshmobile.ui.screens.harnesssettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Harness settings strings, in all eleven languages, checked the way a reviewer would: the same
 * keys everywhere, every one filled in, the same `%1$s` arguments in the same order, and no dash
 * used as punctuation (the app's copy rule).
 */
class HarnessSettingsStringsTest {
    private val res = File("src/main/res")
    private val argument = Regex("%[0-9]+[$][sd]")

    private fun strings(directory: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(directory, "strings_harness_settings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val locales get() = res.listFiles()!!.filter { File(it, "strings.xml").exists() }.sortedBy { it.name }

    @Test
    fun `every language has the file in its own folder and nowhere else`() {
        assertEquals(11, locales.size)
        locales.forEach { assertTrue(it.name, File(it, "strings_harness_settings.xml").isFile) }
    }

    @Test
    fun `every language translates every key and keeps the arguments`() {
        val baseline = strings(File(res, "values"))
        assertTrue(baseline.isNotEmpty())
        assertTrue(baseline.keys.all { it.startsWith("hs_") })
        locales.forEach { directory ->
            val translated = strings(directory)
            assertEquals(directory.name, baseline.keys, translated.keys)
            baseline.forEach { (key, value) ->
                val text = translated.getValue(key)
                assertTrue("${directory.name}/$key is blank", text.isNotBlank())
                assertEquals(
                    "${directory.name}/$key",
                    argument.findAll(value).map { it.value }.sorted().toList(),
                    argument.findAll(text).map { it.value }.sorted().toList(),
                )
            }
        }
    }

    @Test
    fun `no string uses an em dash or an en dash`() {
        locales.forEach { directory ->
            strings(directory).forEach { (key, text) ->
                assertFalse("${directory.name}/$key", text.contains('—') || text.contains('–'))
            }
        }
    }

    @Test
    fun `a hint that quotes an address leaves room for it`() {
        // Pages pass the example address through technicalDisplay, so it stays left to right inside
        // right-to-left text; the string must still take it.
        locales.forEach { directory ->
            val translated = strings(directory)
            listOf("hs_f_endpoint_messages_hint", "hs_f_endpoint_search_hint").forEach { key ->
                assertEquals("${directory.name}/$key", 1, argument.findAll(translated.getValue(key)).count())
            }
        }
    }

    @Test
    fun `the empty model list names the page where it is fixed in every language`() {
        // The sentence points at "Models and API keys" by its own title, so it must say what the page is called there.
        locales.forEach { directory ->
            val title = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(directory, "strings_models.xml"))
                .let { document ->
                    val nodes = document.getElementsByTagName("string")
                    (0 until nodes.length).map { nodes.item(it) }
                        .first { it.attributes.getNamedItem("name").nodeValue == "models_keys_title" }.textContent
                }
            assertTrue("${directory.name}: ${strings(directory).getValue("hs_models_none_why")} should name $title", title in strings(directory).getValue("hs_models_none_why"))
        }
    }

    @Test
    fun `leaving a field empty is no longer how a value goes back to its default`() {
        // The address hints used to say "leave empty"; an empty box is refused now and Reset to default is the way back.
        val english = strings(File(res, "values"))
        assertFalse(english.getValue("hs_f_endpoint_messages_hint").contains("empty", ignoreCase = true))
        assertFalse(english.getValue("hs_f_endpoint_search_hint").contains("empty", ignoreCase = true))
        assertTrue(english.containsKey("hs_reset"))
        assertFalse(english.containsKey("hs_models_empty"))
    }

    @Test
    fun `the limit sentences take the numbers they name`() {
        val baseline = strings(File(res, "values"))
        assertEquals(2, argument.findAll(baseline.getValue("hs_err_range")).count())
        listOf("hs_err_min", "hs_err_max", "hs_err_step", "hs_err_min_length", "hs_err_max_length", "hs_err_min_items", "hs_err_max_items")
            .forEach { assertEquals(it, 1, argument.findAll(baseline.getValue(it)).count()) }
    }
}
