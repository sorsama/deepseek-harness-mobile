package com.labteto.dshmobile.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.ThinkingPanel
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay

/**
 * A debug page for `-DshMarkdownSample top|end`: the chat's shared Markdown (iOS M5a) drawing one fixed answer with
 * every block kind, before any iOS screen shows a transcript (that is M5c). [at] `end` scrolls to the foot, so two
 * screenshots cover the whole sample. [onShown] gets `DSH_MARKDOWN_RESULT shown at=… height=…` once it is laid out.
 */
@Composable
internal fun MarkdownSamplePage(at: String, onShown: (String) -> Unit) {
    DshTheme {
        val scroll = rememberScrollState()
        var thinkingOpen by remember { mutableStateOf(at == "end") }
        Column(
            Modifier
                .fillMaxSize()
                .background(DsTheme.colors.bgBase)
                .safeDrawingPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UserBubble(SAMPLE_PROMPT)
            ThinkingPanel(SAMPLE_THINKING, streaming = false, expanded = thinkingOpen, onToggle = { thinkingOpen = !thinkingOpen })
            MarkdownText(SAMPLE)
        }
        LaunchedEffect(Unit) {
            // Two frames for the layout and the fonts, then the scroll the argument asked for.
            delay(1_500)
            if (at == "end") scroll.scrollTo(scroll.maxValue)
            delay(500)
            onShown("DSH_MARKDOWN_RESULT shown at=$at height=${scroll.maxValue + scroll.viewportSize}")
        }
    }
}

private const val SAMPLE_PROMPT = "Before you change it, write the whole flow up as notes I can keep."

private const val SAMPLE_THINKING =
    "Notes with a list of steps, a table of the files and the code, so the fix reads in one place."

/** The demo harness's gallery answer (`DemoHarness.GALLERY` in `:mock-harness`), which the Android pixel check draws. */
private const val SAMPLE: String =
    "# Login flow notes\n\n" +
        "The crash is fixed in two places. **Both** matter, and the *order* matters too.\n\n" +
        "## Steps\n\n" +
        "1. The field starts empty, not `null`.\n" +
        "2. `submit()` checks it first:\n" +
        "   - an empty password shows the field's error\n" +
        "   - a short one asks for more:\n" +
        "     - at least 8 characters\n" +
        "     - one of them a digit\n" +
        "3. Only then is the password hashed.\n\n" +
        "### What changes where\n\n" +
        "| File | Change | Lines |\n" +
        "| --- | --- | ---: |\n" +
        "| `LoginViewModel.kt` | guard before the hash | 12 |\n" +
        "| `LoginScreen.kt` | show the error | 4 |\n\n" +
        "> The guard belongs in the view model, so the screen stays a plain view.\n>\n> Tests cover both paths.\n\n" +
        "```kotlin\nfun submit() {\n    val value = password.orEmpty()\n    if (value.isEmpty()) return showError(R.string.login_password_required)\n" +
        "    repository.logIn(email, value.sha256())\n}\n```\n\n" +
        "The build log, as it came:\n\n" +
        "```\n> Task :app:compileDebugKotlin\n" +
        "w: file:///C:/Users/demo/mobile-app/app/src/main/java/com/example/login/LoginViewModel.kt:14:9 'sha256' is deprecated.\n" +
        "BUILD SUCCESSFUL in 41s\n```\n\n" +
        "---\n\n" +
        "See [the view model](app/src/main/java/com/example/login/LoginViewModel.kt), " +
        "the [null safety guide](https://kotlinlang.org/docs/null-safety.html) and the dev server at http://localhost:8080/login."
