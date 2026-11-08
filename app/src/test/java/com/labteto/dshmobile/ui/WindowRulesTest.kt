package com.labteto.dshmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps full-screen surfaces from being built by hand again.
 *
 * In 0.19.0 the Automations footer and the Terminal key row sat under the gesture bar on a real
 * phone. Both were Compose `Dialog` windows. Android fits a dialog window inside the system bars
 * and Compose lays the content out at the full display height, so the last rows ended up off the
 * screen and the dialog reported no insets to pad them. The fix lives in one place, `DsWindow`
 * (`ui/components/DsFullScreen.kt`); this test makes sure nothing walks around it.
 *
 * It reads the sources, not the compiled classes, because the mistake is made while typing and a
 * failing build with the file, the line and the component to use is the cheapest place to catch it.
 *
 * The shared components moved to `:sharedUi` when the Connect screen did, so it reads both source
 * trees: the app's and the shared module's. The paths below are relative to a tree, and both trees
 * use the same package directories.
 */
class WindowRulesTest {

    private class Rule(
        val what: String,
        val pattern: Regex,
        /** Source files (relative to the java root) that may contain it. */
        val allowedIn: Set<String>,
        val useInstead: String,
    )

    private val windowFile = "com/labteto/dshmobile/ui/components/DsFullScreen.kt"
    private val sheetFile = "com/labteto/dshmobile/ui/components/DsBottomSheet.kt"

    private val rules = listOf(
        Rule(
            what = "a Compose `Dialog(`",
            pattern = Regex("""(?<![A-Za-z0-9_])Dialog\s*\("""),
            allowedIn = setOf(windowFile),
            useInstead = "DsFullScreenDialog (a full page) or DsDialog (a centred plate)",
        ),
        Rule(
            what = "`DialogProperties(`",
            pattern = Regex("""(?<![A-Za-z0-9_])DialogProperties\s*\("""),
            allowedIn = setOf(windowFile),
            useInstead = "DsFullScreenDialog or DsDialog, which own the window properties",
        ),
        Rule(
            what = "`decorFitsSystemWindows`",
            pattern = Regex("""decorFitsSystemWindows""", RegexOption.IGNORE_CASE),
            allowedIn = setOf(windowFile),
            useInstead = "DsFullScreenDialog or DsDialog; the window setup is in DsWindow",
        ),
        Rule(
            what = "an import of Compose's window classes",
            pattern = Regex("""import\s+androidx\.compose\.ui\.window\.(Dialog|DialogProperties|DialogWindowProvider|Popup|PopupProperties|\*)\b"""),
            allowedIn = setOf(windowFile),
            useInstead = "DsFullScreenDialog, DsDialog or DsBottomSheet",
        ),
        Rule(
            what = "a `ModalBottomSheet(`",
            pattern = Regex("""(?<![A-Za-z0-9_])ModalBottomSheet\s*\("""),
            allowedIn = setOf(sheetFile),
            useInstead = "DsBottomSheet",
        ),
        Rule(
            what = "an `AlertDialog(`",
            pattern = Regex("""(?<![A-Za-z0-9_])(Basic)?AlertDialog\s*\("""),
            allowedIn = emptySet(),
            useInstead = "DsDialog",
        ),
        Rule(
            what = "a `Popup(`",
            pattern = Regex("""(?<![A-Za-z0-9_])Popup\s*\("""),
            allowedIn = emptySet(),
            useInstead = "DsMenu for a menu, or DsDialog",
        ),
    )

