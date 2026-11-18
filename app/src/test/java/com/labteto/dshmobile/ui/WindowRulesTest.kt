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

    /**
     * On Android 10 and 11 back is a key press, and a focused text field (the Plugins search) took the
     * second one: the first back hid the keyboard, the second did nothing, the third closed the page.
     * The full-screen page looks at the key before its fields do, as the sheet does. The handler has to
     * sit on the page's content, above the fields; on the window's outer box it would never see a key
     * that a field below it holds.
     */
    @Test
    fun theFullScreenPageClosesOnBackEvenWithATextFieldFocused() {
        val window = stripCommentsAndStrings(sourceFile(windowFile).readText())
        assertTrue(
            "DsFullScreenDialog no longer closes its page on the Back key; a focused field takes it and back needs three presses",
            Regex("""windowInsetsPadding\(insets\)\s*\.dismissOnBackKey\s*\{\s*onDismissRequest\(\)\s*\}""").containsMatchIn(window),
        )
    }

    /**
     * Rotating the phone with a full-screen page open left the page blank. Compose measures a window's
     * content for its new size before the window's view takes that size, and the insets that place a
     * page against the activity's bars read the view's width and height while measuring: right after
     * the turn they were the old portrait ones, the bottom inset came out taller than the landscape
     * window and the page was measured at zero height. The insets must come from the rectangles both
     * windows had after their last layout, which a layout listener keeps current as state.
     */
    @Test
    fun theHostRelativeInsetsNeverReadAViewSizeWhileMeasuring() {
        val window = stripCommentsAndStrings(sourceFile(windowFile).readText())
        val relative = window.substringAfter("class HostRelative(", "").substringBefore("\nprivate fun TrackPlacement", "")
        assertTrue("HostRelative or TrackPlacement is gone from DsFullScreen.kt", relative.isNotEmpty())
        val getters = Regex("""override fun get(Left|Top|Right|Bottom)\(.*?\n    }""", RegexOption.DOT_MATCHES_ALL).findAll(relative).toList()
        assertEquals("HostRelative should still have its four inset getters", 4, getters.size)
        val viewReads = Regex("""\b(width|height|measuredWidth|measuredHeight|getLocationOnScreen|getLocationInWindow)\b""")
        getters.forEach { getter ->
            assertTrue(
                "HostRelative.get${getter.groupValues[1]} reads a view's size or place while measuring; read the placement kept by TrackPlacement",
                !viewReads.containsMatchIn(getter.value),
            )
        }
        assertTrue("the placement must be state, so a new one measures the page again", Regex("""var placement by mutableStateOf""").containsMatchIn(relative))
        assertTrue("TrackPlacement must listen for layouts of both windows", window.contains("OnGlobalLayoutListener"))
    }

    /**
     * The Subagents sheet could not be closed by a person who had scrolled deep into a long child
     * transcript, or who had the keyboard up: no scrim was left to tap and a swipe scrolled the list.
     * The fix is one close button in the shared sheet's title row, so every sheet has it. Callers must
     * not add a second one beside it (`trailing` is for other actions, like Stop or Done).
     */
    @Test
    fun theSharedSheetDrawsTheCloseButtonAndNoCallerAddsASecond() {
        val sheet = stripCommentsAndStrings(sourceFile(sheetFile).readText())
        assertTrue("DsBottomSheet no longer draws DsIcons.Close in its title row", sheet.contains("DsIcons.Close"))
        assertTrue("the close button lost its description", sheet.contains("Res.string.common_close"))
        assertTrue("the close button must hide the sheet before it dismisses", Regex("""onClick\s*=\s*\{\s*hide\s*\{\s*onDismiss\(\)\s*\}\s*\}""").containsMatchIn(sheet))
        val doubled = mutableListOf<String>()
        sourceRoots().flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { root to it }.toList() }
            .forEach { (root, file) ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative == sheetFile) return@forEach
                val code = stripCommentsAndStrings(file.readText())
                Regex("""DsBottomSheet\s*\(""").findAll(code).forEach { call ->
                    // The call's own argument list, up to the lambda that holds its content.
                    val header = code.substring(call.range.first, minOf(code.length, call.range.first + 900))
                    val trailing = Regex("""trailing\s*=\s*\{""").find(header) ?: return@forEach
                    val tail = header.substring(trailing.range.last)
                    // `trailing = { ... }` ends where the sheet's content lambda opens: `) {`.
                    val body = tail.substringBefore("\n    ) {").substringBefore("\n        ) {")
                    if (Regex("""Icons\.Filled\.Close|DsIcons\.Close|string\.common_(close|done|cancel)\b""").containsMatchIn(body)) {
                        val line = code.substring(0, call.range.first).count { it == '\n' } + 1
                        doubled += "$relative:$line adds its own close button to a DsBottomSheet"
                    }
                }
                // A sheet's own "Done" would sit beside the close button and do the same thing. A button that
                // commits something has its own word ("Apply", "Save"); `common_done` never does.
                if (code.contains("DsBottomSheet(") && Regex("""string\.common_done\b""").containsMatchIn(code)) {
                    doubled += "$relative has a Done button in a file that draws a DsBottomSheet; the sheet's close button is the way out"
                }
            }
        assertTrue("DsBottomSheet already has a close button; remove the extra one.\n" + doubled.joinToString("\n"), doubled.isEmpty())
    }

    /**
     * The sheet used to pad its content by the whole status bar height at any sheet height, which put a
     * wide empty band above the grabber of a short sheet. The bar is now cleared only where it overlaps.
     */
    @Test
    fun theSheetClearsTheStatusBarOnlyWhereItOverlaps() {
        val sheet = stripCommentsAndStrings(sourceFile(sheetFile).readText())
        assertTrue("the sheet no longer clears the status bar at all", sheet.contains("StatusBarClearance(state)"))
        assertTrue(
            "contentWindowInsets must not pad the top by the whole bar again",
            !Regex("""contentWindowInsets\s*=\s*\{\s*WindowInsets\.systemBars\.union""").containsMatchIn(sheet),
        )
        assertTrue(
            "the sheet must still keep its content above the keyboard and the navigation bar",
            sheet.contains("WindowInsets.ime") && sheet.contains("WindowInsetsSides.Bottom"),
        )
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
