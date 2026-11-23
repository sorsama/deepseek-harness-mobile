package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovery: when the item store looks incomplete, how often the app may look on its own, and how
 * a look ends. Names avoid commas and slashes (Kotlin Native test names).
 */
class UiSyncDiscoveryTest {
    private val pet = "dsh-ui-sync-example-pet|shell.overlay|example-pet.pet"
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"

    private fun store(items: String?) = uiSyncItemStore(
        Json.parseToJsonElement(if (items == null) """{"targets":{}}""" else """{"targets":{},"items":$items}"""),
    )

    private val empty = store("{}")
    private val withPet = store(
        """{
        "$pet": {"package":"dsh-ui-sync-example-pet","slot":"shell.overlay","cell":"example-pet.pet","title":"Cat","placement":"overlay","defaultTarget":"both","seen":"2026-11-23"},
        "$treat": {"package":"dsh-ui-sync-example-pet","slot":"conversation.input.right","cell":"example-pet.treat","title":null,"placement":"input-right","defaultTarget":"both","seen":"2026-11-23"}
        }""",
    )

    private fun row(name: String, enabled: Boolean = true) = PluginInventoryEntry(entryId = name, moduleName = name, enabled = enabled)

    private val core = listOf(row("@deepseek-ai/dsh-client-ui-plan"), row("@deepseek-ai/dsh-host-plugin-inventory"), row("cordis:include"))
    private val companion = row("dsh-ui-sync")
    private val petRow = row("dsh-ui-sync-example-pet")

    // ------------------------------------------------------------------ plugin list

    @Test
    fun `harness and companion rows are not third party packages`() {
        assertNull(uiSyncInventoryPackage("@deepseek-ai/dsh-client-ui-plan"))
        assertNull(uiSyncInventoryPackage("@deepseek-ai/dsh-client-ui-plan/gateway"))
        assertNull(uiSyncInventoryPackage("cordis:include"))
        assertNull(uiSyncInventoryPackage("dsh-ui-sync"))
        assertNull(uiSyncInventoryPackage("  "))
    }

    @Test
    fun `a subpath row belongs to its package`() {
        assertEquals("dsh-ui-sync-example-pet", uiSyncInventoryPackage("dsh-ui-sync-example-pet"))
        assertEquals("dsh-ui-sync-example-pet", uiSyncInventoryPackage("dsh-ui-sync-example-pet/host"))
        assertEquals("@someone/pet", uiSyncInventoryPackage("@someone/pet/host"))
        // The companion's own subpath rows are the companion.
        assertNull(uiSyncInventoryPackage("dsh-ui-sync/host"))
    }

    @Test
    fun `a row loaded from a folder has no package name to compare`() {
        assertEquals("?file:///G:/LAB/relay/lib/index.js", uiSyncInventoryPackage("file:///G:/LAB/relay/lib/index.js"))
        assertEquals("?./local", uiSyncInventoryPackage("./local"))
        assertEquals("?/opt/plugin", uiSyncInventoryPackage("/opt/plugin"))
        assertEquals("?C:\\plugins\\pet", uiSyncInventoryPackage("C:\\plugins\\pet"))
    }

    @Test
    fun `only enabled third party packages count and each once`() {
        val list = core + companion + petRow + row("dsh-ui-sync-example-pet/host") + row("other-plugin", enabled = false) + row("@x/notes")
        assertEquals(listOf("@x/notes", "dsh-ui-sync-example-pet"), uiSyncThirdPartyPackages(list))
        assertEquals("@x/notes\ndsh-ui-sync-example-pet", uiSyncPluginFingerprint(list))
        assertEquals(UI_SYNC_UNKNOWN_PLUGINS, uiSyncPluginFingerprint(null))
        // The order rows come in does not change the plugin set.
        assertEquals(uiSyncPluginFingerprint(list), uiSyncPluginFingerprint(list.reversed()))
    }

    // ------------------------------------------------------------------ staleness

    @Test
    fun `an empty store needs a look when a third party plugin is enabled`() {
        val need = uiSyncDiscoveryNeed(empty, core + companion + petRow)
        assertEquals(UiSyncDiscoveryReason.EMPTY_STORE, need?.reason)
        assertEquals("dsh-ui-sync-example-pet", need?.fingerprint)
    }