    @Test
    fun noWindowIsBuiltOutsideTheSharedComponents() {
        val offences = mutableListOf<String>()
        val files = sourceRoots().flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { root to it }.toList() }
        assertTrue("no sources found to scan", files.isNotEmpty())
        files.forEach { (root, file) ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val code = stripCommentsAndStrings(file.readText())
            for (rule in rules) {
                if (relative in rule.allowedIn) continue
                rule.pattern.findAll(code).forEach { match ->
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    offences += "$relative:$line uses ${rule.what}. Use ${rule.useInstead}."
                }
            }
        }
        assertTrue(
            "Full-screen windows and dialogs must be built by the shared components, never by hand.\n" +
                "A plain Compose Dialog is laid out inside the system bars on Android 15 and newer, which put " +
                "the Automations footer and the Terminal keys under the gesture bar. The components fix the " +
                "window and pad for the bars and the keyboard (ui/components/DsFullScreen.kt, DsBottomSheet.kt). " +
                "See \"Windows and system bars\" in CONTRIBUTING.md.\n\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    /** The allowlist must not go stale: if a shared component moves, the rule above would pass for the wrong reason. */
    @Test
    fun theSharedComponentsStillOwnTheirWindows() {
        val window = stripCommentsAndStrings(sourceFile(windowFile).readText())
        val sheet = stripCommentsAndStrings(sourceFile(sheetFile).readText())
        assertTrue("DsFullScreen.kt no longer creates the dialog window", Regex("""(?<![A-Za-z0-9_])Dialog\s*\(""").containsMatchIn(window))
        assertTrue("DsFullScreen.kt no longer sets decorFitsSystemWindows", window.contains("decorFitsSystemWindows"))
        assertTrue("DsBottomSheet.kt no longer creates the sheet window", Regex("""ModalBottomSheet\s*\(""").containsMatchIn(sheet))
        val overlays = stripCommentsAndStrings(sourceFile("com/labteto/dshmobile/ui/components/Overlays.kt").readText())
        assertTrue("DsDialog must open its window through DsWindow", overlays.contains("DsWindow("))
    }

    @Test
    fun theScannerSeesCodeAndIgnoresProseAndStrings() {
        val sample = """
            // Dialog( in a line comment is fine
            /* DialogProperties( in a block comment
               /* nested */ decorFitsSystemWindows */
            val text = "Dialog( inside a string"
            val raw = ${"\"\"\""}ModalBottomSheet( inside a raw string${"\"\"\""}
            val c = '"'
            Dialog(onDismissRequest = {}) { }
        """.trimIndent()
        val code = stripCommentsAndStrings(sample)
        val dialogs = Regex("""(?<![A-Za-z0-9_])Dialog\s*\(""").findAll(code).toList()
        assertEquals("only the real call is seen", 1, dialogs.size)
        assertEquals("line numbers survive", 7, code.substring(0, dialogs.single().range.first).count { it == '\n' } + 1)
        assertTrue(!code.contains("DialogProperties"))
        assertTrue(!code.contains("decorFitsSystemWindows"))
        assertTrue(!code.contains("ModalBottomSheet"))
    }

    @Test
    fun aHandBuiltDialogIsReported() {
        val offending = "androidx.compose.ui.window.Dialog(onDismissRequest = {}) {}\nDsDialog(title = null) {}\nAlertDialog(onDismissRequest = {})"
        val code = stripCommentsAndStrings(offending)
        val hits = rules.filter { it.pattern.containsMatchIn(code) }.map { it.what }
        assertTrue("a qualified Dialog( is caught", "a Compose `Dialog(`" in hits)
        assertTrue("AlertDialog( is caught", "an `AlertDialog(`" in hits)
        assertTrue("DsDialog( is not mistaken for Dialog(", Regex("""(?<![A-Za-z0-9_])Dialog\s*\(""").findAll(code).count() == 1)
    }

    /** The app's sources and every source set of the shared module that the Android app compiles or ships. */
    private fun sourceRoots(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return listOf("app/src/main/java", "sharedUi/src/commonMain/kotlin", "sharedUi/src/androidMain/kotlin", "sharedUi/src/iosMain/kotlin")
                    .map { File(dir, it) }
                    .filter { File(it, "com/labteto/dshmobile").isDirectory }
                    .also { assertTrue("app/src/main/java is missing from $dir", it.any { r -> r.path.replace("\\", "/").endsWith("app/src/main/java") }) }
            }
            dir = dir.parentFile
        }
        error("the repository root was not found from ${System.getProperty("user.dir")}; the window rules cannot run")
    }

    private fun sourceFile(relative: String): File =
        sourceRoots().map { File(it, relative) }.firstOrNull { it.isFile } ?: error("$relative is in none of the source trees")

    /**
     * The source with comments and the insides of string and character literals blanked, newlines
     * kept, so a match can be reported at its real line and prose that mentions `Dialog(` is not a
     * violation.
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var blockDepth = 0
        fun blank(c: Char) = if (c == '\n' || c == '\r') c else ' '
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; out.append("  "); i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; out.append("  "); i += 2 }
                    else -> { out.append(blank(c)); i++ }
                }
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') { out.append(blank(source[i])); i++ }
                }
                c == '/' && next == '*' -> { blockDepth = 1; out.append("  "); i += 2 }
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) { out.append(blank(source[i])); i++ }
                    if (i < source.length) { out.append("\"\"\""); i += 3 }
                }
                c == '"' -> {
                    out.append('"')
                    i++
                    while (i < source.length && source[i] != '"' && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < source.length) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < source.length && source[i] == '"') { out.append('"'); i++ }
                }
                c == '\'' -> {
                    // A character literal: 'x', '\n', 'A'. Anything else is left alone.
                    val close = if (next == '\\') source.indexOf('\'', i + 2) else if (source.getOrNull(i + 2) == '\'') i + 2 else -1
                    if (close in (i + 1)..(i + 8)) {
                        out.append('\'')
                        repeat(close - i - 1) { out.append(' ') }
                        out.append('\'')
                        i = close + 1
                    } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
