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
  iOS targets on every push **on a macOS runner**, which is what stops shared code from quietly
  growing a `java.*` import again (see "The iOS compile gate" below for why that was not true
  before M2b).
- The JVM-only pieces — the OkHttp transport, the commonmark renderer, the TLS pairing and
  URL plumbing — stayed in `jvmMain`, in the same packages, with the same class names. The
  Android app consumes them exactly as before; nothing about the shipping app changed.
- **M2a moved the transport seam into `commonMain`.** `RpcTransport`, `RpcTransportException`,
  the `MuxSocket`/`WsChannelSink` socket contract, `RemoteStreamMux`, `ConnectionLoop` and
  `TransportFailure` are common now; `OkHttpRpcTransport` and `WsChannel` are the JVM
  implementations behind them. Three platform differences were replaced rather than ported:
  `java.io.InputStream` became kotlinx-io's `Source` (and `java.io.IOException` its
  `IOException`, a typealias of the same class on the JVM, so the app's `catch` blocks are
  unchanged); `java.util.concurrent` atomics and `ConcurrentHashMap` became `kotlinx.atomicfu`
  plus a small copy-on-write `AtomicRegistry`; and the part of failure classification that
  inspects `java.net`/`javax.net.ssl` exceptions became an `expect`/`actual` pair.
- **M2b added the second implementation of that seam, on Ktor.** `KtorRpcTransport` and
  `KtorMuxSocket` are `commonMain` classes over an injected Ktor `HttpClient`. On iOS that client
  is `darwinHttpClient()` (the Darwin engine: `NSURLSession` and `NSURLSessionWebSocketTask`); on
  the JVM the same classes run on Ktor's CIO engine, which is how they are proven before an iOS
  device exists: the transport contract tests run against OkHttp *and* Ktor, and the conformance
  suite runs against the pinned real harness once per transport (`-Pdsh.transport=ktor`). The
  Android app is unchanged: it keeps `OkHttpRpcTransport`/`WsChannel`, and its build excludes
  Ktor from the APK (about 2 MB of jars it would otherwise carry for nothing).
  `TransportFailure.ios.kt` classifies what the Darwin engine really throws, by `NSURLErrorDomain`
  code, and relay certificate pinning is implemented on the Darwin challenge handler (see
  "Relay pinning on iOS").
- There is no iOS app yet. Compiling shared code is not an app, and nothing in this repo
  produces an `.ipa` today.

## The iOS compile gate

Kotlin/Native builds Apple targets on macOS only. On Linux and Windows Gradle logs that
`iosArm64, iosSimulatorArm64, iosX64` "cannot be built on this machine and are disabled" and marks
every iOS task `SKIPPED`, so the `./gradlew :core:compileKotlinIosArm64 …` step that M1 added to the
ubuntu `build` job reported `BUILD SUCCESSFUL in 1s` having compiled nothing. Klib cross-compilation
does not rescue it: with `kotlin.native.enableKlibsCrossCompilation=true` on Linux the toolchain
fails to find even `org.jetbrains.kotlin.native.platform.posix`, because it ships no Apple platform
libraries.

So the first real iOS compile was M2b's, and it found three pieces of `commonMain` that were
JVM-only all along (`toByteArray(Charsets.UTF_8)`, `RegexOption.DOT_MATCHES_ALL`, and `Dispatchers.IO`
used from common code), all fixed. The compile now lives in its own `ios-compile` job on
`macos-latest` (free for a public repository), and asserts that the three `core.klib` files exist so
a skip can never pass again. On a host that cannot run it, `./gradlew :core:compileCommonMainKotlinMetadata`
resolves `commonMain` against common declarations only and catches the same class of mistake locally;
it is a smoke check, not the gate.

## Transport parity: Ktor against OkHttp

