package com.labteto.dshmobile.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared UI must not reach Ktor from any source set the Android app compiles.
 *
 * Same rule as `:core`'s `NoKtorOnTheAppPathTest`, and for the same reason: `app/build.gradle.kts`
 * excludes `io.ktor` from the APK (release minification is off, so a class that mentions it would
 * not be stripped), and a shared class that touched it would pass every unit test, where the test
 * classpath puts Ktor back, and crash the shipped app with `NoClassDefFoundError` the first time it
 * loaded. Only `iosMain` carries the iOS networking, and Ktor with it.
 */
class SharedUiHasNoKtorOnTheAndroidPathTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `commonMain and androidMain do not name Ktor`() {
        val offenders = listOf("commonMain", "androidMain").flatMap { set ->
            File(repo, "sharedUi/src/$set").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { Regex("""\bio\.ktor\b""").containsMatchIn(it.readText()) }
                .map { it.relativeTo(repo).path }
                .toList()
        }
        assertEquals("these shared sources name Ktor, which the Android app does not ship: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `the module only asks for Ktor in iosMain`() {
        val build = File(repo, "sharedUi/build.gradle.kts").readText()
        val commonBlock = build.substringAfter("commonMain.dependencies {").substringBefore("}")
        assertTrue("Ktor is a commonMain dependency of :sharedUi", !commonBlock.contains("ktor"))
    }
}
