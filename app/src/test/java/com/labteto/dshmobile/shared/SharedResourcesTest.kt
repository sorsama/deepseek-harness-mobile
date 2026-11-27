package com.labteto.dshmobile.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the two copies of everything the shared UI keeps beside the Android app's own resources.
 *
 * `:sharedUi` reads its strings, fonts and mascot as Compose Multiplatform resources, and the
 * Android app still reads the same ones as Android resources wherever a screen has not moved yet
 * (the strings of the unmoved screens, the splash screen's mascot). Two copies of a string are a
 * way for the 11 languages to drift apart without anyone noticing, so every pair is compared here,
 * in every locale, by the build that already runs for the Android app. The recipe for moving a
 * screen (docs/IOS.md, "The shared UI") relies on this test failing when a copy is missed.
 */
class SharedResourcesTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private val androidRes = File(repo, "app/src/main/res")
    private val sharedRes = File(repo, "sharedUi/src/commonMain/composeResources")
    private val sharedSrc = File(repo, "sharedUi/src")

    private val locales = listOf("values", "values-ar", "values-bn", "values-es", "values-fr", "values-hi", "values-pt", "values-ru", "values-th", "values-ur", "values-zh-rCN")

    private val stringPattern = Regex("""<string name="([^"]+)"[^>]*>([\s\S]*?)</string>""")

    /** Name to text of one Android values directory, across all of its `strings*.xml` files. */
    private fun androidStrings(locale: String): Map<String, String> {
        val files = File(androidRes, locale).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        return files.flatMap { f -> stringPattern.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }.toMap()
    }

    private fun sharedStrings(locale: String): Map<String, String> {
        val file = File(sharedRes, "$locale/strings.xml")
        assertTrue("${file.path} is missing", file.exists())
        return stringPattern.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The only Android escape that is not also an XML one; see tools/shared-strings/sync.mjs. */
    private fun unescape(text: String) = text.replace("\\'", "'")

    private fun keysTheSharedCodeReads(): Set<String> =
        sharedSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""\bRes\.string\.([A-Za-z0-9_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()

    @Test
    fun `every string the shared code reads exists in all eleven languages`() {
        val keys = keysTheSharedCodeReads()
        assertTrue("no Res.string use found; the scan is looking in the wrong place", keys.isNotEmpty())
        for (locale in locales) {
            val have = sharedStrings(locale).keys
            assertEquals("$locale is missing strings the shared code reads", emptySet<String>(), keys - have)
            assertEquals("$locale has strings nothing reads (run tools/shared-strings/sync.mjs)", emptySet<String>(), have - keys)
        }
    }

    @Test
    fun `a string kept in both places is identical in every language`() {
        for (locale in locales) {
            val android = androidStrings(locale)
            for ((key, text) in sharedStrings(locale)) {
                val theirs = android[key] ?: continue
                assertEquals("$locale/$key differs between the Android resources and the shared ones", unescape(theirs), text)
            }
        }
    }

    @Test
    fun `placeholders are the two forms Compose Multiplatform fills in`() {
        val formats = Regex("""%[^\s]{0,6}""")
        val fine = Regex("""%\d\$[sd]""")
        for (locale in locales) {
            for ((key, text) in sharedStrings(locale)) {
                for (spec in formats.findAll(text).map { it.value }) {
                    assertTrue("$locale/$key has a format Compose Multiplatform leaves as it is: $spec", fine.containsMatchIn(spec))
                }
                assertTrue("$locale/$key still has an Android backslash escape", !text.contains("\\'"))
            }
        }
    }

    @Test
    fun `no string differs in its placeholders between languages`() {
        val placeholders = Regex("""%\d\$[sd]""")
        val base = sharedStrings("values")
        for (locale in locales.drop(1)) {
            for ((key, text) in sharedStrings(locale)) {
                assertEquals(
                    "$locale/$key has other placeholders than the English text",
                    placeholders.findAll(base.getValue(key)).map { it.value }.toSortedSet(),
                    placeholders.findAll(text).map { it.value }.toSortedSet(),
                )
            }
        }
    }

    // ---- plurals -----------------------------------------------------------------------------

    private val pluralsPattern = Regex("""<plurals name="([^"]+)"[^>]*>([\s\S]*?)</plurals>""")
    private val itemPattern = Regex("""<item quantity="([a-z]+)">([\s\S]*?)</item>""")

    private fun plurals(text: String): Map<String, List<Pair<String, String>>> =
        pluralsPattern.findAll(text).associate { m -> m.groupValues[1] to itemPattern.findAll(m.groupValues[2]).map { it.groupValues[1] to it.groupValues[2] }.toList() }

    private fun androidPlurals(locale: String): Map<String, List<Pair<String, String>>> =
        File(androidRes, locale).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
            .flatMap { plurals(it.readText()).toList() }.toMap()

    private fun sharedPlurals(locale: String): Map<String, List<Pair<String, String>>> = plurals(File(sharedRes, "$locale/strings.xml").readText())

    /** Android fills a plural's bare `%d`; Compose Multiplatform fills only `%1$d`, so sync.mjs writes that. */
    private fun pluralText(text: String) = unescape(text).replace(Regex("%([sd])"), "%1\\$$1")

    private fun pluralKeysTheSharedCodeReads(): Set<String> =
        sharedSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""\bRes\.plurals\.([A-Za-z0-9_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()

    @Test
    fun `every plural the shared code reads exists in all eleven languages`() {
        val keys = pluralKeysTheSharedCodeReads()
        for (locale in locales) {
            val have = sharedPlurals(locale).keys
            assertEquals("$locale is missing plurals the shared code reads", emptySet<String>(), keys - have)
            assertEquals("$locale has plurals nothing reads (run tools/shared-strings/sync.mjs)", emptySet<String>(), have - keys)
        }
    }

    @Test
    fun `a plural kept in both places says the same in every language`() {
        for (locale in locales) {
            val android = androidPlurals(locale)
            for ((key, items) in sharedPlurals(locale)) {
                val theirs = android[key] ?: continue
                assertEquals("$locale/$key differs between the Android resources and the shared ones", theirs.map { (q, t) -> q to pluralText(t) }, items)
            }
        }
    }

    @Test
    fun `a plural's placeholders are the form Compose Multiplatform fills in`() {
        val formats = Regex("""%[^\s]{0,6}""")
        val fine = Regex("""%\d\$[sd]""")
        for (locale in locales) {
            for ((key, items) in sharedPlurals(locale)) {
                assertTrue("$locale/$key has no items", items.isNotEmpty())
                assertTrue("$locale/$key has no 'other' item", items.any { it.first == "other" })
                for ((quantity, text) in items) {
                    for (spec in formats.findAll(text).map { it.value }) {
                        assertTrue("$locale/$key/$quantity has a format Compose Multiplatform leaves as it is: $spec", fine.containsMatchIn(spec))
                    }
                }
            }
        }
    }

    @Test
    fun `the fonts are the same files for Android and for iOS`() {
        val android = File(repo, "sharedUi/src/androidMain/res/font").listFiles().orEmpty().filter { it.extension == "ttf" }
        assertTrue("no fonts found", android.isNotEmpty())
        for (font in android) {
            val ios = File(repo, "sharedUi/src/commonMain/composeResources/font/${font.name}")
            assertTrue("${ios.path} is missing", ios.exists())
            assertArrayEquals("${font.name} differs between the Android and the Compose copy", font.readBytes(), ios.readBytes())
        }
        val iosOnly = File(repo, "sharedUi/src/commonMain/composeResources/font").listFiles().orEmpty().map { it.name }.toSet() - android.map { it.name }.toSet()
        assertEquals("a font only iOS has", emptySet<String>(), iosOnly)
    }

    @Test
    fun `the mascot is the same picture for the splash screen and for the shared UI`() {
        val splash = File(androidRes, "drawable-nodpi/mascot_mark.png")
        val shared = File(sharedRes, "drawable/mascot_mark.png")
        assertTrue(splash.exists() && shared.exists())
        assertArrayEquals(splash.readBytes(), shared.readBytes())
    }
}
