# Architecture

DSH Mobile is a four-module Gradle project (Kotlin 2.0, Compose, Hilt).

```
core/           Kotlin Multiplatform — shared logic in commonMain, no Android
                  imports; the JVM transport (OkHttp) and the commonmark
                  renderer live in jvmMain behind the RpcTransport interface
  wire/         the DeepSeek Harness web-client protocol:
                  envelopes (client-request / server-response), the
                  lenient WireJson codec, RpcTransport (OkHttp: unary POST,
                  the session-log download, the raw-byte file upload),
                  WsChannel (the bidirectional /api/remote.mux socket),
                  RemoteStreamMux (logical streams over it),
                  DshApiClient (typed unary methods, namespace/method),
                  ConnectionLoop (readiness handshake: mux open + the
                  $events ready frame, exponential backoff)
  wire/dto/     kotlinx.serialization ports of the harness schemas
                  (sessions, session history/follow incl. the assistant
                  stream frames, control, host, workspace, skills, goals,
                  settings, credentials, llm, subagents, agent presets,
                  events, stream protocol, commands, file uploads) —
                  lenient, merge-extensible
  session/      EventFold: raw session events → ConversationSnapshot
                  (turn/step/message/tool nodes, streaming block assembly,
                  a provisional `streaming` node for the attempt being
                  written, interruption marking, gap detection);
                  AssistantLiveState: the open model attempt as the follow
                  stream's assistant frames show it, retired by its durable
                  settlement; AssistantStream: expands the compact stream a
                  settlement or a reconnect baseline embeds
  notify/       CompletionClassifier: turn/goal/approval/question/idle
                  events with dedup keys

app/            Android UI
  connection/   HostsStore (remembered hosts + settings, DataStore),
                  DiscoveryEngine (Wi-Fi subnet sweep +
                  session/canOpenWorkspacePath probe), ConnectionManager
                  (owns the ConnectionLoop, exposes the host event flow
                  and the current generation), ConnectionService
                  (foreground service), KeepAliveWorker (15-min fallback)
  data/         SessionStore — the live mirror of the harness: session
                  list/workspaces/folds per session, queue/jobs/
                  projections, approvals/questions, subagent catalog,
                  the live assistant attempt, file uploads
  notify/       NotificationObserver — classifier → channels, dedup,
                  deep links
  termux/       TermuxBridge (one command into Termux's RunCommandService,
                  result back through a PendingIntent), TermuxScripts (the
                  start/stop/install scripts, shipped in the app),
                  TermuxHarnessController (app-scoped start/stop/install
                  state; signs in from the readiness line a start prints)
  runtime/      the built-in harness (see below): RuntimeInstaller (signed
                  manifest, resumable layer downloads, tar unpack, atomic
                  commit), HarnessSupervisor (proot process, readiness,
                  redacted log, stop sequence), RuntimeEngine (state
                  machine), HarnessRuntimeController (Hilt, Android wiring),
                  HarnessRuntimeService (foreground service, wake lock)
  update/       UpdateChecker — GitHub latest-release lookup, release assets
                  for install via Termux
  media/        AttachmentImages — LruCache + BitmapFactory decoding of
                  session attachments (no image library: the bytes arrive
                  through session/attachment, not a URL)
  ui/           theme (exact DSH design tokens + motion specs), components
                  (buttons, disclosure rows, state dots, tool cards,
                  markdown, overlays, bottom sheets, context meter),
                  screens (connect, main shell with Discord-style drawer +
                  details panel, chat, settings)

The chat surface is split by responsibility rather than living in one file:
ChatScreen (shell, pickers, send path) · ChatTopBar (two-row chrome +
Chat/Trajectory tabs) · ChatTranscript · ChatNodeItem · ToolRowModel (verb +
cwd-relative summary) · Composer (draft, attachment strip: image tiles and
file chips with upload state) · Docks · TrajectoryTab · Sheet*.kt (commands,
models, presets, subagents, permission) · ChatProjections (defensive readers).

mock-harness/   Ktor implementation of the /api protocol for tests
conformance/    JVM tests against a real harness checkout and a deterministic model
tools/capture/  Node recorder of real harness traffic → conformance fixtures
```

