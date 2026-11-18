package com.labteto.dshmobile.ui.screens.harnesssettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Account page strings, in all eleven languages, checked the way a reviewer would: the same keys
 * everywhere, every one filled in, and no dash used as punctuation (the app's copy rule).
 */
class AccountStringsTest {
    private val res = File("src/main/res")

    private fun strings(directory: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(directory, "strings_account.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val locales get() = res.listFiles()!!.filter { File(it, "strings.xml").exists() }.sortedBy { it.name }

    @Test
    fun `every language has the file in its own folder`() {
        assertEquals(11, locales.size)
        locales.forEach { assertTrue(it.name, File(it, "strings_account.xml").isFile) }
    }

    @Test
    fun `every language translates every key`() {
        val baseline = strings(File(res, "values"))
        assertTrue(baseline.isNotEmpty())
        assertTrue(baseline.keys.all { it.startsWith("acct_") })
        locales.forEach { directory ->
            val translated = strings(directory)
            assertEquals(directory.name, baseline.keys, translated.keys)
            baseline.keys.forEach { key ->
                assertTrue("${directory.name}/$key is blank", translated.getValue(key).isNotBlank())
            }
        }
    }

    @Test
    fun `the other languages are not the English text left in place`() {
        val baseline = strings(File(res, "values"))
        locales.filter { it.name != "values" }.forEach { directory ->
            val translated = strings(directory)
            // A word that is the same everywhere (a product name) is allowed; a whole sentence is not.
            baseline.filter { it.value.length > 24 }.forEach { (key, english) ->
                assertFalse("${directory.name}/$key is untranslated", translated.getValue(key) == english)
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
    fun `no string carries a format argument that nothing fills`() {
        // The page passes no arguments to these strings: a leftover placeholder would be shown as written.
        locales.forEach { directory ->
            strings(directory).forEach { (key, text) ->
                assertFalse("${directory.name}/$key", Regex("%[0-9]*[$]?[sd]").containsMatchIn(text))
            }
        }
    }

    @Test
    fun `no string shows a web address or a token`() {
        // A sign-in link and a token never reach the screen, so no string quotes one.
        locales.forEach { directory ->
            strings(directory).forEach { (key, text) ->
                assertFalse("${directory.name}/$key", text.contains("http", ignoreCase = true) || text.contains("token", ignoreCase = true))
            }
        }
    }
}
