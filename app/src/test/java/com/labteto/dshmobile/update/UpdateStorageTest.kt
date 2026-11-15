package com.labteto.dshmobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What the updater keeps on disk between launches: downloads, their records, and the "just updated" marker. */
class UpdateStorageTest {

    @get:Rule val temp = TemporaryFolder()

    // ---- Stale downloads ----------------------------------------------------------------------

    @Test
    fun `only the release being worked on survives cleanup`() {
        val names = listOf(
            "dsh-0.22.0.part", "dsh-0.22.0.json",
            "dsh-0.21.5.apk", "dsh-0.21.5.json",
            "dsh-0.22.0.json.tmp", "stray.bin",
        )
        assertEquals(
            listOf("dsh-0.21.5.apk", "dsh-0.21.5.json", "dsh-0.22.0.json.tmp", "stray.bin"),
            staleDownloadFiles(names, currentVersion = "0.21.1", keepVersion = "0.22.0"),
        )
    }

    @Test
    fun `the release that is now installed is cleaned up too`() {
        val names = listOf("dsh-0.22.0.apk", "dsh-0.22.0.json")
        assertEquals(names, staleDownloadFiles(names, currentVersion = "0.22.0", keepVersion = "0.22.0"))
        assertEquals(names, staleDownloadFiles(names, currentVersion = "0.21.1", keepVersion = null))
    }

    @Test
    fun `version strings become safe file names`() {
        assertEquals("dsh-0.22.0-rc1", downloadStem("0.22.0-rc1"))
        assertEquals("dsh-.._.._etc", downloadStem("../../etc"))
    }

    @Test
    fun `the store resumes promotes and forgets`() {
        val dir = File(temp.root, "updates")
        val store = DownloadStore(dir)
        val update = AvailableUpdate(
            "0.22.0", "https://github.com/r",
            ReleaseAsset("app-release.apk", "https://github.com/a.apk", 30),
            ReleaseAsset("SHA256SUMS.txt", "https://github.com/s.txt"),
            notes = "## Fixes",
        )
        store.save(DownloadRecord.of(update, update.apk!!).copy(validator = "\"e\"", total = 30))
        store.partFile("0.22.0").writeBytes(ByteArray(12))
        assertEquals(12L, store.bytesOnDisk("0.22.0"))

        val restored = store.newest()!!
        assertEquals("\"e\"", restored.validator)
        assertEquals(update, restored.toUpdate())

        store.partFile("0.22.0").writeBytes(ByteArray(30))
        val apk = store.promote("0.22.0")
        assertTrue(apk.isFile)
        assertFalse(store.partFile("0.22.0").exists())
        assertEquals(30L, store.bytesOnDisk("0.22.0"))

        File(dir, "dsh-0.20.0.apk").writeBytes(ByteArray(3))
        store.cleanup(currentVersion = "0.21.1", keepVersion = "0.22.0")
        assertEquals(setOf("dsh-0.22.0.apk", "dsh-0.22.0.json"), dir.list()!!.toSet())

        store.clear("0.22.0")
        assertEquals(emptySet<String>(), dir.list()!!.toSet())
        assertNull(store.newest())
    }

    @Test
    fun `the newest record wins when several are on disk`() {
        val store = DownloadStore(File(temp.root, "updates"))
        listOf("0.21.9", "0.22.0", "0.21.10").forEach { version ->
            store.save(DownloadRecord(version, "r", null, "app-release.apk", "u"))
        }
        assertEquals("0.22.0", store.newest()?.version)
    }

    // ---- "Updated to" -------------------------------------------------------------------------

    private class MemoryPrefs : UpdaterPrefs {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String?) {
            if (value == null) strings.remove(key) else strings[key] = value
        }
        override fun getLong(key: String) = longs[key] ?: 0L
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private val marker = UpdateMarker("0.22.0", 2200, "## Fixes", "https://github.com/r")

    @Test
    fun `the confirmation shows once after the update lands`() {
        val prefs = MemoryPrefs()
        JustUpdatedTracker(prefs, 2101, "0.21.1").markPending(marker)

        val after = JustUpdatedTracker(prefs, 2200, "0.22.0")
        assertEquals(JustUpdated("0.22.0", "## Fixes", "https://github.com/r"), after.consume())
        assertNull("only once", after.consume())
        assertNull(JustUpdatedTracker(prefs, 2200, "0.22.0").consume())
    }

    @Test
    fun `nothing shows while the install has not happened and the marker waits`() {
        val prefs = MemoryPrefs()
        val before = JustUpdatedTracker(prefs, 2101, "0.21.1")
        before.markPending(marker)
        assertNull(before.consume())
        assertEquals(marker, before.pending())
    }

    @Test
    fun `a failed install clears the marker`() {
        val prefs = MemoryPrefs()
        val tracker = JustUpdatedTracker(prefs, 2101, "0.21.1")
        tracker.markPending(marker)
        tracker.clear()
        assertNull(JustUpdatedTracker(prefs, 2200, "0.22.0").consume())
    }

    @Test
    fun `a marker without a version code falls back to the version name`() {
        val prefs = MemoryPrefs()
        JustUpdatedTracker(prefs, 1, "0.21.1").markPending(marker.copy(versionCode = 0))
        assertEquals("0.22.0", JustUpdatedTracker(prefs, 2200, "0.22.0").consume()?.version)
    }

    @Test
    fun `a corrupt marker is dropped`() {
        val prefs = MemoryPrefs()
        prefs.putString("pending_update", "{not json")
        assertNull(JustUpdatedTracker(prefs, 2200, "0.22.0").consume())
        assertTrue(prefs.strings.isEmpty())
    }
}
