package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.ModelCatalogModel
import com.labteto.dshmobile.core.wire.dto.ModelProviderGroup
import com.labteto.dshmobile.core.wire.dto.ModelSelection
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The chat's top bar: one row for the title and the model, a status row only when it has something
 * to say, and view tabs that fold away on request.
 *
 * Heights are the evidence, so no test depends on a string: the bar without tabs and without status is
 * its one 52dp row plus the 1dp hairline, and each extra row makes it taller.
 */
class ChatTopBarTest {
    @get:Rule val compose = createComposeRule()

    private val models = SessionModelsValue(
        current = ModelSelection(provider = "deepseek", model = "deepseek-chat"),
        routable = true,
        groups = listOf(
            ModelProviderGroup("deepseek", "DeepSeek", listOf(ModelCatalogModel("deepseek-chat", "DeepSeek-V3.2"))),
        ),
    )

    /** The bar's own row and its hairline: what is left with no status row and no tabs. */
    private val barOnly = 53.dp

    private fun bar(
        title: String = "Fix the login crash",
        running: Boolean = false,
        needsYou: Boolean = false,
        subagents: Int = 0,
        preset: String? = null,
        showTabs: Boolean = false,
        tabsVisible: Boolean = true,
        sessionOpen: Boolean = true,
        modelsEnabled: Boolean = true,
        onOpenModels: () -> Unit = {},
    ) {
        compose.setContent {
            DshTheme {
                ChatTopBar(
                    title = title,
                    running = running,
                    models = models,
                    agentPresetLabel = preset,
                    subagentCount = subagents,
                    detailsOpen = false,
                    tab = ChatTab.Chat,
                    fileCount = 0,
                    needsYou = needsYou,
                    onOpenDrawer = {},
                    onOpenModels = onOpenModels,
                    onOpenHistory = {},
                    onOpenPresets = {},
                    onOpenSubagents = {},
                    onOpenDetails = {},
                    onTabChange = {},
                    onOpenWorkspace = {},
                    workspaceEnabled = true,
                    historyEnabled = true,
                    sessionOpen = sessionOpen,
                    modelsEnabled = modelsEnabled,
                    showTabs = showTabs,
                    tabsVisible = tabsVisible,
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
        compose.waitForIdle()
    }

    // ----- the status row -----

    @Test
    fun withNothingToSayTheBarIsOneRowAndAHairline() {
        bar()

        compose.onNodeWithTag("bar").assertHeightIsEqualTo(barOnly)
    }

    @Test
    fun aRunningSessionAddsTheStatusRow() {
        bar(running = true)

        compose.onNodeWithTag("bar").assertHeightIsAtLeast(barOnly + 28.dp)
    }

    @Test
    fun aSubagentCountAddsTheStatusRow() {
        bar(subagents = 2)

        compose.onNodeWithTag("bar").assertHeightIsAtLeast(barOnly + 48.dp)
    }

    @Test
    fun anAgentPresetAddsTheStatusRow() {
        bar(preset = "Standard")

        compose.onNodeWithTag("bar").assertHeightIsAtLeast(barOnly + 48.dp)
    }

    // ----- the view tabs -----

    @Test
    fun theTabsFoldAwayAndComeBackWhileTheHairlineStays() {
        var visible by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                ChatTopBar(
                    title = "Fix the login crash",
                    running = false,
                    models = models,
                    agentPresetLabel = null,
                    subagentCount = 0,
                    detailsOpen = false,
                    tab = ChatTab.Chat,
                    fileCount = 0,
                    needsYou = false,
                    onOpenDrawer = {},
                    onOpenModels = {},
                    onOpenHistory = {},
                    onOpenPresets = {},
                    onOpenSubagents = {},
                    onOpenDetails = {},
                    onTabChange = {},
                    onOpenWorkspace = {},
                    workspaceEnabled = true,
                    historyEnabled = true,
                    tabsVisible = visible,
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bar").assertHeightIsAtLeast(barOnly + 48.dp)

        visible = false
        compose.waitForIdle()
        compose.onNodeWithTag("bar").assertHeightIsEqualTo(barOnly)

        visible = true
        compose.waitForIdle()
        compose.onNodeWithTag("bar").assertHeightIsAtLeast(barOnly + 48.dp)
    }

    // ----- the title block -----

    @Test
    fun theTitleAndTheModelAreOneControlThatOpensThePicker() {
        var opened = 0
        bar(onOpenModels = { opened++ })

        compose.onNodeWithText("DeepSeek-V3.2").assertHasClickAction().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun aSubagentChildStatesItsModelWithoutOfferingAPicker() {
        var opened = 0
        bar(modelsEnabled = false, onOpenModels = { opened++ })

        compose.onNodeWithText("DeepSeek-V3.2").assertHasNoClickAction()
        compose.onNodeWithText("Fix the login crash").assertHasNoClickAction()
        assertEquals(0, opened)
    }

    @Test
    fun withNoSessionOpenOnlyTheTitleIsDrawn() {
        bar(title = "Start here", sessionOpen = false)

        compose.onNodeWithText("Start here").assertHasNoClickAction()
        compose.onNodeWithText("DeepSeek-V3.2").assertDoesNotExist()
    }
}
