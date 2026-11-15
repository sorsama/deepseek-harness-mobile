package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.RecordingFsOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/** "Import a .zip": zip slip, lying sizes, links, modes, the wrapping folder. */
class ZipImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = RecordingFsOps()

    /**
     * Writes zips byte by byte, so an entry can carry what `java.util.zip` cannot write: a Unix mode
     * (a symlink, an executable), a declared size that is a lie, an encrypted flag.
     */
    private class ZipWriter {
        private class Entry(val name: String, val data: ByteArray, val mode: Int?, val deflate: Boolean, val declaredSize: Long?, val encrypted: Boolean)

        private val entries = ArrayList<Entry>()

        fun file(name: String, content: String, mode: Int? = "100644".toInt(8), deflate: Boolean = true, declaredSize: Long? = null) =
            apply { entries += Entry(name, content.toByteArray(), mode, deflate, declaredSize, false) }

        fun dir(name: String) = apply { entries += Entry(name.trimEnd('/') + "/", ByteArray(0), "40755".toInt(8), false, null, false) }

        fun link(name: String, target: String) = apply { entries += Entry(name, target.toByteArray(), "120777".toInt(8), false, null, false) }

        fun encrypted(name: String) = apply { entries += Entry(name, "x".toByteArray(), null, false, null, true) }

        fun write(file: File): File {
            val out = ByteArrayOutputStream()
            val central = ByteArrayOutputStream()
            for (e in entries) {
                val offset = out.size()
                val crc = CRC32().apply { update(e.data) }.value
                val body = if (e.deflate) deflate(e.data) else e.data
                val size = e.declaredSize ?: e.data.size.toLong()
                val name = e.name.toByteArray()
                val flags = (if (e.encrypted) 1 else 0) or 0x800
                val method = if (e.deflate) 8 else 0
                // Local header
                out.le32(0x04034b50); out.le16(20); out.le16(flags); out.le16(method); out.le16(0); out.le16(0x21)
                out.le32(crc.toInt()); out.le32(body.size); out.le32(size.toInt()); out.le16(name.size); out.le16(0)
                out.write(name); out.write(body)
                // Central header
                central.le32(0x02014b50); central.le16(if (e.mode != null) (3 shl 8) or 20 else 20); central.le16(20)
                central.le16(flags); central.le16(method); central.le16(0); central.le16(0x21)
                central.le32(crc.toInt()); central.le32(body.size); central.le32(size.toInt())
                central.le16(name.size); central.le16(0); central.le16(0); central.le16(0); central.le16(0)
                central.le32(if (e.mode != null) e.mode shl 16 else 0); central.le32(offset)
                central.write(name)
            }
            val cdOffset = out.size()
            out.write(central.toByteArray())
            out.le32(0x06054b50); out.le16(0); out.le16(0); out.le16(entries.size); out.le16(entries.size)
            out.le32(central.size()); out.le32(cdOffset); out.le16(0)
            file.writeBytes(out.toByteArray())
            return file
        }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(data)
            d.finish()
            val buf = ByteArray(data.size + 64)
            val n = d.deflate(buf)
            d.end()
            return buf.copyOf(n)
        }

        private fun ByteArrayOutputStream.le16(v: Int) {
            write(v and 0xff); write((v ushr 8) and 0xff)
        }

        private fun ByteArrayOutputStream.le32(v: Int) {
            le16(v and 0xffff); le16((v ushr 16) and 0xffff)
        }
    }

    private fun zip(build: ZipWriter.() -> Unit): File = ZipWriter().apply(build).write(File(tmp.root, "in-${System.nanoTime()}.zip"))

    private fun problemOf(block: () -> Unit): TransferProblem? = try {
        block()
        null
    } catch (e: TransferException) {
        e.problem
    }

    @Test
    fun `entry names that leave the folder refuse the whole zip before anything is written`() {
        val hostile = listOf("../evil.txt", "a/../../evil.txt", "/etc/passwd", "C:/Windows/evil.txt", "c:evil", "a\\..\\..\\evil.txt", "..\\evil.txt", "ok/\u0000x")
        for (name in hostile) {
            val z = zip { file("fine.txt", "ok"); file(name, "pwned") }
            assertEquals("for $name", TransferProblem.UNSAFE_ZIP, problemOf { ZipImporter(fs).plan(z, "p") })
        }
        assertEquals(null, ZipImporter.safePath("../x"))
        assertEquals(listOf("a", "b.txt"), ZipImporter.safePath("./a//b.txt"))
        assertEquals(listOf("a", "b"), ZipImporter.safePath("a\\b"))
    }

    @Test
    fun `a zip is unpacked with folders, modes, and its wrapping folder removed`() {
        val z = zip {
            dir("repo-main/")
            dir("repo-main/src/")
            file("repo-main/README.md", "# hi")
            file("repo-main/gradlew", "#!/bin/sh\necho hi", mode = "100755".toInt(8))
            file("repo-main/src/a.kt", "fun a() = 1", deflate = false)
            file("__MACOSX/repo-main/._README.md", "junk")
        }
        val importer = ZipImporter(fs)
        val plan = importer.plan(z, "fallback")
        assertEquals("repo-main", plan.suggestedName)
        assertEquals(3, plan.fileCount)
        assertEquals(("# hi".length + "#!/bin/sh\necho hi".length + "fun a() = 1".length).toLong(), plan.totalBytes)
        val into = File(tmp.root, "out")
        val result = importer.extract(z, plan, into)
        assertEquals(3, result.files)
        assertEquals("# hi", File(into, "README.md").readText())
        assertEquals("fun a() = 1", File(into, "src/a.kt").readText())
        assertFalse(File(into, "repo-main").exists())
        assertFalse(File(into, "__MACOSX").exists())
        assertEquals(WorkspaceTree.EXEC_MODE, fs.modeOf(File(into, "gradlew")))
        assertEquals(WorkspaceTree.FILE_MODE, fs.modeOf(File(into, "README.md")))
    }

    @Test
    fun `a zip with files at the top keeps its layout and the fallback name`() {
        val z = zip { file("a.txt", "a"); file("b/c.txt", "c") }
        val plan = ZipImporter(fs).plan(z, "mine")
        assertEquals("mine", plan.suggestedName)
        assertEquals(listOf(listOf("a.txt"), listOf("b", "c.txt")), plan.entries.map { it.path })
    }

    @Test
    fun `links inside the project are made and links out are skipped`() {
        val z = zip {
            file("p/README.md", "x")
            link("p/inside", "README.md")
            link("p/sub/up", "../README.md")
            link("p/out", "../../etc/passwd")
            link("p/abs", "/etc/passwd")
            link("p/win", "C:\\Windows")
            link("p/root-escape", "..")
        }
        val importer = ZipImporter(fs)
        val plan = importer.plan(z, null)
        val into = File(tmp.root, "out")
        val result = importer.extract(z, plan, into)
        assertEquals(4, result.skippedLinks)
        assertEquals("README.md", fs.linkTarget(File(into, "inside")))
        assertEquals("../README.md", fs.linkTarget(File(into, "sub/up")))
        assertFalse(File(into, "out").exists())
        assertFalse(File(into, "abs").exists())
        assertFalse(File(into, "win").exists())
        assertFalse(File(into, "root-escape").exists())
        assertTrue(ZipImporter.linkStaysInside(listOf("a", "b"), "../c"))
        assertFalse(ZipImporter.linkStaysInside(listOf("a"), "../c"))
    }

    @Test
    fun `a link written before a file of the same path cannot redirect the write`() {
        // Links are made last, so "p/dir" is a real folder when "p/dir/x" is written, and the link is skipped.
        val z = zip {
            link("p/dir", "../../outside")
            file("p/dir/x.txt", "data")
        }
        val importer = ZipImporter(fs)
        val into = File(tmp.root, "out")
        val result = importer.extract(z, importer.plan(z, null), into)
        assertEquals("data", File(into, "dir/x.txt").readText())
        assertEquals(1, result.skippedLinks)
        assertFalse(File(tmp.root, "outside").exists())
    }

    @Test
    fun `an entry that inflates past its declared size stops the import`() {
        val z = zip { file("big.txt", "a".repeat(10_000), declaredSize = 10) }
        val importer = ZipImporter(fs)
        val plan = importer.plan(z, "p")
        assertEquals(10L, plan.totalBytes)
        assertEquals(TransferProblem.DAMAGED_ZIP, problemOf { importer.extract(z, plan, File(tmp.root, "out")) })
    }

    @Test
    fun `duplicates, encrypted entries, too many entries and non-zips are refused`() {
        assertEquals(TransferProblem.DAMAGED_ZIP, problemOf { ZipImporter(fs).plan(zip { file("a.txt", "1"); file("a.txt", "2") }, "p") })
        assertEquals(TransferProblem.UNSUPPORTED_ZIP, problemOf { ZipImporter(fs).plan(zip { encrypted("secret.txt") }, "p") })
        assertEquals(TransferProblem.TOO_MANY_ENTRIES, problemOf { ZipImporter(fs, maxEntries = 2).plan(zip { file("a", "1"); file("b", "2"); file("c", "3") }, "p") })
        val notZip = File(tmp.root, "not.zip").apply { writeText("this is plain text, long enough to look for a record in it") }
        assertEquals(TransferProblem.NOT_A_ZIP, problemOf { ZipImporter(fs).plan(notZip, "p") })
        val tiny = File(tmp.root, "tiny.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(TransferProblem.NOT_A_ZIP, problemOf { ZipImporter(fs).plan(tiny, "p") })
    }

    @Test
    fun `a zip written by java util zip reads the same`() {
        val file = File(tmp.root, "jdk.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("proj/"))
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("proj/a.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        val importer = ZipImporter(fs)
        val plan = importer.plan(file, null)
        assertEquals("proj", plan.suggestedName)
        val into = File(tmp.root, "out")
        importer.extract(file, plan, into)
        assertEquals("hello", File(into, "a.txt").readText())
        assertNull(ZipDirectory.read(file, 10).first { it.name == "proj/a.txt" }.unixMode)
    }

    @Test
    fun `cancelling stops between buffers`() {
        val z = zip { file("a.txt", "a"); file("b.txt", "b") }
        val importer = ZipImporter(fs)
        try {
            importer.extract(z, importer.plan(z, "p"), File(tmp.root, "out"), cancelled = { true })
            fail("expected a cancel")
        } catch (e: TransferCancelled) {
            // expected
        }
    }
}
