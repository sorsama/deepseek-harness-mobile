<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile, the DeepSeek Harness on your phone" width="100%">
</p>

<h1 align="center">DSH Mobile</h1>

<p align="center">
  An Android app for driving a <a href="https://github.com/deepseek-ai/deepseek-harness">DeepSeek Harness</a> from your phone.
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <b>English</b> ·
  <a href="README.zh-CN.md">中文</a> ·
  <a href="README.hi.md">हिन्दी</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.th.md">ไทย</a>
</p>

The DeepSeek Harness is an AI coding agent that runs on your computer and has a web interface.
DSH Mobile connects to that harness and gives you the same controls in a native Android app. You can
start a task at your desk, then follow it, answer its questions and approve its commands from
your phone.

This is an unofficial, community-built app. It needs a harness running somewhere it can reach: on
your computer, or on the phone itself through Termux.

The [project site](https://dshm.zyphite.com) has a one-page overview, and the
[wiki](https://github.com/sorsama/deepseek-harness-mobile/wiki) has the full user guide.

## Screenshots

| Connect | Chat | Trajectory | Details |
|:--:|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="200" alt="Connect screen with the harness address filled in"> | <img src="docs/images/chat.png" width="200" alt="A reply streaming in while the top bar says Working and the stop button is showing"> | <img src="docs/images/trajectory.png" width="200" alt="Trajectory tab listing every tool call of the first turn"> | <img src="docs/images/session-info.png" width="200" alt="Details panel with the session, its model, context use and activity"> |
| Paste the link the harness prints, and you are connected and signed in. | Replies stream in. Tool calls fold into one row you can open. | Every tool call, turn by turn. | The session's model, context, goal, plan mode and jobs. |

| Subagents | Terminal | Sessions | Preview |
|:--:|:--:|:--:|:--:|
| <img src="docs/images/subagent.png" width="200" alt="A subagent's transcript with its findings and a box for a follow-up message"> | <img src="docs/images/terminal.png" width="200" alt="A terminal on the harness computer showing a passing test run"> | <img src="docs/images/sessions.png" width="200" alt="The sessions drawer grouped by project, with a drag handle on every row"> | <img src="docs/images/preview.png" width="200" alt="A README in full-screen preview, drawn like GitHub draws it, with badges, a table and a code block"> |
| Read what a subagent did and send it a follow-up. | A real shell on the harness computer. | Group sessions by project and drag them into your own order. | A README drawn the way GitHub draws it, on its own screen. |

| Appearance | Color picker | Light theme | Dark theme |
|:--:|:--:|:--:|:--:|
| <img src="docs/images/appearance.png" width="200" alt="Appearance settings with a custom accent color, text size and the haptics options"> | <img src="docs/images/accent-picker.png" width="200" alt="The custom color picker with a hue strip, a brightness area and a hex field"> | <img src="docs/images/light.png" width="200" alt="The chat in the light theme"> | <img src="docs/images/dark.png" width="200" alt="The chat in the dark theme"> |
| Seven accent colors or any color you like, and haptics you can try. | Pick a color with the strip and the area, or type a hex code. | Light, with a warm paper look. | Dark, or follow the system. |

## What you can do

- Chat with the agent. Replies stream in with their reasoning, code blocks and tool calls. You
  can attach photos and files, reference files with `@`, and run commands and skills with `/`.
- Press and hold a message, a tool call or a file to see what else you can do with it. Copy
  it, fix a message you sent and send it again, or mention a file in your next message.
- Answer what the agent needs from you: approve or reject a command, answer its questions, and
  review a plan before it starts.
- Keep an eye on long work. Goals, todo lists, queued messages, background jobs and subagents
  each have their own view, and you can pause, steer or stop them.
- Keep your sessions the way you like them. Drag them into your own order, or group them by
  project and fold the groups you are not using.
- Find things with one search field that works the same everywhere: sessions, models, commands,
  plugins, scheduled tasks and workspace files.
- Get a notification when a turn finishes, a goal completes or gets blocked, or the agent is
  waiting for an answer. The connection stays up in the background.
- Browse and preview the workspace files, and open a terminal on the harness computer.
- Open files full screen in Preview: CSV tables, JSON trees, notebooks, PDFs, pictures, video,
  audio and archives, with find in file. Save a copy puts any file on your phone.
- Manage the harness itself: models, agent presets, plugins, scheduled tasks and archived
  sessions.
- Make it yours. The Ink design comes in light and dark, with accent presets or a colour of your
  own, true black for OLED screens and a text size you can change.
- Use the app in English, Chinese, Hindi, Spanish, French, Arabic, Bengali, Portuguese,
  Russian, Urdu or Thai. Right-to-left languages are supported.

The [changelog](CHANGELOG.md) lists what changed in each release.

## Getting started

### 1. Install the app

Download the APK from the [latest release](https://github.com/sorsama/deepseek-harness-mobile/releases/latest)
and install it. You need Android 8.0 or newer.

### 2. Make the harness reachable

The harness only listens on its own computer by default. Pick the setup that matches where your
phone will be.

#### Same Wi-Fi as the computer

Start the harness on the computer's local network address. On
harness 0.2.1-alpha.2 and newer:

```sh
dsh web --host 192.168.1.20
```

Use your computer's real address instead of `192.168.1.20`. The harness prints a line like
`dsh web: http://192.168.1.20:3080/?token=…`. In the app, choose **Local network**, paste that
whole link into the address field (or tap the paste button), and tap **Connect**. The token in the
link signs the phone in, and the harness then appears under **Recent** for next time. Older harness
versions need a small patch instead of `--host`; see [`harness/README.md`](harness/README.md).

#### Away from home

Install [dsh-relay](https://github.com/sorsama/deepseek-harness-relay), a
harness plugin that adds a password, encryption and device pairing. Then choose **Relay** in the
app and scan the pairing QR code. The [Relay wiki page](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay)
walks through it.

#### USB cable or emulator

Run `dsh web` on the computer and `adb reverse tcp:3080 tcp:3080`. In
the app, choose **This phone** and sign in with the link `dsh web` printed.

#### On the phone itself

The harness can run in [Termux](https://f-droid.org/packages/com.termux/).
Choose **This phone**, and the app can start and stop the harness and sign in for you. Setting up
Node in Termux does not work on every device yet; read [`harness/TERMUX.md`](harness/TERMUX.md)
first.

#### Your own HTTPS reverse proxy

Paste its `https://` address in **Local network** mode. The proxy
encrypts the connection, but it does not check who is connecting. See
[`harness/README.md`](harness/README.md).

### 3. Start working

Pick a session from the list (swipe in from the left edge) or start a new one. If a connection
fails, the app says why, and the wiki's
[troubleshooting page](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting) is
organized by those messages.

## Security

Read [docs/SECURITY.md](docs/SECURITY.md) before you connect over a network. A signed-in phone can
do anything the agent can do, and the agent runs commands on your computer. Treat signing in like
giving out shell access.

- Local network mode checks the sign-in token but does not encrypt anything. Use it only on a
  network you trust.
- Relay mode adds encryption and a pinned certificate, and works from outside your network.
- Keep the `dsh web` link private. Anyone who has it can sign in until the harness restarts.

## Compatibility

DSH Mobile is tested against **DeepSeek Harness 0.2.2-rc.1**. Older harness versions back to
0.1.6 still work for the most part, but newer features need a newer harness. The
[compatibility table](docs/COMPATIBILITY.md) lists which app version goes with which harness.

## Building from source

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK, signed when the keystore environment is set
./gradlew :core:jvmTest :app:testDebugUnitTest
```

To check the app against a real harness, build a harness checkout and run the conformance suite:

```sh
DSH_HARNESS_SRC=/path/to/deepseek-harness ./gradlew :conformance:test
```

Release builds take their version from the git tag. [CONTRIBUTING.md](CONTRIBUTING.md) covers the
development loop, the module layout and the release steps.

| Path | Contents |
|---|---|
| `app/` | The Android app: screens, connection, notifications, translations |
| `core/` | Plain Kotlin protocol code: wire types, RPC and stream clients, reconnect logic, the session event fold |
| `conformance/` | Tests that start a real harness and check every call the app makes |
| `mock-harness/` | A small fake harness server for tests |
| `tools/capture/` | Records real harness traffic as test fixtures |
| `harness/` | Setup guides for local network and Termux |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [protocol notes](docs/PROTOCOL.md), [compatibility](docs/COMPATIBILITY.md), [security](docs/SECURITY.md) |

The relay plugin lives in its own repository,
[sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay).

## License

[MIT](LICENSE). Third-party material bundled with the app is listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). DeepSeek Harness and its brand belong to their
owners. This project is not affiliated with them.
