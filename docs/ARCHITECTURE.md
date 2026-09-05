# Architecture

DSH Mobile is a three-module Gradle project (Kotlin 2.0, Compose, Hilt).

```
core/           pure JVM — no Android imports
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
   pending approvals/questions are answered through `$events/result`. A
   picked file is streamed to `/api/session/uploadFileBinary` as soon as it is
   picked, and the message cites the receipt that came back.
4. `NotificationObserver` classifies host events into completion events and
   posts channel-notifications that deep-link into sessions. Turn and goal
   completions reach it from `SessionStore`, which owns the only stream
   they travel on.

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
- Tool cards are derived in the app from raw call/result data; the host
  sends no render intent.
- Transient assistant rows never touch the durable cursor, never count as a
  gap, and are never paged; only the settlement is history.
- Protocol baseline: harness `0.1.3-alpha.1` (`core.DshCore.PROTOCOL_BASELINE`).
