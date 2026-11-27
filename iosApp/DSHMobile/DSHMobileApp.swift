import SwiftUI
import DshShared

/// The iOS app: a SwiftUI shell around the Compose Multiplatform UI in `:sharedUi`.
///
/// Everything the user sees is Kotlin, and it is the same Compose code the Android app runs. This
/// file only owns the app lifecycle and hands the shared view controller to SwiftUI.
@main
struct DSHMobileApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // Compose draws under the status bar and home indicator and pads for them itself
                // (`safeDrawingPadding`), so SwiftUI must not inset it a second time.
                .ignoresSafeArea(.all)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let launch = LaunchOptions.fromProcess()
        return MainViewControllerKt.MainViewController(
            prefillAddress: launch.address,
            autoConnect: launch.autoConnect,
            openSignIn: launch.openSignIn,
            signInToken: launch.signInToken,
            reset: launch.reset,
            report: launch.report,
            autoReconnect: launch.autoReconnect,
            connectMode: launch.connectMode,
            openPair: launch.openPair,
            pairUrl: launch.pairUrl,
            pairCode: launch.pairCode,
            pairSubmit: launch.pairSubmit,
            pairPayload: launch.pairPayload,
            pairScanner: launch.pairScanner,
            reconnectOnce: launch.reconnectOnce,
            openDrawer: launch.openDrawer,
            markdownSample: launch.markdownSample,
            toolCardsSample: launch.toolCardsSample,
            openSession: launch.openSession,
            chatScroll: launch.chatScroll,
            composerDraft: launch.composerDraft,
            prompt: launch.prompt,
            sendPrompt: launch.sendPrompt,
            pressStop: launch.pressStop,
            focusComposer: launch.focusComposer,
            answer: launch.answer,
            reconnect: launch.reconnect,
            awaitBanner: launch.awaitBanner,
            focusAddress: launch.focusAddress
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Launch arguments that let a script drive the app without a person typing, for CI and for
/// trying the app against a harness from the command line:
///
///     xcrun simctl launch booted com.labteto.dshmobile -DshConnectAddress http://127.0.0.1:3080 -DshAutoConnect YES
///
/// - `-DshConnectAddress <address>` pre-fills the address field; `-DshAutoConnect YES` then taps Connect.
/// - `-DshOpenSignIn YES` opens the sign-in sheet when a harness wants a session;
///   `-DshSignInToken <token>` opens it and submits the token, as pasting it would.
/// - `-DshReset YES` forgets everything the app remembered before the screen is built.
/// - `-DshReport YES` writes the result line (connected, or why not) without a Connect tap.
/// - `-DshAutoReconnect NO` turns off "reconnect to the last harness" (the Recent list shows), `YES` turns it on again.
/// - `-DshConnectMode relay` (or `lan`) switches the Connect screen's mode before it is drawn.
/// - The pairing arguments open the Pair screen and drive it as a person would: `-DshOpenPair YES` opens it,
///   `-DshPairUrl <address>` and `-DshPairCode <digits>` fill the form, `-DshPairSubmit YES` taps Pair,
///   `-DshPairPayload <hex of the json>` hands it the text a QR scan would have decoded (a simulator has no camera),
///   `-DshPairScanner YES` opens the camera sheet. The result is written to `Documents/dsh-pair-result.txt`.
/// - `-DshReconnectOnce YES` starts a fresh connection once the session list has been read, as coming back from the
///   background does, and keeps every sessions line in `Documents/dsh-sessions-result.txt` instead of the last.
/// - `-DshOpenDrawer YES` opens the session list once the app is connected (a phone has it in a drawer; an iPad docks
///   it anyway) and writes `DSH_DRAWER_RESULT shown …` to `Documents/dsh-drawer-result.txt` once it shows sessions.
/// - `-DshMarkdownSample top` (or `end`, scrolled to the foot) shows a page of the chat's shared Markdown on one fixed
///   answer instead of the app, and writes `DSH_MARKDOWN_RESULT shown …` to `Documents/dsh-markdown-result.txt`.
/// - `-DshToolCardsSample top` (or `end`) shows a page of the chat's shared tool cards, every kind open, and writes
///   `DSH_TOOLCARDS_RESULT shown …` to `Documents/dsh-toolcards-result.txt`.
/// - `-DshOpenSession first` (or a session id) opens that session once connected, as a tap on its row does, and writes
///   `DSH_CHAT_RESULT opened rows=… session=… more=…` to `Documents/dsh-chat-result.txt` once its transcript is laid
///   out. `-DshChatScroll older` then drags the transcript up to the oldest loaded message as a finger does, which loads
///   the page before it, and adds `DSH_CHAT_RESULT older …` once it has landed. Once the composer under the transcript
///   has been laid out it adds `DSH_CHAT_RESULT composer chars=… lines=… send=…`.
/// - `-DshComposerDraft <hex of the text>` puts that text in the composer of the session `-DshOpenSession` opens, as
///   typing it would (hex for the reason `-DshPairPayload` is: newlines and quotes survive).
/// - `-DshPrompt <hex of the text>` puts a prompt in that composer the same way; `-DshSendPrompt YES` sends it as the
///   send button does and adds `DSH_CHAT_RESULT sent …`, `streaming …` and `replied chars=… state=idle` as the reply
///   arrives. `-DshPressStop YES` presses Stop instead once the reply streams and the script has created
///   `Documents/dsh-press-stop`, and adds `DSH_CHAT_RESULT stopped …`. `-DshFocusComposer YES` moves focus into the
///   composer, which brings the software keyboard up, and adds `DSH_CHAT_RESULT keyboard ime=… above=…`.
/// - `-DshAnswer allow` (or `deny`, or for questions the options picked, `2`, `1+2/1`: `+` between options, `/` between
///   questions) answers the approval or question the sent prompt's turn puts to the reader, once the script has created
///   `Documents/dsh-answer`, and adds `DSH_CHAT_RESULT request …`, `answered …` and `settled …`. `-DshReconnect YES`
///   starts a fresh connection while that request is open, as coming back to the foreground does, and adds
///   `DSH_CHAT_RESULT reconnected …`. `-DshAwaitBanner YES` waits for the chat's red connection strip instead of a
///   reply and adds `DSH_CHAT_RESULT banner shown=…`.
/// - `-DshFocusAddress YES` brings the software keyboard up over the Connect screen's address field and writes
///   `DSH_KEYBOARD_RESULT field=address ime=… above=…` to `Documents/dsh-keyboard-result.txt`.
///
/// Debug builds only. A release build ignores them, so the shipped app has no way to be told which
/// address to connect to, or what to forget, from outside.
struct LaunchOptions {
    let address: String?
    let autoConnect: Bool
    let openSignIn: Bool
    let signInToken: String?
    let reset: Bool
    let report: Bool
    let autoReconnect: String?
    let connectMode: String?
    let openPair: Bool
    let pairUrl: String?
    let pairCode: String?
    let pairSubmit: Bool
    let pairPayload: String?
    let pairScanner: Bool
    let reconnectOnce: Bool
    let openDrawer: Bool
    let markdownSample: String?
    let toolCardsSample: String?
    let openSession: String?
    let chatScroll: String?
    let composerDraft: String?
    let prompt: String?
    let sendPrompt: Bool
    let pressStop: Bool
    let focusComposer: Bool
    let answer: String?
    let reconnect: Bool
    let awaitBanner: Bool
    let focusAddress: Bool

    /// A launch argument's value is parsed as a property list before the app sees it, which mangles JSON, so a
    /// pairing payload is passed hex-encoded (`xxd -p`). A value that is not hex is returned as it was given.
    static func decodeHex(_ value: String?) -> String? {
        guard let value = value, !value.isEmpty, value.count % 2 == 0 else { return value }
        var bytes = [UInt8]()
        var index = value.startIndex
        while index < value.endIndex {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else { return value }
            bytes.append(byte)
            index = next
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    static func fromProcess() -> LaunchOptions {
        #if DEBUG
        // `-Key value` launch arguments land in UserDefaults' argument domain.
        let defaults = UserDefaults.standard
        let address = defaults.string(forKey: "DshConnectAddress")
        return LaunchOptions(
            address: address,
            autoConnect: address != nil && defaults.bool(forKey: "DshAutoConnect"),
            openSignIn: defaults.bool(forKey: "DshOpenSignIn"),
            signInToken: defaults.string(forKey: "DshSignInToken"),
            reset: defaults.bool(forKey: "DshReset"),
            report: defaults.bool(forKey: "DshReport"),
            autoReconnect: defaults.string(forKey: "DshAutoReconnect"),
            connectMode: defaults.string(forKey: "DshConnectMode"),
            openPair: defaults.bool(forKey: "DshOpenPair"),
            pairUrl: defaults.string(forKey: "DshPairUrl"),
            pairCode: defaults.string(forKey: "DshPairCode"),
            pairSubmit: defaults.bool(forKey: "DshPairSubmit"),
            pairPayload: decodeHex(defaults.string(forKey: "DshPairPayload")),
            pairScanner: defaults.bool(forKey: "DshPairScanner"),
            reconnectOnce: defaults.bool(forKey: "DshReconnectOnce"),
            openDrawer: defaults.bool(forKey: "DshOpenDrawer"),
            markdownSample: defaults.string(forKey: "DshMarkdownSample"),
            toolCardsSample: defaults.string(forKey: "DshToolCardsSample"),
            openSession: defaults.string(forKey: "DshOpenSession"),
            chatScroll: defaults.string(forKey: "DshChatScroll"),
            composerDraft: decodeHex(defaults.string(forKey: "DshComposerDraft")),
            prompt: decodeHex(defaults.string(forKey: "DshPrompt")),
            sendPrompt: defaults.bool(forKey: "DshSendPrompt"),
            pressStop: defaults.bool(forKey: "DshPressStop"),
            focusComposer: defaults.bool(forKey: "DshFocusComposer"),
            answer: defaults.string(forKey: "DshAnswer"),
            reconnect: defaults.bool(forKey: "DshReconnect"),
            awaitBanner: defaults.bool(forKey: "DshAwaitBanner"),
            focusAddress: defaults.bool(forKey: "DshFocusAddress")
        )
        #else
        return LaunchOptions(
            address: nil, autoConnect: false, openSignIn: false, signInToken: nil, reset: false, report: false,
            autoReconnect: nil, connectMode: nil, openPair: false, pairUrl: nil, pairCode: nil, pairSubmit: false,
            pairPayload: nil, pairScanner: false, reconnectOnce: false, openDrawer: false, markdownSample: nil,
            toolCardsSample: nil, openSession: nil, chatScroll: nil, composerDraft: nil, prompt: nil, sendPrompt: false,
            pressStop: false, focusComposer: false, answer: nil, reconnect: false, awaitBanner: false, focusAddress: false
        )
        #endif
    }
}
