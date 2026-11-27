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
 * line the attempt produces is also written to a file (see [reportOutcome]) so CI can read it back; the
 * pairing arguments do the same for pairing (see [reportPairOutcome]).
 *
 * [markdownSample] (`-DshMarkdownSample top|end`) shows [MarkdownSamplePage] instead of the app: the shared chat
 * Markdown on one fixed answer, for a CI screenshot before any iOS screen shows a transcript. [toolCardsSample]
 * (`-DshToolCardsSample top|end`) shows [ToolCardsSamplePage] the same way: every shared tool card, open.
 *
 * [openSession] (`-DshOpenSession first|<session id>`) opens a session once connected, and [chatScroll]
 * (`-DshChatScroll tail|older`) says where its transcript is left; both write `DSH_CHAT_RESULT …` lines (see
 * [reportChat]). [composerDraft] (`-DshComposerDraft <hex>`, decoded by the host) is the text the opened session's
 * composer starts with, as if typed.
 */
fun MainViewController(
    prefillAddress: String?,
    autoConnect: Boolean,
    openSignIn: Boolean,
    signInToken: String?,
    reset: Boolean,
    report: Boolean,
    autoReconnect: String?,
    connectMode: String?,
    openPair: Boolean,
    pairUrl: String?,
    pairCode: String?,
    pairSubmit: Boolean,
    pairPayload: String?,
    pairScanner: Boolean,
    reconnectOnce: Boolean,
    openDrawer: Boolean,
    markdownSample: String?,
    toolCardsSample: String?,
    openSession: String?,
    chatScroll: String?,
    composerDraft: String?,
): UIViewController =
    ComposeUIViewController {
        if (markdownSample != null) {
            MarkdownSamplePage(markdownSample.lowercase()) { writeReport(MARKDOWN_OUTCOME_FILE, it) }
            return@ComposeUIViewController
        }
        if (toolCardsSample != null) {
            ToolCardsSamplePage(toolCardsSample.lowercase()) { writeReport(TOOL_CARDS_OUTCOME_FILE, it) }
            return@ComposeUIViewController
        }
        val request = remember {
            val pairing = openPair || pairUrl != null || pairCode != null || pairSubmit || pairPayload != null || pairScanner
            LaunchRequest(
                address = prefillAddress,
                autoConnect = autoConnect,
                openSignIn = openSignIn,
                signInToken = signInToken,
                reset = reset,
                connectMode = connectMode?.lowercase(),
                openPair = openPair,
                pairUrl = pairUrl,
                pairCode = pairCode,
                pairSubmit = pairSubmit,
                pairPayload = pairPayload,
                pairScanner = pairScanner,
                // A pairing launch also reports the connection that follows it, so a script can wait for the relay.
                onOutcome = if (autoConnect || report || pairing) ::reportOutcome else null,
                onPairOutcome = if (pairing) ::reportPairOutcome else null,
                onProbe = if (autoConnect || report || pairing) ::reportProbe else null,
                // A reconnect run keeps every line, so a script can see the one written after the reconnect.
                onSessions = when {
                    reconnectOnce -> ::appendSessions
                    autoConnect || report || pairing -> ::reportSessions
                    else -> null
                },
                reconnectOnce = reconnectOnce,
                openDrawer = openDrawer,
                onDrawer = if (openDrawer) ::reportDrawer else null,
                openSession = openSession,
                chatScroll = when (chatScroll?.lowercase()) {
                    "older" -> ChatScroll.Older
                    "tail" -> ChatScroll.Tail
                    else -> null
                },
                onChat = if (openSession != null) ::reportChat else null,
                composerDraft = composerDraft,
            )
        }
        // One controller for the life of the process, on the main thread where Compose runs. The reset,
        // when asked for, happens first so that nothing remembered reaches the screen.
        val app = remember {
            if (request.reset) resetStorage()
            when (autoReconnect?.uppercase()) {
                "NO" -> UserDefaultsConnectStore.setAutoConnectLast(false)
                "YES" -> UserDefaultsConnectStore.setAutoConnectLast(true)
            }
            IosApp(CoroutineScope(SupervisorJob() + Dispatchers.Main), reportPair = request.onPairOutcome, reportProbe = request.onProbe)
        }
        DshApp(app, request)
    }

