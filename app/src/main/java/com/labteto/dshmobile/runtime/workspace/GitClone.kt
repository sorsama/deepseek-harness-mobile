package com.labteto.dshmobile.runtime.workspace

import com.labteto.dshmobile.runtime.GuestCommand
import java.net.URI
import java.util.Base64

/**
 * `git clone`, as the person asked for it: a URL, a branch if they gave one, a token if the
 * repository is private. Builds the guest command and reads git's output; [GuestCommandRunner]
 * runs it.
 *
 * Nothing here goes through a shell. The URL, the branch and the folder are separate arguments
 * after the options, behind `--`, so a value such as `--upload-pack=...` or `$(id)` is a URL git
 * refuses, never an option or a command. The checks below exist for clear messages, not as the
 * line of defence.
 *
 * **The token** is used for this one clone and is never stored or logged:
 * - It goes to git as an `Authorization` header in git's environment
 *   (`GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_0`/`GIT_CONFIG_VALUE_0`), never on a command line, where
 *   `ps` and a log could show it, and never in the URL, which git would write into
 *   `.git/config`. The header is scoped to the repository's own origin, so a redirect to another
 *   host does not get it.
 * - `credential.helper` is emptied for the clone, so no credential helper the person configured
 *   inside the runtime can save it.
 * - [GuestCommand.toString] leaves it out, and every output line is redacted before anyone sees it.
 * - A URL with a user name and password in it is refused, with a pointer to the token field.
 *
 * SSH (`git@host:...`, `ssh://`) is not offered: it needs a key on the phone, which is its own
 * feature.
 */
object GitClone {

    enum class UrlProblem { EMPTY, NOT_A_URL, SSH_NOT_SUPPORTED, UNSUPPORTED_SCHEME, CREDENTIALS_IN_URL, TOKEN_NEEDS_HTTPS }

    sealed interface UrlCheck {
        data class Ok(val url: String, val origin: String, val suggestedName: String) : UrlCheck
        data class Bad(val problem: UrlProblem) : UrlCheck
    }

    fun checkUrl(raw: String, withToken: Boolean = false): UrlCheck {
        val url = raw.trim()
        if (url.isEmpty()) return UrlCheck.Bad(UrlProblem.EMPTY)
        if (url.any { it.isWhitespace() || it.isISOControl() } || url.startsWith("-")) return UrlCheck.Bad(UrlProblem.NOT_A_URL)
        if (url.startsWith("ssh://") || url.startsWith("git+ssh://") || SCP_LIKE.matches(url)) return UrlCheck.Bad(UrlProblem.SSH_NOT_SUPPORTED)
        val uri = runCatching { URI(url) }.getOrNull() ?: return UrlCheck.Bad(UrlProblem.NOT_A_URL)
        val scheme = uri.scheme?.lowercase() ?: return UrlCheck.Bad(UrlProblem.NOT_A_URL)
        if (scheme != "https" && scheme != "http") return UrlCheck.Bad(UrlProblem.UNSUPPORTED_SCHEME)
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return UrlCheck.Bad(UrlProblem.NOT_A_URL)
        if (uri.rawUserInfo != null) return UrlCheck.Bad(UrlProblem.CREDENTIALS_IN_URL)
        if (withToken && scheme != "https") return UrlCheck.Bad(UrlProblem.TOKEN_NEEDS_HTTPS)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val origin = "$scheme://${host.lowercase()}$port/"
        return UrlCheck.Ok(url, origin, suggestedName(uri.path.orEmpty()))
    }

    /** The folder name git itself would pick: the last path part without `.git`. */
    fun suggestedName(path: String): String {
        val last = path.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        val clean = runCatching { WorkspaceTree.cleanName(last, topLevel = true) }.getOrNull()
        return clean ?: "project"
    }

    /**
     * Whether [branch] is a name git accepts for `--branch` (a branch or a tag), by the rules of
     * `git check-ref-format` that matter here. Empty means "the default branch" and is fine.
     */
    fun branchOk(branch: String): Boolean {
        val b = branch.trim()
        if (b.isEmpty()) return true
        if (b.length > 255 || b.startsWith("-") || b.startsWith("/") || b.endsWith("/") || b.endsWith(".") || b.endsWith(".lock")) return false
        if (b == "@" || ".." in b || "@{" in b || "//" in b) return false
        if (b.any { it.isISOControl() || it.isWhitespace() || it in "~^:?*[\\" }) return false
        // Stricter than git: shell punctuation is legal in a ref but never in a branch a person types.
        if (b.any { !(it.isLetterOrDigit() || it in "._-/+@#,=%") }) return false
        if (b.split('/').any { it.isEmpty() || it.startsWith(".") }) return false
        return true
    }

