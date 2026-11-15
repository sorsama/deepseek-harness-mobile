package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandTest {
    private val layout = RuntimeLayout(File("/data/user/0/app/no_backup/runtime"), File("/data/user/0/app/cache/runtime"))
    private val record = InstalledRecord(
        releaseId = "r",
        sequence = 1,
        abi = "x86_64",
        harnessVersion = "0.2.2",
        nodeVersion = "24.22.3",
        base = LayerRecord("b1", "base-b1", "0".repeat(64)),
        harness = LayerRecord("dsh-0.2.2", "harness-dsh-0.2.2", "0".repeat(64)),
        entrypoint = "/usr/local/bin/dsh",
        readinessPrefix = "dsh web: http://127.0.0.1:",
        fakeProc = listOf(
            RuntimeManifest.FakeProc("/opt/dsh-runtime/proc/stat", "/proc/stat"),
            RuntimeManifest.FakeProc("/opt/dsh-runtime/proc/loadavg", "/proc/loadavg"),
        ),
        resolvConf = "/etc/resolv.conf",
    )
    private val command = ProotCommand.build(ProotCommand.Inputs(File("/data/app/x/lib/x86_64"), layout, record, 3080))
    private val argv = command.argv.map { it.replace('\\', '/') }

    private fun bindsTo(guest: String): String? {
        val i = argv.indices.firstOrNull { argv[it - 0] == "-b" && it + 1 < argv.size && argv[it + 1].endsWith(":$guest") }
        return i?.let { argv[it + 1].substringBeforeLast(":$guest") }
    }

    @Test
    fun `the wrapper prints proot's pid and then becomes proot`() {
        assertEquals(listOf("/system/bin/sh", "-c", "echo \$\$; exec \"\$@\"", "sh", "/data/app/x/lib/x86_64/libproot.so"), argv.take(5))
    }

    @Test
    fun `the README's proot flags and binds are all there`() {
        val proot = argv.drop(5)
        assertEquals(listOf("--kill-on-exit", "--link2symlink", "-0", "-r", "/data/user/0/app/no_backup/runtime/base-b1"), proot.take(5))
        for (bind in listOf("/dev", "/proc", "/sys", "/proc/self/fd:/dev/fd", "/data/user/0/app/no_backup/runtime/base-b1/tmp:/dev/shm")) {
            assertTrue("missing -b $bind", argv.windowed(2).any { it == listOf("-b", bind) })
        }
        assertTrue(argv.windowed(2).any { it == listOf("-w", "/root") })
    }

    @Test
    fun `user data, the harness layer and generated files are bound in`() {
        assertEquals("/data/user/0/app/no_backup/runtime/home", bindsTo("/root"))
        assertEquals("/data/user/0/app/no_backup/runtime/workspaces", bindsTo("/workspaces"))
        assertEquals("/data/user/0/app/no_backup/runtime/harness-dsh-0.2.2/opt/dsh", bindsTo("/opt/dsh"))
        assertEquals("/data/user/0/app/no_backup/runtime/harness-dsh-0.2.2/usr/local/bin/dsh", bindsTo("/usr/local/bin/dsh"))
        assertEquals("/data/user/0/app/no_backup/runtime/run/resolv.conf", bindsTo("/etc/resolv.conf"))
        // /proc/stat comes from the generated file; the other fake files from the base as shipped.
        assertEquals("/data/user/0/app/no_backup/runtime/run/proc-stat", bindsTo("/proc/stat"))
        assertEquals("/data/user/0/app/no_backup/runtime/base-b1/opt/dsh-runtime/proc/loadavg", bindsTo("/proc/loadavg"))
    }

    @Test
    fun `the guest gets a clean environment, umask 022 and the requested port`() {
        val env = argv.indexOf("/usr/bin/env")
        assertEquals("-i", argv[env + 1])
        assertTrue(argv.contains("HOME=/root"))
        assertTrue(argv.contains("LANG=C.UTF-8"))
        assertTrue(argv.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin"))
        assertEquals(
            "umask 022; mkdir -p /root/work && cd /root/work && exec /usr/local/bin/dsh web --no-open --port 3080",
            argv.last(),
        )
    }

    @Test
    fun `fast start off turns the module pack off in the guest, and only then`() {
        assertFalse(argv.any { it.startsWith("DSH_RUNTIME_MODULE_PACK") })
        val off = ProotCommand.build(ProotCommand.Inputs(File("/data/app/x/lib/x86_64"), layout, record, 3080, modulePack = false)).argv
        // Inside `env -i`, so the guest sees it, and before the shell that execs the harness.
        val env = off.indexOf("/usr/bin/env")
        val variable = off.indexOf("DSH_RUNTIME_MODULE_PACK=0")
        assertTrue(variable > env)
        assertTrue(variable < off.indexOf("/bin/bash"))
        assertFalse(command.environment.containsKey("DSH_RUNTIME_MODULE_PACK"))
    }

    @Test
    fun `proot's environment names the loader and the temp dir and drops PROOT_NO_SECCOMP`() {
        assertEquals("/data/app/x/lib/x86_64/libproot-loader.so", command.environment["PROOT_LOADER"]?.replace('\\', '/'))
        assertEquals("/data/user/0/app/cache/runtime/proot-tmp", command.environment["PROOT_TMP_DIR"]?.replace('\\', '/'))
        assertFalse(command.environment.containsKey("PROOT_NO_SECCOMP"))
        assertTrue("PROOT_NO_SECCOMP" in command.removeFromEnvironment)
    }

    @Test
    fun `resolv conf lists IPv4 first, at most three, with a fallback`() {
        assertEquals(
            "# Written by DSH Mobile at harness start from the device's DNS servers.\nnameserver 10.0.2.3\nnameserver 192.168.1.1\nnameserver fd00::1\n",
            ProotCommand.resolvConf(listOf("fd00::1", "10.0.2.3", "fe80::1%wlan0", "192.168.1.1", "10.0.2.3")),
        )
        assertTrue(ProotCommand.resolvConf(emptyList()).contains("nameserver 8.8.8.8\nnameserver 1.1.1.1\n"))
        assertTrue(ProotCommand.resolvConf(listOf("; rm -rf /")).contains("8.8.8.8"))
    }

    @Test
    fun `proc stat gets one cpu line per CPU in the shipped format`() {
        val stat = ProotCommand.procStat(RuntimeFixture.SHIPPED_STAT, 3)
        val lines = stat.lines()
        assertEquals("cpu  1500 0 750 24000 36 0 18 0 0 0", lines[0])
        assertEquals("cpu0 500 0 250 8000 12 0 6 0 0 0", lines[1])
        assertEquals("cpu2 500 0 250 8000 12 0 6 0 0 0", lines[3])
        assertEquals("intr 0", lines[4])
        assertTrue(stat.contains("btime 1762732800"))
        assertEquals(3, lines.count { Regex("^cpu\\d+ ").containsMatchIn(it) })
        // Without a shipped file there is still a well-formed one.
        assertEquals(1, ProotCommand.procStat(null, 1).lines().count { it.startsWith("cpu0 ") })
    }
}
