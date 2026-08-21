<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — the DeepSeek Harness in your pocket" width="100%">
</p>

<h1 align="center">DSH Mobile — DeepSeek Harness Remote</h1>

<p align="center">
  An open-source Android companion that puts your <b>DeepSeek Harness</b> in your pocket.<br>
  Drive sessions, review plans and goals, answer approvals and questions, and get notified
  when the harness finishes — from your phone, over your local network.
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

DSH Mobile is an **unofficial companion app** for the
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT), mirroring its web GUI
feature-for-feature in the harness's own visual language. Android only, Kotlin + Jetpack Compose.

Its companion on the other end is
[**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay) — a harness plugin that adds the
authentication layer the harness says it lacks, so this app can reach a harness with a real
credential and a pinned key instead of an open port. See
[Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay).

**[dshm.zyphite.com](https://dshm.zyphite.com)** is the project site — what the app is, what it
looks like, and how to get it running, on one page.

The [**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) is the user-facing guide:
[getting started](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started),
[connecting](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting),
[troubleshooting](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting),
a [feature tour](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour) and an
[FAQ](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ).

---

## Screenshots

| Connect | Chat | Trajectory |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="Connect screen: recent harnesses with live reachability, discovery, manual entry and auto-connect toggles"> | <img src="docs/images/chat.png" width="240" alt="Chat: streamed turns with per-tool icons, tool cards, goal dock and composer"> | <img src="docs/images/trajectory.png" width="240" alt="Trajectory: a per-turn ledger with usage totals"> |
| Recent harnesses with live reachability, LAN discovery, manual `host:port`, auto-connect. | Streamed turns, a glyph per tool, expandable tool cards, permission picker. | The same session as a per-turn ledger with usage totals. |

| Session details | Subagents |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="Details panel: context breakdown, goal, plan mode, jobs, queue, subagents, host information"> | <img src="docs/images/subagent.png" width="240" alt="Subagent catalog with continuable children"> |
| Context breakdown, goal, plan mode, background jobs, queued turns, host info, session-log export. | The subagent catalog — open a child's transcript, follow up, or interrupt it. |

## Features

- **Connect effortlessly** — auto-discovers a harness on your Wi-Fi (active subnet scan +
  readiness handshake), remembers hosts and probes them for liveness on the way in, supports
  manual `host:port` entry, loopback for same-device setups, and auto-connect toggles
  (last used / LAN / same device).
- **Discord-style navigation** — swipe right from the left edge to open the workspace-grouped
  chat list, swipe left to close it, swipe left from the right edge for the session details panel.
- **Full chat experience** — streamed turns with reasoning disclosure, markdown,
  terminal/diff/read/search/web tool cards, queue dock (edit / remove / steer), history paging,
  image attachments.
- **Slash commands and skills** — the composer adjudicates a `/` line against the session's own
  command catalog and runs it through the harness's command gateway; anything the catalog does not
  claim is sent as a prompt, which is how skills are invoked.
- **Everything the GUI does** — goals (phases, rounds, pause/resume/edit), plan mode + plan review,
  permission approvals, user questions, todo dock, subagents (catalog, follow-ups, interrupt),
  background jobs, workflow runs, skills, model selection, agent presets, session search,
  trajectory ledger, session export, message feedback.
- **Notifications** — turn complete, goal complete / blocked, review or question waiting for you;
  background connection via a foreground service.
- **Looks like the harness** — the exact DeepSeek Harness design tokens (colors, type, radii,
  disclosure rows, shimmer, ink buttons) with light / dark / system themes.
- **11 languages** — English, 中文, हिन्दी, Español, Français, العربية, বাংলা, Português, Русский,
  اردو, ไทย (RTL aware).

## Requirements

- Android 8.0+ (minSdk 26).
- A running [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
  (tested against `0.1.1-rc.2`).

## Quick start

1. Install the latest APK from
   [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest).
2. Open the app and choose how to connect. The two are not variations of one
   setting — pick the one that matches what you set up on the computer.

   **Relay** — encrypted, authenticated, and works from outside your Wi-Fi.
   Install [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) into
   the harness web profile:

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   Open the printed URL **on that computer**, set a password, then open
   `/relay/pair`. In the app: **Relay → Pair a relay**, scan the QR. Once every
   client you use has paired, turn off the relay's `compat.addressGrants` —
   nothing here needs it.

   **Local network** — no setup on the phone, no authentication at all. Apply
   the one-file LAN patch in [`harness/README.md`](harness/README.md), restart
   `dsh web`, then tap **Scan network**. Only on networks you trust.

   **Behind your own HTTPS reverse proxy** — paste the `https://` address into
   local-network mode. The proxy can forward to loopback, so the harness needs
   no patch; but it encrypts the link without authenticating anyone. See
   [`harness/README.md`](harness/README.md).

   **USB / emulator** — `dsh web`, then `adb reverse tcp:3080 tcp:3080`, and
   connect to `127.0.0.1:3080` in local-network mode. No patch needed.
3. Pick a session, chat, and get notified when the harness is done.

If a connect attempt fails, the app names the cause; the wiki's
[Troubleshooting](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting) page is
keyed on that exact sentence.

## Compatibility & security

- See [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the harness version matrix and
  loopback-only surfaces.
- **Read [docs/SECURITY.md](docs/SECURITY.md) first.** The bare harness has no authentication, so
  local-network mode is for trusted networks only — the app says so on the connect screen for the
  same reason. Relay mode adds a real credential and a pinned certificate, but authenticating still
  grants the same power as a shell on that computer, because the agent runs commands there.

## Building

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (signed when keystore env is set)
```

The shipped version comes from the git tag: the release workflow exports `DSH_VERSION_NAME` from
the tag name, and `versionCode` is derived from it. A local build falls back to the literal in
`app/build.gradle.kts`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development loop against a real harness, the module
layout, and the release workflow.

## Repository

| Path | What |
|---|---|
| `core/` | Pure-JVM protocol core: wire DTOs, RPC client, WebSocket downlinks, reconnect loop, session folding, notification classifier |
| `app/` | Android UI: screens, discovery/connection, foreground service, notifications, i18n |
| `mock-harness/` | Ktor mock of the harness `/api` server for tests |
| `tools/capture/` | Records real harness traffic into conformance fixtures |
| `harness/` | Companion patch + guide for LAN mode |
| — | The relay itself lives in [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [protocol notes](docs/PROTOCOL.md), [compatibility](docs/COMPATIBILITY.md), [security](docs/SECURITY.md) |

## License

[MIT](LICENSE). Bundled third-party material is listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The DeepSeek Harness and its brand are property
of their respective owners; this project is an independent, community-built remote.
