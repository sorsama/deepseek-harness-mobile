package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

/** Runs the production lazy section and saveable expansion state, not a copied list algorithm. */
class CompactWorkspaceSectionTest {
    @get:Rule val compose = createComposeRule()
    private fun rows(count: Int) = (1..count).map { n ->
        SessionRow("s$n", "Session $n", false, false, null, null, null, null, n.toLong(), null)
    }

    @Test fun expansionStateSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        var expansion: WorkspaceExpansion? = null
        restoration.setContent {
            val state = rememberWorkspaceExpansion()
            expansion = state
            Text("Requested ${state.requested("host", "workspace")}")
        }
        compose.runOnIdle { expansion!!.showMore("host", "workspace", 5) }
        compose.onNodeWithText("Requested 10").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Requested 10").assertExists()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, expansion!!.requested("other", "workspace")) }
    }

    @Test fun moreCollapseAndHostWorkspaceStateAreIndependent() {
        var host by mutableStateOf("hostA")
        var workspace by mutableStateOf("workspaceA")
        compose.setContent {
            DshTheme {
                val expansion = rememberWorkspaceExpansion()
                val window = compactWorkspaceWindow(rows(16), expansion.requested(host, workspace), emptySet())
                LazyColumn(Modifier.testTag("list")) {
                    workspaceSessionItems(workspace, window,
                        onMore = { expansion.showMore(host, workspace, window.rows.size) },
                        onCollapse = { expansion.collapse(host, workspace) },
                    ) { row -> item(key = row.sessionId) { Text(row.title!!) } }
                }
            }
        }
        compose.onNodeWithText("Session 6").assertDoesNotExist()
        compose.onNodeWithTag("workspace-more-workspaceA").performClick()
        compose.onNodeWithText("Session 10").assertExists()
        compose.runOnIdle { workspace = "workspaceB" }
        compose.onNodeWithTag("workspace-more-workspaceB").assertExists()
        compose.onNodeWithText("Session 10").assertDoesNotExist()
        compose.runOnIdle { workspace = "workspaceA"; host = "hostB" }
        compose.onNodeWithText("Session 10").assertDoesNotExist()
        compose.runOnIdle { host = "hostA" }
        compose.onNodeWithText("Session 10").assertExists()
        compose.onNodeWithTag("workspace-collapse-workspaceA").assertIsDisplayed()
        compose.onNodeWithTag("workspace-collapse-workspaceA").performClick()
        compose.onNodeWithText("Session 10").assertDoesNotExist()
    }

    @Test fun runningDescendantAndPendingAttentionSetCollapseBaseline() {
        val rows = rows(18)
        val child = rows.first().copy(sessionId = "child", parentSessionId = "s8", origin = "subagent", running = true)
        compose.setContent {
            DshTheme {
                val expansion = rememberWorkspaceExpansion()
                val window = compactWorkspaceWindow(rows, expansion.requested("host", "project"), emptySet(), mapOf("s8" to listOf(child)))
                LazyColumn(Modifier.testTag("list")) {
                    workspaceSessionItems("project", window,
                        { expansion.showMore("host", "project", window.rows.size) },
                        { expansion.collapse("host", "project") },
                    ) { row -> item(key = row.sessionId) { Text(row.title!!) } }
                }
            }
        }
        compose.onNodeWithText("Session 8").assertExists()
        compose.onNodeWithText("Session 9").assertDoesNotExist()
        compose.onNodeWithTag("workspace-more-project").assertIsDisplayed()
        compose.onNodeWithTag("workspace-more-project").performClick()
        compose.onNodeWithTag("workspace-collapse-project").assertIsDisplayed()
        compose.onNodeWithTag("workspace-collapse-project").performClick()
        compose.onNodeWithText("Session 8").assertExists()
        compose.onNodeWithText("Session 9").assertDoesNotExist()
    }

    @Test fun largeFontRtlControlsRemainReachable() {
        compose.setContent {
            DshTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.5f),
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
                ) {
                    val expansion = rememberWorkspaceExpansion()
                    val window = compactWorkspaceWindow(rows(16), expansion.requested("host", "rtl"), emptySet())
                    LazyColumn(Modifier.testTag("list")) {
                        workspaceSessionItems("rtl", window,
                            { expansion.showMore("host", "rtl", window.rows.size) },
                            { expansion.collapse("host", "rtl") },
                        ) { row -> item(key = row.sessionId) { Text(row.title!!) } }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToNode(androidx.compose.ui.test.hasTestTag("workspace-more-rtl"))
        compose.onNodeWithTag("workspace-more-rtl").performClick()
        compose.onNodeWithTag("list").performScrollToNode(androidx.compose.ui.test.hasTestTag("workspace-collapse-rtl"))
        compose.onNodeWithTag("workspace-collapse-rtl").performClick()
        val bitmap = compose.onNodeWithTag("list").captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, "compact-section-rtl.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun currentBeyondFiftyIsNotSilentlyHidden() {
        compose.setContent {
            DshTheme {
                val window = compactWorkspaceWindow(rows(65), 0, setOf("s60"))
                LazyColumn(Modifier.testTag("list")) {
                    workspaceSessionItems("project", window, {}, {}) { row ->
                        item(key = row.sessionId) { Text(row.title!!) }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToNode(hasText("Session 60"))
        compose.onNodeWithText("Session 60").assertExists()
    }
}
