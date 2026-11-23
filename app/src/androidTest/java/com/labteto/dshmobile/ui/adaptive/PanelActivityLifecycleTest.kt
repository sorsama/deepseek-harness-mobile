package com.labteto.dshmobile.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PanelActivityLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun activityPauseDuringOpeningAllowsNextGesture() {
        var shown by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Box(Modifier.wrapContentSize(unbounded = true).requiredSize(800.dp, 420.dp)) {
                    AdaptiveShell(
                        plan = planShell(800.dp, 420.dp, null, false),
                        drawerState = rememberDrawerState(DrawerValue.Closed),
                        listShown = shown, detailsShown = false,
                        preferredListWidth = 300.dp, onListShownChange = { shown = it },
                        list = { Box(Modifier.fillMaxSize()) },
                        details = {}, chat = {
                            Column(Modifier.fillMaxSize().testTag("chat")) {
                                var draft by remember { mutableStateOf("") }
                                OutlinedTextField(draft, { draft = it }, Modifier.testTag("composer"))
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("composer").performClick()
        compose.onNodeWithTag("composer").performTextInput("draft")
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        val distance = with(compose.density) { 270.dp.toPx() }
        compose.onNodeWithTag("chat").performTouchInput {
            down(Offset(20f, 250f)); moveBy(Offset(distance, 0f), 700)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("chat").performTouchInput { cancel() }
        compose.onNodeWithTag("composer").performClick()
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("chat").performTouchInput {
            swipe(Offset(20f, 250f), Offset(20f + distance, 250f), 700)
        }
        compose.runOnIdle { assertTrue(shown) }
    }
}
