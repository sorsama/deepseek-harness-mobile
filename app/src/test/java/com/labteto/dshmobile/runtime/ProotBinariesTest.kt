package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The vendored proot binaries are exactly the ones `app/third_party/proot/SHA256SUMS` records,
 * and nothing else sits next to them. Unit tests run with the module directory as working dir.
 */
class ProotBinariesTest {
    private val jniLibs = File("src/main/jniLibs")
    private val sums = File("third_party/proot/SHA256SUMS")

    private val recorded: Map<String, String> = sums.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line -> line.substringAfter("  ").trim() to line.substringBefore("  ").trim() }

    @Test
    fun `every committed binary matches its recorded hash`() {
        assertEquals(
            setOf("arm64-v8a/libproot.so", "arm64-v8a/libproot-loader.so", "x86_64/libproot.so", "x86_64/libproot-loader.so"),
            recorded.keys,
        )
        for ((path, sha) in recorded) {
            val file = File(jniLibs, path)
            assertTrue("$path is missing", file.isFile)
            assertEquals("$path does not match third_party/proot/SHA256SUMS", sha, LayerDownloader.sha256(file))
        }
    }

    @Test
    fun `no unrecorded native binary is in jniLibs`() {
        val present = jniLibs.walkTopDown().filter { it.isFile }.map { it.relativeTo(jniLibs).path.replace('\\', '/') }.toSet()
        assertEquals(recorded.keys, present)
    }

    @Test
    fun `only the 64-bit ABIs the runtime is built for`() {
        assertEquals(ManifestPolicy.SUPPORTED_ABIS.toSet(), jniLibs.list()!!.toSet())
    }
}
