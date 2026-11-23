package com.labteto.dshmobile.ui.screens.addons

import com.labteto.dshmobile.core.uisync.UiSyncPlacement
import com.labteto.dshmobile.core.uisync.UiSyncStoredItem
import com.labteto.dshmobile.core.uisync.UiSyncTarget
import com.labteto.dshmobile.core.wire.dto.ManagedPlugin
import com.labteto.dshmobile.core.wire.dto.titleFor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Add-ons page's rules: slot words, the choices an item offers, package names, and when the picker stacks. */
class AddonsPresentationTest {
    private fun item(
        slot: String,
        cell: String = "",
        title: String? = null,
        pkg: String = "dsh-relay",
        placement: UiSyncPlacement? = null,
        target: UiSyncTarget = UiSyncTarget.BOTH,
    ) = UiSyncStoredItem(
        id = "$pkg|$slot|$cell",
        pkg = pkg,
        slot = slot,
        cell = cell,
        title = title,
        placement = placement,
        defaultTarget = UiSyncTarget.BOTH,
        seen = "2026-11-23",
        target = target,
    )

    // ---- slot words ----

    @Test
    fun `the slots the harness lifts or the page showed raw read as words`() {
        assertEquals(AddonSlot.OVERLAY, addonSlot("shell.overlay"))
        assertEquals(AddonSlot.MESSAGE_BOX, addonSlot("conversation.input.left"))
        assertEquals(AddonSlot.MESSAGE_BOX, addonSlot("conversation.input.right"))
        assertEquals(AddonSlot.SESSION_HEADER, addonSlot("conversation.session.header.actions"))
        assertEquals(AddonSlot.PLUGIN_ROW, addonSlot("plugins.row.config"))
        assertEquals(AddonSlot.SIDEBAR_FOOTER, addonSlot("sidebar.footer.action"))
    }

    @Test
    fun `a slot the app has no words for is other, whatever it looks like`() {
        assertEquals(AddonSlot.OTHER, addonSlot("sidebar.toggle.badge"))
        assertEquals(AddonSlot.OTHER, addonSlot("settings.plugins.tab"))
        assertEquals(AddonSlot.OTHER, addonSlot(""))
        // Ids are exact: a near miss is not a known slot.
        assertEquals(AddonSlot.OTHER, addonSlot("plugins.row.config.extra"))
        assertEquals(AddonSlot.OTHER, addonSlot("Shell.Overlay"))
    }

    @Test
    fun `two items with one title in different known slots need no id line`() {
        val row = item("plugins.row.config", cell = "relay", title = "Relay")
        val footer = item("sidebar.footer.action", cell = "relay", title = "Relay")
        assertNull(addonIdLine(row, listOf(row, footer)))
        assertNull(addonIdLine(footer, listOf(row, footer)))
    }

    @Test
    fun `an unknown slot keeps its own id as the small line`() {
        val odd = item("sidebar.toggle.badge", cell = "x", title = "Badge")
        assertEquals("sidebar.toggle.badge", addonIdLine(odd, listOf(odd)))
    }

    @Test
    fun `two items with one title in one kind of slot are told apart by their cells`() {
        val left = item("conversation.input.left", cell = "first", title = "Treat")
        val right = item("conversation.input.right", cell = "second", title = "Treat")
        assertEquals("first", addonIdLine(left, listOf(left, right)))
        assertEquals("second", addonIdLine(right, listOf(left, right)))
        // No cell to show: the slot id is what is left.
        val bare = item("conversation.input.left", cell = "", title = "Treat")
        assertEquals("conversation.input.left", addonIdLine(bare, listOf(bare, right)))
    }

    @Test
    fun `an item alone with its title needs no id line`() {
        val only = item("shell.overlay", cell = "cat", title = "Cat", pkg = "dsh-ui-sync-example-pet")
        assertNull(addonIdLine(only, listOf(only)))
    }

    // ---- the choices an item offers ----

    @Test
    fun `a slot with a place on the phone offers the four choices in order`() {
        assertEquals(
            listOf(AddonOption.BOTH, AddonOption.PHONE, AddonOption.DESKTOP, AddonOption.OFF),
            addonOptions(phoneCanShow = true),
        )
    }

