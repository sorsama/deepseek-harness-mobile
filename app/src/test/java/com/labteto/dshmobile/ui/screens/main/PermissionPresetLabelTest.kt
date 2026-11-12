package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the permission-preset label rules: shipped presets localize, deployment-renamed ones keep
 * the wire label, the reserved `auto` always localizes, and the humanised fallback matches the
 * desktop client. The locale resources are checked directly, so the zh copy the UI shows is
 * pinned against the harness's own Simplified Chinese dictionary.
 */
class PermissionPresetLabelTest {

    // ----- shipped-preset resolution -----

    @Test
    fun `shipped presets resolve by id with default or blank names`() {
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("read-only", "read-only"))
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("read-only", null))
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("read-only", ""))
        assertEquals(ShippedPermissionPreset.WorkspaceWrite, shippedPermissionPreset("workspace-write", "workspace-write"))
        assertEquals(ShippedPermissionPreset.FullAccess, shippedPermissionPreset("danger-full-access", "danger-full-access"))
    }

    @Test
    fun `shipped presets localize under their shipped English label too`() {
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("read-only", "Read Only"))
        assertEquals(ShippedPermissionPreset.WorkspaceWrite, shippedPermissionPreset("workspace-write", "Workspace Write"))
        assertEquals(ShippedPermissionPreset.FullAccess, shippedPermissionPreset("danger-full-access", "Full access"))
    }

    @Test
    fun `legacy id spellings resolve to the shipped preset`() {
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("readonly", "readonly"))
        assertEquals(ShippedPermissionPreset.ReadOnly, shippedPermissionPreset("read", "read"))
        assertEquals(ShippedPermissionPreset.WorkspaceWrite, shippedPermissionPreset("write", "write"))
    }

    @Test
    fun `a deployment that renames a shipped preset keeps its own label`() {
        // The preset table is configurable, so localizing by id alone would mislabel any
        // deployment that renamed one — same guard the harness web client applies.
        assertEquals(null, shippedPermissionPreset("workspace-write", "Sandboxed"))
        assertEquals(null, shippedPermissionPreset("danger-full-access", "Unrestricted"))
        assertEquals(null, shippedPermissionPreset("read-only", "نمایش فقط"))
    }

    @Test
    fun `auto is a reserved id and always localizes`() {
        // The harness refuses to compose a preset table containing `auto`, so no deployment can
        // rename it; the label is the app's in every locale.
        assertEquals(ShippedPermissionPreset.AutoReview, shippedPermissionPreset("auto", "auto"))
        assertEquals(ShippedPermissionPreset.AutoReview, shippedPermissionPreset("auto", "Whatever"))
        assertEquals(ShippedPermissionPreset.AutoReview, shippedPermissionPreset("auto", null))
    }

    @Test
    fun `unknown presets are never localized`() {
        assertEquals(null, shippedPermissionPreset("some-org-preset", "some-org-preset"))
        assertEquals(null, shippedPermissionPreset("custom", "custom"))
    }

    // ----- humanised fallback (the desktop client's transform) -----

    @Test
    fun `kebab ids humanise to title case`() {
        assertEquals("Read Only", humanisedPresetLabel("read-only", null))
        assertEquals("My Org Preset", humanisedPresetLabel("my-org-preset", "my-org-preset"))
        assertEquals("Work Space", humanisedPresetLabel("x", "work_space"))
    }

    @Test
    fun `already-spaced host names pass through unchanged`() {
        assertEquals("Sandboxed", humanisedPresetLabel("workspace-write", "Sandboxed"))
        assertEquals("Custom", humanisedPresetLabel("custom", "custom"))
    }

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

    private fun strings(locale: String): Map<String, String> {
        val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "$locale/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("name").nodeValue in keys }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
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
        // Guard the R entries the three entry points and the auto description read.
        val names = R.string::class.java.fields.map { it.name }.toSet()
        for (k in keys) assertNotNull(k, names.contains(k))
    }
}