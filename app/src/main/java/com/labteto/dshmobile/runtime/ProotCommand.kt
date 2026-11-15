package com.labteto.dshmobile.runtime

import java.io.File

/**
 * The command line and environment that run the harness inside proot.
 *
 * This is the runtime README's "Running" section, plus binds of the app's own: the harness layer
 * (kept in its own directory), the persistent `/root` and `/workspaces`, and the generated
 * `resolv.conf` and `/proc/stat`. Pure, so the exact argv is a unit-tested value.
 */
data class ProotCommand(val argv: List<String>, val environment: Map<String, String>, val removeFromEnvironment: Set<String>) {

    /** Names only: a guest command's environment may hold a secret (see [buildGuest]). */
    override fun toString() = "ProotCommand(argv=$argv, environment=${environment.keys})"

    data class Inputs(
        val nativeLibraryDir: File,
        val layout: RuntimeLayout,
        val record: InstalledRecord,
        val port: Int,
        /**
         * Whether the harness may load its module pack (release `2026.11.14-2` and later), which
         * is what makes a warm start take seconds instead of half a minute. Off sets
         * [MODULE_PACK_ENV]`=0` in the guest, the runtime's switch for loading the plain
         * `node_modules` tree instead; it exists for a pack that misbehaves on some device.
         */
        val modulePack: Boolean = true,
        /**
         * The preset new sessions start on. Passed as the release's `runtime.permissions.defaultEnv`
         * only when the installed release has that field (see [commandPermissionEnvironment]).
         */
        val commandPermission: CommandPermission = CommandPermission.DEFAULT,
        /** More guest variables, after the fixed ones (debug builds' fault injection; empty otherwise). */
        val extraEnvironment: Map<String, String> = emptyMap(),
    )

