package com.labteto.dshmobile.update

import android.content.pm.PackageInstaller

/** Why the system did not install an update, in the terms the dialog explains it in. */
enum class InstallFailure(
    /** Worth offering "Try again" with the same file. */
    val retryable: Boolean,
    /** The file itself is the problem: delete it, so "try again" downloads it afresh. */
    val discardFile: Boolean,
) {
    /** The person said no in the system prompt. */
    Aborted(retryable = true, discardFile = false),

    /**
     * The device's app verifier (Play Protect on most phones) did not approve the install, or did
     * not answer in time. Android reports this as "aborted" too; it is told apart by its message.
     */
    Verification(retryable = true, discardFile = false),

    /** A device policy, a work profile or a security setting would not allow it. */
    Blocked(retryable = false, discardFile = false),

    /** It clashes with what is installed: another signer, or the same version from elsewhere. */
    Conflict(retryable = false, discardFile = true),

    /** Built for something this device is not (ABI, SDK level). */
    Incompatible(retryable = false, discardFile = true),

    /** Not a valid APK, or damaged. */
    Invalid(retryable = true, discardFile = true),

    /** Not enough room to install. */
    Storage(retryable = true, discardFile = false),

    /** The system gave up waiting. */
    Timeout(retryable = true, discardFile = false),

    /** Anything else the platform reports as a plain failure. */
    Unknown(retryable = true, discardFile = false),
}

/** What a [PackageInstaller] status broadcast means for the updater. */
sealed interface InstallOutcome {
    /** The system wants the person to confirm; the broadcast carries the activity to show. */
    data object NeedsUserAction : InstallOutcome

    data object Success : InstallOutcome

    data class Failed(val failure: InstallFailure, val detail: String?) : InstallOutcome
}

/** `STATUS_FAILURE_TIMEOUT`, added in API 34; spelled out so this compiles on any level. */
private const val STATUS_FAILURE_TIMEOUT = 8

/** `INSTALL_FAILED_VERIFICATION_FAILURE` or `_TIMEOUT` in the status message: the verifier, not the person. */
private fun isVerifierRefusal(message: String?): Boolean =
    message?.contains("INSTALL_FAILED_VERIFICATION", ignoreCase = true) == true

/** Map [PackageInstaller.EXTRA_STATUS] (and its message) to an [InstallOutcome]. */
internal fun mapInstallStatus(status: Int, message: String?): InstallOutcome {
    val detail = message?.trim()?.takeIf { it.isNotEmpty() }
    return when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.NeedsUserAction
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Success
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            InstallOutcome.Failed(if (isVerifierRefusal(detail)) InstallFailure.Verification else InstallFailure.Aborted, detail)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.Failed(InstallFailure.Blocked, detail)
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.Failed(InstallFailure.Conflict, detail)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.Failed(InstallFailure.Incompatible, detail)
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome.Failed(InstallFailure.Invalid, detail)
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.Failed(InstallFailure.Storage, detail)
        STATUS_FAILURE_TIMEOUT -> InstallOutcome.Failed(InstallFailure.Timeout, detail)
        else -> InstallOutcome.Failed(InstallFailure.Unknown, detail)
    }
}
