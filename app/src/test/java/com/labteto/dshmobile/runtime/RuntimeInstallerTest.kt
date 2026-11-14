package com.labteto.dshmobile.runtime

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fx: RuntimeFixture

    @Before
    fun setUp() {
        fx = RuntimeFixture(tmp)
    }

    @After
    fun tearDown() = fx.close()

    private fun available(installer: RuntimeInstaller): RuntimeInstaller.CheckResult.Available = runBlocking {
        val check = installer.check()
        check as? RuntimeInstaller.CheckResult.Available ?: error("expected an available release, got $check")
    }

    private fun install(installer: RuntimeInstaller, events: MutableList<InstallEvent> = mutableListOf()): InstalledRecord = runBlocking {
        val check = available(installer)
        installer.apply(check.manifest, check.decision, onEvent = { events += it })
    }

    /** Nothing under the runtime root but what [record] names and the user-data directories. */
    private fun assertOnlyCommitted(record: InstalledRecord?) {
        val names = fx.layout.root.list().orEmpty().toSet()
        val layerDirs = names.filter { it.startsWith("base-") || it.startsWith("harness-") || it.startsWith(".staging-") }.toSet()
        val expected = record?.layerDirs().orEmpty()
        assertEquals(expected, layerDirs)
        assertEquals(record != null, fx.layout.installedFile.exists())
    }

    @Test
    fun `a fresh install downloads, verifies, unpacks and commits both layers`() {
        val base = fx.baseLayer()
        val harness = fx.harnessLayer("1.0.0")
        fx.publish(base, harness, sequence = 100)
        val events = mutableListOf<InstallEvent>()
        val record = install(fx.installer(), events)

        assertEquals("test-100", record.releaseId)
        assertEquals("1.0.0", record.harnessVersion)
        assertNull(record.trial)
        assertEquals(record, InstalledStore(fx.layout).read())
        assertOnlyCommitted(record)

        val rootfs = File(fx.layout.root, record.base.dir)
        assertEquals("elf", File(rootfs, "usr/bin/bash").readText())
        assertEquals("usr/bin", fx.fs.linkTarget(File(rootfs, "bin")))
        // Mount points for the binds start adds.
        assertTrue(File(rootfs, "opt/dsh").isDirectory)
        assertTrue(File(rootfs, "usr/local/bin/dsh").isFile)
        assertTrue(File(rootfs, "workspaces").isDirectory)
        val harnessDir = File(fx.layout.root, record.harness.dir)
        assertTrue(File(harnessDir, "opt/dsh/layer.json").readText().contains("1.0.0"))

        // Progress went through every phase for both layers, and the downloads are gone.
        assertTrue(events.any { it is InstallEvent.Downloading && it.progress.layer == "harness" })
        assertEquals(listOf("base", "harness"), events.filterIsInstance<InstallEvent.Verifying>().map { it.layer })
        assertTrue(events.any { it is InstallEvent.Unpacking && it.progress.layer == "base" && it.progress.entriesDone == 21 })
        assertEquals(emptyList<String>(), fx.layout.downloads.list().orEmpty().toList())
    }

    @Test
    fun `a manifest signed by another key is refused before anything is downloaded`() = runBlocking {
        val base = fx.baseLayer()
        val harness = fx.harnessLayer("1.0.0")
        fx.publish(base, harness, sequence = 100, signer = TestSigningKey())
        assertEquals(RuntimeInstaller.CheckResult.Refused(RuntimeFailure.BadSignature), fx.installer().check())
        assertTrue(fx.assetRequests(base).isEmpty())
        assertOnlyCommitted(null)
    }

    @Test
    fun `an app older than minAppVersion is refused`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100, minAppVersion = "0.22.0")
        assertEquals(
            RuntimeInstaller.CheckResult.Refused(RuntimeFailure.AppTooOld("0.22.0")),
            fx.installer(appVersion = "0.21.1").check(),
        )
    }

    @Test
    fun `too little space fails before the first byte is fetched`() = runBlocking {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        fx.freeBytes = 4096
        val installer = fx.installer()
        val check = available(installer)
        try {
            installer.apply(check.manifest, check.decision, onEvent = {})
            fail("must refuse for space")
        } catch (e: InstallException) {
            val failure = e.failure as RuntimeFailure.NoSpace
            assertEquals(4096L, failure.availableBytes)
            assertTrue(failure.neededBytes > 128L shl 20)
        }
        assertTrue(fx.assetRequests(base).isEmpty())
        assertOnlyCommitted(null)
    }

    @Test
    fun `a layer that does not match its hash is deleted and nothing is installed`() = runBlocking {
        val base = fx.baseLayer()
        val tampered = base.bytes.copyOf().also { it[600] = (it[600] + 1).toByte() }
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100, servedBase = tampered)
        val installer = fx.installer()
        val check = available(installer)
        try {
            installer.apply(check.manifest, check.decision, onEvent = {})
            fail("must refuse the tampered layer")
        } catch (e: DownloadException) {
            assertEquals(RuntimeFailure.ChecksumMismatch(base.file), e.failure)
        }
        assertOnlyCommitted(null)
        assertEquals(emptyList<String>(), fx.layout.downloads.list().orEmpty().toList())
    }

    @Test
    fun `an archive that breaks a rule halfway leaves nothing installed`() = runBlocking {
        val base = fx.baseLayer()
        val evil = fx.harnessLayer("1.0.0") { file("../../escaped", "x") }
        fx.publish(base, evil, sequence = 100)
        val installer = fx.installer()
        val check = available(installer)
        try {
            installer.apply(check.manifest, check.decision, onEvent = {})
            fail("must refuse")
        } catch (e: InstallException) {
            assertTrue(e.failure.toString(), e.failure is RuntimeFailure.BadArchive)
        }
        // The base had been unpacked and renamed into place; it is taken away again.
        assertOnlyCommitted(null)
        assertFalse(File(fx.layout.root.parentFile, "escaped").exists())
        assertFalse(File(fx.layout.root, "escaped").exists())
    }

    @Test
    fun `a broken-off download resumes on retry and only the rest is fetched`() = runBlocking {
        val base = fx.baseLayer()
        val harness = fx.harnessLayer("1.0.0")
        fx.publish(base, harness, sequence = 100)
        fx.release.breakNext += "rel/assets/${harness.file}"
        val installer = fx.installer()
        val check = available(installer)
        try {
            installer.apply(check.manifest, check.decision, onEvent = {})
            fail("the first attempt breaks off")
        } catch (e: DownloadException) {
            assertTrue(e.failure is RuntimeFailure.Network)
        }
        assertOnlyCommitted(null)
        val record = installer.apply(check.manifest, check.decision, onEvent = {})
        assertOnlyCommitted(record)
        // The base finished the first time and was not fetched again; the harness was resumed.
        assertEquals(1, fx.assetRequests(base).size)
        assertTrue(fx.assetRequests(harness).last().second!!.startsWith("bytes="))
    }

    @Test
    fun `a lower sequence than the installed release is refused`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        install(fx.installer())
        fx.publish(fx.baseLayer(), fx.harnessLayer("0.9.0"), sequence = 90)
        assertEquals(
            RuntimeInstaller.CheckResult.Refused(RuntimeFailure.OlderRelease(100, 90)),
            fx.installer().check(),
        )
    }

    @Test
    fun `a harness-only update fetches only the harness and keeps the previous one on trial`() = runBlocking {
        val base = fx.baseLayer()
        val v1 = fx.harnessLayer("1.0.0")
        fx.publish(base, v1, sequence = 100)
        val first = install(fx.installer())

        val v2 = fx.harnessLayer("2.0.0")
        fx.publish(base, v2, sequence = 101)
        val installer = fx.installer()
        val check = available(installer)
        assertEquals(listOf("harness"), check.decision.layers.map { it.name })
        assertEquals(v2.bytes.size.toLong(), check.toUpdate().downloadBytes)
        val updated = installer.apply(check.manifest, check.decision, onEvent = {})

        assertEquals(1, fx.assetRequests(base).size) // only by the first install
        assertEquals(first.base, updated.base)
        assertEquals(first.harness, updated.trial!!.harness)
        assertEquals("1.0.0", updated.trial!!.harnessVersion)
        assertTrue(updated.onTrial)
        assertTrue(updated.toInstalledRuntime().harnessOnTrial)
        assertOnlyCommitted(updated)

        // Ready once: the previous harness goes.
        val committed = installer.commitTrial()!!
        assertNull(committed.trial)
        assertEquals("2.0.0", committed.harnessVersion)
        assertFalse(File(fx.layout.root, first.harness.dir).exists())
        assertOnlyCommitted(committed)
    }

    @Test
    fun `a new archive under the same harness id updates beside the old one`() = runBlocking {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        val first = install(fx.installer())
        // Same id, different bytes: what release 2026.11.14-2 did.
        fx.publish(base, fx.harnessLayer("1.0.0") { file("opt/dsh/runtime-pack/index", "packed") }, sequence = 101)
        val updated = install(fx.installer())
        assertEquals(first.harness.id, updated.harness.id)
        assertTrue(first.harness.dir != updated.harness.dir)
        assertEquals(first.harness, updated.trial?.harness)
        assertTrue(File(fx.layout.root, first.harness.dir).isDirectory)
        assertTrue(File(File(fx.layout.root, updated.harness.dir), "opt/dsh/runtime-pack/index").isFile)
        assertOnlyCommitted(updated)
        assertTrue(fx.installer().check() is RuntimeInstaller.CheckResult.UpToDate)
    }

    @Test
    fun `a harness on trial that never became ready is rolled back`() = runBlocking {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        val first = install(fx.installer())
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101)
        val updated = install(fx.installer())

        val rolledBack = fx.installer().rollbackTrial()!!
        assertEquals(first.harness, rolledBack.harness)
        assertEquals("1.0.0", rolledBack.harnessVersion)
        // The whole release comes back, not only its harness: the next check offers the update again.
        assertEquals(first.releaseId, rolledBack.releaseId)
        assertEquals(100, rolledBack.sequence)
        assertNull(rolledBack.trial)
        assertFalse(File(fx.layout.root, updated.harness.dir).exists())
        assertTrue(File(fx.layout.root, first.harness.dir).isDirectory)
        assertOnlyCommitted(rolledBack)
    }

    @Test
    fun `a base update swaps the base and keeps user data`() = runBlocking {
        val v1 = fx.harnessLayer("1.0.0")
        fx.publish(fx.baseLayer("base-a"), v1, sequence = 100)
        val first = install(fx.installer())
        fx.layout.home.mkdirs()
        File(fx.layout.home, "settings.json").writeText("{\"apiKey\":\"kept\"}")

        fx.publish(fx.baseLayer("base-b"), v1, sequence = 101)
        val updated = install(fx.installer())
        assertEquals("base-b", updated.base.id)
        assertEquals(first.harness, updated.harness)
        // A base change goes on trial too: the old base stays until the new one has started once.
        assertEquals(first.base, updated.trial!!.base)
        assertTrue(File(fx.layout.root, first.base.dir).isDirectory)
        assertOnlyCommitted(updated)

        val committed = fx.installer().commitTrial()!!
        assertFalse(File(fx.layout.root, first.base.dir).exists())
        assertEquals("{\"apiKey\":\"kept\"}", File(fx.layout.home, "settings.json").readText())
        assertOnlyCommitted(committed)
    }

    @Test
    fun `a base and harness update that never starts is rolled back whole and user data stays`() = runBlocking {
        fx.publish(fx.baseLayer("base-a"), fx.harnessLayer("1.0.0"), sequence = 100)
        val first = install(fx.installer())
        fx.layout.home.mkdirs()
        File(fx.layout.home, "sessions.json").writeText("[1]")

        fx.publish(fx.baseLayer("base-b"), fx.harnessLayer("2.0.0"), sequence = 101)
        val updated = install(fx.installer())
        assertEquals(setOf("base-b", "dsh-2.0.0"), setOf(updated.base.id, updated.harness.id))

        val back = fx.installer().rollbackTrial()!!
        assertEquals(first.base, back.base)
        assertEquals(first.harness, back.harness)
        assertEquals(first.releaseId, back.releaseId)
        assertFalse(File(fx.layout.root, updated.base.dir).exists())
        assertFalse(File(fx.layout.root, updated.harness.dir).exists())
        assertEquals("[1]", File(fx.layout.home, "sessions.json").readText())
        assertOnlyCommitted(back)
    }

    @Test
    fun `only the changed layers count toward the download size`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        val fresh = available(fx.installer())
        assertEquals(base.bytes.size.toLong() + fx.harnessLayer("1.0.0").bytes.size, fresh.toUpdate().downloadBytes)
        install(fx.installer())

        val v2 = fx.harnessLayer("2.0.0")
        fx.publish(base, v2, sequence = 101)
        val update = available(fx.installer()).toUpdate()
        assertEquals(v2.bytes.size.toLong(), update.downloadBytes)
        assertFalse(update.baseChanges)
        assertEquals(101, update.sequence)

        // A layer still on the device (the release kept for a rollback) is not fetched again.
        val layers = available(fx.installer()).decision.layers
        assertEquals(0, RuntimeInstaller.changedDownloadBytes(layers) { true })
        assertEquals(v2.bytes.size.toLong(), RuntimeInstaller.changedDownloadBytes(layers) { false })
    }

    @Test
    fun `going back fetches the previous manifest, accepts its lower sequence and checks it is that release`() = runBlocking {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100, dir = "r100")
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101, previous = "r100" to 100L)
        val latest = install(fx.installer())
        assertEquals(100, latest.previousRelease!!.sequence)
        assertEquals(fx.manifestUrlOf("r100"), latest.previousRelease!!.manifestUrl)
        fx.installer().commitTrial()

        // The ordinary check still refuses the older release, even fetched from its own URL.
        assertEquals(
            RuntimeInstaller.CheckResult.Refused(RuntimeFailure.OlderRelease(101, 100)),
            fx.installer().check(fx.manifestUrlOf("r100")),
        )

        val back = fx.installer().checkPrevious() as RuntimeInstaller.CheckResult.Available
        assertEquals(100, back.manifest.sequence)
        assertEquals(listOf("harness"), back.decision.layers.map { it.name })
        val record = fx.installer().apply(back.manifest, back.decision, onEvent = {})
        assertEquals(100, record.sequence)
        assertEquals("1.0.0", record.harnessVersion)
        assertNull(record.previousRelease)
        assertTrue(record.onTrial)

        // A pointer whose manifest is another release is refused.
        fx.publish(base, fx.harnessLayer("3.0.0"), sequence = 102, previous = "r100" to 100L)
        install(fx.installer())
        fx.installer().commitTrial()
        fx.publish(base, fx.harnessLayer("1.5.0"), sequence = 99, dir = "r100")
        assertTrue(fx.installer().checkPrevious() is RuntimeInstaller.CheckResult.Refused)
    }

    @Test
    fun `a previous manifest signed by another key is refused`() = runBlocking {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100, dir = "r100", signer = TestSigningKey())
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101, previous = "r100" to 100L)
        install(fx.installer())
        assertEquals(RuntimeInstaller.CheckResult.Refused(RuntimeFailure.BadSignature), fx.installer().checkPrevious())
    }

    @Test
    fun `an address that is not a URL is refused instead of crashing`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        val result = fx.installer().check("--es")
        assertTrue(result is RuntimeInstaller.CheckResult.Refused && result.failure is RuntimeFailure.BadManifest)
    }

    @Test
    fun `without a previous pointer there is nothing to go back to`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        install(fx.installer())
        assertTrue(fx.installer().checkPrevious() is RuntimeInstaller.CheckResult.Refused)
    }

    @Test
    fun `leftover staging and unreferenced layers are cleaned up`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        val record = install(fx.installer())
        File(fx.layout.root, ".staging-base-x/usr").mkdirs()
        File(fx.layout.root, "harness-dsh-0.0.1/opt").mkdirs()
        fx.installer().cleanOrphans()
        assertOnlyCommitted(record)
    }

    @Test
    fun `uninstall removes everything, or everything but user data`() = runBlocking {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        install(fx.installer())
        fx.layout.home.mkdirs()
        File(fx.layout.home, "state").writeText("x")
        fx.layout.logs.mkdirs()
        File(fx.layout.logs, "harness.log").writeText("log")

        fx.installer().uninstall(keepUserData = true)
        assertNull(InstalledStore(fx.layout).read())
        assertEquals(setOf("home"), fx.layout.root.list()!!.toSet())

        fx.installer().uninstall(keepUserData = false)
        assertEquals(emptySet<String>(), fx.layout.root.list().orEmpty().toSet())
        assertNotNull(fx.layout.root)
    }
}
