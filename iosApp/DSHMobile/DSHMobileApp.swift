import SwiftUI
import DshShared

/// The iOS app: a SwiftUI shell around the Compose Multiplatform UI in `:sharedUi`.
///
/// Everything the user sees is Kotlin. This file only owns the app lifecycle and hands the shared
/// view controller to SwiftUI.
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
            autoConnect: launch.autoConnect
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Launch arguments that let a script drive the app without a person typing, for CI and for
/// trying the app against a harness from the command line:
///
///     xcrun simctl launch booted com.labteto.dshmobile -DshConnectAddress http://127.0.0.1:3080 -DshAutoConnect YES
///
/// Debug builds only. A release build ignores them, so the shipped app has no way to be told which
/// address to connect to from outside.
struct LaunchOptions {
    let address: String?
    let autoConnect: Bool

    static func fromProcess() -> LaunchOptions {
        #if DEBUG
        // `-Key value` launch arguments land in UserDefaults' argument domain.
        let defaults = UserDefaults.standard
        let address = defaults.string(forKey: "DshConnectAddress")
        return LaunchOptions(address: address, autoConnect: address != nil && defaults.bool(forKey: "DshAutoConnect"))
        #else
        return LaunchOptions(address: nil, autoConnect: false)
        #endif
    }
}