## Data flow

1. `ConnectionManager` performs the readiness handshake and pumps the mux;
   frames fan out as SharedFlows.
2. `SessionStore` folds session events into `ConversationSnapshot`s from that
   session's `session/follow` stream (opened with `assistantStream: true`),
   keeps the workspace registry from `workspace/follow`, and merges queue/jobs/
   projection snapshots from `session/control`. Typed projection views
   (permissions, stats, usage, context, image limits) are *derived* from
   that snapshot rather than fetched, so they stay in lockstep with the
   transcript and cost no round trips.
2a. The reply being written never enters the durable window. Its
   `assistant-stream` frames are folded by `AssistantLiveState`, whose
   transient chunks are handed to the fold beside the durable events and
   rendered as one provisional `streaming` message; the settlement event
   (`assistant/message` or `assistant/attempt`) retires it the moment it
   lands. A reconnect seeds the state from the snapshot's baseline, so a
   partial answer survives the socket.
2b. On first connect `baseline()` resolves a landing session
   (`data/InitialSession.kt`): the session last opened on this harness,
   else the most recently active one. Reconnects keep whatever was open.
3. Screens observe `StateFlow`s and render; user actions go back through
   `SessionStore` → `DshApiClient` (`POST /api/<namespace>/<method>`), and
   pending approvals and foreground questions are answered through `$events/result`;
   continued questions use `userQuestions/answer`. A
   picked file is streamed to `/api/session/uploadFileBinary` as soon as it is
   picked, and the message cites the receipt that came back.
4. `NotificationObserver` classifies host events into completion events and
   posts channel-notifications that deep-link into sessions. Turn and goal
   completions reach it from `SessionStore`, which owns the only stream
   they travel on.

## Theme, appearance and touch

- **One palette object, two schemes.** `ui/theme/Color.kt` holds the raw Ink (dark) and Paper
  (light) scales; `ui/theme/Theme.kt` exposes the semantic `DsColors` roles through
  `LocalDsColors`/`DsTheme.colors`. Screens never read primitives directly, so a token change
  restyles the whole app — that is what made the Ink redesign a theme swap plus a component pass
  instead of twenty-one independent restyles.
- **The appearance is user data, not a build constant.** `ThemeCustomization` (scheme, accent,
  OLED canvas, app text size, haptics) is assembled in `AppRoot` from `AppSettings` and handed to
  `DshTheme`. Accent palettes carry a dark and a light ramp; AMOLED only moves the canvas rungs;
  text size is applied by scaling the local `Density.fontScale` (so every `sp` and the user's
  system font scale compose); haptics are gated through `LocalHapticsEnabled`, which
  `rememberDsHaptics()` reads.
- **Persistence follows the settings recipe** in `connection/HostsStore` (Keys → mapper →
  `setSetting`). `themePreference` is additionally mirrored to `SharedPreferences` so
  `DshApplication.onCreate` can set AppCompat night mode before any activity; the window
  background resources (`values/colors.xml`, `values-night/colors.xml`) follow the same scheme so
  a language-driven recreate never flashes the wrong colour.
- **Haptics are a vocabulary, not a buzz.** `DsHaptics` maps tap / toggle / selection / confirm /
  reject / attention / long-press to distinct platform effects (API-aware fallbacks), and every
  control that matters calls the matching one. Disabling haptics in Appearance silences all of it.
- **Motion is tokenised.** `DsAnimations` carries the kit's curves (standard, decelerate,
  overshoot), screen/sheet/card/list transitions and the 0.97 press scale. Compose honours the
  system animation scale; loops only run for live states (streaming, shimmer while loading).
- **Contrast is part of the token contract.** Status colours that appear at caption sizes use the
  text-tuned light-scheme values (`success`/`error` differ from their fill variants); the Paper
  accents were chosen to pass AA on the warm canvas. See `.team/design-recon/SPEC.md` for the
  measured table that the implementation follows.
- **Non-Latin text falls back.** Geist/Geist Mono are bundled as Latin subsets in `res/font`; the
  platform fallback carries the other ten shipped languages, and every layout is verified for
  RTL (start/end alignment, auto-mirrored icons) and large font scales.