    @Test
    fun `a slot with no place on the phone offers only show on desktop and off`() {
        assertEquals(listOf(AddonOption.SHOW_ON_DESKTOP, AddonOption.OFF), addonOptions(phoneCanShow = false))
        assertFalse(AddonOption.PHONE in addonOptions(false))
        assertFalse(AddonOption.BOTH in addonOptions(false))
    }

    @Test
    fun `with a phone slot every stored target reads as its own choice`() {
        assertEquals(AddonOption.BOTH, addonOptionOf(UiSyncTarget.BOTH, true))
        assertEquals(AddonOption.PHONE, addonOptionOf(UiSyncTarget.PHONE, true))
        assertEquals(AddonOption.DESKTOP, addonOptionOf(UiSyncTarget.DESKTOP, true))
        assertEquals(AddonOption.OFF, addonOptionOf(UiSyncTarget.OFF, true))
    }

    @Test
    fun `with no phone slot both and desktop read as shown, phone and off as off`() {
        assertEquals(AddonOption.SHOW_ON_DESKTOP, addonOptionOf(UiSyncTarget.BOTH, false))
        assertEquals(AddonOption.SHOW_ON_DESKTOP, addonOptionOf(UiSyncTarget.DESKTOP, false))
        assertEquals(AddonOption.OFF, addonOptionOf(UiSyncTarget.PHONE, false))
        assertEquals(AddonOption.OFF, addonOptionOf(UiSyncTarget.OFF, false))
    }

    @Test
    fun `whatever is stored, the picker has a choice picked and offers it`() {
        listOf(true, false).forEach { phoneCanShow ->
            UiSyncTarget.entries.forEach { target ->
                assertTrue("$target phone=$phoneCanShow", addonOptionOf(target, phoneCanShow) in addonOptions(phoneCanShow))
            }
        }
    }

    @Test
    fun `choosing on or off for a desktop only item writes both or off, never phone or desktop`() {
        assertEquals(UiSyncTarget.BOTH, AddonOption.SHOW_ON_DESKTOP.stores)
        assertEquals(UiSyncTarget.OFF, AddonOption.OFF.stores)
        addonOptions(phoneCanShow = false).forEach {
            assertTrue(it.stores == UiSyncTarget.BOTH || it.stores == UiSyncTarget.OFF)
        }
        // The four phone choices store themselves.
        assertEquals(UiSyncTarget.PHONE, AddonOption.PHONE.stores)
        assertEquals(UiSyncTarget.DESKTOP, AddonOption.DESKTOP.stores)
    }

    // ---- package names ----

    @Test
    fun `a package name reads as words, not capitals`() {
        assertEquals("Relay", readableAddonPackage("dsh-relay"))
        assertEquals("UI Sync Example Pet", readableAddonPackage("dsh-ui-sync-example-pet"))
        assertEquals("Relay", readableAddonPackage("@deepseek-ai/dsh-relay"))
        assertEquals("Cool Thing", readableAddonPackage("cool_thing"))
        assertEquals("Foo Bar", readableAddonPackage("@someone/foo.bar"))
    }

    @Test
    fun `a package name with nothing readable in it stays as it is`() {
        assertEquals("---", readableAddonPackage("---"))
        // The bare prefix is the whole name: it is not dropped to nothing.
        assertEquals("Dsh", readableAddonPackage("dsh-"))
    }

    @Test
    fun `the plugin's own title wins over the readable name`() {
        val titles = mapOf("dsh-relay" to "Phone Relay")
        assertEquals("Phone Relay", addonPackageTitle("dsh-relay", titles))
        assertEquals("UI Sync Example Pet", addonPackageTitle("dsh-ui-sync-example-pet", titles))
    }

    @Test
    fun `a module under the package's path supplies its title`() {
        assertEquals("Pet Friend", addonPackageTitle("dsh-pet", mapOf("dsh-pet/plugin" to "Pet Friend")))
        // A package that merely starts with the same letters is another package: the readable name stays.
        assertEquals("Pet", addonPackageTitle("dsh-pet", mapOf("dsh-pets/plugin" to "Pets")))
    }