    @Test
    fun `an empty store with only harness plugins needs nothing`() {
        // No plugin that could register an item: a look by hand can still say "Nothing found".
        assertNull(uiSyncDiscoveryNeed(empty, core + companion))
        assertNull(uiSyncDiscoveryNeed(empty, core + companion + row("other-plugin", enabled = false)))
    }

    @Test
    fun `an empty store with an unreadable plugin list needs a look`() {
        val need = uiSyncDiscoveryNeed(empty, null)
        assertEquals(UiSyncDiscoveryReason.EMPTY_STORE, need?.reason)
        assertEquals(UI_SYNC_UNKNOWN_PLUGINS, need?.fingerprint)
    }

    @Test
    fun `a store that knows every third party plugin needs nothing`() {
        assertNull(uiSyncDiscoveryNeed(withPet, core + companion + petRow))
        // With the plugin list unreadable a store that has items is left alone.
        assertNull(uiSyncDiscoveryNeed(withPet, null))
    }

    @Test
    fun `a plugin with nothing in the store makes it stale`() {
        val need = uiSyncDiscoveryNeed(withPet, core + companion + petRow + row("new-plugin"))
        assertEquals(UiSyncDiscoveryReason.NEW_PACKAGES, need?.reason)
        assertEquals("dsh-ui-sync-example-pet\nnew-plugin", need?.fingerprint)
    }

    @Test
    fun `a companion without an item store needs nothing`() {
        assertNull(uiSyncDiscoveryNeed(store(null), core + petRow))
    }

    // ------------------------------------------------------------------ rate limit

    private val hour = 60L * 60 * 1000
    private val need = UiSyncDiscoveryNeed(UiSyncDiscoveryReason.EMPTY_STORE, "dsh-ui-sync-example-pet")

    @Test
    fun `the first look for a host is allowed`() {
        assertTrue(uiSyncAutoDiscoveryAllowed(null, need, 1_000))
    }

    @Test
    fun `the same plugin set is looked at once`() {
        assertFalse(uiSyncAutoDiscoveryAllowed(UiSyncDiscoveryRecord(need.fingerprint, 0, UiSyncDiscoveryOutcome.NOTHING), need, 10 * 24 * hour))
        // A plugin that never gets an item (no web part) does not make the app look on every start.
        val missing = UiSyncDiscoveryNeed(UiSyncDiscoveryReason.NEW_PACKAGES, "dsh-ui-sync-example-pet\nhost-only")
        assertFalse(uiSyncAutoDiscoveryAllowed(UiSyncDiscoveryRecord(missing.fingerprint, 0, UiSyncDiscoveryOutcome.FOUND), missing, 10 * 24 * hour))
    }

    @Test
    fun `a store emptied after a look that found items is looked at again`() {
        // The harness was set up afresh at the same address: same plugins, nothing stored.
        val record = UiSyncDiscoveryRecord(need.fingerprint, 0, UiSyncDiscoveryOutcome.FOUND)
        assertFalse(uiSyncAutoDiscoveryAllowed(record, need, UI_SYNC_DISCOVERY_MIN_GAP_MS - 1))
        assertTrue(uiSyncAutoDiscoveryAllowed(record, need, UI_SYNC_DISCOVERY_MIN_GAP_MS))
    }

    @Test
    fun `a failed look is tried again after a while`() {
        val record = UiSyncDiscoveryRecord(need.fingerprint, 0, UiSyncDiscoveryOutcome.FAILED)
        assertFalse(uiSyncAutoDiscoveryAllowed(record, need, UI_SYNC_DISCOVERY_FAILED_RETRY_MS - 1))
        assertTrue(uiSyncAutoDiscoveryAllowed(record, need, UI_SYNC_DISCOVERY_FAILED_RETRY_MS))
    }

    @Test
    fun `a new plugin set is looked at after a short gap`() {
        val record = UiSyncDiscoveryRecord("dsh-ui-sync-example-pet", 0, UiSyncDiscoveryOutcome.FOUND)
        val grown = UiSyncDiscoveryNeed(UiSyncDiscoveryReason.NEW_PACKAGES, "dsh-ui-sync-example-pet\nnew-plugin")
        assertFalse(uiSyncAutoDiscoveryAllowed(record, grown, UI_SYNC_DISCOVERY_MIN_GAP_MS - 1))
        assertTrue(uiSyncAutoDiscoveryAllowed(record, grown, UI_SYNC_DISCOVERY_MIN_GAP_MS))
    }

