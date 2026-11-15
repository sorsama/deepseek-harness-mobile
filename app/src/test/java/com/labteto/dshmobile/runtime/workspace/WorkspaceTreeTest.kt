package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.FileKind
import com.labteto.dshmobile.runtime.FsOps
import com.labteto.dshmobile.runtime.NioFsOps
import com.labteto.dshmobile.runtime.RecordingFsOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The documents provider's rules: what an id may name, how links are shown, what a listing holds.
 * A symlink here is [RecordingFsOps]'s marker (Windows has none without Developer Mode); the last
 * test repeats the escape attempts with real links where the host has them.
 */
class WorkspaceTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = RecordingFsOps()
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var tree: WorkspaceTree

    @Before
    fun setUp() {
        base = tmp.newFolder("runtime")
        root = File(base, "workspaces").apply { mkdirs() }
        // Next to the workspaces: what must never be reachable through the provider.
        File(base, "home/.dsh").mkdirs()
        File(base, "home/.dsh/credentials.json").writeText("{\"key\":\"sk-secret\"}")
        File(root, "app/src").mkdirs()
        File(root, "app/README.md").writeText("# app")
        File(root, "app/src/main.kt").writeText("fun main() {}")
        tree = WorkspaceTree(root, fs)
    }

    @Test
    fun `ids map to paths below the root and back`() {
        assertEquals("workspaces", tree.idOf(emptyList()))
        assertEquals("workspaces/app/src/main.kt", tree.idOf(listOf("app", "src", "main.kt")))
        assertEquals(listOf("app", "src"), tree.segmentsOf("workspaces/app/src"))
        assertEquals("workspaces/app", tree.parentId("workspaces/app/src"))
        assertNull(tree.parentId("workspaces"))
        val doc = tree.resolve("workspaces/app/src/main.kt")!!
        assertEquals(File(root, "app/src/main.kt"), doc.file)
        assertEquals(WorkspaceTree.Kind.FILE, doc.kind)
        assertEquals("/workspaces/app/src", tree.guestPathOf("workspaces/app/src"))
        assertEquals("workspaces/app/src", tree.idForGuestPath("/workspaces/app/src/"))
        assertEquals("workspaces", tree.idForGuestPath("/workspaces"))
        assertNull(tree.idForGuestPath("/root/.dsh"))
    }

    @Test
    fun `ids that try to leave the root resolve to nothing`() {
        val hostile = listOf(
            "workspaces/..",
            "workspaces/../home/.dsh/credentials.json",
            "workspaces/app/../../home/.dsh/credentials.json",
            "workspaces/./app",
            "workspaces//app",
            "workspaces/app/",
            "workspaces/app/src/main.kt/..",
            "workspaces/\u0000",
            "workspacesX/app",
            "workspaces\\..\\home",
            "/workspaces/app",
            "../home",
            "",
            "home/.dsh/credentials.json",
            "workspaces/" + base.absolutePath.replace('\\', '/'),
        )
        for (id in hostile) {
            assertNull("must not resolve: $id", tree.resolve(id))
            assertNull("must not have an entry: $id", tree.entry(id))
            assertTrue("must not list: $id", tree.list(id).isEmpty())
        }
        // A name with dots in it is just a name.
        File(root, "app/..hidden").writeText("x")
        assertNotNull(tree.resolve("workspaces/app/..hidden"))
    }

    @Test
    fun `nothing can be created renamed copied or moved outside the root`() {
        assertThrows(Exception::class.java) { tree.create("workspaces/../home", "x", directory = false) }
        assertFalse(File(base, "escaped.md").exists())
        assertThrows(Exception::class.java) { tree.copy("workspaces/../home/.dsh/credentials.json", "workspaces/app") }
        assertThrows(Exception::class.java) { tree.move("workspaces/app", "workspaces/../home") }
        assertThrows(Exception::class.java) { tree.delete("workspaces") }
        assertThrows(Exception::class.java) { tree.delete("workspaces/../home") }
        assertTrue(File(base, "home/.dsh/credentials.json").exists())
    }

    @Test
    fun `a rename with a slash keeps it in the name`() {
        val renamed = tree.rename("workspaces/app/README.md", "../../escaped.md")
        assertEquals("workspaces/app/.._.._escaped.md", renamed.id)
        assertTrue(File(root, "app/.._.._escaped.md").isFile)
    }

    @Test
    fun `a link to a file inside is shown as that file and opens the target`() {
        fs.symlink("README.md", File(root, "app/readme-link"))
        fs.symlink("/workspaces/app/src/main.kt", File(root, "app/guest-abs-link"))
        fs.symlink("../README.md", File(root, "app/src/up-link"))
        val names = tree.list("workspaces/app").associateBy { it.name }
        assertTrue(names.getValue("readme-link").link)
        assertEquals("# app".length.toLong(), names.getValue("readme-link").size)
        assertEquals("text/markdown", names.getValue("README.md").mimeType)
        val abs = tree.resolve("workspaces/app/guest-abs-link")!!
        assertEquals(File(root, "app/src/main.kt"), abs.content)
        assertEquals(File(root, "app/guest-abs-link"), abs.file)
        assertEquals(File(root, "app/README.md"), tree.resolve("workspaces/app/src/up-link")!!.content)
    }

    @Test
    fun `links out of the root, to folders, to links and broken links are skipped`() {
        fs.symlink("../../home/.dsh/credentials.json", File(root, "app/out-relative"))
        fs.symlink(File(base, "home/.dsh/credentials.json").absolutePath, File(root, "app/out-absolute"))
        fs.symlink("/root/.dsh/credentials.json", File(root, "app/out-guest"))
        fs.symlink("/workspaces/../root/.dsh/credentials.json", File(root, "app/out-guest-dotdot"))
        fs.symlink("src", File(root, "app/dir-link"))
        fs.symlink("readme-link-2", File(root, "app/chain"))
        fs.symlink("README.md", File(root, "app/readme-link-2"))
        fs.symlink("missing.txt", File(root, "app/broken"))
        fs.symlink("dir-link/main.kt", File(root, "app/through-link"))
        val names = tree.list("workspaces/app").map { it.name }.toSet()
        for (skipped in listOf("out-relative", "out-absolute", "out-guest", "out-guest-dotdot", "dir-link", "chain", "broken", "through-link")) {
            assertFalse("$skipped must be skipped", skipped in names)
            assertNull("$skipped must not resolve", tree.resolve("workspaces/app/$skipped"))
        }
        assertTrue("readme-link-2" in names)
        // Not even through a link to a folder.
        assertNull(tree.resolve("workspaces/app/dir-link/main.kt"))
    }

    @Test
    fun `deleting a link removes the link and never its target`() {
        fs.symlink("README.md", File(root, "app/readme-link"))
        tree.delete("workspaces/app/readme-link")
        assertFalse(File(root, "app/readme-link").exists())
        assertTrue(File(root, "app/README.md").isFile)
    }

    @Test
    fun `staging folders, unreadable names and special files are not listed`() {
        File(root, ".dsh-incoming-123/x").mkdirs()
        File(root, "bad�name").writeText("x")
        val decoding = object : FsOps by fs {
            override fun lstat(file: File): FileKind = if ('�' in file.name) FileKind.NONE else fs.lstat(file)
        }
        val listed = WorkspaceTree(root, decoding).list("workspaces").map { it.name }
        assertEquals(listOf("app"), listed)
        assertNull(tree.resolve("workspaces/.dsh-incoming-123"))
        assertNull(tree.resolve("workspaces/.dsh-incoming-123/x"))
        assertThrows(IllegalArgumentException::class.java) { tree.create("workspaces", ".dsh-mine", directory = true) }
        // Only at the top: a project may hold a folder of that name.
        assertEquals("workspaces/app/.dsh-ok", tree.create("workspaces/app", ".dsh-ok", directory = true).id)
    }

    @Test
    fun `listing gives folders and files with MIME types, sizes and times`() {
        val entries = tree.list("workspaces/app").associateBy { it.name }
        assertEquals(MimeTypes.DIRECTORY, entries.getValue("src").mimeType)
        assertTrue(entries.getValue("src").isDirectory)
        assertEquals(5L, entries.getValue("README.md").size)
        val mime = MimeTypes()
        assertEquals("text/plain", mime.forName("Main.KT"))
        assertEquals("application/json", mime.forName("package.json"))
        assertEquals("text/plain", mime.forName(".gitignore"))
        assertEquals("text/plain", mime.forName("Makefile"))
        assertEquals("image/png", mime.forName("logo.PNG"))
        assertEquals("application/octet-stream", mime.forName("blob.weird"))
        assertEquals("video/x-custom", MimeTypes { if (it == "custom") "video/x-custom" else null }.forName("a.custom"))
        assertEquals("md", mime.extensionFor("text/markdown"))
        assertEquals("txt", mime.extensionFor("text/plain"))
        assertNull(mime.extensionFor("application/octet-stream"))
    }

    @Test
    fun `create makes unique names and the usual modes`() {
        val a = tree.create("workspaces/app", "notes.txt", directory = false)
        val b = tree.create("workspaces/app", "notes.txt", directory = false)
        val d = tree.create("workspaces/app", "  folder  ", directory = true)
        assertEquals("workspaces/app/notes.txt", a.id)
        assertEquals("workspaces/app/notes (1).txt", b.id)
        assertEquals("workspaces/app/folder", d.id)
        assertEquals(WorkspaceTree.FILE_MODE, fs.modeOf(File(root, "app/notes.txt")))
        assertEquals(WorkspaceTree.DIR_MODE, fs.modeOf(File(root, "app/folder")))
        assertThrows(IllegalArgumentException::class.java) { tree.create("workspaces/app", "..", directory = true) }
        assertThrows(IllegalArgumentException::class.java) { tree.create("workspaces/app", "   ", directory = true) }
        assertThrows(IllegalArgumentException::class.java) { tree.create("workspaces/app/README.md", "x", directory = true) }
    }

    @Test
    fun `long names are cut to 255 bytes before the extension`() {
        val long = "é".repeat(200) + ".txt"
        val clean = WorkspaceTree.cleanName(long, topLevel = false)
        assertTrue(clean.toByteArray().size <= 255)
        assertTrue(clean.endsWith(".txt"))
        val emoji = "😀".repeat(80)
        val cut = WorkspaceTree.cleanName(emoji, topLevel = false)
        assertTrue(cut.toByteArray().size <= 255)
        assertFalse("no half surrogate pair", cut.last().isHighSurrogate())
    }

    @Test
    fun `copy and move stay inside and refuse a folder into itself`() {
        val copied = tree.copy("workspaces/app/src", "workspaces")
        assertEquals("workspaces/src", copied.id)
        assertTrue(File(root, "src/main.kt").isFile)
        assertThrows(IllegalArgumentException::class.java) { tree.copy("workspaces/app", "workspaces/app/src") }
        assertThrows(IllegalArgumentException::class.java) { tree.move("workspaces/app", "workspaces/app") }
        val moved = tree.move("workspaces/src", "workspaces/app")
        assertEquals("workspaces/app/src (1)", moved.id)
        assertFalse(File(root, "src").exists())
        assertTrue(tree.isChild("workspaces/app", "workspaces/app/src/main.kt"))
        assertFalse(tree.isChild("workspaces/app", "workspaces/app"))
        assertFalse(tree.isChild("workspaces/app", "workspaces/application"))
        assertFalse(tree.isChild("workspaces/app", "workspaces/app/../x"))
    }

    @Test
    fun `copy keeps links as links and the executable bit`() {
        File(root, "app/gradlew").writeText("#!/bin/sh")
        File(root, "app/gradlew").setExecutable(true)
        fs.symlink("README.md", File(root, "app/readme-link"))
        tree.copy("workspaces/app", "workspaces")
        assertEquals("README.md", fs.linkTarget(File(root, "app (1)/readme-link")))
        if (File(root, "app/gradlew").canExecute()) {
            assertEquals(WorkspaceTree.EXEC_MODE, fs.modeOf(File(root, "app (1)/gradlew")))
        }
    }

    @Test
    fun `search and recent skip heavy folders and stay in budget`() {
        File(root, "app/node_modules/pkg").mkdirs()
        File(root, "app/node_modules/pkg/main.kt").writeText("x")
        val hits = tree.search("MAIN")
        assertEquals(listOf("workspaces/app/src/main.kt"), hits.map { it.id })
        assertTrue(tree.search("main", budget = 1).isEmpty())
        val recent = tree.recent()
        assertTrue(recent.all { !it.isDirectory })
        assertFalse(recent.any { "node_modules" in it.id })
        assertEquals(listOf("workspaces", "workspaces/app", "workspaces/app/src"), tree.pathTo("workspaces/app/src"))
        assertNull(tree.pathTo("workspaces/nope"))
    }

    @Test
    fun `a real path check accepts the root and below only`() {
        assertTrue(WorkspaceTree.isInside("/data/data/x/no_backup/runtime/workspaces", "/data/data/x/no_backup/runtime/workspaces/app/a.txt"))
        assertTrue(WorkspaceTree.isInside("/data/data/x/no_backup/runtime/workspaces", "/data/data/x/no_backup/runtime/workspaces"))
        assertFalse(WorkspaceTree.isInside("/data/data/x/no_backup/runtime/workspaces", "/data/data/x/no_backup/runtime/workspaces2/a"))
        assertFalse(WorkspaceTree.isInside("/data/data/x/no_backup/runtime/workspaces", "/data/data/x/no_backup/runtime/home/.dsh/credentials.json"))
    }

    @Test
    fun `real symlinks cannot be used to escape`() {
        assumeTrue(File.separatorChar == '/')
        val real = WorkspaceTree(root, NioFsOps)
        NioFsOps.symlink(File(base, "home").absolutePath, File(root, "app/home-link"))
        NioFsOps.symlink("../../home/.dsh/credentials.json", File(root, "app/key-link"))
        NioFsOps.symlink("README.md", File(root, "app/readme-link"))
        assertNull(real.resolve("workspaces/app/home-link"))
        assertNull(real.resolve("workspaces/app/home-link/.dsh/credentials.json"))
        assertNull(real.resolve("workspaces/app/key-link"))
        assertEquals(File(root, "app/README.md"), real.resolve("workspaces/app/readme-link")!!.content)
        val names = real.list("workspaces/app").map { it.name }
        assertFalse("home-link" in names)
        assertFalse("key-link" in names)
        assertTrue("readme-link" in names)
        real.delete("workspaces/app/readme-link")
        assertTrue(File(root, "app/README.md").isFile)
    }
}
