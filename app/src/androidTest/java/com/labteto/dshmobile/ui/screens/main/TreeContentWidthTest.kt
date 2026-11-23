package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.labteto.dshmobile.connection.ConnectionPhase
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TreeContentWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerActionsFitAtMeasuredMinimum() {
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.8f)) {
                    val width = treeContentMinimumWidth()
                    androidx.compose.foundation.layout.Box(Modifier.width(width - 32.dp)) {
                        TreeDrawerHeader("Long connection address", ConnectionPhase.CONNECTED, {
                            androidx.compose.material3.IconButton(onClick = {}, modifier = Modifier.testTag("pin")) {}
                        }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("pin").assertIsDisplayed().assertHasClickAction()
        compose.onNode(hasText("Long connection address") and hasClickAction()).assertIsDisplayed()
        val settings = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(com.labteto.dshmobile.R.string.settings_title)
        compose.onNodeWithContentDescription(settings).assertIsDisplayed().assertHasClickAction()
    }

    @Test fun minimumKeepsHeaderUsableAndGrowsWithTextScale() {
        var normal = Dp.Unspecified
        var enlarged = Dp.Unspecified
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                    normal = treeContentMinimumWidth()
                }
                CompositionLocalProvider(LocalDensity provides Density(2f, 1.8f)) {
                    enlarged = treeContentMinimumWidth()
                }
            }
        }
        compose.runOnIdle {
            assertTrue("normal minimum: $normal", normal >= 320.dp)
            assertTrue("enlarged minimum: $enlarged, normal: $normal", enlarged > normal)
        }
    }
}
