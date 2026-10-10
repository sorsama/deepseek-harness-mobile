package com.labteto.dshmobile.termux

/**
 * What a script printed, in a form the app can act on.
 *
 * The scripts speak to the app through `DSHM:` markers on stdout rather than exit codes alone,
 * because the same exit code can mean two things and the tail of a log is worth more than either.
 * [Exited] and [ReadyTimeout] carry the last lines of the harness log for the same reason.
 */
sealed interface ScriptMarker {
    data object Started : ScriptMarker
    data object AlreadyRunning : ScriptMarker
    data object MissingDsh : ScriptMarker
    data object MissingNode : ScriptMarker
    data class Exited(val tail: String) : ScriptMarker
    data class ReadyTimeout(val tail: String) : ScriptMarker
    data object Stopped : ScriptMarker
    data object NotRunning : ScriptMarker
    data object DownloadFailed : ScriptMarker
    data object ChecksumFailed : ScriptMarker
    data object OpenFailed : ScriptMarker
    data object Installing : ScriptMarker
}

/**
 * The shell scripts "this phone" mode runs inside Termux.
 *
 * They ship inside the app and are handed to `bash -c` whole, so there is nothing to install in
 * Termux and nothing that can drift from the app that expects its output. Every value spliced in
 * is validated and single-quoted first: the scripts are shell source, and a port or a URL is the
 * only outside input they take.
 *
 * What they rely on, and why:
 *  - `termux-wake-lock`. Termux keeps a process tree alive only while its own service is running,
 *    and that service stops itself once it has no session, no task and no wake lock. A harness
 *    started from here has none of the first two, so the lock is what keeps it up after the script
 *    that started it has returned.
 *  - `setsid`, when present. The harness is put in its own process group so a stop can signal the
 *    whole tree — `dsh` spawns further node processes — in one call.
 *  - The `dsh web:` readiness line. Printed only once every route is mounted, and from 0.1.2
 *    carrying the launch token, so echoing it back is both "it is up" and "here is the credential".
 */
object TermuxScripts {

    private const val DIR = "\$HOME/.dsh"
    const val LOG_PATH = "~/.dsh/dsh-mobile-web.log"
    private const val LOG = "$DIR/dsh-mobile-web.log"
    private const val PID = "$DIR/dsh-mobile-web.pid"

    /** How long the start script itself waits for the readiness line before giving up. */
    const val READY_SECONDS = 60

    /** How long the stop script gives the harness to shut down before it is killed. */
    const val GRACE_SECONDS = 6

    private const val MARKER = "DSHM:"

    /** `PATH` as an interactive Termux shell would have it; a background command may not. */
    private const val PRELUDE = """set -u
PREFIX="${'$'}{PREFIX:-/data/data/com.termux/files/usr}"
export PATH="${'$'}PREFIX/bin:${'$'}PATH"
"""

    fun startCommand(port: Int): TermuxCommand = TermuxCommand(
        script = startHarness(port),
        label = "DSH Mobile: start harness",
        timeoutMs = (READY_SECONDS + 30) * 1_000L,
        kind = TermuxCommandKind.START,
        port = port,
    )

    fun stopCommand(port: Int): TermuxCommand = TermuxCommand(
        script = stopHarness(),
        label = "DSH Mobile: stop harness",
        timeoutMs = (GRACE_SECONDS + 15) * 1_000L,
        kind = TermuxCommandKind.STOP,
        port = port,
    )

    fun installCommand(apkUrl: String, apkName: String, sha256Url: String?): TermuxCommand = TermuxCommand(
        script = installApk(apkUrl, apkName, sha256Url),
        label = "DSH Mobile: install update",
        timeoutMs = 10 * 60 * 1_000L,
        kind = TermuxCommandKind.INSTALL,
    )

    /**
     * Start `dsh web` on [port] and print its readiness line.
     *
     * Exit 0 with `DSHM:started` or `DSHM:already-running` followed by the line; exit 3 when `node`
     * or `dsh` is missing; exit 2 (`DSHM:exited`) when the harness died first; exit 4
     * (`DSHM:timeout`) when it never printed the line, both followed by the log's last lines.
     */
    fun startHarness(port: Int): String {
        require(port in 1..65535) { "port out of range: $port" }
        return PRELUDE + """
PORT=$port
mkdir -p "$DIR"
if [ -f "$PID" ] && kill -0 "${'$'}(cat "$PID" 2>/dev/null)" 2>/dev/null; then
  echo "${MARKER}already-running"
  grep -m1 'dsh web: http' "$LOG" 2>/dev/null
  exit 0
fi
command -v node >/dev/null 2>&1 || { echo "${MARKER}missing-node"; exit 3; }
command -v dsh >/dev/null 2>&1 || { echo "${MARKER}missing-dsh"; exit 3; }
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
: > "$LOG"
if command -v setsid >/dev/null 2>&1; then
  setsid nohup dsh web --no-open --port "${'$'}PORT" >"$LOG" 2>&1 </dev/null &
else
  nohup dsh web --no-open --port "${'$'}PORT" >"$LOG" 2>&1 </dev/null &
fi
echo ${'$'}! > "$PID"
i=0
while [ "${'$'}i" -lt ${READY_SECONDS * 2} ]; do
  line=${'$'}(grep -m1 'dsh web: http' "$LOG" 2>/dev/null)
  if [ -n "${'$'}line" ]; then
    echo "${MARKER}started"
    echo "${'$'}line"
    exit 0
  fi
  if ! kill -0 "${'$'}(cat "$PID" 2>/dev/null)" 2>/dev/null; then
    echo "${MARKER}exited"
    tail -n 20 "$LOG" 2>/dev/null
    rm -f "$PID"
    exit 2
  fi
  sleep 0.5
  i=${'$'}((i + 1))
done
echo "${MARKER}timeout"
tail -n 20 "$LOG" 2>/dev/null
exit 4
"""
    }

