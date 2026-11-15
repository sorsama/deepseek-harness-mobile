package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.RecordingFsOps
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** Exports (zip and copy out) with their folder filter, the folder import, and the project list. */
class ProjectTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fs = RecordingFsOps()
    private lateinit var project: File

    @Before
    fun setUp() {
        project = tmp.newFolder("web")
        File(project, "src").mkdirs()
        File(project, "src/index.ts").writeText("export {}")
        File(project, "package.json").writeText("{}")
        File(project, "node_modules/left-pad").mkdirs()
        File(project, "node_modules/left-pad/index.js").writeText("x".repeat(1000))
        File(project, ".git/objects").mkdirs()
        File(project, ".git/HEAD").writeText("ref: refs/heads/main")
        File(project, "dist").mkdirs()
        File(project, "dist/bundle.js").writeText("bundle")
        File(project, "build").mkdirs()
        File(project, "build/script.sh").writeText("echo")
        File(project, ".gitignore").writeText("# output\n/dist/\nnode_modules\n")
        fs.symlink("package.json", File(project, "pkg-link"))
    }

    private fun zipNames(filter: ExportFilter): List<String> {
        val transfer = ProjectTransfer(fs)
        val scan = transfer.scan(project, filter)
        val out = ByteArrayOutputStream()
        transfer.writeZip(project, "web", filter, out, scan)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) names += (z.nextEntry ?: break).name
        }
        return names
    }

    @Test
    fun `the default zip skips dependencies, git and ignored build output but keeps a tracked build folder`() {
        val names = zipNames(ExportFilter())
        assertTrue("web/src/index.ts" in names)
        assertTrue("web/package.json" in names)
        assertTrue("web/.gitignore" in names)
        assertTrue("build is not ignored, so it is source here", "web/build/script.sh" in names)
        assertFalse(names.any { "node_modules" in it })
        assertFalse(names.any { ".git/" in it })
        assertFalse(names.any { it.startsWith("web/dist") })
        assertFalse("links are left out", names.any { "pkg-link" in it })
        assertTrue(names.all { it.startsWith("web/") })
    }

    @Test
    fun `git can be included on its own and everything means everything`() {
        val withGit = zipNames(ExportFilter(skipHeavy = true, includeGit = true))
        assertTrue("web/.git/HEAD" in withGit)
        assertFalse(withGit.any { "node_modules" in it })
        val all = zipNames(ExportFilter.EVERYTHING)
        assertTrue("web/node_modules/left-pad/index.js" in all)
        assertTrue("web/dist/bundle.js" in all)
        assertTrue("web/.git/HEAD" in all)
    }

    @Test
    fun `gitignore patterns are read plainly and anchored ones only at the root`() {
        val root = tmp.newFolder("p")
        File(root, ".gitignore").writeText("/out\n**/target/\n!keep\n")
        val nested = File(root, "lib").apply { mkdirs() }
        assertTrue(ExportFilter.ignoredByGit("out", root, root, 0))
        assertFalse("anchored to the root only", ExportFilter.ignoredByGit("out", nested, root, 1))
        assertTrue(ExportFilter.ignoredByGit("target", nested, root, 1))
        File(nested, ".gitignore").writeText("/bin\n")
        assertTrue(ExportFilter.ignoredByGit("bin", nested, root, 1))
        assertFalse(ExportFilter.ignoredByGit("dist", nested, root, 1))
    }

    @Test
    fun `the scan counts what the export writes`() {
        val scan = ProjectTransfer(fs).scan(project, ExportFilter())
        assertEquals(4, scan.files) // index.ts, package.json, .gitignore, build/script.sh; pkg-link is a link
        assertEquals(1, scan.links)
        assertEquals(3, scan.skippedFolders) // node_modules, .git, dist
    }

    @Test
    fun `copy out writes the same tree through the sink`() {
        val target = tmp.newFolder("picked")
        val sink = object : TreeSink {
            override fun createFolder(parent: Any?, name: String): Any = File((parent as? File) ?: target, name).apply { mkdirs() }
            override fun createFile(parent: Any?, name: String, mimeType: String): OutputStream = File(parent as File, name).outputStream()
        }
        val transfer = ProjectTransfer(fs)
        val filter = ExportFilter()
        transfer.copyOut(project, "web", filter, sink, transfer.scan(project, filter))
        assertEquals("export {}", File(target, "web/src/index.ts").readText())
        assertFalse(File(target, "web/node_modules").exists())
    }

    @Test
    fun `a cancelled export stops`() {
        val transfer = ProjectTransfer(fs)
        try {
            transfer.writeZip(project, "web", ExportFilter(), ByteArrayOutputStream(), ExportScan(0, 0, 0, 0), cancelled = { true })
            fail("expected a cancel")
        } catch (e: TransferCancelled) {
            // expected
        }
    }

    /** A picked folder, in memory: names a phone allows that Linux does not, and two entries with one name. */
    private class FakeTree : SourceTree {
        val tree = mapOf<Any?, List<SourceTree.Node>>(
            null to listOf(
                SourceTree.Node("readme", "README.md", false, 5),
                SourceTree.Node("slash", "a/b.txt", false, 1),
                SourceTree.Node("dup1", "same.txt", false, 1),
                SourceTree.Node("dup2", "same.txt", false, 1),
                SourceTree.Node("dots", "..", false, 1),
                SourceTree.Node("dir", "src", true, 0),
            ),
            "dir" to listOf(SourceTree.Node("main", "main.kt", false, 3)),
        )
        val content = mapOf("readme" to "hello", "slash" to "s", "dup1" to "1", "dup2" to "2", "dots" to ".", "main" to "abc")

        override fun children(parent: Any?): List<SourceTree.Node> = tree[parent].orEmpty()
        override fun open(node: SourceTree.Node): InputStream = ByteArrayInputStream(content.getValue(node.handle as String).toByteArray())
    }

    @Test
    fun `a folder import copies the tree, makes names safe and keeps both duplicates`() {
        val transfer = ProjectTransfer(fs)
        val source = FakeTree()
        val total = transfer.scanSource(source, null)
        assertEquals(6 to 12L, total)
        val into = File(tmp.root, "imported")
        val (files, bytes) = transfer.copyIn(source, null, into, total)
        assertEquals(5, files) // ".." is not a usable name and is skipped
        assertEquals(11L, bytes)
        assertEquals("hello", File(into, "README.md").readText())
        assertEquals("s", File(into, "a_b.txt").readText())
        assertEquals(setOf("1", "2"), setOf(File(into, "same.txt").readText(), File(into, "same (1).txt").readText()))
        assertEquals("abc", File(into, "src/main.kt").readText())
        assertEquals(WorkspaceTree.FILE_MODE, fs.modeOf(File(into, "README.md")))
    }

    @Test
    fun `the project list shows top folders only, measures them and finds free names`() {
        val root = tmp.newFolder("workspaces")
        File(root, "alpha/src").mkdirs()
        File(root, "alpha/src/a.txt").writeText("12345")
        File(root, "alpha/b.txt").writeText("12")
        fs.symlink("b.txt", File(root, "alpha/link"))
        File(root, "beta").mkdirs()
        File(root, ".dsh-incoming-1").mkdirs()
        File(root, "loose.txt").writeText("x")
        fs.symlink("alpha", File(root, "gamma"))
        val names = ProjectList.names(root, fs).map { it.name }.toSet()
        assertEquals(setOf("alpha", "beta"), names)
        val alpha = ProjectList.measure(root, fs, ProjectList.names(root, fs).first { it.name == "alpha" })
        assertEquals(7L, alpha.sizeBytes)
        assertEquals(2, alpha.files)
        assertEquals("/workspaces/alpha", alpha.guestPath)
        assertEquals("workspaces/alpha", alpha.documentId)
        assertEquals("alpha-2", ProjectList.uniqueName(root, fs, "alpha"))
        assertEquals("delta", ProjectList.uniqueName(root, fs, "  delta "))
        assertNull(ProjectList.uniqueName(root, fs, ".."))
        assertEquals("a_b", ProjectList.uniqueName(root, fs, "a/b"))
        assertEquals(ProjectList.NameProblem.TAKEN, ProjectList.nameProblem(root, fs, "beta"))
        assertEquals(ProjectList.NameProblem.INVALID, ProjectList.nameProblem(root, fs, "a/b"))
        assertEquals(ProjectList.NameProblem.INVALID, ProjectList.nameProblem(root, fs, ".dsh-x"))
        assertNull(ProjectList.nameProblem(root, fs, "beta", current = "beta"))
        assertNull(ProjectList.nameProblem(root, fs, "epsilon"))
        assertNotNull(ProjectList.nameProblem(root, fs, ""))
    }

    @Test
    fun `a session whose folder is gone is recognised from the harness error`() {
        assertTrue(ProjectList.isMissingFolderError("working-directory: directory does not exist: \"/workspaces/Spoon-Knife\""))
        assertFalse(ProjectList.isMissingFolderError("llm-deepseek: no API key for provider route"))
        assertFalse(ProjectList.isMissingFolderError(null))
    }

    @Test
    fun `free space is checked with a margin`() {
        requireSpace(available = SPACE_MARGIN_BYTES + 100, needed = 100)
        try {
            requireSpace(available = SPACE_MARGIN_BYTES + 99, needed = 100)
            fail("expected no space")
        } catch (e: TransferException) {
            assertEquals(TransferProblem.NO_SPACE, e.problem)
        }
        assertEquals(0.5f, TransferProgress(1, 2, 50, 100).fraction)
        assertEquals(0.25f, TransferProgress(1, 4, 0, 0).fraction)
        assertNull(TransferProgress(0, 0, 0, 0).fraction)
    }
}
