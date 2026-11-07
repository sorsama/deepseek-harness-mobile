# The iOS port

DSH Mobile is an Android app today, and this document is the plan for an iOS sibling that
shares as much of it as possible. It records what exists, what the milestones are, and the
constraints that no amount of code removes — so the decisions do not have to be re-derived.

## Where this stands

The port has started at the bottom, where the sharing is real:

- `core` is a Kotlin Multiplatform module. Everything that never needed the JVM — the wire
  DTOs, the JSON-RPC envelope codec, session folding and classification, the assistant
  stream expansion, the notification classifier, the Markdown sanitizer — lives in
  `commonMain` and compiles for Android, iOS device and iOS simulator. CI compiles all three
  iOS targets on every push, which is what stops shared code from quietly growing a
  `java.*` import again.
- The JVM-only pieces — the OkHttp transport, the commonmark renderer, the TLS pairing and
  URL plumbing — stayed in `jvmMain`, in the same packages, with the same class names. The
  Android app consumes them exactly as before; nothing about the shipping app changed.
- There is no iOS app yet. Compiling shared code is not an app, and nothing in this repo
  produces an `.ipa` today.

## The path

**M1 — shared core (done, this change).** `core` is multiplatform and guarded by CI.

**M2 — an iOS transport.** The `RpcTransport` interface already exists; it is the seam the
OkHttp implementation sits behind. An iOS implementation has to be written against that
interface — Ktor's Darwin engine or NSURLSession directly — covering the unary POST, the
binary download and upload channels, and the `/api/remote.mux` WebSocket with its
header/auth/cookie rules (see `RpcTransport.kt` and `RelayTls.kt` for the exact carrier
contract). The JVM-only files that stayed behind (`ConnectionLoop`, `RemoteStreamMux`,
`DshApiClient`, `TransportFailure` and friends) move to `commonMain` one at a time as the
platform APIs they lean on — `java.io` streams, `java.time`, `java.util.concurrent` atomics —
are replaced with common equivalents or `expect`/`actual` pairs. Each move is verifiable:
the JVM tests that already exist keep running against the moved code.

**M3 — shared UI, decided by measurement.** The UI is ~49k lines of Compose with no XML
layouts, which is the one fact that makes Compose Multiplatform plausible here. Before
committing, port one screen (Connect is the smallest real one) as a proof, run it in the
iOS simulator, and judge the feel — haptics, scrolling, text input, accessibility — against
the Android app. If CMP holds up, the app module becomes a CMP `composeApp` with thin
`androidMain`/`iosMain` walls; if it does not, the fallback is a native SwiftUI app over the
shared core, and that decision is better made on evidence than on enthusiasm.

**M4 — the iOS app.** Xcode project, app lifecycle, and the platform walls: notifications
(UNUserNotificationCenter), background handling, QR scanning (AVFoundation), file preview,
share/save targets, the 11-language string resources (which move from Android `res/values`
to CMP string resources so both apps read one set). Two features are cut on iOS by the
platform, not by choice: **Termux cannot exist there** (iOS does not allow the background
processes it runs), and the always-up foreground service becomes best-effort — iOS suspends
the app, so live connection becomes notifications plus push rather than a held socket.

**M5 — building and shipping an `.ipa`.** This is the part with gates no code clears:

- **An `.ipa` requires Xcode on macOS.** It cannot be built on Windows or Linux — final
  assembly, asset processing and signing are Xcode operations. The repo has no macOS
  machine; the plan is GitHub Actions `macos-latest` runners, exactly the way `release.yml`
  already produces the APK on ubuntu.
- **Signing requires Apple.** An unsigned `.ipa` installs on nothing. The options:
  the App Store / TestFlight (an Apple Developer Program membership, USD 99/year, review),
  an ad-hoc `.ipa` signed for up to 100 registered devices attached to a GitHub release
  (same membership, expires yearly), or free sideloading through AltStore/Sideloadly with a
  personal Apple ID (7-day expiry, 3-app limit — fine for one operator, hostile for a
  community). Until a membership exists, CI can produce an unsigned `.app`/framework as a
  build artifact; the signed `.ipa` step lands with the account.
- **App Store review** applies only if that route is taken; connecting to a user's own
  server is well-trodden territory (Tailscale, Working Copy), but the Termux-free feature
  set and the relay's TLS profile need a pass for ATS/local-network privacy prompts.

## Working rules for the port

- The Android app is the product. No iOS step may regress it, and "the tests still pass on
  JVM" is the bar for every move — the conformance suite is the referee it already trusts.
- One file or one seam per change, compilable at every step; this repo does not take
  big-bang rewrites.
- Platform differences are documented where they are implemented (`expect`/`actual` pairs
  carry their reasoning in KDoc), not discovered in QA.
