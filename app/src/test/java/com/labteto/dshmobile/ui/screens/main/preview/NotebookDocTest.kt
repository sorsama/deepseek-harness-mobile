package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookDocTest {

    private val notebook = """
    {
      "metadata": {"kernelspec": {"language": "python", "name": "python3"}, "language_info": {"name": "python"}},
      "nbformat": 4,
      "cells": [
        {"cell_type": "markdown", "source": ["# Title\n", "text"]},
        {"cell_type": "code", "execution_count": 3, "source": "print('hi')\n1 + 1",
         "outputs": [
           {"output_type": "stream", "name": "stdout", "text": ["hi\n"]},
           {"output_type": "stream", "name": "stderr", "text": "warn"},
           {"output_type": "execute_result", "data": {"text/plain": ["2"]}},
           {"output_type": "display_data", "data": {"image/png": "iVBO\nRw0K", "text/plain": ["<Figure>"]}},
           {"output_type": "display_data", "data": {"text/html": "<b>x</b>"}},
           {"output_type": "error", "ename": "ValueError", "evalue": "bad", "traceback": ["\u001b[31mValueError\u001b[0m: bad"]}
         ]},
        {"cell_type": "raw", "source": "raw text"}
      ]
    }
    """.trimIndent()

    private fun doc() = (parseNotebook(notebook) as NotebookParse.Valid).doc

    @Test
    fun `cells keep their kind, source and run number`() {
        val cells = doc().cells
        assertEquals(listOf(NotebookCellKind.Markdown, NotebookCellKind.Code, NotebookCellKind.Raw), cells.map { it.kind })
        assertEquals("# Title\ntext", cells[0].source)
        assertEquals("print('hi')\n1 + 1", cells[1].source)
        assertEquals(3, cells[1].executionCount)
        assertNull(cells[0].executionCount)
    }

    @Test
    fun `the kernel language is found for the label and the highlighter`() {
        assertEquals("python", doc().languageName)
        assertEquals("py", doc().languageKey)
    }

    @Test
    fun `outputs are turned into text, pictures and notes`() {
        val outputs = doc().cells[1].outputs
        assertEquals(6, outputs.size)
        assertEquals(NotebookOutput.Text("hi\n"), outputs[0])
        assertEquals(NotebookOutput.Text("warn", stderr = true), outputs[1])
        assertEquals(NotebookOutput.Text("2"), outputs[2])
        // A picture wins over the text description that came with it, and whitespace in the base64 is dropped.
        assertEquals(NotebookOutput.Image("image/png", "iVBORw0K"), outputs[3])
        assertEquals(NotebookOutput.Unsupported("text/html"), outputs[4])
        val error = outputs[5] as NotebookOutput.Text
        assertTrue(error.error)
        assertEquals("ValueError: bad", error.text)
    }

    @Test
    fun `a notebook without a kernel name still reads`() {
        val result = parseNotebook("""{"cells": [{"cell_type": "code", "source": ""}]}""") as NotebookParse.Valid
        assertNull(result.doc.languageKey)
        assertEquals(1, result.doc.cells.size)
    }

    @Test
    fun `something that is not a notebook is invalid`() {
        assertTrue(parseNotebook("[1,2]") is NotebookParse.Invalid)
        assertTrue(parseNotebook("""{"hello": 1}""") is NotebookParse.Invalid)
        assertTrue(parseNotebook("not json") is NotebookParse.Invalid)
    }

    @Test
    fun `a huge output is cut`() {
        val big = "x".repeat(50_000)
        val result = parseNotebook(
            """{"cells":[{"cell_type":"code","source":"","outputs":[{"output_type":"stream","name":"stdout","text":"$big"}]}]}""",
        ) as NotebookParse.Valid
        val text = result.doc.cells[0].outputs[0] as NotebookOutput.Text
        assertTrue(text.text.length < 20_100)
        assertTrue(text.text.endsWith("…"))
    }

    @Test
    fun `terminal colour codes are stripped`() {
        assertEquals("red and plain", stripAnsi("\u001B[31mred\u001B[0m and plain"))
        assertEquals("a b", stripAnsi("a \u001B[1;32;40m\u001B[Kb"))
        assertEquals("nothing to do", stripAnsi("nothing to do"))
    }
}
