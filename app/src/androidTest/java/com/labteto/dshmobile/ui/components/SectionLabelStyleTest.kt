package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Section labels read the same on every page: the harness settings pages ("Limits", "Models for
 * subagents") and the Add-ons page (a plugin's name) both head a group with [SectionHeader].
 *
 * The Add-ons page used to switch the uppercase off for a plugin's name, so its groups looked unlike
 * every other section label in Settings. [SectionHeader] no longer has the switch, and this holds
 * what that means on screen: a name is shown in capitals, in the same style.
 */
class SectionLabelStyleTest {

    @get:Rule val compose = createComposeRule()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun aPluginNameIsLabelledLikeAHarnessSettingsSection() {
        compose.setContent {
            DshTheme {
                Column {
                    SectionHeader("Limits")
                    SectionHeader("UI Sync Example Pet")
                }
            }
        }
        val settings = layoutOf("LIMITS")
        val plugin = layoutOf("UI SYNC EXAMPLE PET")
        assertEquals("the plugin name is set in capitals like the other labels", "UI SYNC EXAMPLE PET", plugin.layoutInput.text.text)
        assertEquals("same type style", settings.layoutInput.style, plugin.layoutInput.style)
    }
}