    /**
     * Stop the harness this app started: SIGTERM the process group, wait [GRACE_SECONDS], SIGKILL.
     *
     * Only ever the process recorded in the pid file. A harness the user started by hand in a
     * Termux session is theirs to stop; guessing at it with `pkill` would take down whatever else
     * matched.
     */
    fun stopHarness(): String = PRELUDE + """
if [ ! -f "$PID" ]; then
  echo "${MARKER}not-running"
  command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock
  exit 0
fi
pid=${'$'}(cat "$PID" 2>/dev/null)
if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
  kill -TERM -- -"${'$'}pid" 2>/dev/null || kill -TERM "${'$'}pid" 2>/dev/null
  i=0
  while [ "${'$'}i" -lt ${GRACE_SECONDS * 2} ] && kill -0 "${'$'}pid" 2>/dev/null; do
    sleep 0.5
    i=${'$'}((i + 1))
  done
  if kill -0 "${'$'}pid" 2>/dev/null; then
    kill -KILL -- -"${'$'}pid" 2>/dev/null || kill -KILL "${'$'}pid" 2>/dev/null
  fi
fi
rm -f "$PID"
command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock
echo "${MARKER}stopped"
"""

    /**
     * Download a release APK into Termux and hand it to the package installer.
     *
     * The file keeps its release name so `sha256sum -c` can find it in `SHA256SUMS.txt` when a
     * checksum file is published; without one the download is opened unverified, which is exactly
     * what a browser download would be. `termux-open` is Termux's own bridge to the installer.
     */
    fun installApk(apkUrl: String, apkName: String, sha256Url: String?): String {
        val url = quoted(validUrl(apkUrl))
        val name = quoted(validFileName(apkName))
        val verify = if (sha256Url != null) {
            val sums = quoted(validUrl(sha256Url))
            """
curl -fL --retry 3 -o "${'$'}DIR/SHA256SUMS.txt" $sums || { echo "${MARKER}download-failed"; exit 2; }
( cd "${'$'}DIR" && sha256sum -c --ignore-missing SHA256SUMS.txt ) || { echo "${MARKER}checksum-failed"; rm -f "${'$'}APK"; exit 3; }
"""
        } else {
            ""
        }
        return PRELUDE + """
DIR="${'$'}HOME/downloads"
mkdir -p "${'$'}DIR"
APK="${'$'}DIR/"$name
curl -fL --retry 3 -o "${'$'}APK" $url || { echo "${MARKER}download-failed"; exit 2; }
""" + verify + """
termux-open --content-type application/vnd.android.package-archive "${'$'}APK" || { echo "${MARKER}open-failed"; exit 4; }
echo "${MARKER}installing"
"""
    }

    /** The same download-and-open, as one line to paste into any shell that reaches the phone. */
    fun installOneLiner(apkUrl: String, apkName: String): String {
        val url = quoted(validUrl(apkUrl))
        val name = validFileName(apkName)
        return "mkdir -p ~/downloads && curl -fL -o ~/downloads/$name $url && termux-open ~/downloads/$name"
    }

    /** The first marker in [stdout], with the log tail for the two that carry one. */
    fun marker(stdout: String): ScriptMarker? {
        val lines = stdout.lines()
        val index = lines.indexOfFirst { it.trim().startsWith(MARKER) }
        if (index < 0) return null
        val tail = lines.drop(index + 1).joinToString("\n").trim()
        return when (lines[index].trim().removePrefix(MARKER).trim()) {
            "started" -> ScriptMarker.Started
            "already-running" -> ScriptMarker.AlreadyRunning
            "missing-dsh" -> ScriptMarker.MissingDsh
            "missing-node" -> ScriptMarker.MissingNode
            "exited" -> ScriptMarker.Exited(tail)
            "timeout" -> ScriptMarker.ReadyTimeout(tail)
            "stopped" -> ScriptMarker.Stopped
            "not-running" -> ScriptMarker.NotRunning
            "download-failed" -> ScriptMarker.DownloadFailed
            "checksum-failed" -> ScriptMarker.ChecksumFailed
            "open-failed" -> ScriptMarker.OpenFailed
            "installing" -> ScriptMarker.Installing
            else -> null
        }
    }

    private val URL_SHAPE = Regex("""^https://[A-Za-z0-9._~:/?#\[\]@!$&()*+,;=%-]+$""")
    private val FILE_NAME_SHAPE = Regex("""^[A-Za-z0-9._-]+$""")

    private fun validUrl(url: String): String {
        require(URL_SHAPE.matches(url) && '\'' !in url) { "not a plain https URL" }
        return url
    }

    private fun validFileName(name: String): String {
        require(FILE_NAME_SHAPE.matches(name) && name != "." && name != "..") { "not a plain file name" }
        return name
    }

    /** Single-quote a value whose shape has already been checked to contain no quote. */
    private fun quoted(value: String): String = "'$value'"
}
