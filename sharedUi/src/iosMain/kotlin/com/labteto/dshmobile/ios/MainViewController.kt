package com.labteto.dshmobile.ios

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

/**
 * The view controller the SwiftUI host shows (`iosApp/DSHMobile/DSHMobileApp.swift`). Swift sees it as
 * `MainViewControllerKt.MainViewController(prefillAddress:autoConnect:)` in the `DshShared` framework.
 *
 * [prefillAddress] and [autoConnect] are the debug-build launch arguments the host forwards; a normal
 * launch passes `null` and `false`. When [autoConnect] is on, the one result line the attempt
 * produces is also written to a file (see [reportOutcome]) so CI can read it back.
 */
fun MainViewController(prefillAddress: String?, autoConnect: Boolean): UIViewController =
    ComposeUIViewController {
        DshApp(
            LaunchRequest(
                address = prefillAddress,
                autoConnect = autoConnect,
                onOutcome = if (autoConnect) ::reportOutcome else null,
            ),
        )
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
