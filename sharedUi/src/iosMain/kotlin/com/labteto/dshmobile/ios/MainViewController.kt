package com.labteto.dshmobile.ios

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * The view controller the SwiftUI host shows (`iosApp/DSHMobile/DSHMobileApp.swift`). Swift sees it as
 * `MainViewControllerKt.MainViewController(...)` in the `DshShared` framework.
 *
 * The parameters are the debug-build launch arguments the host forwards (see [LaunchRequest]); a normal
 * launch passes `null` and `false` for all of them. When [autoConnect] or [report] is on, the one result
 * line the attempt produces is also written to a file (see [reportOutcome]) so CI can read it back.
 */
fun MainViewController(
    prefillAddress: String?,
    autoConnect: Boolean,
    openSignIn: Boolean,
    signInToken: String?,
    reset: Boolean,
    report: Boolean,
    autoReconnect: Boolean,
): UIViewController =
    ComposeUIViewController {
        val request = remember {
            LaunchRequest(
                address = prefillAddress,
                autoConnect = autoConnect,
                openSignIn = openSignIn,
                signInToken = signInToken,
                reset = reset,
                onOutcome = if (autoConnect || report) ::reportOutcome else null,
            )
        }
        // One controller for the life of the process, on the main thread where Compose runs. The reset,
        // when asked for, happens first so that nothing remembered reaches the screen.
        val app = remember {
            if (request.reset) resetStorage()
            if (!autoReconnect) UserDefaultsConnectStore.disableAutoConnectLast()
            IosApp(CoroutineScope(SupervisorJob() + Dispatchers.Main))
        }
        DshApp(app, request)
    }

/** Forget what the app remembered: the harnesses and settings in NSUserDefaults, the sessions in the Keychain. */
private fun resetStorage() {
    val defaults = NSUserDefaults.standardUserDefaults
    NSBundle.mainBundle.bundleIdentifier?.let { defaults.removePersistentDomainForName(it) }
    KeychainSessionStore().wipe()
}

/** Where [reportOutcome] writes, inside the app's own Documents directory. */
private const val OUTCOME_FILE = "dsh-connect-result.txt"

/**
 * Say what the last connect attempt did, to the two places a script can look: the process's output
 * (`xcrun simctl launch --console`) and a file in the app container
 * (`xcrun simctl get_app_container <device> <bundle id> data`, then `Documents/dsh-connect-result.txt`).
 * The file is the one to trust: it does not depend on how the console is buffered.
 */
@OptIn(ExperimentalForeignApi::class)
private fun reportOutcome(line: String) {
    println(line)
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return
    val file = fopen("$documents/$OUTCOME_FILE", "w") ?: return
    fputs(line, file)
    fclose(file)
}
