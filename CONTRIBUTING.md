# Contributing

Thanks for your interest in DSH Mobile!

## Setup

- Android Studio (Koala or newer) with JDK 17+ and Android SDK 35.
- `./gradlew :app:assembleDebug` builds the debug APK.

## Development against a real harness

1. Run the DeepSeek Harness: `dsh web` on your computer (default port 3080).
2. Device/emulator via USB: `adb reverse tcp:3080 tcp:3080`, then pick
   **This phone** in the app (it probes `127.0.0.1:3080`). Or run the harness
   in Termux on the device itself — see `harness/TERMUX.md`.
3. Wi-Fi LAN mode: apply `harness/cordis.patch.lan.yml` as described in
   `harness/README.md`.

## Repository layout

- `core/` - Kotlin Multiplatform: the wire protocol (DTOs, RPC client, WebSocket
  downlinks, reconnect loop), session event folding and notification classifier build
  from `commonMain` for Android and iOS; the OkHttp transport and commonmark renderer
  stay in `jvmMain`. iOS targets are compiled on CI, not on a Windows desktop.
- `app/` — Android UI: Compose screens, connection/discovery, foreground
  service, notifications, i18n.
- `mock-harness/` — Ktor mock of the harness `/api` server for tests.
- `tools/capture/` — Node script that records real harness traffic into JSON
  fixtures for conformance tests.

## Conventions

- Kotlin official code style; one file per screen/component where sensible.
- The wire layer parses leniently (unknown keys/events/tool cards must fall
  back, never crash).
- UI strings live in `values*/strings.xml` only — never hardcode. A screen that has moved to `:sharedUi` (the
  Connect screen so far) reads its strings from `sharedUi/src/commonMain/composeResources/values*/strings.xml`
  instead, in the same 11 languages; `docs/IOS.md` ("Strings") has the rule and `tools/shared-strings/sync.mjs`.
- Pull requests need CI green (unit tests + lint + assemble).

## Windows and system bars

The app draws edge to edge, so every window has to pad for the status bar, the gesture or
navigation bar and the keyboard itself. Two windows got this wrong in 0.19.0 (the Automations
footer and the Terminal key row sat under the gesture bar on a real phone), because a plain Compose
`Dialog` is laid out *inside* the system bars on Android 15 and newer while its content is measured
at the full display height.

- A full-screen page or a viewer: **`DsFullScreenDialog`** (`ui/components/DsFullScreen.kt`).
- A centred confirmation, a rename, a small form: **`DsDialog`**.
- A picker or a list of choices from the bottom: **`DsBottomSheet`**.
- Never call `Dialog(`, `DialogProperties(`, `AlertDialog(`, `Popup(` or `ModalBottomSheet(`
  directly, and never write `decorFitsSystemWindows` anywhere else. `WindowRulesTest` scans
  `app/src/main/java` and fails the build with the file, the line and the component to use.
- Anything pinned to the bottom of a screen (a footer, a key row, a composer) must sit inside the
  insets, not under them. Check it on the emulator with **both** gesture navigation
  (`adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural`) and three-button
  navigation (`...navbar.threebutton`), on an API 35 or newer image. Unit tests and lint cannot see
  this kind of bug.

## Release

Tag `v*` → the Release workflow builds and publishes a signed APK (when
signing secrets are configured) or an unsigned APK.
