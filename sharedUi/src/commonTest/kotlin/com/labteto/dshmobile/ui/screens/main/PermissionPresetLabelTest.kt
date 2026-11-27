package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.permission_preset_ask_commands
import com.labteto.dshmobile.shared.resources.permission_preset_ask_commands_desc
import com.labteto.dshmobile.shared.resources.permission_preset_full_access
import com.labteto.dshmobile.shared.resources.permission_preset_full_access_desc
import com.labteto.dshmobile.shared.resources.permission_preset_read_only
import com.labteto.dshmobile.shared.resources.permission_preset_workspace_write
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * Pins the permission-preset label rules: shipped presets localize, deployment-renamed ones keep
 * the wire label, the reserved `auto` always localizes, and the humanised fallback matches the
 * desktop client. The Android copies of the strings are checked in :app's
 * PermissionPresetLabelResourcesTest.
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

    // ----- the built-in harness's two Android presets -----

    @Test fun `the built-in harness's presets get the app's own labels and descriptions`() {
        assertEquals(ShippedPermissionPreset.AskCommands, shippedPermissionPreset("ask-commands", "Ask before commands"))
        assertEquals(ShippedPermissionPreset.AskCommands, shippedPermissionPreset("ask-commands", null))
        assertEquals(Res.string.permission_preset_ask_commands, permissionPresetLabelRes("ask-commands", "Ask before commands"))
        assertEquals(Res.string.permission_preset_full_access, permissionPresetLabelRes("danger-full-access", "Full access"))
        assertEquals(Res.string.permission_preset_ask_commands_desc, permissionPresetDescriptionRes("ask-commands", "Ask before commands"))
        assertEquals(Res.string.permission_preset_full_access_desc, permissionPresetDescriptionRes("danger-full-access", "Full access"))
    }

    @Test fun `desktop presets keep their labels and the harness's own descriptions`() {
        assertEquals(Res.string.permission_preset_read_only, permissionPresetLabelRes("read-only", "Read Only"))
        assertEquals(Res.string.permission_preset_workspace_write, permissionPresetLabelRes("workspace-write", "Workspace Write"))
        assertEquals(null, permissionPresetDescriptionRes("read-only", "Read Only"))
        assertEquals(null, permissionPresetDescriptionRes("workspace-write", "Workspace Write"))
    }

    @Test fun `unknown ids and renamed presets fall back to the harness's names`() {
        assertEquals(null, permissionPresetLabelRes("some-org-preset", "Some Org Preset"))
        assertEquals("Some Org Preset", humanisedPresetLabel("some-org-preset", "Some Org Preset"))
        assertEquals("Review Commands", humanisedPresetLabel("review-commands", null))
        // A deployment that renamed the ask preset keeps its own name and description.
        assertEquals(null, permissionPresetLabelRes("ask-commands", "Approve each command"))
        assertEquals(null, permissionPresetDescriptionRes("ask-commands", "Approve each command"))
    }
}
