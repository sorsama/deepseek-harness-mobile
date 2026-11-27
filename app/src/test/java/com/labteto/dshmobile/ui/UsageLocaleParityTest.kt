package com.labteto.dshmobile.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UsageLocaleParityTest {
    @Test fun allSupportedLocalesTranslateEveryUsageKeyAndPreserveArguments() {
        val res = File("src/main/res")
        // The compact window names moved to the shared UI's resources with the chat's stats footer (iOS M6b); a usage
        // key is checked wherever it lives, in the same locale.
        val shared = File("../sharedUi/src/commonMain/composeResources")
        fun strings(directory: File): Map<String,String> =
            listOf(File(directory,"strings.xml"), File(File(shared,directory.name),"strings.xml")).filter { it.exists() }.flatMap { file ->
                val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes=document.getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) }.filter { it.attributes.getNamedItem("name").nodeValue.startsWith("quota_") }
                    .map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
            }.toMap()
        val baseline=strings(File(res,"values"))
        assertEquals(31,baseline.size)
        // The card never prints "no data": an unknown state is simply not drawn.
        assertFalse(baseline.containsKey("quota_unknown"))
        val locales=res.listFiles()!!.filter { File(it,"strings.xml").exists() }
        assertEquals(11,locales.size)
        val argument=Regex("%[0-9]+[$][sd]")
        locales.forEach { directory ->
            val translated=strings(directory)
            assertEquals(directory.name,baseline.keys,translated.keys)
            baseline.forEach { (key,value) ->
                assertTrue("${directory.name}/$key",translated.getValue(key).isNotBlank())
                assertEquals("${directory.name}/$key",argument.findAll(value).map { it.value }.toList(),argument.findAll(translated.getValue(key)).map { it.value }.toList())
            }
            if (directory.name == "values-hi") assertEquals("सप्ताह", translated.getValue("quota_compact_week"))
            if (directory.name == "values-bn") assertEquals("সপ্তাহ", translated.getValue("quota_compact_week"))
            if (directory.name == "values-zh-rCN") {
                assertEquals("5小时", translated.getValue("quota_compact_session"))
                assertEquals("小时", translated.getValue("quota_compact_hour"))
            }
            if (directory.name == "values-ur") {
                assertEquals("5گھ", translated.getValue("quota_compact_session"))
                assertTrue(translated.getValue("quota_short").startsWith("5 "))
            }
            assertFalse("Separate usage.xml violates the resource convention",File(directory,"usage.xml").exists())
        }
    }
}
