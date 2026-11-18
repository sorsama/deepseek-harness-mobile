package com.labteto.dshmobile.ui.screens.models

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Keeps an API key on the one path it is allowed: typed into the Models screen, handed to the
 * controller for one call, sent to the harness. The controller's own guarantees (no key in its
 * state, its log lines or its messages) are proven in `ModelsControllerTest` in `:sharedUi`; this
 * reads the Android sources around it, where the mistakes would be a saveable field, a log call,
 * a clipboard write or a store on disk, and fails with the file and the line.
 */
class ModelKeyRulesTest {

    private val appRoot = File("src/main/java/com/labteto/dshmobile").let { if (it.exists()) it else File("app/src/main/java/com/labteto/dshmobile") }
    private val sharedRoot = File("../sharedUi/src/commonMain/kotlin/com/labteto/dshmobile").let { if (it.exists()) it else File("sharedUi/src/commonMain/kotlin/com/labteto/dshmobile") }

    /** Every file a key passes through on its way to the harness. */
    private val keyPath = listOf(
        File(appRoot, "ui/screens/models/ModelsScreen.kt"),
        File(appRoot, "ui/screens/models/ModelSetupEntries.kt"),
        // The web search key on the Harness settings pages, which now come back after the activity is recreated.
        File(appRoot, "ui/screens/harnesssettings/SecretControl.kt"),
        File(appRoot, "models/ModelSetupMonitor.kt"),
        File(sharedRoot, "models/ModelsController.kt"),
        File(sharedRoot, "models/ModelsPort.kt"),
        File(sharedRoot, "models/ProviderDirectory.kt"),
    )

    private val forbidden = listOf(
        Regex("""rememberSaveable""") to "saved-state bundles outlive the screen; keep the key in remember {}",
        Regex("""SavedStateHandle""") to "saved-state bundles outlive the screen",
        Regex("""(?<![A-Za-z])Log\.[vdiwe]\(|println\(|Timber\.""") to "a log line can carry the key; use the controller's masked log",
        Regex("""setText\(|setPrimaryClip""") to "the app must not put a key on the clipboard",
        Regex("""DataStore|SharedPreferences|openFileOutput|writeText\(""") to "nothing on the key path writes to disk",
    )

    @Test
    fun `nothing on the key path saves logs copies or stores`() {
        val problems = mutableListOf<String>()
        for (file in keyPath) {
            assertTrue("missing ${file.path}", file.exists())
            file.readLines().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                for ((pattern, why) in forbidden) {
                    if (pattern.containsMatchIn(code)) problems += "${file.name}:${index + 1}: ${pattern.pattern} ($why)\n    $line"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `the key field is a masked password field in a secure window`() {
        val screen = File(appRoot, "ui/screens/models/ModelsScreen.kt").readText()
        listOf(
            "SecureContent()" to "FLAG_SECURE and the autofill exclusion",
            "PasswordVisualTransformation()" to "the key masked by default",
            "KeyboardType.Password" to "no keyboard suggestions or learning",
            "autoCorrectEnabled = false" to "no autocorrect on the key",
            "NoCopyTextToolbar(" to "no copy or cut from the key field",
        ).forEach { (needle, why) -> assertTrue("ModelsScreen.kt lost $needle ($why)", needle in screen) }
        val secure = File(appRoot, "ui/components/SecureContent.kt").readText()
        assertTrue("FLAG_SECURE is no longer set", "addFlags(WindowManager.LayoutParams.FLAG_SECURE)" in secure)
        assertTrue("the capture switch must stay debug-only", "BuildConfig.DEBUG &&" in secure)
        assertTrue("autofill is no longer excluded", "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS" in secure)
    }
}
