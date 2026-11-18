package com.labteto.dshmobile.core.session

/**
 * Whether a tool result's error is the user (or the harness on their behalf) stopping the call, as
 * opposed to the tool failing. A stopped call is not a failure: the Trajectory says "Stopped", with
 * its own icon and word, and does not count it with the failed calls.
 *
 * The forms the harness records, from its sources:
 *  - `{ name: AbortError, code: ABORTED }`: the Stop button while the tool was running;
 *  - `{ name: AbortError, code: ABORTED_BEFORE_DISPATCH }`: stopped before the tool body started;
 *  - a tool's own abort code, which by the harness's convention ends in `_ABORTED`
 *    (`ASK_ABORTED`, `WEB_ABORTED`, `FS_ABORTED`);
 *  - the older `interrupted`.
 *
 * Plain string comparisons only, so it behaves the same on every platform.
 */
fun isToolAbortError(errorCode: String?, errorName: String?): Boolean =
    errorName == ABORT_ERROR_NAME ||
        errorCode == TOOL_ABORTED_CODE ||
        errorCode == TOOL_ABORTED_BEFORE_DISPATCH_CODE ||
        errorCode == TOOL_INTERRUPTED_CODE ||
        errorCode?.endsWith(TOOL_ABORT_CODE_SUFFIX) == true

private const val ABORT_ERROR_NAME = "AbortError"
private const val TOOL_ABORTED_CODE = "ABORTED"
private const val TOOL_ABORTED_BEFORE_DISPATCH_CODE = "ABORTED_BEFORE_DISPATCH"
private const val TOOL_INTERRUPTED_CODE = "interrupted"
private const val TOOL_ABORT_CODE_SUFFIX = "_ABORTED"