## Built-in harness runtime

The app can run the DeepSeek Harness on the phone itself, without Termux. The harness
listens on `127.0.0.1:<port>` and the existing loopback mode ("This phone") talks to it like
any other local harness. The runtime is built and published by
[`sorsama/dsh-mobile-runtime`](https://github.com/sorsama/dsh-mobile-runtime); its README is the
contract (manifest schema, unpacking rules, proot command line).

- **What ships in the APK**: only proot and its loader (`jniLibs/<abi>/libproot.so`,
  `libproot-loader.so`, 64-bit ABIs only, provenance in `app/third_party/proot`). They are
  extracted to `nativeLibraryDir` (legacy packaging), the one app-owned place a targetSdk 29+
  app may execute from. A device without `arm64-v8a` or `x86_64` reports `NotSupported`.
- **What is downloaded**: two layers per ABI, a Debian base with Node.js and the harness, as
  zstd-compressed GNU tar files described by a signed `manifest.json`. The manifest signature
  (Ed25519, key pinned in `ManifestVerifier`) is checked before the manifest is parsed; every
  layer is checked against the manifest's size and SHA-256 before it is unpacked. Downloads
  resume with HTTP `Range` and live in the cache directory.
- **Where it lives**: `noBackupFilesDir/runtime/` (see `RuntimeLayout`): one directory per
  layer, `home/` (bound over the guest's `/root`, where the harness keeps its state),
  `workspaces/` (bound at `/workspaces`), per-start files in `run/`, the log in `logs/`.
  `installed.json` is the commit record, written last with an atomic rename; a layer
  directory it does not name is deleted. Updates never touch `home/` or `workspaces/`.
- **Updates**: only layers whose id or SHA-256 changed are fetched. Every update goes on trial:
  the replaced layers stay until the new release has printed its readiness line once; if it
  never does, the whole previous release (both layers, release id, command line) is put back
  and started (`UpdateOutcome.RolledBack`). "Go back" installs the manifest's `previous`
  release after verifying it with the same key; `ManifestPolicy.decide(allowOlder)` accepts a
  lower sequence only on that path. `RuntimeUpdateCoordinator` (plain Kotlin, wired by
  `RuntimeUpdates`) decides when to check (on app open at most every four hours, and a daily
  WorkManager job, `RuntimeUpdateWorker`, scheduled only while a runtime is installed), when to
  notify (once per release, on the "Harness updates" channel), and when an automatic install
  may run (opt-in, unmetered network, nothing in a turn, conditions held for 30 s). A release
  the person went back from, or one that already failed to start here, is never installed by
  itself. The control page asks before a hand-started update on a metered network.
- **App updates are separate.** The app's own update check (`UpdateChecker`) and the in-app
  updater (`update/InAppUpdater`: download, SHA-256 against `SHA256SUMS.txt`, signer and version
  checks, `PackageInstaller` session) know nothing about the runtime. Their optional daily
  background check rides on `KeepAliveWorker`; their notification has its own "App updates"
  channel. A harness release that needs a newer app sends the person to the app's update check,
  so the in-app updater offers the install.
- **Commands on a phone**: Android allows no kernel sandbox, so the runtime adds two presets
  (README "Shell commands and permissions on Android"). `CommandPermission` is the person's
  choice of default ("Run it" is `danger-full-access`, "Ask me first" is `ask-commands`),
  passed as `DSH_RUNTIME_DEFAULT_PERMISSION` on the next start, only when the installed manifest
  has `runtime.permissions`. `InstalledRuntime.commandPermissionsVersion` carries its version;
  below 2 (approval text cut at 2,000 characters) the control page says to update before relying
  on "Ask me first". In `:core`, `permissionDenialOf` reads a gate denial from the tool result's
  `error.code` (falling back to the version 1 sentence for the subagent case only), and
  `sandboxRefusalShown` recognises the sandbox refusal by `SANDBOX_UNAVAILABLE`, or on the
  built-in harness by the README's documented sentence when the harness dropped the code.
- **Models and API keys**: the screen (`ui/screens/models`, flows in `:sharedUi` `models/`) talks
  only to the connected harness (`llm/*`, `settings/*`, `credentials/*`). The key lives in the
  field's composition and is handed to one `credentials/set` call; the harness stores it in its
  home (`/root/.dsh` on the built-in harness).
- **Running**: proot is started through `/system/bin/sh -c "echo $; exec ..."` so the first
  output line is its pid. The base is the root; the harness layer, `home/`, `workspaces/`,
  a `resolv.conf` written from the active network's DNS and a `/proc/stat` with the real CPU
  count are bound in. The supervisor waits for the readiness line, then `LoopbackSignIn`
  (shared with the Termux path) exchanges its token for the session cookie. The token never
  reaches the log file or logcat.
- **Stopping**: proot ignores SIGTERM, so `Process.destroy()` does nothing. The supervisor
  sends SIGTERM to the guest's main process (node), then SIGQUIT to proot after 5 s, then
  SIGKILL to everything left. Before each start it stops any proot of this uid left by an
  earlier app process, then checks that the port is free.
- **Process and power**: `HarnessRuntimeService` (type `specialUse`) keeps the process in the
  foreground while the runtime installs or runs and holds a partial wake lock while it does
  (`HarnessRuntimeController.setHoldWakeLock` turns it off). Everything runs in the app
  process; there is no second process. Both foreground services (this one and the
  keep-connected `ConnectionService`, also `specialUse`) are asked for through
  `RuntimeServiceStarter`: `startService` while an activity is visible (no foreground clock),
  `startForegroundService` otherwise, one request at a time, and a refusal by Android 12+
  background limits retried when the app is next visible. Each service calls `startForeground`
  first in `onCreate` and `onStartCommand`. `ConnectionService` was `dataSync` until 0.22.0;
  it moved to `specialUse` so Android 15's six hours a day limit (and its `onTimeout`) no
  longer applies.
- **UI**: the controller exposes `StateFlow<RuntimeState>` (types in `:sharedUi`).
  `BuiltInHarnessHost` (app-scoped) adds the set-up flow on top: install then start, a
  download that broke off with the network resumed when it comes back, a cancel that deletes
  partial downloads, and the latest release's sizes read before anything is downloaded. It is
  the Android side of the shared `BuiltInHarness` port; iOS and 32-bit devices pass
  `NoBuiltInHarness`, and the Connect screen is then what it was before. What each state shows
  and which one action it offers is plain code in `:sharedUi` (`RuntimePresentation.kt`, tested
  in `commonTest`): the Connect card, the set-up page's step tracker, the failure-to-fix mapping,
  auto-start and whether "Use Termux instead" starts open. The screens are in `:app`
  (`ui/screens/runtime`): the card in "This phone" mode, the set-up page and the control page
  (also reached from Settings, "Harness on this phone"). The controller never connects; the
  Connect screen's own "connect when ready" rules do, the same as after a Termux start.
- **First run**: with no mode stored, `firstRunConnectMode` opens the Connect screen on "This
  phone" when the phone can run the built-in harness (or has Termux) and remembers no harness;
  Local network otherwise. A stored mode always wins.
- **Settings of its own**: `RuntimePreferences` keeps the wake-lock switch and "fast start"
  (the runtime's module pack; off sets `DSH_RUNTIME_MODULE_PACK=0` in the guest on the next
  start). Starting the harness when the app opens is an app setting beside Termux's
  (`AppSettings.autoStartBuiltInHarness`).
- Debug builds can drive the controller from adb through `RuntimeDebugActivity` (`app/src/debug`).

### Projects (the `workspaces` folder)

`runtime/workspace/` is everything about the person's project files on the built-in harness.

- **`WorkspaceTree`** (plain Kotlin over `FsOps`) is the folder as documents: id mapping,
  listing, MIME types (`MimeTypes`), create, rename, copy, move, delete, search and recent
  files, with the symlink and escape rules in its class comment. **`WorkspacesProvider`** is the
  `DocumentsProvider` over it (cursors, file descriptors, `FileObserver` change notifications for
  folders being looked at). It is disabled in the manifest and enabled at start on devices that
  can run the runtime; its root is listed once a runtime is installed.
- **`GuestCommandRunner`** runs one program inside the runtime for the app itself, as an
  argument list (`ProotCommand.buildGuest`: the harness's binds, a clean environment, umask 022),
  with streamed and redacted output, a timeout and cancellation that kills the process tree.
  `HarnessRuntimeController.runGuestCommand` is the only door; `RuntimeEngine` refuses an
  install, update, go-back or uninstall while one runs, and the supervisor's stale-proot sweep
  leaves these proots alone. Today it runs `git clone` (`GitClone` builds the arguments and reads
  git's progress). The clone uses this rather than the harness's API because it must work with the
  harness stopped, needs no session, and takes an argument list with no shell anywhere.
- **`ProjectOperations`** (app-scoped) is the one long project operation at a time (clone,
  import a folder or a zip, share as zip, save a copy) with progress, cancel and clean-up, plus
  the project list (`ProjectList`), new empty projects, rename and delete. Imports and clones are
  built in a `.dsh-incoming-*` staging folder and renamed into place. `ZipImporter` and
  `ProjectTransfer` are plain Kotlin and unit-tested.
- **UI** (`ui/screens/runtime/ProjectsPage.kt`, `ProjectDialogs.kt`): the Projects page (from the
  control page and, on the built-in harness, from the chat list's menu), the four ways to start a
  project in the new-session dialog (which then opens a session in it), and Files, zip and copy
  for a project's workspace in the workspace list. Nothing of this shows on any other harness.

## Key invariants

- The wire layer never crashes on unknown data: unknown keys are ignored,
  unknown event/frame/card types fall back to `Unknown*` passthroughs.
- HTTP status is carrier-only; business failures arrive as `ok: false`
  with a typed, namespaced error code (see `docs/PROTOCOL.md`).
- The mux socket is **bidirectional** — the client opens and cancels
  logical streams on it. (Its two predecessors were downlink-only.)
- Every `/api` request needs a harness browser session; 401 and 403 are
  different facts and are reported separately.
- There is no loopback-only method tier: harness 0.1.2 deleted it, and one
  authenticated caller reaches the whole API (see `docs/COMPATIBILITY.md`).
- Every remembered host belongs to exactly one connect mode
  (`HostConfig.belongsTo`), and auto-connect never crosses modes. A loopback
  host is probed with its stored cookie; an anonymous probe of a 0.1.2+ harness
  can only ever answer 401.
- Nothing runs in Termux without a tap having asked for it, and the app never
  issues a Termux command from the background.
- Tool cards are derived in the app from raw call/result data; the host
  sends no render intent.
- Transient assistant rows never touch the durable cursor, never count as a
  gap, and are never paged; only the settlement is history.
- Protocol baseline: harness `0.2.1-alpha.1` (`core.DshCore.PROTOCOL_BASELINE`).
  `DshCore.PROTOCOL_COMMIT` pins `5badb15009ae1756c3afe0ae0cef1faafc290ccc`;
  `core/src/test/resources/protocol/5badb15.json` carries the same baseline.

## 0.13.0 state and management

- `HarnessUrl` resolves routes under a host's configured proxy path. `HostConfig`
  and `HostsStore` preserve that path; host identity includes the scheme and path.
- `Harness021Api` supplies Automation, plugin management and session-reference calls.
  `HarnessFeatures` edits existing tasks with their observed storage record and stages
  new-task drafts. `PluginManagerScreen` exposes package and plugin controls.
- `SessionStore` owns `QuestionSessions` and `PluginOperations`, so closing a screen
  does not discard a live question claim or restart an installation. Question projections
  determine whether a call is open, continued or settled. Install request IDs persist
  for recovery through `waitForInstall`; a missing result stays unknown.
- `ComposerRepository` saves text and structured references in local preferences,
  keyed by host and session. `ModernQuestions` separately saves answer drafts by host,
  session and call ID. Pending attachments and preview-panel state remain in memory.
- `PartialArguments` extracts display text from incomplete tool-call JSON.
  `DocumentFrontmatter` uses bounded, safe YAML parsing for Markdown previews.
