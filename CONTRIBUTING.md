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

- `core/` — pure JVM: wire protocol (DTOs, RPC client, WebSocket downlinks,
  reconnect loop), session event folding, notification classifier.
- `app/` — Android UI: Compose screens, connection/discovery, foreground
  service, notifications, i18n.
- `mock-harness/` — Ktor mock of the harness `/api` server for tests.
- `tools/capture/` — Node script that records real harness traffic into JSON
  fixtures for conformance tests.

## Conventions

- Kotlin official code style; one file per screen/component where sensible.
- The wire layer parses leniently (unknown keys/events/tool cards must fall
  back, never crash).
- UI strings live in `values*/strings.xml` only — never hardcode.
- Pull requests need CI green (unit tests + lint + assemble).

## Release

Tag `v*` → the Release workflow builds and publishes a signed APK (when
signing secrets are configured) or an unsigned APK.
