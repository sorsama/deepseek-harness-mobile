package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestPolicyTest {

    private val real: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/runtime/release-2026.11.14-1.manifest.json")).use { it.readBytes() }
    private val manifest = RuntimeManifest.parse(real)

    private fun installed(base: String, harness: String, sequence: Long = manifest.sequence, abi: String = "x86_64") = InstalledRecord(
        releaseId = "old",
        sequence = sequence,
        abi = abi,
        harnessVersion = "0.0.1",
        nodeVersion = "24",
        base = LayerRecord(base, "base-$base", "0".repeat(64)),
        harness = LayerRecord(harness, "harness-$harness", "0".repeat(64)),
        entrypoint = "/usr/local/bin/dsh",
        readinessPrefix = "dsh web: http://127.0.0.1:",
        fakeProc = emptyList(),
        resolvConf = "/etc/resolv.conf",
    )

    @Test
    fun `minAppVersion refuses an older app`() {
        val decision = ManifestPolicy.decide(manifest, "0.21.1", listOf("x86_64"), null)
        assertEquals(ManifestPolicy.Decision.Refused(RuntimeFailure.AppTooOld("0.22.0")), decision)
        // A prerelease of the minimum version is still older than it.
        assertTrue(ManifestPolicy.decide(manifest, "0.22.0-rc.1", listOf("x86_64"), null) is ManifestPolicy.Decision.Refused)
        assertTrue(ManifestPolicy.decide(manifest, "garbage", listOf("x86_64"), null) is ManifestPolicy.Decision.Refused)
    }

    @Test
    fun `a fresh install takes both layers for the first supported ABI`() {
        val decision = ManifestPolicy.decide(manifest, "0.22.0", listOf("arm64-v8a", "x86_64"), null) as ManifestPolicy.Decision.Install
        assertEquals("arm64-v8a", decision.abi)
        assertEquals(listOf("base", "harness"), decision.layers.map { it.name })
        assertEquals(74011586L + 85601767L, decision.downloadBytes)
        val x86 = ManifestPolicy.decide(manifest, "1.0.0", listOf("x86_64"), null) as ManifestPolicy.Decision.Install
        assertEquals("x86_64", x86.abi)
    }

    @Test
    fun `a device with no 64-bit ABI we ship gets no build`() {
        val decision = ManifestPolicy.decide(manifest, "0.22.0", listOf("armeabi-v7a"), null)
        assertTrue((decision as ManifestPolicy.Decision.Refused).failure is RuntimeFailure.NoBuildForDevice)
        assertEquals(null, ManifestPolicy.deviceAbi(listOf("x86", "armeabi-v7a")))
    }

    @Test
    fun `a lower sequence than the installed one is a rollback and refused`() {
        val newer = installed(base = "x", harness = "y", sequence = manifest.sequence + 1)
        val decision = ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), newer)
        assertEquals(
            ManifestPolicy.Decision.Refused(RuntimeFailure.OlderRelease(manifest.sequence + 1, manifest.sequence)),
            decision,
        )
    }

    @Test
    fun `a lower sequence is accepted only for the person's own go back`() {
        val newer = installed(base = "x", harness = "y", sequence = manifest.sequence + 1)
        val goBack = ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), newer, allowOlder = true)
        assertTrue(goBack is ManifestPolicy.Decision.Install)
        // Every other path (the default) still refuses it.
        assertTrue(ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), newer) is ManifestPolicy.Decision.Refused)
        assertTrue(ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), newer, allowOlder = false) is ManifestPolicy.Decision.Refused)
        // Going back does not get past the other checks: an app too old for it is still refused.
        assertEquals(
            ManifestPolicy.Decision.Refused(RuntimeFailure.AppTooOld(manifest.minAppVersion)),
            ManifestPolicy.decide(manifest, "0.21.1", listOf("x86_64"), newer, allowOlder = true),
        )
    }

    @Test
    fun `only changed layers are fetched`() {
        val base = manifest.layer("x86_64", "base")!!
        val harness = manifest.layer("x86_64", "harness")!!
        fun record(layer: RuntimeManifest.Layer) = LayerRecord(layer.id, "dir", layer.sha256)
        val current = installed("x", "y").copy(base = record(base), harness = record(harness))
        fun decide(record: InstalledRecord) = ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), record)

        assertTrue(decide(current) is ManifestPolicy.Decision.UpToDate)
        val harnessOnly = decide(current.copy(harness = LayerRecord("dsh-0.1.0", "dir", "0".repeat(64)))) as ManifestPolicy.Decision.Install
        assertEquals(listOf("harness"), harnessOnly.layers.map { it.name })
        assertFalse(harnessOnly.baseChanges)
        val baseOnly = decide(current.copy(base = LayerRecord("old-base", "dir", "0".repeat(64)))) as ManifestPolicy.Decision.Install
        assertEquals(listOf("base"), baseOnly.layers.map { it.name })
    }

    @Test
    fun `a different archive under the same layer id is still an update`() {
        // Release 2026.11.14-2 shipped a new harness archive as dsh-0.2.2-rc.1, the id 2026.11.14-1 used.
        val base = manifest.layer("x86_64", "base")!!
        val harness = manifest.layer("x86_64", "harness")!!
        val record = installed("x", "y").copy(
            base = LayerRecord(base.id, "dir", base.sha256),
            harness = LayerRecord(harness.id, "dir", "f".repeat(64)),
        )
        val decision = ManifestPolicy.decide(manifest, "0.22.0", listOf("x86_64"), record) as ManifestPolicy.Decision.Install
        assertEquals(listOf("harness"), decision.layers.map { it.name })
        val layout = RuntimeLayout(java.io.File("r"), java.io.File("c"))
        assertTrue(layout.harnessDir(harness.id, harness.sha256) != layout.harnessDir(harness.id, "f".repeat(64)))
    }

    @Test
    fun `unknown fields are ignored and a newer schema is refused`() {
        val text = real.decodeToString()
        val extended = text.replaceFirst("{", """{"somethingNew": {"nested": [1, 2]},""")
        assertEquals(manifest, RuntimeManifest.parse(extended.toByteArray()))
        expectBad(text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"), "schema")
    }

    @Test
    fun `asset names that are not plain file names are refused`() {
        val text = real.decodeToString()
        expectBad(text.replace("\"dsh-runtime-base-x86_64.tar.zst\"", "\"../../evil.tar.zst\""), "asset name")
        expectBad(text.replace("https://github.com/sorsama", "http://github.com/sorsama"), "https")
        expectBad(text.replace("\"compression\": \"zstd\"", "\"compression\": \"xz\""), "compression")
    }

    @Test
    fun `semantic versions compare like semver`() {
        fun v(s: String) = AppVersion.parse(s)!!
        assertTrue(v("0.22.0") > v("0.21.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.22.0") > v("0.22.0-rc.2"))
        assertTrue(v("0.22.0-rc.10") > v("0.22.0-rc.2"))
        assertTrue(v("0.22.0-beta") > v("0.22.0-alpha.9"))
        assertEquals(0, v("v0.22.0+build.5").compareTo(v("0.22.0")))
        assertEquals(0, v("0.22").compareTo(v("0.22.0")))
        assertEquals(null, AppVersion.parse("latest"))
        assertTrue(AppVersion.satisfies("0.22.0", "0.22.0"))
        assertFalse(AppVersion.satisfies("0.21.1", "0.22.0"))
    }

    private fun expectBad(json: String, mention: String) {
        try {
            RuntimeManifest.parse(json.toByteArray())
            fail("expected a refusal mentioning '$mention'")
        } catch (e: ManifestException) {
            assertTrue("'${e.message}' should mention '$mention'", e.message!!.contains(mention))
        }
    }
}
