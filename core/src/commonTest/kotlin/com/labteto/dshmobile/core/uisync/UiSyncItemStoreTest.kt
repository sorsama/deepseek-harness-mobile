package com.labteto.dshmobile.core.uisync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The item store read the way `itemsFromSettings` in `dsh-ui-sync/protocol` reads it: the effective
 * target of each item, and whether anything would show on the phone. Names avoid commas and slashes.
 */
class UiSyncItemStoreTest {
    private val pet = "dsh-ui-sync-example-pet|shell.overlay|example-pet.pet"
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"
    private val sidebar = "other-plugin|sidebar.right.pane.tab|notes"

    private fun store(targets: String = "{}", items: String? = null) = uiSyncItemStore(
        Json.parseToJsonElement(
            if (items == null) """{"targets":$targets}""" else """{"targets":$targets,"items":$items}""",
        ),
    )

    private val items = """{
        "$pet": {"package":"dsh-ui-sync-example-pet","slot":"shell.overlay","cell":"example-pet.pet","title":"Cat","placement":"overlay","defaultTarget":"both","seen":"2026-11-23"},
        "$treat": {"package":"dsh-ui-sync-example-pet","slot":"conversation.input.right","cell":"example-pet.treat","title":"Treat button","placement":"input-right","defaultTarget":"both","seen":"2026-11-23"},
        "$sidebar": {"package":"other-plugin","slot":"sidebar.right.pane.tab","cell":"notes","title":null,"placement":null,"defaultTarget":"both","seen":"2026-11-20"}
    }"""

    @Test
    fun `the effective target is the stored one else the default`() {
        val read = store(targets = """{"$pet":"desktop","$sidebar":"nonsense"}""", items = items)
        assertEquals(listOf(pet, sidebar, treat).sorted(), read.items.map { it.id })
        val byId = read.items.associateBy { it.id }
        assertEquals(UiSyncTarget.DESKTOP, byId.getValue(pet).target)
        assertEquals(UiSyncTarget.BOTH, byId.getValue(treat).target)
        // A value that is not a target reads as the default, as the reference reader does.
        assertEquals(UiSyncTarget.BOTH, byId.getValue(sidebar).target)
        assertEquals(UiSyncPlacement.INPUT_RIGHT, byId.getValue(treat).placement)
        assertEquals(null, byId.getValue(sidebar).placement)
    }

    @Test
    fun `the page is worth loading only when a lifted item targets the phone`() {
        assertTrue(store(items = items).worthLoading)
        assertEquals(listOf(pet, treat).sorted(), store(items = items).phone.map { it.id })

        val desktopOnly = store(targets = """{"$pet":"desktop","$treat":"off"}""", items = items)
        // The sidebar item targets both, but its slot is not lifted on the phone.
        assertFalse(desktopOnly.worthLoading)
        assertTrue(desktopOnly.phone.isEmpty())

        val phoneOnly = store(targets = """{"$pet":"phone","$treat":"off"}""", items = items)
        assertTrue(phoneOnly.worthLoading)
        assertEquals(listOf(pet), phoneOnly.phone.map { it.id })
    }

    @Test
    fun `an empty store means nothing to load`() {
        val empty = store(items = "{}")
        assertTrue(empty.hasStore)
        assertFalse(empty.worthLoading)
    }

    @Test
    fun `a companion without an item store cannot say so the page loads as before`() {
        val legacy = store(targets = """{"$pet":"desktop"}""")
        assertFalse(legacy.hasStore)
        assertTrue(legacy.worthLoading)
        assertFalse(uiSyncItemStore(JsonNull).hasStore)
        assertFalse(uiSyncItemStore(null).hasStore)
    }

    @Test
    fun `broken entries are left out`() {
        val read = store(
            items = """{
                "good": {"package":"p","slot":"shell.overlay","cell":"c","title":"T","placement":"overlay","defaultTarget":"phone","seen":"2026-11-23"},
                "no-package": {"slot":"shell.overlay","cell":"c","defaultTarget":"both","seen":"2026-11-23"},
                "bad-default": {"package":"p","slot":"s","cell":"c","defaultTarget":"everywhere","seen":"2026-11-23"},
                "no-seen": {"package":"p","slot":"s","cell":"c","defaultTarget":"both"},
                "odd-placement": {"package":"p","slot":"s","cell":"c","placement":"floating","defaultTarget":"both","seen":"2026-11-23"},
                "not-an-object": 5,
                "": {"package":"p","slot":"s","cell":"c","defaultTarget":"both","seen":"2026-11-23"}
            }""",
        )
        assertEquals(listOf("good", "odd-placement"), read.items.map { it.id })
        assertEquals(UiSyncTarget.PHONE, read.items.first().target)
        assertEquals(null, read.items.last().placement)
    }

    @Test
    fun `items group by package in a stable order`() {
        val groups = store(items = items).byPackage
        assertEquals(listOf("dsh-ui-sync-example-pet", "other-plugin"), groups.map { it.first })
        assertEquals(2, groups.first().second.size)
    }

    @Test
    fun `an item without a label gets a readable name`() {
        val byId = store(items = items).items.associateBy { it.id }
        assertEquals("Cat", byId.getValue(pet).displayTitle)
        assertEquals("Notes", byId.getValue(sidebar).displayTitle)
        val bare = UiSyncStoredItem("p|shell.overlay|", "p", "shell.overlay", "", null, UiSyncPlacement.OVERLAY, UiSyncTarget.BOTH, "2026-11-23", UiSyncTarget.BOTH)
        assertEquals("Shell overlay", bare.displayTitle)
        val keyed = bare.copy(cell = "example-pet.give_treat", title = "  ")
        assertEquals("Give treat", keyed.displayTitle)
        assertEquals("Relay", bare.copy(cell = "dsh-relay#relay").displayTitle)
        assertEquals("Odd", bare.copy(cell = "odd.").displayTitle)
    }
}