/** Forget what the app remembered: the harnesses and settings in NSUserDefaults, the sessions and relay tokens in the Keychain. */
private fun resetStorage() {
    val defaults = NSUserDefaults.standardUserDefaults
    NSBundle.mainBundle.bundleIdentifier?.let { defaults.removePersistentDomainForName(it) }
    KeychainSessionStore().wipe()
    KeychainRelayTokens().wipe()
}

/** Where [reportOutcome] writes, inside the app's own Documents directory. */
private const val OUTCOME_FILE = "dsh-connect-result.txt"

/** Where [reportProbe] appends: one line per probe, in a file of its own so it never replaces the connection result. */
private const val PROBE_OUTCOME_FILE = "dsh-probe-result.txt"

/** Where [reportPairOutcome] writes: its own file, so a pairing result is not overwritten by the connection it starts. */
private const val PAIR_OUTCOME_FILE = "dsh-pair-result.txt"

/** Where [reportSessions] writes: the session list call made after connecting, in a file of its own. */
private const val SESSIONS_OUTCOME_FILE = "dsh-sessions-result.txt"

/** Where [reportDrawer] writes: whether the session list is on screen, for `-DshOpenDrawer`. */
private const val DRAWER_OUTCOME_FILE = "dsh-drawer-result.txt"

/** Where the Markdown sample page (`-DshMarkdownSample`) says it has been laid out. */
private const val MARKDOWN_OUTCOME_FILE = "dsh-markdown-result.txt"

/** Where the tool-card sample page (`-DshToolCardsSample`) says it has been laid out. */
private const val TOOL_CARDS_OUTCOME_FILE = "dsh-toolcards-result.txt"

/** Where [reportChat] appends: what the opened session's transcript shows, for `-DshOpenSession`. */
private const val CHAT_OUTCOME_FILE = "dsh-chat-result.txt"

/**
 * Say what the last connect attempt did, to the two places a script can look: the process's output
 * (`xcrun simctl launch --console`) and a file in the app container
 * (`xcrun simctl get_app_container <device> <bundle id> data`, then `Documents/dsh-connect-result.txt`).
 * The file is the one to trust: it does not depend on how the console is buffered.
 */
private fun reportOutcome(line: String) = writeReport(OUTCOME_FILE, line)

/** [reportOutcome] for the pairing screen (`Documents/dsh-pair-result.txt`). */
private fun reportPairOutcome(line: String) = writeReport(PAIR_OUTCOME_FILE, line)

/**
 * [reportOutcome] for a probe (`Documents/dsh-probe-result.txt`). Appended, one line each, because every remembered
 * host is probed and the last line alone would hide the others.
 */
private fun reportProbe(line: String) = writeReport(PROBE_OUTCOME_FILE, line, append = true)

/** [reportOutcome] for the session list call made after connecting (`Documents/dsh-sessions-result.txt`). */
private fun reportSessions(line: String) = writeReport(SESSIONS_OUTCOME_FILE, line)

/** [reportSessions] that keeps every line, one per line, for `-DshReconnectOnce`. */
private fun appendSessions(line: String) = writeReport(SESSIONS_OUTCOME_FILE, line, append = true)

/** [reportOutcome] for the session list being on screen (`Documents/dsh-drawer-result.txt`). */
private fun reportDrawer(line: String) = writeReport(DRAWER_OUTCOME_FILE, line)

/**
 * [reportOutcome] for an opened session's transcript (`Documents/dsh-chat-result.txt`). Appended, one line each: a
 * `-DshChatScroll older` run writes the `opened` line and then the `older` one, and a script checks both.
 */
private fun reportChat(line: String) = writeReport(CHAT_OUTCOME_FILE, line, append = true)

@OptIn(ExperimentalForeignApi::class)
private fun writeReport(fileName: String, line: String, append: Boolean = false) {
    println(line)
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: return
    val file = fopen("$documents/$fileName", if (append) "a" else "w") ?: return
    fputs(if (append) line + "\n" else line, file)
    fclose(file)
}
