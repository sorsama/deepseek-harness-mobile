package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android copies of the permission-preset labels, in every locale, pinned against the harness's own
 * Simplified Chinese dictionary. The rules that pick a label are tested in sharedUi's PermissionPresetLabelTest.
 */
class PermissionPresetLabelResourcesTest {
    // ----- locale resources -----

    private val locales = listOf(
        "values", "values-ar", "values-bn", "values-es", "values-fr", "values-hi",
        "values-pt", "values-ru", "values-th", "values-ur", "values-zh-rCN",
    )

    private val keys = listOf(
        "permission_preset_read_only",
        "permission_preset_workspace_write",
        "permission_preset_full_access",
        "permission_preset_auto",
        "permission_preset_auto_desc",
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
    fun `every supported locale carries all five permission label strings`() {
        for (locale in locales) {
            val have = strings(locale)
            assertEquals(locale, keys, have.keys.toList())
            for (k in keys) assertTrue("$locale/$k is blank", have.getValue(k).isNotBlank())
        }
    }

    @Test
    fun `english keeps the shipped labels`() {
        val en = strings("values")
        assertEquals("Read Only", en.getValue("permission_preset_read_only"))
        assertEquals("Workspace Write", en.getValue("permission_preset_workspace_write"))
        assertEquals("Full access", en.getValue("permission_preset_full_access"))
        assertEquals("Auto review", en.getValue("permission_preset_auto"))
        assertTrue(en.getValue("permission_preset_auto_desc").startsWith("Run without a sandbox"))
    }

    @Test
    fun `simplified chinese matches the harness web dictionary`() {
        // Pinned verbatim to dsh-client-ui-permission-presets' accessZh dictionary, so the app and
        // the harness web client show the same Chinese labels for the same presets.
        val zh = strings("values-zh-rCN")
        assertEquals("仅可查看", zh.getValue("permission_preset_read_only"))
        assertEquals("工作区内修改", zh.getValue("permission_preset_workspace_write"))
        assertEquals("完全权限", zh.getValue("permission_preset_full_access"))
        assertEquals("自动审查", zh.getValue("permission_preset_auto"))
        assertTrue(zh.getValue("permission_preset_auto_desc").startsWith("无沙箱运行"))
    }

    @Test fun `string resources the composables read exist`() {
        // Guard the entries the three entry points and the auto description read: an Android resource for the
        // ones unmoved code still reads (R), a shared one for the rest.
        val names = R.string::class.java.fields.map { it.name }.toSet() + strings("values").keys
        for (k in keys) assertTrue(k, names.contains(k))
    }
}