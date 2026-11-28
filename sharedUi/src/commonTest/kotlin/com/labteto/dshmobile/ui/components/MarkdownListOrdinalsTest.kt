package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.core.markdown.MdBlock
import com.labteto.dshmobile.core.markdown.parseMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownListOrdinalsTest {

    private fun ordinalsOf(markdown: String): List<Int?> =
        listOrdinals(parseMarkdown(markdown).filterIsInstance<MdBlock.ListItem>())

    @Test
    fun bulletsNestedUnderNumberedItemStayBullets() {
        // The exact list from issue #154.
        val markdown = """
            1. The field starts empty.
            2. `submit()` checks it first:
               - an empty password shows the field's error
               - a short one asks for more:
                 - at least 8 characters
                 - one of them a digit
            3. Only then is the password hashed.
        """.trimIndent()
        assertEquals(listOf(1, 2, null, null, null, null, 3), ordinalsOf(markdown))
    }

    @Test
    fun eachDepthCountsOnItsOwnAndRestartsUnderTheNextParent() {
        val markdown = """
            1. one
              1. one.a
              2. one.b
            2. two
              1. two.a
        """.trimIndent()
        assertEquals(listOf(1, 1, 2, 2, 1), ordinalsOf(markdown))
    }

    @Test
    fun numberedItemsNestedUnderBulletsAreNumbered() {
        val markdown = """
            - first
              1. step
              2. step
            - second
        """.trimIndent()
        assertEquals(listOf(null, 1, 2, null), ordinalsOf(markdown))
    }
}
