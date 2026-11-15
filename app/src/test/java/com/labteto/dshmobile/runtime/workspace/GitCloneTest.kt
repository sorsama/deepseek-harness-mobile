package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.InstalledRecord
import com.labteto.dshmobile.runtime.LayerRecord
import com.labteto.dshmobile.runtime.ProotCommand
import com.labteto.dshmobile.runtime.RuntimeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** The clone's command line: arguments, never a shell string; the token only where nothing shows it. */
class GitCloneTest {

    private fun ok(url: String, token: Boolean = false) = GitClone.checkUrl(url, token) as GitClone.UrlCheck.Ok

    private fun bad(url: String, token: Boolean = false) = (GitClone.checkUrl(url, token) as? GitClone.UrlCheck.Bad)?.problem

    @Test
    fun `addresses a person can mean are taken and named the way git would`() {
        val gh = ok("https://github.com/octocat/Hello-World.git")
        assertEquals("Hello-World", gh.suggestedName)
        assertEquals("https://github.com/", gh.origin)
        assertEquals("spoon-knife", ok(" https://GitHub.com/octocat/spoon-knife/ ").suggestedName)
        assertEquals("https://github.com/", ok(" https://GitHub.com/octocat/spoon-knife/ ").origin)
        assertEquals("http://gitea.local:3000/", ok("http://gitea.local:3000/me/x").origin)
        assertEquals("project", ok("https://example.com/").suggestedName)
    }

    @Test
    fun `hostile and unsupported addresses are refused with a reason`() {
        assertEquals(GitClone.UrlProblem.EMPTY, bad("  "))
        assertEquals(GitClone.UrlProblem.NOT_A_URL, bad("--upload-pack=touch /tmp/pwned"))
        assertEquals(GitClone.UrlProblem.NOT_A_URL, bad("-c core.sshCommand=evil"))
        assertEquals(GitClone.UrlProblem.NOT_A_URL, bad("https://github.com/a b"))
        assertEquals(GitClone.UrlProblem.NOT_A_URL, bad("https://github.com/a\nb"))
        assertEquals(GitClone.UrlProblem.SSH_NOT_SUPPORTED, bad("git@github.com:octocat/x.git"))
        assertEquals(GitClone.UrlProblem.SSH_NOT_SUPPORTED, bad("ssh://git@github.com/octocat/x.git"))
        assertEquals(GitClone.UrlProblem.UNSUPPORTED_SCHEME, bad("file:///root/.dsh"))
        assertEquals(GitClone.UrlProblem.NOT_A_URL, bad("ext::sh -c touch% /tmp/pwned"))
        assertEquals(GitClone.UrlProblem.UNSUPPORTED_SCHEME, bad("ext::sh"))
        assertEquals(GitClone.UrlProblem.UNSUPPORTED_SCHEME, bad("git://github.com/x"))
        assertEquals(GitClone.UrlProblem.CREDENTIALS_IN_URL, bad("https://me:hunter2@github.com/me/private.git"))
        assertEquals(GitClone.UrlProblem.CREDENTIALS_IN_URL, bad("https://ghp_abc@github.com/me/private.git"))
        assertEquals(GitClone.UrlProblem.TOKEN_NEEDS_HTTPS, bad("http://example.com/x.git", token = true))
    }

    @Test
    fun `branch names are checked the way git checks them`() {
        for (good in listOf("", "main", "release/1.2", "v1.0.0", "feature-x_y")) assertTrue(good, GitClone.branchOk(good))
        for (hostile in listOf("-x", "--upload-pack=evil", "a b", "a..b", "a@{1}", "a~1", "a^", "a:b", "a?", "a*", "a[", "a\\b", "/a", "a/", "a.", "a.lock", "@", ".hidden", "a/.b", "\$(id)", "`id`", "a;rm -rf /", "a\u0000")) {
            assertFalse(hostile, GitClone.branchOk(hostile))
        }
    }

    @Test
    fun `the url and branch are single arguments after the options, never a script`() {
        val url = ok("https://github.com/o/r\$(id);touch&x")
        val cmd = GitClone.command(url, "main", "/workspaces/.dsh-incoming-1", token = null)
        val argv = cmd.argv
        assertEquals("/usr/bin/git", argv.first())
        val dashDash = argv.indexOf("--")
        assertTrue(dashDash > 0)
        assertEquals(listOf("--", url.url, "/workspaces/.dsh-incoming-1"), argv.subList(dashDash, argv.size))
        assertEquals("main", argv[argv.indexOf("--branch") + 1])
        assertTrue(argv.indexOf("--branch") < dashDash)
        // Only https and http, also for submodules and redirects.
        assertTrue(argv.containsAll(listOf("protocol.allow=never", "protocol.https.allow=always", "credential.helper=")))
        assertEquals("0", cmd.environment["GIT_TERMINAL_PROMPT"])
        assertTrue(cmd.secretEnvironment.isEmpty())
        // No branch: no --branch at all.
        assertFalse("--branch" in GitClone.command(url, "  ", "/workspaces/x", null).argv)
    }

