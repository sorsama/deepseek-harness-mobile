package com.labteto.dshmobile.core.wire

/**
 * The readiness line `dsh web` prints once its routes are mounted, taken apart.
 *
 * [raw] is the whole line as printed, which is what [HarnessSession.tokenFrom] wants; [baseUrl]
 * is the origin without path or query; [token] is the launch token when the line carried one
 * (harness 0.1.2 and later) and null for the token-less line older harnesses print.
 */
data class LaunchLine(val raw: String, val baseUrl: String, val token: String?) {
    /** `host:port` of [baseUrl]. */
    val authority: String get() = baseUrl.substringAfter("://").substringBefore('/')
}

/**
 * Finds the harness startup line in whatever a shell printed.
 *
 * The line is the one readiness signal the harness offers: the web bundle prints it only after
 * the loader has settled, so a supervisor that sees it can talk to the harness at once. When the
 * app starts the harness itself, the token on that line is the credential it needs, and reading
 * it here is what turns "copy the link out of Termux and paste it" into nothing at all.
 *
 * Tolerant on purpose. The script that captures the line may print markers before it, the
 * harness may colour it, and an all-interfaces bind appends ` (LAN: …)` after the URL; none of
 * that is the line's business.
 */
object HarnessLaunchLine {

    /** CSI escape sequences — colour and cursor control. */
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private val LINE = Regex("""dsh web:\s*(https?://[^\s)]+)""")

    /** The first startup line in [stdout], or null when there is none. */
    fun parse(stdout: String): LaunchLine? {
        val clean = ANSI.replace(stdout, "")
        for (line in clean.lineSequence()) {
            val match = LINE.find(line) ?: continue
            val url = match.groupValues[1]
            val scheme = url.substringBefore("://")
            val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
            if (authority.isBlank()) continue
            val token = if ("token=" in url) HarnessSession.tokenFrom(url) else null
            return LaunchLine(raw = line.trim(), baseUrl = "$scheme://$authority", token = token)
        }
        return null
    }
}
