package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Files/Preview path joins against the host's listing paths.
 *
 * `workspaceFiles/list` answers the directory relative to the workspace root and the EMPTY STRING
 * for the root itself, which is what made a root entry open as `/name` (`workspace-file/not-found:
 * no entry at "/name"`). These cover the shapes the panel can meet: the root `""`, a nested
 * directory, an entry join from either, the parent action, and an absolute Windows reference.
 */
class WorkspacePathsTest {

    @Test
    fun `the host's empty root listing path is the workspace root`() {
        assertEquals(".", workspacePath(""))
        assertEquals(".", workspacePath(null))
        assertEquals(".", workspacePath("."))
        assertEquals(".", workspacePath("./"))
        assertEquals(".", workspacePath("  "))
    }

    @Test
    fun `a nested listing path stays workspace relative`() {
        assertEquals("app/src", workspacePath("app/src"))
        assertEquals("app/src", workspacePath("app/src/"))
        assertEquals("app/src", workspacePath("app//src"))
        assertEquals("app/src", workspacePath("./app/src"))
    }

    @Test
    fun `a root entry joins as its name, never as a leading slash`() {
        assertEquals("README.md", workspaceChildPath("", "README.md"))
        assertEquals("README.md", workspaceChildPath(".", "README.md"))
        assertEquals("app", workspaceChildPath(null, "app"))
    }

    @Test
    fun `a nested entry joins under its listing directory`() {
        assertEquals("app/src/Main.kt", workspaceChildPath("app/src", "Main.kt"))
        assertEquals("app/src/Main.kt", workspaceChildPath("app/src/", "Main.kt"))
        assertEquals("app/src/Main.kt", workspaceChildPath("./app/src", "Main.kt"))
        assertEquals("app/src/Main.kt", workspaceChildPath(workspacePath("app//src"), "Main.kt"))
    }

    @Test
    fun `the parent of the root is the root, and a nested parent is workspace relative`() {
        assertEquals(".", workspaceParentPath(""))
        assertEquals(".", workspaceParentPath(null))
        assertEquals(".", workspaceParentPath("."))
        assertEquals("app", workspaceParentPath("app/src"))
        assertEquals("app", workspaceParentPath("app/src/"))
        assertEquals(".", workspaceParentPath("app"))
    }

    @Test
    fun `an absolute Windows reference keeps its drive and is slash separated`() {
        assertEquals("G:/work/proj/README.md", workspacePath("G:\\work\\proj\\README.md"))
        assertEquals("G:/work/proj", workspaceParentPath("G:\\work\\proj\\src"))
        assertEquals("G:/work/proj/README.md", workspaceChildPath("G:\\work\\proj", "README.md"))
        assertEquals("G:/work/proj/README.md", workspaceChildPath("G:/work/proj", "README.md"))
    }

    @Test
    fun `a Windows workspace on a network share keeps its UNC root`() {
        assertEquals("//server/share/proj/a.kt", workspacePath("\\\\server\\share\\proj\\a.kt"))
        assertEquals("//server/share/proj", workspaceParentPath("\\\\server\\share\\proj\\a.kt"))
        assertEquals("//server/share/proj/a.kt", workspaceChildPath("\\\\server\\share\\proj", "a.kt"))
    }

    @Test
    fun `an absolute posix reference keeps its leading slash`() {
        assertEquals("/tmp/a.md", workspacePath("/tmp/a.md"))
        assertEquals("/tmp", workspaceParentPath("/tmp/a.md"))
        assertEquals("/tmp/a.md", workspaceChildPath("/tmp", "a.md"))
    }

    @Test
    fun `a join never makes a double separator or a dot prefix`() {
        assertEquals("app/src/Main.kt", workspaceChildPath(workspacePath("app//src/"), "./Main.kt"))
        assertEquals("app/src", workspaceChildPath(workspaceChildPath("", "app"), "src"))
    }

    @Test
    fun `a root listing opens its entry as a workspace relative path, not as the refused leading-slash path`() {
        // The FilesSection flow: listing.path -> directory -> entry join. The harness answers "" for
        // the root, and "/name" is the path it refuses with `workspace-file/not-found`.
        val directory = workspacePath("")
        assertEquals(".", directory)
        val opened = workspaceChildPath(directory, "README.md")
        assertEquals("README.md", opened)
        assertFalse(opened.startsWith("/"))
    }
}