    @Test
    fun `clones go only under the workspaces folder`() {
        val url = ok("https://github.com/o/r")
        for (target in listOf("/root/.dsh", "/workspacesX", "relative")) {
            val refused = runCatching { GitClone.command(url, "", target, null) }.isFailure
            assertTrue(target, refused)
        }
    }

    @Test
    fun `the token is only in the secret environment, scoped to the origin, and never printed`() {
        val token = "ghp_TestToken1234567890"
        val url = ok("https://github.com/me/private.git", token = true)
        val chars = token.toCharArray()
        val cmd = GitClone.command(url, "", "/workspaces/.dsh-incoming-2", chars)
        assertFalse(cmd.argv.any { token in it })
        assertFalse(cmd.environment.values.any { token in it })
        val basic = Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
        assertEquals("http.https://github.com/.extraHeader", cmd.secretEnvironment["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Basic $basic", cmd.secretEnvironment["GIT_CONFIG_VALUE_0"])
        assertEquals("1", cmd.secretEnvironment["GIT_CONFIG_COUNT"])
        assertFalse(cmd.toString().contains(token))
        assertFalse(cmd.toString().contains(basic))
        assertTrue(cmd.redact.containsAll(listOf(token, basic)))

        // On proot's command line: still nowhere. In its environment, where only this uid can read it.
        val proot = ProotCommand.buildGuest(File("/n"), RuntimeLayout(File("/rt"), File("/cache")), record(), cmd)
        assertFalse(proot.argv.any { token in it || basic in it })
        assertEquals("Authorization: Basic $basic", proot.environment["GIT_CONFIG_VALUE_0"])
        assertFalse(proot.toString().contains(basic))
    }

    @Test
    fun `git's progress lines become phases and a rising share`() {
        assertEquals(GitClone.Progress(GitClone.Phase.STARTING, null), GitClone.parseProgress("Cloning into '/workspaces/.dsh-incoming-1'..."))
        assertEquals(GitClone.Progress(GitClone.Phase.COUNTING, 50), GitClone.parseProgress("remote: Enumerating objects:  50% (5/10)"))
        assertEquals(GitClone.Progress(GitClone.Phase.RECEIVING, 45), GitClone.parseProgress("Receiving objects:  45% (450/1000), 1.20 MiB | 600.00 KiB/s"))
        assertEquals(GitClone.Progress(GitClone.Phase.RESOLVING, 100), GitClone.parseProgress("Resolving deltas: 100% (20/20), done."))
        assertEquals(GitClone.Progress(GitClone.Phase.CHECKING_OUT, 7), GitClone.parseProgress("Updating files:   7% (7/100)"))
        assertNull(GitClone.parseProgress("warning: redirecting to https://github.com/o/r.git/"))
        val shares = listOf(
            GitClone.Progress(GitClone.Phase.COUNTING, 100),
            GitClone.Progress(GitClone.Phase.COMPRESSING, 100),
            GitClone.Progress(GitClone.Phase.RECEIVING, 50),
            GitClone.Progress(GitClone.Phase.RECEIVING, 100),
            GitClone.Progress(GitClone.Phase.RESOLVING, 100),
            GitClone.Progress(GitClone.Phase.CHECKING_OUT, 100),
        ).map { GitClone.overallFraction(it)!! }
        assertEquals(shares.sorted(), shares)
        assertEquals(1f, shares.last())
    }

    @Test
    fun `failures are told apart from git's last lines`() {
        fun c(vararg lines: String) = GitClone.classify(lines.toList())
        assertEquals(GitClone.Failure.NOT_FOUND_OR_PRIVATE, c("fatal: could not read Username for 'https://github.com': terminal prompts disabled"))
        assertEquals(GitClone.Failure.NOT_FOUND_OR_PRIVATE, c("remote: Repository not found.", "fatal: repository 'https://github.com/a/b/' not found"))
        assertEquals(GitClone.Failure.AUTH_FAILED, c("remote: Invalid username or password.", "fatal: Authentication failed for 'https://github.com/a/b/'"))
        assertEquals(GitClone.Failure.NETWORK, c("fatal: unable to access 'https://nope.invalid/x/': Could not resolve host: nope.invalid"))
        assertEquals(GitClone.Failure.BRANCH_NOT_FOUND, c("warning: Could not find remote branch nope to clone.", "fatal: Remote branch nope not found in upstream origin"))
        assertEquals(GitClone.Failure.NO_SPACE, c("error: unable to write file x: No space left on device"))
        assertEquals(GitClone.Failure.NOT_A_REPOSITORY, c("fatal: https://example.com/info/refs not valid: is this a git repository?"))
        assertEquals(GitClone.Failure.OTHER, c("Receiving objects:  40% (403/1000)", "fatal: early EOF"))
    }

    private fun record() = InstalledRecord(
        releaseId = "r",
        sequence = 1,
        abi = "x86_64",
        harnessVersion = "1",
        nodeVersion = "24",
        base = LayerRecord("base-x", "base-x-123", "123"),
        harness = LayerRecord("harness-x", "harness-x-456", "456"),
        entrypoint = "dsh",
        readinessPrefix = "dsh web:",
        fakeProc = emptyList(),
        resolvConf = "/etc/resolv.conf",
    )
}