    @Test
    fun `titles come from the plugin list in the best language it has`() {
        val plugins = Json { ignoreUnknownKeys = true }.decodeFromString<List<ManagedPlugin>>(
            """
            [
              {"entryId":"a","moduleName":"dsh-relay","enabled":true,"fiberPhase":"active","patchId":"p",
               "meta":{"title":{"en":"Relay","zh-cn":"中继","ru":"Ретранслятор"},"description":"x","icon":"data:image/png;base64,AAAA"}},
              {"entryId":"b","moduleName":"dsh-ui-sync-example-pet","enabled":true,"patchId":"p","meta":{"title":"Example pet"}},
              {"entryId":"c","moduleName":"dsh-nometa","enabled":true,"patchId":"p"},
              {"entryId":"d","moduleName":"dsh-blank","enabled":false,"readOnlyReason":"unmanaged","meta":{"title":"  "}}
            ]
            """.trimIndent(),
        )
        assertEquals(
            mapOf("dsh-relay" to "中继", "dsh-ui-sync-example-pet" to "Example pet"),
            addonTitleMap(plugins, listOf("zh-CN", "zh")),
        )
        assertEquals("Relay", addonTitleMap(plugins, listOf("de-DE", "de")).getValue("dsh-relay"))
        assertEquals("Ретранслятор", addonTitleMap(plugins, listOf("ru-RU", "ru")).getValue("dsh-relay"))
    }

    @Test
    fun `a title with no english and no match falls back to any translation it has`() {
        val plugins = Json { ignoreUnknownKeys = true }.decodeFromString<List<ManagedPlugin>>(
            """[{"entryId":"a","moduleName":"m","enabled":true,"patchId":"p","meta":{"title":{"fr":"Relais"}}}]""",
        )
        assertEquals("Relais", plugins.single().meta!!.titleFor(listOf("de")))
    }

    @Test
    fun `a plugin entry without meta or with an odd title decodes and has no title`() {
        val json = Json { ignoreUnknownKeys = true }
        val plain = json.decodeFromString<ManagedPlugin>("""{"entryId":"a","moduleName":"m","enabled":true,"patchId":"p"}""")
        assertNull(plain.meta)
        val odd = json.decodeFromString<ManagedPlugin>("""{"entryId":"a","moduleName":"m","enabled":true,"patchId":"p","meta":{"title":42}}""")
        assertNull(odd.meta!!.titleFor(listOf("en")))
        val empty = json.decodeFromString<ManagedPlugin>("""{"entryId":"a","moduleName":"m","enabled":true,"patchId":"p","meta":{}}""")
        assertNull(empty.meta!!.titleFor(listOf("en")))
    }

    // ---- the picker's fit ----

    @Test
    fun `labels that fit side by side keep the row`() {
        // Four labels of 60 px, 20 px of padding each side, 3 gaps of 4 px, 16 px of track: 4 * 100 + 12 + 16 = 428.
        assertTrue(addonPickerFitsOneLine(listOf(60, 60, 60, 60), available = 428, segmentPadding = 20, gap = 4, chrome = 16))
        assertFalse(addonPickerFitsOneLine(listOf(60, 60, 60, 60), available = 427, segmentPadding = 20, gap = 4, chrome = 16))
    }

    @Test
    fun `a bigger font or a longer word stacks the choices`() {
        val narrow = 1080 - 2 * 48 - 2 * 60
        // Default font: short labels fit a phone.
        assertTrue(addonPickerFitsOneLine(listOf(90, 120, 150, 55), narrow, segmentPadding = 22, gap = 6, chrome = 16))
        // The same labels at twice the size do not.
        assertFalse(addonPickerFitsOneLine(listOf(180, 240, 300, 110), narrow, segmentPadding = 22, gap = 6, chrome = 16))
        // One long word is enough.
        assertFalse(addonPickerFitsOneLine(listOf(90, 120, 400, 55), narrow, segmentPadding = 22, gap = 6, chrome = 16))
    }

    @Test
    fun `two choices need less room than four`() {
        assertTrue(addonPickerFitsOneLine(listOf(300, 55), available = 800, segmentPadding = 22, gap = 6, chrome = 16))
        assertTrue(addonPickerFitsOneLine(emptyList(), available = 0, segmentPadding = 22, gap = 6, chrome = 16))
    }
}
