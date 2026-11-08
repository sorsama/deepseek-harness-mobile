package com.labteto.dshmobile.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UsageLocaleParityTest {
    @Test fun allSupportedLocalesTranslateEveryUsageKeyAndPreserveArguments() {
        val res = File("src/main/res")
        fun strings(directory: File): Map<String,String> {
            val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(directory,"strings.xml"))
            val nodes=document.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) }.filter { it.attributes.getNamedItem("name").nodeValue.startsWith("quota_") }
                .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }
        val baseline=strings(File(res,"values"))
        assertEquals(19,baseline.size)
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