Both implementations sit behind `RpcTransport`/`MuxSocket` and are held to one contract by
`TransportContractTest`, `TransportSeamTest`, `ProxyTransportTest` and `MuxSocketContractTest`
(each runs once per implementation), and by the real-harness conformance suite. What matches:
the hand-spelled `Host` (default port omitted, IPv6 bracketed), `Content-Type: application/json;
charset=utf-8`, `Authorization`/`Cookie` only when given, the reverse-proxy prefix and its escape
refusal (`HarnessEndpoint` is checked against `resolveHarnessUrl` over 12 bases × 25 routes),
30 s default connect/read deadlines, no read deadline for `pluginManager/installBundle` and
`waitForInstall`, ten minutes for downloads and uploads, the full non-2xx → `RpcTransportException`
table (401/403 apart, the relay's 403 reason, `Retry-After` on 429, 413, 502), multipart answers as
raw bytes, a live `Source` for downloads that is closed when the consumer returns, a consumer's own
exception passing through unwrapped, uploads that stream and report every 64 KiB plus the total, and
the mux socket's credentials, `Host`, rejected-upgrade shape, queueing before the handshake,
idempotent `start`/`close`, and ping answering.

What does not match, and why:

- **Redirects are not followed** by the Ktor transport (OkHttp's default client follows them). An
  `/api` call that is redirected becomes a non-2xx `RpcTransportException`, so a credential is never
  replayed at another address. Darwin refuses redirects itself.
- **A body that ends short of its `Content-Length` fails the call.** Ktor's CIO engine ends such a
  body quietly (OkHttp throws), and a half-saved session ZIP must not look whole. The check is off
  for the Darwin client, where `NSURLSession` reports a short body itself and may decompress while
  leaving the compressed length in the headers (`DshBodyLengthCheck`).
- **No write timeout, and on iOS no separate connect timeout.** OkHttp has `writeTimeoutMs`; the
  Ktor transport has one idle (`socketTimeout`) deadline, and `NSURLRequest.timeoutInterval` is a
  single idle timeout that also covers connecting, so on Darwin the connect deadline folds into the
  read one.
- **Compression.** OkHttp negotiates gzip; Ktor on CIO does not (tests only). `NSURLSession` does.
- **IDN hostnames** must be given as punycode; OkHttp converts them, `HarnessEndpoint` refuses
  non-ASCII hosts.
- **Frame limit.** The Ktor mux accepts messages up to 128 MiB; OkHttp has none. (Darwin's own default
  of 1 MiB would be a real limit for a large session snapshot, which is why it is raised.)
- **Download memory on iOS.** `NSURLSession` pushes data as it arrives and Ktor's Darwin engine
  queues it in an unbounded channel, so a consumer slower than the network does not slow the download
  and the unread part is held in memory. The `Source` the consumer reads is live and nothing is
  copied a second time, but the "bytes are pulled from the connection as `consume` reads them"
  guarantee of the OkHttp path is not available on Darwin. A session export is a file written to
  disk, which is normally faster than the link.
- **A rejected mux upgrade on iOS** keeps its HTTP status for `401` only (Ktor works around
  KTOR-7363 for that one); any other rejection, a `403` included, arrives as
  `NSURLErrorBadServerResponse` and classifies as "not a harness". The unary calls carry their status
  and are unaffected, so the 401/403 distinction survives wherever an `/api` call is made; a mux
  refused with a 403 and nothing else would read wrongly on iOS until the status can be recovered
  from the failed task (a follow-up that needs a device to check what `NSURLSession` exposes).
- **`WsChannel` never reports a host-initiated clean close** (it has no `onClosing` handler, so OkHttp
  never reaches `onClosed` until something fails). `KtorMuxSocket` reports it. Android's behaviour
  is deliberately untouched; it is worth a look separately.
- **After a local `close()`**, `WsChannel` reports a "Canceled" failure to the sink and `KtorMuxSocket`
  reports nothing; the mux already knows.
- Ktor adds `Accept`, `Accept-Charset` and a `User-Agent`; OkHttp adds `Accept-Encoding` and its own
  `User-Agent`. Neither side reads them.

What was written but cannot be exercised here: the Darwin engine itself. There is no iOS device or
simulator in the loop, so the `Host`, `Authorization` and `Cookie` headers reaching the wire through
`NSURLSession` (it documents `Host` as one it manages), the WebSocket ping/pong at the protocol layer,
and the failure codes in `TransportFailure.ios.kt` are reasoned from the Ktor 3.0.3 sources and
Apple's documentation and compile-checked by CI, not observed. The first thing to do on a simulator
is run the contract against the real harness over `darwinHttpClient()`.

## Relay pinning on iOS

The relay's default posture is a self-signed certificate, so the app pins its key: the pairing QR
carries `base64(sha256(DER SubjectPublicKeyInfo))` and the key *is* the trust decision. On the JVM
that is `RelayTls.pinnedClient`. On iOS it is `darwinHttpClient(pinnedSpkiSha256 = …)`, whose
challenge handler applies the same rules: the leaf certificate's key must match exactly; there is no
fallback to system validation; the hostname is not checked; and every unreadable or absent input
cancels the challenge (it fails closed). A refused challenge surfaces from `NSURLSession` as a bare
`NSURLErrorCancelled`, so the handler records the mismatch first and the client rethrows the failure as
`IosPinMismatchException`, which `hasPinMismatch` finds and which classifies as `CERTIFICATE_PIN`
rather than a generic TLS error.

The pin value must be the number the JVM computes. Apple's Security framework only hands back raw key
bytes, and rebuilding the SubjectPublicKeyInfo header from a table of key types and sizes is how a pin
silently stops matching, so `SpkiPin` reads the SubjectPublicKeyInfo out of the certificate's DER for
any key type, and `SpkiPinTest` holds it equal to `RelayTls.spkiPin` over RSA and EC certificates.
Trust-on-first-use pairing (`RelayTls.trustOnFirstUseClient`) is OkHttp-typed and moves with
`RelayPairing` in M2c; nothing on iOS connects to a relay without either a pin or an explicit absence
of one.

## The path

**M1 — shared core (done).** `core` is multiplatform and guarded by CI.

**M2 — an iOS transport.** The `RpcTransport` interface already exists; it is the seam the
OkHttp implementation sits behind. An iOS implementation has to be written against that
interface — Ktor's Darwin engine or NSURLSession directly — covering the unary POST, the
binary download and upload channels, and the `/api/remote.mux` WebSocket with its
header/auth/cookie rules (see `RpcTransport.kt` and `RelayTls.kt` for the exact carrier
contract). The shared logic above the seam moves to `commonMain` one file at a time as the
platform APIs it leans on — `java.io` streams, `java.time`, `java.util.concurrent` atomics —
are replaced with common equivalents or `expect`/`actual` pairs. Each move is verifiable:
the JVM tests that already exist keep running against the moved code.

- **M2a (done):** the seam itself and the logic directly on top of it — see "Where this
  stands". `HarnessLaunchLine` stayed behind because it calls `HarnessSession.tokenFrom`, and
  `AutomationTiming` because its validation is `java.time`'s (`Instant.parse`, `ZoneId.of`,
  `LocalTime.parse`); swapping in `kotlinx-datetime` needs a new dependency and a check of every
  input the old code accepted, which is more than a mechanical move.
- **M2b (done):** the Ktor-backed `RpcTransport` and `MuxSocket`, the Darwin client, the iOS failure
  classification, relay pinning, and the macOS compile gate — see "Where this stands".
- **M2c (next):** the rest of the JVM-only wire files (`DshApiClient`, `Harness021Api`,
  `ReasoningTranslation`, `HarnessSession`, `RelayPairing`/`RelayTls`, `HarnessUrl`, `RpcMultipart`
  and their neighbours) follow it into `commonMain`. Each one currently leans on OkHttp types
  (`HttpUrl`, `MultipartReader`, `OkHttpClient`) or `java.*`; `HarnessEndpoint` and `SpkiPin` are
  the common replacements for the URL and pinning parts, and `HarnessSession`'s launch-token
  exchange is the other piece of the connect flow that still needs a transport-seam form.

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
  assembly, asset processing and signing are Xcode operations. The repo now has a macOS
  runner for the compile gate; the plan for the `.ipa` is the same `macos-latest` runners, the
  way `release.yml` already produces the APK on ubuntu.
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
- A CI step that can be skipped is not a gate. Check that the thing it guards actually ran.