    /**
     * The guest command for a clone of [url] into [targetGuestPath] (a new folder under
     * `/workspaces`). [token], when given, is only ever placed in [GuestCommand.secretEnvironment].
     */
    fun command(url: UrlCheck.Ok, branch: String, targetGuestPath: String, token: CharArray?): GuestCommand {
        require(targetGuestPath.startsWith(WorkspaceTree.GUEST_ROOT + "/")) { "clones go under /workspaces" }
        val argv = buildList {
            add("/usr/bin/git")
            // Only the transports a person can mean here; no file://, no ext::, also for submodules.
            add("-c"); add("protocol.allow=never")
            add("-c"); add("protocol.https.allow=always")
            add("-c"); add("protocol.http.allow=always")
            add("-c"); add("credential.helper=")
            add("-c"); add("core.askPass=")
            add("clone")
            add("--progress")
            val b = branch.trim()
            if (b.isNotEmpty()) {
                add("--branch"); add(b)
            }
            add("--")
            add(url.url)
            add(targetGuestPath)
        }
        val env = mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to "/bin/false",
            "GIT_CONFIG_NOSYSTEM" to "1",
        )
        val secret = if (token != null && token.isNotEmpty()) {
            val basic = Base64.getEncoder().encodeToString(("x-access-token:" + String(token)).toByteArray())
            mapOf(
                "GIT_CONFIG_COUNT" to "1",
                "GIT_CONFIG_KEY_0" to "http.${url.origin}.extraHeader",
                "GIT_CONFIG_VALUE_0" to "Authorization: Basic $basic",
            )
        } else {
            emptyMap()
        }
        val redact = if (token != null && token.isNotEmpty()) listOf(String(token), secret.getValue("GIT_CONFIG_VALUE_0").substringAfter("Basic ")) else emptyList()
        return GuestCommand(argv = argv, environment = env, secretEnvironment = secret, workingDirectory = WorkspaceTree.GUEST_ROOT, redact = redact)
    }

    /** One step of a clone as git reports it on stderr (`Receiving objects:  45% (450/1000)`). */
    data class Progress(val phase: Phase, val percent: Int?)

    enum class Phase { STARTING, COUNTING, COMPRESSING, RECEIVING, RESOLVING, CHECKING_OUT }

    fun parseProgress(line: String): Progress? {
        val text = line.trim().removePrefix("remote:").trim()
        val phase = when {
            text.startsWith("Cloning into") -> return Progress(Phase.STARTING, null)
            text.startsWith("Enumerating objects") || text.startsWith("Counting objects") -> Phase.COUNTING
            text.startsWith("Compressing objects") -> Phase.COMPRESSING
            text.startsWith("Receiving objects") -> Phase.RECEIVING
            text.startsWith("Resolving deltas") -> Phase.RESOLVING
            text.startsWith("Updating files") || text.startsWith("Checking out files") -> Phase.CHECKING_OUT
            else -> return null
        }
        val percent = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        return Progress(phase, percent)
    }

    /** A rough share of the whole clone for [p]: receiving is most of the time. */
    fun overallFraction(p: Progress): Float? {
        val pct = (p.percent ?: return null) / 100f
        return when (p.phase) {
            Phase.STARTING -> 0f
            Phase.COUNTING -> 0.02f * pct
            Phase.COMPRESSING -> 0.02f + 0.03f * pct
            Phase.RECEIVING -> 0.05f + 0.75f * pct
            Phase.RESOLVING -> 0.80f + 0.12f * pct
            Phase.CHECKING_OUT -> 0.92f + 0.08f * pct
        }
    }

    enum class Failure { NOT_FOUND_OR_PRIVATE, AUTH_FAILED, NETWORK, BRANCH_NOT_FOUND, NO_SPACE, NOT_A_REPOSITORY, TIMED_OUT, OTHER }

    /** What went wrong, from git's last lines. */
    fun classify(lines: List<String>): Failure {
        val text = lines.joinToString("\n").lowercase()
        return when {
            "no space left" in text -> Failure.NO_SPACE
            "remote branch" in text && "not found" in text -> Failure.BRANCH_NOT_FOUND
            "authentication failed" in text || "invalid username or password" in text || "http basic: access denied" in text || "returned error: 403" in text -> Failure.AUTH_FAILED
            "could not read username" in text || "terminal prompts disabled" in text || "repository not found" in text || ("not found" in text && "repository" in text) -> Failure.NOT_FOUND_OR_PRIVATE
            "could not resolve host" in text || "failed to connect" in text || "connection timed out" in text || "network is unreachable" in text ||
                "connection refused" in text || "ssl" in text || "tls" in text || "couldn't connect" in text -> Failure.NETWORK
            "not a git repository" in text || "does not appear to be a git repository" in text || "is this a git repository" in text || "dumb http transport" in text ||
                "unexpected" in text && "content-type" in text -> Failure.NOT_A_REPOSITORY
            else -> Failure.OTHER
        }
    }

    private val SCP_LIKE = Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:.*")
    private val PERCENT = Regex("(\\d{1,3})%")
}
