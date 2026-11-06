package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Test
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.Added
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.Context
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.FileHeader
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.Hunk
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.Meta
import com.labteto.dshmobile.ui.screens.main.preview.DiffLineKind.Removed

class DiffLinesTest {

    private fun kinds(text: String) = classifyDiffLines(text.lines())

    @Test
    fun `a git diff is split into headers, hunks and changes`() {
        val diff = """
            diff --git a/x.kt b/x.kt
            index 83db48f..bf2a3c1 100644
            --- a/x.kt
            +++ b/x.kt
            @@ -1,3 +1,3 @@ fun main()
             keep
            -old
            +new
             tail
        """.trimIndent()
        assertEquals(
            listOf(Meta, Meta, FileHeader, FileHeader, Hunk, Context, Removed, Added, Context),
            kinds(diff),
        )
    }

    @Test
    fun `a removed line that looks like a file header is still a removed line`() {
        val diff = """
            --- a/notes.sql
            +++ b/notes.sql
            @@ -1,2 +1,2 @@
            --- a comment in the file
            +-- a different comment
             select 1;
        """.trimIndent()
        assertEquals(listOf(FileHeader, FileHeader, Hunk, Removed, Added, Context), kinds(diff))
    }

    @Test
    fun `two hunks and a second file are each recognised`() {
        val diff = """
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -a
            +b
            @@ -10,2 +10,3 @@
             c
            +d
             e
            --- a/b.txt
            +++ b/b.txt
            @@ -0,0 +1 @@
            +new
        """.trimIndent()
        assertEquals(
            listOf(FileHeader, FileHeader, Hunk, Removed, Added, Hunk, Context, Added, Context, FileHeader, FileHeader, Hunk, Added),
            kinds(diff),
        )
    }

    @Test
    fun `no newline markers are metadata`() {
        val diff = "@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file"
        assertEquals(listOf(Hunk, Removed, Meta, Added, Meta), kinds(diff))
    }

    @Test
    fun `a bare fragment is read by its first character`() {
        assertEquals(listOf(Removed, Added, Context), kinds("-x\n+y\nplain"))
    }

    @Test
    fun `rename and mode lines are metadata`() {
        val diff = "diff --git a/a b/b\nsimilarity index 100%\nrename from a\nrename to b\nnew file mode 100644\nBinary files a and b differ"
        assertEquals(List(6) { Meta }, kinds(diff))
    }

    @Test
    fun `an empty context line inside a hunk counts as context`() {
        val diff = "@@ -1,3 +1,3 @@\n a\n\n-b\n+c"
        assertEquals(listOf(Hunk, Context, Context, Removed, Added), kinds(diff))
    }

    @Test
    fun `totals count added and removed`() {
        val result = diffTotals(kinds("@@ -1,2 +1,3 @@\n-a\n+b\n+c\n d"))
        assertEquals(2 to 1, result)
    }
}
