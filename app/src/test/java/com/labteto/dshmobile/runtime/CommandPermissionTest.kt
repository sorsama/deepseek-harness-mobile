package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CommandPermissionTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/runtime/$name")).use { it.readBytes() }

    /** The first release with the presets, as published. */
    private val withPresets = RuntimeManifest.parse(resource("release-2026.11.15-1.manifest.json"))

    /** A release from before them. */
    private val withoutPresets = RuntimeManifest.parse(resource("release-2026.11.14-1.manifest.json"))

    private fun record(permissions: RuntimeManifest.Permissions?) = InstalledRecord(
        releaseId = "r",
        sequence = 1,
        abi = "x86_64",
        harnessVersion = "0.2.2",
        nodeVersion = "24.22.3",
        base = LayerRecord("b1", "base-b1", "0".repeat(64)),
        harness = LayerRecord("dsh-0.2.2", "harness-dsh-0.2.2", "0".repeat(64)),
        entrypoint = "/usr/local/bin/dsh",
        readinessPrefix = "dsh web: http://127.0.0.1:",
        fakeProc = emptyList(),
        resolvConf = "/etc/resolv.conf",
        permissions = permissions,
    )

    private fun guestWords(record: InstalledRecord, choice: CommandPermission): List<String> {
        val layout = RuntimeLayout(File("/data/r"), File("/data/c"))
        val argv = ProotCommand.build(ProotCommand.Inputs(File("/lib"), layout, record, 3080, commandPermission = choice)).argv
        // The guest's own environment: the words between `env -i` and the shell it starts.
        return argv.dropWhile { it != "-i" }.drop(1).takeWhile { it != "/bin/bash" }
    }

    // ---- The default ---------------------------------------------------------------------------

    @Test
    fun `someone who never chose gets Run it`() {
        // The one line to change if the owner flips the default (CommandPermission.DEFAULT).
        assertEquals(CommandPermission.RunIt, CommandPermission.DEFAULT)
        assertEquals(CommandPermission.DEFAULT, CommandPermission.fromStored(null))
        assertEquals(CommandPermission.DEFAULT, CommandPermission.fromStored("something-else"))
    }

    @Test
    fun `each choice is one of the runtime's two presets and env values`() {
        assertEquals("full" to "danger-full-access", CommandPermission.RunIt.envValue to CommandPermission.RunIt.presetId)
        assertEquals("ask" to "ask-commands", CommandPermission.AskFirst.envValue to CommandPermission.AskFirst.presetId)
        val published = withPresets.runtime.permissions!!.presets.associate { it.envValue to it.id }
        for (choice in CommandPermission.entries) assertEquals(choice.presetId, published[choice.envValue])
        assertEquals(CommandPermission.AskFirst, CommandPermission.ofPreset("ask-commands"))
        assertNull(CommandPermission.ofPreset("workspace-write"))
    }

    // ---- The manifest ----------------------------------------------------------------------------

    @Test
    fun `the published manifest's permissions block is read and an older one has none`() {
        val p = withPresets.runtime.permissions!!
        assertEquals("DSH_RUNTIME_DEFAULT_PERMISSION", p.defaultEnv)
        assertEquals(listOf("full", "ask"), p.defaultEnvValues)
        assertEquals("DSH_RUNTIME_MOBILE_PATCH", p.disableEnv)
        assertEquals(listOf("danger-full-access", "ask-commands"), p.presets.map { it.id })
        assertNull(withoutPresets.runtime.permissions)
    }

    @Test
    fun `a permissions block that would put odd words in the guest's environment is refused`() {
        val text = resource("release-2026.11.15-1.manifest.json").decodeToString()
        for (bad in listOf(
            text.replace("\"defaultEnv\": \"DSH_RUNTIME_DEFAULT_PERMISSION\"", "\"defaultEnv\": \"X=1 LD_PRELOAD\""),
            text.replace("\"defaultEnvValues\": [\n        \"full\",", "\"defaultEnvValues\": [\n        \"full; rm\","),
        )) {
            assertTrue("the replacement must change the text", bad != text)
            try {
                RuntimeManifest.parse(bad.toByteArray())
                fail("expected a refusal")
            } catch (e: ManifestException) {
                assertTrue(e.message!!.contains("permissions"))
            }
        }
    }

    @Test
    fun `the installed record keeps the block and says so to the screens`() {
        assertTrue(record(withPresets.runtime.permissions).toInstalledRuntime().commandPermissions)
        assertFalse(record(null).toInstalledRuntime().commandPermissions)
        // Kept through a trial and a rollback to it.
        val prior = record(withPresets.runtime.permissions).asPrior()
        assertEquals(withPresets.runtime.permissions, prior.restore("x86_64").permissions)
    }

    @Test
    fun `Ask me first is trusted only from permissions version 2`() {
        // 2026.11.15-1 published version 1: an approval's text was cut at 2,000 characters.
        val v1 = withPresets.runtime.permissions!!
        assertEquals(1, v1.version)
        val installedV1 = record(v1).toInstalledRuntime()
        assertEquals(1, installedV1.commandPermissionsVersion)
        assertTrue(installedV1.commandPermissions)
        assertFalse(installedV1.askFirstTrusted)
        // Version 2 (2026.11.15-2 and later) sends the whole text.
        val v2Text = resource("release-2026.11.15-1.manifest.json").decodeToString().replace("\"version\": 1,", "\"version\": 2,")
        val v2 = RuntimeManifest.parse(v2Text.toByteArray()).runtime.permissions!!
        assertEquals(2, v2.version)
        assertTrue(record(v2).toInstalledRuntime().askFirstTrusted)
        // No block at all: neither the choice nor the trust.
        val none = record(null).toInstalledRuntime()
        assertEquals(0, none.commandPermissionsVersion)
        assertFalse(none.askFirstTrusted)
    }

    // ---- The guest environment -------------------------------------------------------------------

    @Test
    fun `the choice reaches the guest only on a release that reads it`() {
        val supported = record(withPresets.runtime.permissions)
        assertTrue("DSH_RUNTIME_DEFAULT_PERMISSION=ask" in guestWords(supported, CommandPermission.AskFirst))
        assertTrue("DSH_RUNTIME_DEFAULT_PERMISSION=full" in guestWords(supported, CommandPermission.RunIt))
        val older = guestWords(record(null), CommandPermission.AskFirst)
        assertTrue(older.none { it.startsWith("DSH_RUNTIME_DEFAULT_PERMISSION") })
        // Never the escape hatch, and never the harness's own mode variable.
        for (words in listOf(guestWords(supported, CommandPermission.AskFirst), older)) {
            assertTrue(words.none { it.startsWith("DSH_RUNTIME_MOBILE_PATCH") || it.startsWith("DSH_PERMISSION_MODE") })
        }
    }

    @Test
    fun `a value the release does not list is not passed`() {
        val onlyFull = withPresets.runtime.permissions!!.copy(defaultEnvValues = listOf("full"))
        assertNull(commandPermissionEnvironment(onlyFull, CommandPermission.AskFirst))
        assertEquals("DSH_RUNTIME_DEFAULT_PERMISSION" to "full", commandPermissionEnvironment(onlyFull, CommandPermission.RunIt))
        assertNull(commandPermissionEnvironment(null, CommandPermission.RunIt))
        // The variable is the one the manifest names.
        val renamed = withPresets.runtime.permissions!!.copy(defaultEnv = "DSH_OTHER")
        assertEquals("DSH_OTHER" to "ask", commandPermissionEnvironment(renamed, CommandPermission.AskFirst))
    }

    // ---- The restart offer -------------------------------------------------------------------------

    private fun running(commandPermissions: Boolean) = RuntimeState.Running(
        port = 3080,
        harnessVersion = "0.2.2",
        sinceEpochMs = 0,
        signIn = StartSignIn.SIGNED_IN,
        installed = InstalledRuntime("r", 1, "0.2.2", "24", "b", "h", commandPermissions = commandPermissions),
    )

    @Test
    fun `a restart is offered only when the running harness has another choice`() {
        val run = running(commandPermissions = true)
        assertTrue(offersCommandPermissionRestart(run, startedWith = CommandPermission.RunIt, chosen = CommandPermission.AskFirst))
        assertFalse(offersCommandPermissionRestart(run, startedWith = CommandPermission.AskFirst, chosen = CommandPermission.AskFirst))
        // Started on a release without the presets, or not by this process: nothing to compare.
        assertFalse(offersCommandPermissionRestart(run, startedWith = null, chosen = CommandPermission.AskFirst))
        assertFalse(offersCommandPermissionRestart(running(commandPermissions = false), CommandPermission.RunIt, CommandPermission.AskFirst))
        // Stopped: the next start reads the new choice by itself.
        val stopped = RuntimeState.Stopped(run.installed)
        assertFalse(offersCommandPermissionRestart(stopped, CommandPermission.RunIt, CommandPermission.AskFirst))
    }

    // ---- The chat's sandbox notice ------------------------------------------------------------------

    @Test
    fun `the notice offers the person's choice on the built-in harness only`() {
        val phone = listOf("danger-full-access", "ask-commands")
        assertEquals("ask-commands", sandboxNoticeTarget(onBuiltIn = true, CommandPermission.AskFirst, phone))
        assertEquals("danger-full-access", sandboxNoticeTarget(onBuiltIn = true, CommandPermission.RunIt, phone))
        // A desktop harness: nothing offered, only explained, even where it has danger-full-access.
        val desktop = listOf("read-only", "workspace-write", "danger-full-access")
        assertNull(sandboxNoticeTarget(onBuiltIn = false, CommandPermission.RunIt, desktop))
        // The built-in harness without the presets (patch off, older runtime): the chosen preset is not there.
        assertNull(sandboxNoticeTarget(onBuiltIn = true, CommandPermission.AskFirst, desktop))
    }

    // ---- What is remembered ---------------------------------------------------------------------------

    private class MapStore : RuntimePreferences.KeyValue {
        val booleans = mutableMapOf<String, Boolean>()
        val strings = mutableMapOf<String, String>()
        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }
    }

    @Test
    fun `only a choice that was made is stored, and it outlives the app process`() {
        val store = MapStore()
        val first = RuntimePreferences(store)
        assertEquals(CommandPermission.DEFAULT, first.commandPermission.value)
        assertTrue("nothing stored until the person picks", store.strings.isEmpty())
        first.setCommandPermission(CommandPermission.AskFirst)
        assertEquals(CommandPermission.AskFirst, RuntimePreferences(store).commandPermission.value)
    }

    @Test
    fun `the explanation is shown once`() {
        val store = MapStore()
        val first = RuntimePreferences(store)
        assertFalse(first.commandPermissionExplained.value)
        first.setCommandPermissionExplained(true)
        assertTrue(first.commandPermissionExplained.value)
        assertTrue("seen in one process, not shown in the next", RuntimePreferences(store).commandPermissionExplained.value)
    }
}
