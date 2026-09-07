package com.labteto.dshmobile.termux

/**
 * The fields Termux puts in a result bundle, lifted out of the `Bundle` so the reading can be
 * tested without Android.
 */
internal data class TermuxResultFields(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val err: Int,
    val errmsg: String?,
)

/**
 * Turn Termux's report into a [TermuxResult].
 *
 * `err` is Termux's own verdict and is separate from the script's exit code: `-1` means the
 * command ran, whatever it printed or exited with. Anything else is Termux refusing or failing
 * to run it at all, and the one refusal worth naming is the `allow-external-apps` policy, because
 * its fix is one line in a file the user can be told about.
 */
internal fun classifyResult(fields: TermuxResultFields): TermuxResult {
    if (fields.err == TermuxPaths.ERR_NONE) {
        return TermuxResult.Ok(fields.stdout, fields.stderr, fields.exitCode)
    }
    val message = fields.errmsg.orEmpty()
    return if (message.contains("allow-external-apps", ignoreCase = true)) {
        TermuxResult.ExternalAppsDisabled
    } else {
        TermuxResult.Failed(fields.err, fields.errmsg)
    }
}