    @Test
    fun `an unreadable plugin list is looked at again once a day at most`() {
        val unknown = UiSyncDiscoveryNeed(UiSyncDiscoveryReason.EMPTY_STORE, UI_SYNC_UNKNOWN_PLUGINS)
        val record = UiSyncDiscoveryRecord(UI_SYNC_UNKNOWN_PLUGINS, 0, UiSyncDiscoveryOutcome.NOTHING)
        assertFalse(uiSyncAutoDiscoveryAllowed(record, unknown, UI_SYNC_DISCOVERY_UNKNOWN_RETRY_MS - 1))
        assertTrue(uiSyncAutoDiscoveryAllowed(record, unknown, UI_SYNC_DISCOVERY_UNKNOWN_RETRY_MS))
    }

    @Test
    fun `a clock that went backwards allows a look`() {
        val record = UiSyncDiscoveryRecord(need.fingerprint, 5 * hour, UiSyncDiscoveryOutcome.NOTHING)
        assertTrue(uiSyncAutoDiscoveryAllowed(record, need, hour))
    }

    @Test
    fun `the page offers a look until this plugin set was looked at`() {
        assertFalse(uiSyncDiscoveryStale(null, null))
        assertTrue(uiSyncDiscoveryStale(need, null))
        assertTrue(uiSyncDiscoveryStale(need, UiSyncDiscoveryRecord("older set", 0, UiSyncDiscoveryOutcome.FOUND)))
        assertFalse(uiSyncDiscoveryStale(need, UiSyncDiscoveryRecord(need.fingerprint, 0, UiSyncDiscoveryOutcome.NOTHING)))
        assertTrue(uiSyncDiscoveryStale(need, UiSyncDiscoveryRecord(need.fingerprint, 0, UiSyncDiscoveryOutcome.FAILED)))
    }

    @Test
    fun `a record survives its one line form`() {
        val record = UiSyncDiscoveryRecord("a\nb\tc", 1_234_567, UiSyncDiscoveryOutcome.NOTHING)
        assertEquals(record, UiSyncDiscoveryRecord.decode(record.encode()))
        assertNull(UiSyncDiscoveryRecord.decode(null))
        assertNull(UiSyncDiscoveryRecord.decode("garbage"))
        assertNull(UiSyncDiscoveryRecord.decode("MAYBE\t1\tx"))
        assertNull(UiSyncDiscoveryRecord.decode("FOUND\tnot a time\tx"))
    }

    // ------------------------------------------------------------------ the run

    private val timing = UiSyncDiscoveryTiming(loadTimeoutMs = 30_000, itemsTimeoutMs = 15_000, quietMs = 5_000, settleMs = 2_000)

    private fun outcome(discovery: UiSyncDiscovery): UiSyncDiscoveryOutcome? = (discovery.state.value as? UiSyncDiscoveryPhase.Done)?.outcome

