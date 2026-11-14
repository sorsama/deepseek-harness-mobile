package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class TarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = RecordingFsOps()

    private fun extract(tar: TarBuilder, target: File = tmp.newFolder("t")): TarExtractor.Stats =
        TarExtractor(fs).extract(ByteArrayInputStream(tar.bytes()), target)

    private fun assertRefused(tar: TarBuilder, expect: String): File {
        val target = tmp.newFolder()
        try {
            TarExtractor(fs).extract(ByteArrayInputStream(tar.bytes()), target)
            fail("expected the archive to be refused ($expect)")
        } catch (e: ArchiveException) {
            assertTrue("message '${e.message}' should mention '$expect'", e.message!!.contains(expect))
        }
        return target
    }

    @Test
    fun `files, directories and modes land as stored`() {
        val target = tmp.newFolder("t")
        val stats = extract(
            TarBuilder()
                .dir("usr")
                .dir("usr/bin")
                .file("usr/bin/tool", "#!/bin/sh\necho hi\n", "755".toInt(8))
                .file("etc/motd", "hello")
                .file("usr/bin/setuid", "x", "4755".toInt(8)),
            target,
        )
        assertEquals(5, stats.entries)
        assertEquals(3, stats.files)
        assertEquals(2, stats.directories)
        assertEquals("#!/bin/sh\necho hi\n", File(target, "usr/bin/tool").readText())
        assertEquals("hello", File(target, "etc/motd").readText())
        assertEquals("755".toInt(8), fs.modeOf(File(target, "usr/bin/tool")))
        // setuid and setgid mean nothing to an app; they are dropped, the rest is kept.
        assertEquals("755".toInt(8), fs.modeOf(File(target, "usr/bin/setuid")))
    }

    @Test
    fun `symlinks are created exactly as stored, absolute targets included`() {
        val target = tmp.newFolder("t")
        val stats = extract(
            TarBuilder()
                .dir("usr/bin")
                .file("usr/bin/bash", "elf")
                .symlink("bin", "usr/bin")
                .symlink("etc/alternatives/sh", "/usr/bin/bash")
                .symlink("usr/bin/rbash", "bash"),
            target,
        )
        assertEquals(3, stats.symlinks)
        assertEquals("usr/bin", fs.linkTarget(File(target, "bin")))
        assertEquals("/usr/bin/bash", fs.linkTarget(File(target, "etc/alternatives/sh")))
        assertEquals("bash", fs.linkTarget(File(target, "usr/bin/rbash")))
    }

    @Test
    fun `GNU long names and long link targets are read`() {
        val target = tmp.newFolder("t")
        val longDir = "opt/dsh/node_modules/" + "a".repeat(60) + "/" + "b".repeat(60)
        val longTarget = "/opt/dsh/node_modules/" + "c".repeat(120)
        extract(
            TarBuilder()
                .file("$longDir/index.js", "module.exports = 1")
                .symlink("opt/link", longTarget),
            target,
        )
        assertEquals("module.exports = 1", File(target, "$longDir/index.js").readText())
        assertEquals(longTarget, fs.linkTarget(File(target, "opt/link")))
    }

    @Test
    fun `directory modes are applied after every entry, deepest first`() {
        val target = tmp.newFolder("t")
        extract(
            TarBuilder()
                .dir("usr", "555".toInt(8))
                .dir("usr/share", "555".toInt(8))
                .file("usr/share/doc", "text")
                .dir("tmp", "1777".toInt(8)),
            target,
        )
        val order = fs.chmods.map { it.first.removePrefix(target.absolutePath).replace('\\', '/') to it.second }
        // The file's chmod comes first; the read-only directories only after it, child before parent.
        assertEquals("/usr/share/doc", order.first().first)
        val dirs = order.drop(1).map { it.first }
        assertTrue(dirs.indexOf("/usr/share") < dirs.indexOf("/usr"))
        assertEquals("555".toInt(8), fs.modeOf(File(target, "usr")))
        // The sticky bit on /tmp is kept.
        assertEquals("1777".toInt(8), fs.modeOf(File(target, "tmp")))
    }

    @Test
    fun `a dot-dot component is refused and nothing escapes`() {
        val target = assertRefused(TarBuilder().file("ok", "1").file("../evil", "x"), "..")
        assertFalse(File(target.parentFile, "evil").exists())
        val nested = assertRefused(TarBuilder().file("a/../../evil2", "x"), "..")
        assertFalse(File(nested.parentFile, "evil2").exists())
    }

    @Test
    fun `an absolute name is refused`() {
        assertRefused(TarBuilder().file("/etc/passwd", "root"), "absolute")
    }

    @Test
    fun `an entry through a symlinked parent is refused`() {
        val outside = tmp.newFolder("outside")
        val target = assertRefused(
            TarBuilder()
                .symlink("lib", outside.absolutePath)
                .file("lib/payload", "x"),
            "symlink",
        )
        assertFalse(File(outside, "payload").exists())
        assertFalse(File(target, "lib/payload").exists())
    }

    @Test
    fun `hard links, FIFOs and corrupt headers are refused`() {
        assertRefused(TarBuilder().file("a", "1").hardlink("b", "a"), "hard link")
        assertRefused(TarBuilder().fifo("pipe"), "unsupported entry type")
        assertRefused(TarBuilder().corruptHeader("x"), "checksum")
    }

    @Test
    fun `a repeated name is refused`() {
        assertRefused(TarBuilder().file("a", "1").file("a", "2"), "twice")
        assertRefused(TarBuilder().file("a", "1").symlink("a", "b"), "twice")
    }

    @Test
    fun `a truncated archive fails instead of passing as complete`() {
        val bytes = TarBuilder().file("big", ByteArray(4000)).bytes()
        val truncated = bytes.copyOf(1200)
        try {
            TarExtractor(fs).extract(ByteArrayInputStream(truncated), tmp.newFolder())
            fail("a truncated archive must fail")
        } catch (e: java.io.EOFException) {
            // expected
        }
    }

    @Test
    fun `progress reports entries and bytes`() {
        val tar = TarBuilder()
        repeat(600) { tar.file("f/$it", "x") }
        val seen = mutableListOf<Int>()
        TarExtractor(fs).extract(ByteArrayInputStream(tar.bytes()), tmp.newFolder(), onProgress = { entries, _ -> seen += entries })
        assertEquals(listOf(256, 512, 600), seen)
    }

    /** The same rules on a real POSIX filesystem (CI's Linux runner), with real symlinks and modes. */
    @Test
    fun `real symlinks and modes on a POSIX filesystem`() {
        assumeTrue(File.separatorChar == '/')
        val target = tmp.newFolder("posix")
        TarExtractor(NioFsOps).extract(
            ByteArrayInputStream(
                TarBuilder()
                    .dir("usr/bin")
                    .file("usr/bin/tool", "x", "750".toInt(8))
                    .symlink("bin", "usr/bin")
                    .dir("ro", "555".toInt(8))
                    .file("ro/inside", "y")
                    .bytes(),
            ),
            target,
        )
        val bin = File(target, "bin").toPath()
        assertTrue(Files.isSymbolicLink(bin))
        assertEquals("usr/bin", Files.readSymbolicLink(bin).toString())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(target, "usr/bin/tool").toPath(), LinkOption.NOFOLLOW_LINKS)))
        assertEquals("r-xr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(target, "ro").toPath())))
        // And the tree helper deletes it all, read-only directory included, without following the link.
        FsTree.delete(NioFsOps, target)
        assertFalse(target.exists())
    }

    @Test
    fun `refused through a real symlink on a POSIX filesystem`() {
        assumeTrue(File.separatorChar == '/')
        val outside = tmp.newFolder("outside-posix")
        try {
            TarExtractor(NioFsOps).extract(
                ByteArrayInputStream(TarBuilder().symlink("lib", outside.absolutePath).file("lib/payload", "x").bytes()),
                tmp.newFolder("posix2"),
            )
            fail("must refuse")
        } catch (e: ArchiveException) {
            assertFalse(File(outside, "payload").exists())
        }
    }
}
