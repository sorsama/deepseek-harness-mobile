package com.labteto.dshmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The "Also show balance in" strings: all eleven languages carry every key, keep each `%1$s`, and
 * hold no em dash (on-screen text in this app is written the way a person writes it).
 */
class BalanceCurrencyLocaleParityTest {
    private val res = File("src/main/res")
    private val argument = Regex("%[0-9]+[$][sd]")
    private val emDash = Char(0x2014)

    private fun strings(directory: File): Map<String, String> {
        val file = File(directory, "strings_balance_currency.xml")
        assertTrue("${directory.name} has no ${file.name}", file.exists())
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }.associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun everyLanguageTranslatesEveryKeyAndKeepsItsArguments() {
        val baseline = strings(File(res, "values"))
        assertEquals(10, baseline.size)
        val locales = res.listFiles()!!.filter { File(it, "strings.xml").exists() }
        assertEquals(11, locales.size)
        locales.forEach { directory ->
            val translated = strings(directory)
            assertEquals(directory.name, baseline.keys, translated.keys)
            baseline.forEach { (key, english) ->
                val text = translated.getValue(key)
                assertTrue("${directory.name}/$key is blank", text.isNotBlank())
                assertEquals(
                    "${directory.name}/$key",
                    argument.findAll(english).map { it.value }.toList(),
                    argument.findAll(text).map { it.value }.toList(),
                )
                assertFalse("${directory.name}/$key has an em dash", emDash in text)
            }
            // A real translation, not the English copied across (the "≈" line is a sign, so it may repeat).
            if (directory.name != "values") {
                listOf("balance_currency_title", "balance_currency_hint", "balance_currency_region").forEach { key ->
                    assertTrue("${directory.name}/$key is still English", translated.getValue(key) != baseline.getValue(key))
                }
            }
        }
    }
}
