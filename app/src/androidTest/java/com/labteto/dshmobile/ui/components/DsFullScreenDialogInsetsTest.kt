package com.labteto.dshmobile.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The 0.19.0 bug, on a device: a full-screen dialog window was laid out inside the system bars while
 * its content was measured at the full display height, so a footer pinned to the bottom ended up
 * under the gesture bar (Automations) or off the screen (three-button navigation, Terminal keys).
 *
 * Run it on an API 35 or newer emulator with both `navbar.gestural` and `navbar.threebutton`.
 *
 * It drives an [ActivityScenario] directly rather than a Compose test rule: the rule waits through
 * Espresso, and Espresso 3.6 cannot run on Android 16 and newer.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DsFullScreenDialogInsetsTest {

    private class Measured {
        @Volatile var footerBottom = -1
        @Volatile var headerTop = -1
        @Volatile var windowTop = Int.MIN_VALUE
        @Volatile var windowHeight = -1
        val ready get() = footerBottom > 0 && windowHeight > 0 && headerTop >= 0
    }

    @Composable
    private fun Page(measured: Measured) {
        DsFullScreenDialog(onDismissRequest = {}) {
            val view = LocalView.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .onGloballyPositioned { measured.headerTop = it.positionOnScreen().y.roundToInt() },
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    measured.windowTop = location[1]
                    measured.windowHeight = view.height
                },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .onGloballyPositioned { measured.footerBottom = (it.positionOnScreen().y + it.size.height).roundToInt() },
            )
        }
    }

    @Test
    fun theWindowCoversTheDisplayAndTheFooterClearsTheBars() {
        val measured = Measured()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { DshTheme { Page(measured) } }
            }
            // The dialog window is created, and laid out, a frame or two after composition.
            val deadline = System.currentTimeMillis() + 8_000
            while (!measured.ready && System.currentTimeMillis() < deadline) Thread.sleep(50)
            Thread.sleep(500)
            assertTrue("the dialog never laid out", measured.ready)

            scenario.onActivity { activity ->
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                val bars = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val bottomBar = bars?.bottom ?: 0
                val topBar = bars?.top ?: 0

                // The root cause: the window is the whole display, not the space between the bars.
                assertEquals("the dialog window should start at the top of the display", 0, measured.windowTop)
                assertTrue(
                    "the dialog window is ${measured.windowHeight}px tall on a ${bounds.height()}px display",
                    measured.windowHeight >= bounds.height() - 1,
                )
                // The symptom: nothing pinned to the bottom may sit under the navigation bar, and
                // nothing at the top may sit under the status bar.
                assertTrue(
                    "footer ends at ${measured.footerBottom}px but the navigation bar starts at ${bounds.bottom - bottomBar}px",
                    measured.footerBottom <= bounds.bottom - bottomBar,
                )
                assertTrue(
                    "header starts at ${measured.headerTop}px but the status bar reaches ${topBar}px",
                    measured.headerTop >= topBar,
                )
            }
        }
    }
}
