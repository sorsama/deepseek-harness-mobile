package com.labteto.dshmobile.ui.screens.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Add-ons strings, in all eleven languages, checked the way a reviewer would: the same keys
 * everywhere, every one filled in, the same `%1$s` arguments, and no dash used as punctuation (the
 * app's copy rule).
 */
class UiAddonsStringsTest {
    private val res = File("src/main/res")
    private val argument = Regex("%[0-9]+[$][sd]")

    private fun strings(directory: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(directory, "strings_ui_addons.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val locales get() = res.listFiles()!!.filter { File(it, "strings.xml").exists() }.sortedBy { it.name }

    @Test
    fun `every language has the file in its own folder`() {
        assertEquals(11, locales.size)
        locales.forEach { assertTrue(it.name, File(it, "strings_ui_addons.xml").isFile) }
    }

    @Test
    fun `every language translates every key and keeps the arguments`() {
        val baseline = strings(File(res, "values"))
        assertTrue(baseline.isNotEmpty())
        assertTrue(baseline.keys.all { it.startsWith("ua_") })
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
                // A spaced hyphen reads as a dash too.
                assertFalse("${directory.name}/$key", text.contains(" - "))
            }
        }
    }

    @Test
    fun `the four targets have four different names in every language`() {
        locales.forEach { directory ->
            val translated = strings(directory)
            val names = listOf("ua_target_both", "ua_target_phone", "ua_target_desktop", "ua_target_off").map { translated.getValue(it) }
            assertEquals(directory.name, 4, names.toSet().size)
        }
    }

    @Test
    fun `the long names say the full meaning and differ from each other`() {
        // Read out by a screen reader, and shown when the picker stacks: four ways to set an item, four names.
        locales.forEach { directory ->
            val translated = strings(directory)
            val long = listOf("ua_target_both_long", "ua_target_phone_long", "ua_target_desktop_long", "ua_target_off").map { translated.getValue(it) }
            assertEquals(directory.name, 4, long.toSet().size)
            // The two choices of an item with no phone slot are different words too.
            assertFalse(directory.name, translated.getValue("ua_target_show_desktop") == translated.getValue("ua_target_off"))
        }
    }

    @Test
    fun `the slot words are six different phrases and never a raw slot id`() {
        val keys = listOf(
            "ua_slot_overlay", "ua_slot_message_box", "ua_slot_session_header",
            "ua_slot_plugin_row", "ua_slot_sidebar_footer", "ua_slot_other",
        )
        val ids = Regex("(plugins|sidebar|conversation|shell)[.][a-z]")
        locales.forEach { directory ->
            val translated = strings(directory)
            val words = keys.map { translated.getValue(it) }
            assertEquals(directory.name, keys.size, words.toSet().size)
            words.forEach { assertFalse("${directory.name}: $it", ids.containsMatchIn(it)) }
        }
    }

    @Test
    fun `the three strings that said where the phone draws an item are gone`() {
        // The slot words replaced them: the page now says where an item sits, not where the phone would put it.
        locales.forEach { directory ->
            val translated = strings(directory)
            listOf("ua_place_overlay", "ua_place_input", "ua_place_header").forEach { assertFalse("${directory.name}/$it", it in translated) }
            assertTrue(directory.name, "ua_place_none" in translated)
        }
    }

    @Test
    fun `the Add-ons title is not the word the language already uses for plugins`() {
        // Two cards side by side in Settings must not carry the same heading.
        locales.forEach { directory ->
            val plugins = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(directory, "strings.xml"))
                .let { document ->
                    val nodes = document.getElementsByTagName("string")
                    (0 until nodes.length).map { nodes.item(it) }
                        .firstOrNull { it.attributes.getNamedItem("name").nodeValue == "settings_plugins" }?.textContent
                }
            if (plugins != null) assertFalse(directory.name, plugins == strings(directory).getValue("ua_title"))
        }
    }
}
