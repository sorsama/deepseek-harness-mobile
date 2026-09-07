package com.labteto.dshmobile.termux

/**
 * Termux's public surface, as this app addresses it.
 *
 * All of it is defined by the Termux app (`TermuxConstants` in `termux-shared`), which is why the
 * values are spelled out here rather than imported: there is no library to depend on, and the
 * strings are the contract. The `/data/data/com.termux/...` paths are Termux's own, not this
 * app's storage, which is what the lint check being suppressed here is about.
 */
@Suppress("SdCardPath")
object TermuxPaths {
    const val PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    /** Declared `dangerous` by Termux, so it is requested at runtime like any other. */
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val PREFIX = "/data/data/com.termux/files/usr"
    const val BASH = "$PREFIX/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** The bundle Termux attaches to the result intent, and the keys inside it. */
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** `err` when Termux itself had nothing to report — the command ran, whatever it exited with. */
    const val ERR_NONE = -1
}

/** What a command was for, echoed back on its result so an orphaned result can still be acted on. */
enum class TermuxCommandKind { START, STOP, INSTALL }

/**
 * One script to run in Termux's bash.
 *
 * [script] is handed to `bash -c` whole rather than piped to stdin: a harness the script starts
 * would inherit that stdin and could read the rest of the script as its own input. [timeoutMs]
 * is the app's own patience for the result, over and above whatever the script waits for.
 */
data class TermuxCommand(
    val script: String,
    val label: String,
    val timeoutMs: Long,
    val kind: TermuxCommandKind,
    val port: Int? = null,
)

/** What came back from Termux, or why nothing did. */
sealed interface TermuxResult {
    /** The command ran to completion; [exitCode] is the script's own verdict. */
    data class Ok(val stdout: String, val stderr: String, val exitCode: Int) : TermuxResult

    /** No Termux on this phone. */
    data object NotInstalled : TermuxResult

    /** The user has not granted, or has revoked, the run-command permission. */
    data object PermissionDenied : TermuxResult

    /** Termux refused: `allow-external-apps` is not `true` in its `termux.properties`. */
    data object ExternalAppsDisabled : TermuxResult

    /** Termux is installed but its service could not be started; [detail] is Android's reason. */
    data class ServiceUnavailable(val detail: String?) : TermuxResult

    /** Termux accepted the command and never reported back within the app's budget. */
    data object Timeout : TermuxResult

    /** Termux reported an error of its own before or instead of running the command. */
    data class Failed(val err: Int, val errmsg: String?) : TermuxResult
}
