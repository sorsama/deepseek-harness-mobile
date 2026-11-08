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
            pairScanner: launch.pairScanner
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
            pairScanner: defaults.bool(forKey: "DshPairScanner")
        )
        #else
        return LaunchOptions(
            address: nil, autoConnect: false, openSignIn: false, signInToken: nil, reset: false, report: false,
            autoReconnect: nil, connectMode: nil, openPair: false, pairUrl: nil, pairCode: nil, pairSubmit: false,
            pairPayload: nil, pairScanner: false
        )
        #endif
    }
}
