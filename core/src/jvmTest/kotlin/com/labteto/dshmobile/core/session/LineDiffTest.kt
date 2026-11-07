package com.labteto.dshmobile.core.session

import org.junit.Assert.*
import org.junit.Test

class LineDiffTest {
    @Test fun unchangedContextIsNotReportedAsRemovedAndAdded() {
        val lines = lineDiff("first\nold\nlast", "first\nnew\nlast")
        assertEquals(listOf(' ', '-', '+', ' '), lines.map { it.kind })
        assertEquals(listOf(1, 2, null, 3), lines.map { it.oldLine })
        assertEquals(listOf(1, null, 2, 3), lines.map { it.newLine })
    }
    @Test fun emptyCreationAndDeletionHaveNoPhantomLine() {
        assertTrue(lineDiff("", "").isEmpty())
        assertEquals(listOf('+'), lineDiff(null, "new").map { it.kind })
        assertEquals(listOf('-'), lineDiff("old", null).map { it.kind })
    }
}