    companion object {
        const val PROOT = "libproot.so"
        const val LOADER = "libproot-loader.so"
        const val GUEST_HOME = "/root"
        const val GUEST_WORKSPACES = "/workspaces"
        const val GUEST_WORKDIR = "/root/work"
        const val GENERATED_RESOLV = "resolv.conf"
        const val GENERATED_PROC_STAT = "proc-stat"

        /** The guest variable that turns the runtime's module pack off (`0`). */
        const val MODULE_PACK_ENV = "DSH_RUNTIME_MODULE_PACK"

        private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin"

        /**
         * The command, wrapped in `/system/bin/sh` so the first line of output is proot's pid.
         *
         * `echo $$; exec "$@"` prints the shell's pid and then becomes proot in place, so the pid
         * is proot's own. Android's `Process` hides its pid before API 33, and the stop sequence
         * needs it to find the guest's main process.
         */
        fun build(inputs: Inputs): ProotCommand {
            val layout = inputs.layout
            val record = inputs.record
            val rootfs = File(layout.root, record.base.dir)
            val harness = File(layout.root, record.harness.dir)
            val proot = File(inputs.nativeLibraryDir, PROOT)
            val argv = buildList {
                add("/system/bin/sh")
                add("-c")
                add("echo \$\$; exec \"\$@\"")
                add("sh")
                add(proot.path)
                add("--kill-on-exit")
                add("--link2symlink")
                add("-0")
                add("-r"); add(rootfs.path)
                add("-b"); add("/dev")
                add("-b"); add("/proc")
                add("-b"); add("/sys")
                add("-b"); add("/proc/self/fd:/dev/fd")
                add("-b"); add("${File(rootfs, "tmp").path}:/dev/shm")
                for (fake in record.fakeProc) {
                    val source = if (fake.target == "/proc/stat") File(layout.run, GENERATED_PROC_STAT) else File(rootfs, fake.source.removePrefix("/"))
                    add("-b"); add("${source.path}:${fake.target}")
                }
                add("-b"); add("${File(layout.run, GENERATED_RESOLV).path}:${record.resolvConf}")
                add("-b"); add("${layout.home.path}:$GUEST_HOME")
                add("-b"); add("${layout.workspaces.path}:$GUEST_WORKSPACES")
                add("-b"); add("${File(harness, "opt/dsh").path}:/opt/dsh")
                add("-b"); add("${File(harness, "usr/local/bin/dsh").path}:/usr/local/bin/dsh")
                add("-w"); add(GUEST_HOME)
                add("/usr/bin/env"); add("-i")
                add("HOME=$GUEST_HOME"); add("USER=root"); add("LOGNAME=root")
                add("PATH=$GUEST_PATH")
                add("LANG=C.UTF-8"); add("TERM=dumb"); add("TMPDIR=/tmp")
                if (!inputs.modulePack) add("$MODULE_PACK_ENV=0")
                commandPermissionEnvironment(record.permissions, inputs.commandPermission)?.let { (name, value) -> add("$name=$value") }
                inputs.extraEnvironment.forEach { (name, value) -> add("$name=$value") }
                add("/bin/bash"); add("-c")
                // umask: the app's is 077, which would leave everything the guest writes unreadable
                // to the guest's own tools. exec keeps the guest's main process one pid, which ends
                // up being node: that is the process the stop sequence sends SIGTERM to.
                add("umask 022; mkdir -p $GUEST_WORKDIR && cd $GUEST_WORKDIR && exec ${record.entrypoint} web --no-open --port ${inputs.port}")
            }
            val environment = mapOf(
                "PROOT_LOADER" to File(inputs.nativeLibraryDir, LOADER).path,
                "PROOT_TMP_DIR" to layout.prootTmp.path,
            )
            // PROOT_NO_SECCOMP makes proot slower and breaks git's rename path; never inherit it.
            return ProotCommand(argv, environment, setOf("PROOT_NO_SECCOMP", "LD_PRELOAD"))
        }

        /**
         * `resolv.conf` from the active network's DNS servers.
         *
         * glibc reads at most three `nameserver` lines. IPv4 first, since an emulator or a network
         * without IPv6 routing answers IPv6 resolvers with a timeout. Public resolvers are the
         * fallback when the device reports none (no network yet, or a VPN that hides them).
         */
        fun resolvConf(dnsServers: List<String>): String {
            val clean = dnsServers
                .map { it.trim().removePrefix("/").substringBefore('%') }
                .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' } }
                .distinct()
            val ordered = clean.filter { ':' !in it } + clean.filter { ':' in it }
            val servers = ordered.ifEmpty { FALLBACK_DNS }.take(3)
            return buildString {
                append("# Written by DSH Mobile at harness start from the device's DNS servers.\n")
                servers.forEach { append("nameserver ").append(it).append('\n') }
            }
        }

        val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

        /**
         * `/proc/stat` with one `cpuN` line per CPU, in the format of the file the base ships.
         *
         * Lines the shipped file has after the CPU lines (`intr`, `ctxt`, `btime`, ...) are kept as
         * they are. Without a shipped file to copy, a minimal one in the same format is written.
         */
        fun procStat(shipped: String?, cpuCount: Int): String {
            val count = cpuCount.coerceIn(1, 1024)
            val perCpu = "500 0 250 8000 12 0 6 0 0 0"
            val lines = shipped?.lines()?.filter { it.isNotBlank() }.orEmpty()
            val template = lines.firstOrNull { CPU_N.containsMatchIn(it) }?.substringAfter(' ')?.trim() ?: perCpu
            val rest = lines.filterNot { it.startsWith("cpu") }
                .ifEmpty { listOf("intr 0", "ctxt 0", "btime 0", "processes 1", "procs_running 1", "procs_blocked 0", "softirq 0 0 0 0 0 0 0 0 0 0 0") }
            val fields = template.split(Regex("\\s+")).map { it.toLongOrNull() ?: 0L }
            val total = fields.map { it * count }.joinToString(" ")
            return buildString {
                append("cpu  ").append(total).append('\n')
                for (i in 0 until count) append("cpu").append(i).append(' ').append(template).append('\n')
                rest.forEach { append(it).append('\n') }
            }
        }

        private val CPU_N = Regex("^cpu\\d+ ")

        /** The fixed script a guest command runs under: umask, then the program itself, from "$@". */
        const val GUEST_WRAPPER = "umask 022; exec \"\$@\""

        /**
         * The command line for a [GuestCommand]: the harness's root file system and binds, then
         * `/bin/sh -c '<GUEST_WRAPPER>' sh <argv...>`. The program's arguments are never part of a
         * script; they reach it as its own `argv`.
         *
         * The guest environment is the process environment rather than an `env -i` list, so a
         * secret (a clone's token) is never on a command line, where `ps` would show it: the caller
         * clears everything inherited from Android ([GuestCommandRunner] passes every inherited
         * name in `removeFromEnvironment`) and proot hands the rest to the guest unchanged.
         */
        fun buildGuest(nativeLibraryDir: File, layout: RuntimeLayout, record: InstalledRecord, command: GuestCommand): ProotCommand {
            val rootfs = File(layout.root, record.base.dir)
            val harness = File(layout.root, record.harness.dir)
            val argv = buildList {
                add("/system/bin/sh")
                add("-c")
                add("echo \$\$; exec \"\$@\"")
                add("sh")
                add(File(nativeLibraryDir, PROOT).path)
                add("--kill-on-exit")
                add("--link2symlink")
                add("-0")
                add("-r"); add(rootfs.path)
                add("-b"); add("/dev")
                add("-b"); add("/proc")
                add("-b"); add("/sys")
                add("-b"); add("/proc/self/fd:/dev/fd")
                add("-b"); add("${File(rootfs, "tmp").path}:/dev/shm")
                for (fake in record.fakeProc) {
                    val source = if (fake.target == "/proc/stat") File(layout.run, GENERATED_PROC_STAT) else File(rootfs, fake.source.removePrefix("/"))
                    add("-b"); add("${source.path}:${fake.target}")
                }
                add("-b"); add("${File(layout.run, GENERATED_RESOLV).path}:${record.resolvConf}")
                add("-b"); add("${layout.home.path}:$GUEST_HOME")
                add("-b"); add("${layout.workspaces.path}:$GUEST_WORKSPACES")
                add("-b"); add("${File(harness, "opt/dsh").path}:/opt/dsh")
                add("-w"); add(command.workingDirectory)
                add("/bin/sh"); add("-c"); add(GUEST_WRAPPER); add("sh")
                addAll(command.argv)
            }
            val environment = linkedMapOf(
                "PROOT_LOADER" to File(nativeLibraryDir, LOADER).path,
                "PROOT_TMP_DIR" to layout.prootTmp.path,
                "HOME" to GUEST_HOME,
                "USER" to "root",
                "LOGNAME" to "root",
                "PATH" to GUEST_PATH,
                "LANG" to "C.UTF-8",
                "TERM" to "dumb",
                "TMPDIR" to "/tmp",
            )
            environment.putAll(command.environment)
            environment.putAll(command.secretEnvironment)
            return ProotCommand(argv, environment, setOf("PROOT_NO_SECCOMP", "LD_PRELOAD"))
        }
    }
}
