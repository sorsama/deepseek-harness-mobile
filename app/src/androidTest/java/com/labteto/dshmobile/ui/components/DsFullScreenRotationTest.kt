package com.labteto.dshmobile.ui.components

import android.app.UiAutomation
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Turning the phone with a full-screen page open must not leave the page blank.
 *
 * It did, on every page (Plugins, Models, the Workspace panel), in both directions. The activity
 * handles a rotation itself (it is not recreated), so the page's window is only resized; Compose
 * measured the page for the new size while the window's view still had its old one, and the insets
 * worked out from that view's size were taller than the whole landscape window. The page was laid
 * out at zero height and nothing measured it again.
 *
 * The test needs the app's own activity for that reason: the test activity Compose provides is
 * recreated on a rotation, which reopens the page at the new size and hides the bug. It puts a page
 * over whatever the activity shows, turns the display with [UiAutomation.setRotation] both ways, and
 * checks that the page's title is laid out with a real size inside the window each time.
 *
 * It drives an [ActivityScenario] directly rather than a Compose test rule: the rule waits through
 * Espresso, and Espresso 3.6 cannot run on Android 16 and newer.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DsFullScreenRotationTest {

    private class Measured {
        @Volatile var windowWidth = 0
        @Volatile var windowHeight = 0
        @Volatile var titleTop = -1
        @Volatile var titleBottom = -1
        @Volatile var titleWidth = 0
        @Volatile var titleHeight = 0

        val landscape get() = windowWidth > windowHeight
        val titleShown
            get() = titleWidth > 0 && titleHeight > 0 && titleTop >= 0 && titleBottom <= windowHeight

        override fun toString() =
            "window ${windowWidth}x$windowHeight, title ${titleWidth}x$titleHeight from $titleTop to $titleBottom"
    }

    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun waitFor(measured: Measured, what: String, condition: (Measured) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!condition(measured) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        // A blank page and a laid-out one can look the same for a frame: settle, then look again.
        Thread.sleep(700)
        assertTrue("$what: $measured", condition(measured))
    }

    @Test
    fun thePageTitleStaysOnScreenWhenThePhoneTurnsBothWays() {
        val measured = Measured()
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val overlay = ComposeView(activity).apply {
                        setContent {
                            DshTheme {
                                DsFullScreenDialog(onDismissRequest = {}) {
                                    val window: View = LocalView.current
                                    Box(Modifier.fillMaxWidth().height(56.dp)) {
                                        Text(
                                            "Rotation page",
                                            Modifier.onGloballyPositioned {
                                                val top = it.positionInWindow().y.roundToInt()
                                                measured.windowWidth = window.width
                                                measured.windowHeight = window.height
                                                measured.titleTop = top
                                                measured.titleBottom = top + it.size.height
                                                measured.titleWidth = it.size.width
                                                measured.titleHeight = it.size.height
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(
                        overlay,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
                waitFor(measured, "the page never showed its title in portrait") { !it.landscape && it.titleShown }

                automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
                waitFor(measured, "after turning to landscape the title is gone") { it.landscape && it.titleShown }

                automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
                waitFor(measured, "after turning back to portrait the title is gone") { !it.landscape && it.titleShown }

                automation.setRotation(UiAutomation.ROTATION_FREEZE_270)
                waitFor(measured, "after turning to landscape the other way the title is gone") { it.landscape && it.titleShown }
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
