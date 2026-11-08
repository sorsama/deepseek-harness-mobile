package com.labteto.dshmobile.core.wire

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android app must never load a Ktor class.
 *
 * `app/build.gradle.kts` excludes `io.ktor` from the app's dependency on `:core` (about 2 MB of jars
 * the app would carry for nothing) and release minification is off. So a `:core` class that reaches
 * a Ktor type — by an import, a signature, or a call into a class that has one — is fine to compile,
 * fine in every unit test (the app's tests add Ktor back), and a `NoClassDefFoundError` in the
 * shipped APK the first time it runs. This is the check that fails before that can happen.
 *
 * The rule is an island. The only `:core` classes allowed to mention Ktor are [island]: the Ktor
 * transport, mux socket and plain-HTTP carrier, and the URL helpers that exist for them. Nothing
 * outside the island may reference an island class either, so the app, which only ever reaches the
 * classes outside it, cannot reach Ktor through anything it calls. It is read from the compiled
 * classes, not from imports: a type used only in a signature or an inlined body counts.
 *
 * If a new class needs Ktor, it joins the island (and a line in `docs/IOS.md`); if a class outside
 * the island needs one that is in it, the dependency is pointing the wrong way.
 */
class NoKtorOnTheAppPathTest {

    /** Simple-name prefixes of the classes that may use Ktor, nested classes and file classes included. */
    private val island = listOf(
        "KtorRpcTransport", // also holds dshCarrier(), DshBodyLengthCheck and carrier()
        "ChannelSource", // KtorRpcTransport.kt's private download source
        "KtorMuxSocket",
        "KtorPlainHttp",
        "HarnessEndpoint",
        "PortableUrls", // reaches HarnessEndpoint; only the iOS actuals call it
    )

    private val packagePath = "com/labteto/dshmobile/core/"

    private fun isIsland(internalName: String): Boolean {
        val simple = internalName.removePrefix(packagePath).substringAfterLast('/')
        return internalName.startsWith(packagePath + "wire/") && island.any { simple.startsWith(it) }
    }

    private class Compiled(val internalName: String, val strings: List<String>)

    @Test
    fun `only the island mentions Ktor and nothing outside it reaches the island`() {
        val classes = compiledCoreClasses()
        assertTrue("found only ${classes.size} :core classes; the scan is not looking at the build", classes.size > 150)
        for (name in island) {
            assertTrue("no class of the island's $name was found; the scan is not looking at the right build", classes.any { isIsland(it.internalName) && it.internalName.contains("/$name") })
        }

        val mentionKtor = classes.filter { c -> c.strings.any { "io/ktor/" in it } }.map { it.internalName }
        val leaks = mentionKtor.filterNot(::isIsland)
        assertEquals(
            "these :core classes mention Ktor but are not in the island, so the APK, which has no Ktor, " +
                "would fail with NoClassDefFoundError when the app loads them:\n" + leaks.joinToString("\n"),
            emptyList<String>(),
            leaks,
        )

        val reachIsland = classes.filterNot { isIsland(it.internalName) }.mapNotNull { c ->
            val hit = c.strings.firstOrNull { s -> island.any { "${packagePath}wire/$it" in s } }
            hit?.let { "${c.internalName} -> $it" }
        }
        assertEquals(
            "these :core classes outside the island reference one inside it, which would drag Ktor onto the app's path:\n" +
                reachIsland.joinToString("\n"),
            emptyList<String>(),
            reachIsland,
        )

        // The island does use Ktor, so a scan that found none would be reading the wrong classes.
        assertTrue("no island class mentions Ktor", mentionKtor.isNotEmpty())
    }

    @Test
    fun `no source file outside the island imports Ktor, and the app's sources never do`() {
        val allowed = setOf("KtorRpcTransport.kt", "KtorMuxSocket.kt", "KtorPlainHttp.kt", "HarnessEndpoint.kt")
        val core = sourceRoot("src")
        val offenders = listOf("commonMain", "jvmMain").flatMap { set ->
            File(core, set).walkTopDown().filter { it.extension == "kt" && it.name !in allowed }
                .filter { file -> file.useLines { lines -> lines.any { it.trimStart().startsWith("import io.ktor.") } } }
                .map { "$set/${it.name}" }
                .toList()
        }
        assertEquals("these :core sources import Ktor but are not in the island:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)

        val app = sourceRoot("../app/src/main")
        val appOffenders = app.walkTopDown().filter { it.extension == "kt" }
            .filter { file -> file.useLines { lines -> lines.any { "io.ktor" in it } } }
            .map { it.name }
            .toList()
        assertEquals("the app's own sources mention Ktor, which its APK does not carry:\n" + appOffenders.joinToString("\n"), emptyList<String>(), appOffenders)
    }

    // ---------------------------------------------------------------- reading the build

    private fun sourceRoot(relative: String): File {
        // Gradle runs a module's tests from the module directory; an IDE may run them from the root.
        return listOf(File(relative), File("core/$relative")).firstOrNull { it.isDirectory }
            ?: error("cannot find $relative from ${File(".").absoluteFile}")
    }

    /** Every class of this module's main output (not its tests, not its libraries). */
    private fun compiledCoreClasses(): List<Compiled> {
        val location = File(RpcTransport::class.java.protectionDomain.codeSource.location.toURI())
        val classes = ArrayList<Compiled>()
        if (location.isDirectory) {
            location.walkTopDown().filter { it.extension == "class" }.forEach { classes += parse(it.readBytes()) }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    classes += parse(jar.getInputStream(entry).readBytes())
                }
            }
        }
        return classes.filter { it.internalName.startsWith(packagePath) }
    }

    /** The class's name and every string in its constant pool, which is where every type it names lives. */
    private fun parse(bytes: ByteArray): Compiled {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNameIndex = IntArray(count)
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    index++
                }
                7 -> classNameIndex[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                15 -> input.skipBytes(3)
                else -> error("unknown constant pool tag $tag")
            }
            index++
        }
        input.readUnsignedShort() // access flags
        val thisClass = input.readUnsignedShort()
        return Compiled(utf8[classNameIndex[thisClass]]!!, utf8.filterNotNull())
    }
}
