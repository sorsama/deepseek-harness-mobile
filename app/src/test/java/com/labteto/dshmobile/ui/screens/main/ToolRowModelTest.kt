package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tool rows read `Read · app\build.gradle.kts` rather than a presenter title and an absolute path.
 * These pin the derivation, which is the whole reason the transcript's verb column is consistent.
 */
class ToolRowModelTest {

    @Test
    fun `a web search shows its queries, however many the model asked for`() {
        // Harness 0.1.0-rc.8 turned `web_search`'s single `query` into a 1-4 element `queries`
        // array. Reading only the old key left the row saying "Search" and nothing else.
        val one = toolRowModel(
            toolName = "web_search",
            argumentsJson = """{"queries":["deepseek harness architecture"]}""",
            cwd = null,
        )
        assertEquals("deepseek harness architecture", one.summary)

        val several = toolRowModel(
            toolName = "web_search",
            argumentsJson = """{"queries":["kotlin coroutines","structured concurrency"]}""",
            cwd = null,
        )
        assertEquals("kotlin coroutines, structured concurrency", several.summary)

        // An older harness still sends the singular key, and grep and glob always did.
        val older = toolRowModel(
            toolName = "web_search",
            argumentsJson = """{"query":"just the one"}""",
            cwd = null,
        )
        assertEquals("just the one", older.summary)
    }

    @Test
    fun `a read shows the verb and a path relative to the session cwd`() {
        val row = toolRowModel(
            toolName = "read",
            argumentsJson = """{"file_path":"D:\\LabTeto\\deepseek-mobile\\app\\build.gradle.kts"}""",
            cwd = "D:\\LabTeto\\deepseek-mobile",
        )
        assertEquals("Read", row.title)
        assertEquals("app\\build.gradle.kts", row.summary)
        assertEquals("D:\\LabTeto\\deepseek-mobile\\app\\build.gradle.kts", row.filePath)
    }

    @Test
    fun `posix hosts relativise too`() {
        val row = toolRowModel(
            toolName = "edit",
            argumentsJson = """{"path":"/home/me/project/src/main.kt"}""",
            cwd = "/home/me/project",
        )
        assertEquals("Edit", row.title)
        assertEquals("src/main.kt", row.summary)
    }

    @Test
    fun `a path outside the workspace is left alone`() {
        val row = toolRowModel(
            toolName = "read",
            argumentsJson = """{"path":"/etc/hosts"}""",
            cwd = "/home/me/project",
        )
        assertEquals("/etc/hosts", row.summary)
    }

    @Test
    fun `pwsh classifies as a shell but keeps its presenter title`() {
        // The generic table cannot tell PowerShell from bash; the tool's own presenter can, and
        // its title wins so the row matches what the desktop client shows.
        val row = toolRowModel(
            toolName = "pwsh",
            argumentsJson = """{"description":"Push v0.1.0 tag","command":"git push --tags"}""",
            cwd = null,
            viewTitle = "Pwsh",
        )
        assertEquals("Pwsh", row.title)
        assertEquals("Push v0.1.0 tag", row.summary)
    }

    @Test
    fun `a shell without a description falls back to the command`() {
        val row = toolRowModel("bash", """{"command":"./gradlew test"}""", cwd = null)
        assertEquals("Bash", row.title)
        assertEquals("./gradlew test", row.summary)
    }

    @Test
    fun `an unclassified tool still identifies itself`() {
        val row = toolRowModel("some_new_tool", """{"whatever":1}""", cwd = null)
        assertEquals("Tool call", row.title)
        assertEquals("some_new_tool", row.summary)
    }

    @Test
    fun `malformed arguments degrade to no summary rather than throwing`() {
        val row = toolRowModel("read", "{not json", cwd = null)
        assertEquals("Read", row.title)
        assertNull(row.summary)
    }

    @Test
    fun `basename handles both separators`() {
        assertEquals("deepseek-mobile", basename("D:\\LabTeto\\deepseek-mobile"))
        assertEquals("project", basename("/home/me/project/"))
        assertEquals("plain", basename("plain"))
    }
}