    @Test
    fun `a page whose items reach the store finds them`() {
        val d = UiSyncDiscovery(timing)
        val run = d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        assertTrue(d.running)
        d.onPageReady(4_000)
        d.onPageItems(setOf(pet, treat), 4_500)
        // Not in the store yet: still running.
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(7_000, empty))
        // In the store, but the page's list changed less than the settle time ago.
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(6_000, withPet))
        val done = d.check(6_600, withPet)
        assertIs<UiSyncDiscoveryPhase.Done>(done)
        assertEquals(run, done.run)
        assertEquals(UiSyncDiscoveryOutcome.FOUND, done.outcome)
        assertFalse(d.running)
    }

    @Test
    fun `a late second item restarts the settle time`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageReady(1_000)
        d.onPageItems(setOf(pet), 1_000)
        d.onPageItems(setOf(pet, treat), 2_900)
        // The pet alone was settled, but the list changed: wait for the treat to settle too.
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(3_100, withPet))
        assertEquals(UiSyncDiscoveryOutcome.FOUND, (d.check(4_900, withPet) as UiSyncDiscoveryPhase.Done).outcome)
    }

    @Test
    fun `a page that reports no item and stays quiet finds nothing`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.MANUAL, 0)
        d.onPageReady(3_000)
        d.onPageItems(emptySet(), 3_100)
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(8_000, empty))
        assertEquals(UiSyncDiscoveryOutcome.NOTHING, (d.check(8_100, empty) as UiSyncDiscoveryPhase.Done).outcome)
    }

    @Test
    fun `no new item but a store that has some is found`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.MANUAL, 0)
        d.onPageReady(1_000)
        d.onPageItems(emptySet(), 1_000)
        d.check(6_000, withPet)
        assertEquals(UiSyncDiscoveryOutcome.FOUND, outcome(d))
    }

    @Test
    fun `items before ready do not count`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageItems(setOf(pet), 100)
        assertNull((d.state.value as UiSyncDiscoveryPhase.Running).pageItems)
    }

    @Test
    fun `a ready again forgets the list but keeps the clock`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageReady(1_000)
        d.onPageItems(setOf(pet), 1_200)
        d.onPageReady(1_300)
        val run = d.state.value as UiSyncDiscoveryPhase.Running
        assertEquals(1_000L, run.readyAt)
        assertNull(run.pageItems)
    }

    @Test
    fun `a page that never comes up fails`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(29_999, empty))
        d.check(30_000, empty)
        assertEquals(UiSyncDiscoveryOutcome.FAILED, outcome(d))
    }

    @Test
    fun `items that never reach the store fail`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.MANUAL, 0)
        d.onPageReady(2_000)
        d.onPageItems(setOf(pet, treat), 2_100)
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(16_999, empty))
        d.check(17_000, empty)
        assertEquals(UiSyncDiscoveryOutcome.FAILED, outcome(d))
    }

    @Test
    fun `a page that says nothing after ready fails unless the store has items`() {
        val silent = UiSyncDiscovery(timing)
        silent.start(UiSyncDiscoveryTrigger.AUTO, 0)
        silent.onPageReady(1_000)
        silent.check(16_000, empty)
        assertEquals(UiSyncDiscoveryOutcome.FAILED, outcome(silent))

        val known = UiSyncDiscovery(timing)
        known.start(UiSyncDiscoveryTrigger.AUTO, 0)
        known.onPageReady(1_000)
        known.check(16_000, withPet)
        assertEquals(UiSyncDiscoveryOutcome.FOUND, outcome(known))
    }

    @Test
    fun `an unknown store keeps the run waiting`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageReady(1_000)
        d.onPageItems(emptySet(), 1_000)
        assertIs<UiSyncDiscoveryPhase.Running>(d.check(9_000, null))
    }

    @Test
    fun `a page failure ends the run at once`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.MANUAL, 0)
        d.onPageFailed(500)
        assertEquals(UiSyncDiscoveryOutcome.FAILED, outcome(d))
        // A late page message changes nothing.
        d.onPageReady(600)
        d.onPageItems(setOf(pet), 700)
        assertEquals(UiSyncDiscoveryOutcome.FAILED, outcome(d))
    }

    @Test
    fun `a request while one runs joins it and a person makes it theirs`() {
        val d = UiSyncDiscovery(timing)
        val auto = d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageReady(1_000)
        val manual = d.start(UiSyncDiscoveryTrigger.MANUAL, 2_000)
        assertEquals(auto, manual)
        val run = d.state.value as UiSyncDiscoveryPhase.Running
        assertEquals(UiSyncDiscoveryTrigger.MANUAL, run.trigger)
        // Joining does not restart the clocks.
        assertEquals(0L, run.startedAt)
        assertEquals(1_000L, run.readyAt)
    }

    @Test
    fun `each run gets its own number`() {
        val d = UiSyncDiscovery(timing)
        val first = d.start(UiSyncDiscoveryTrigger.AUTO, 0)
        d.onPageFailed(1)
        val second = d.start(UiSyncDiscoveryTrigger.MANUAL, 2)
        assertTrue(second > first)
        assertEquals(UiSyncDiscoveryTrigger.MANUAL, (d.state.value as UiSyncDiscoveryPhase.Running).trigger)
    }

    @Test
    fun `a host change forgets the run`() {
        val d = UiSyncDiscovery(timing)
        d.start(UiSyncDiscoveryTrigger.MANUAL, 0)
        d.cancel()
        assertEquals(UiSyncDiscoveryPhase.Idle, d.state.value)
        assertFalse(d.running)
        // A check after that changes nothing.
        assertEquals(UiSyncDiscoveryPhase.Idle, d.check(60_000, empty))
    }
}
