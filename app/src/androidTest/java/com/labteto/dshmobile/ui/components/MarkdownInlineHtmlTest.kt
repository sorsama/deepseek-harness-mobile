package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A reply that mixes in a little HTML reads as formatted text, not as tags. */
class MarkdownInlineHtmlTest {
    @get:Rule val compose = createComposeRule()

    private fun render(markdown: String) {
        compose.setContent { DshTheme { MarkdownText(markdown, Modifier.width(320.dp)) } }
    }

    @Test fun commonTagsAreNotShownAsText() {
        render("Run <b>the tests</b> first<br>then press <kbd>Ctrl</kbd> and <i>wait</i>. <!-- hidden note --> See <a href=\"https://example.com/docs\">the docs</a>.")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("<", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("hidden note", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("the tests", substring = true).assertExists()
        compose.onNodeWithText("the docs", substring = true).assertExists()
    }

    @Test fun codeKeepsItsAngleBrackets() {
        render("Use `List<String>` and\n\n```html\n<b>bold</b>\n```")
        compose.waitForIdle()
        compose.onNodeWithText("List<String>", substring = true).assertExists()
        compose.onNodeWithText("<b>bold</b>", substring = true).assertExists()
    }
}
