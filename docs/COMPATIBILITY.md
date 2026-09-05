# Compatibility

DSH Mobile speaks the DeepSeek Harness **web client protocol** (the JSON-RPC
surface the harness GUI itself consumes over `/api`). That protocol is internal
to the harness and is not versioned on the wire, so this app pins a protocol
baseline: the harness release its DTOs and call shapes were ported from and
checked against.

| DSH Mobile | Harness version | Status |
|---|---|---|
| 0.10.0 | 0.1.3-alpha.1 | Supported baseline |
| 0.9.3 | 0.1.2-alpha.1 | Previous baseline — no streaming on 0.1.3, commands refused |
| 0.9.2 | 0.1.2-alpha.1 | Cannot send messages |
| 0.9.1 | 0.1.2-alpha.1 | Cannot send messages |
| 0.9.0 | 0.1.2-alpha.1 | Cannot send messages |
| 0.8.0 | 0.1.1-rc.2 | |
| 0.7.0 | 0.1.1-rc.2 | |
| 0.6.0 | 0.1.1-rc.2 | |
| 0.5.0 | 0.1.0-rc.8 | |
| 0.4.0 | 0.1.0-rc.7 | |
| 0.1.0 – 0.3.1 | 0.1.0-rc.5 | |

**0.10.0 and 0.9.x do not interchange.** A 0.9.x app connects to a 0.1.3
harness and reads history, but it never sees a reply being written — session
format v2 logs no per-token events and the app does not ask for the live
stream that replaced them — and every slash command is refused, because the
argument that carries attachments was renamed. A 0.10.0 app against a 0.1.2
harness is the mirror image: it asks `session/follow` for a stream 0.1.2 does
not know and sends `commands/execute` an argument 0.1.2 does not declare.
Upgrade both, or neither.

**0.9.0 does not speak the 0.1.1 protocol** either; that break was at the
handshake rather than partway through a session (see 0.9.0 in the changelog).

## Relay

Reaching a harness through [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay)
is a separate contract with its own version, because the relay is a plugin
mounted beside the harness rather than part of it.

| DSH Mobile | [dsh-relay](https://github.com/sorsama/deepseek-harness-relay) | Notes |
|---|---|---|
| 0.10.0 | 0.2.1 | Pairing payload `v: 1`; mDNS TXT `v: 1`. File uploads need the relay to proxy `/api/session/uploadFileBinary`, or the app falls back to the `fileUploads/upload` Remote |
| 0.9.2 – 0.9.3 | 0.2.1 | |
| 0.9.1 | 0.2.1 | |
| 0.9.0 | 0.2.0 | |
| 0.8.0 | 0.1.1 | |

A relay older than 0.2.0 cannot serve a 0.1.2 or later harness at all. The
harness authenticates its whole `/api` surface against a browser-session
cookie, and the relay deliberately strips the client's `Cookie` header before
forwarding — so every proxied request is answered 401 until the relay supplies
a harness session of its own. That is a relay change, not an app one.

Both versions are checked, unlike the harness baseline. A pairing payload is a
credential exchange, so a `kind` other than `dsh-relay-pair` or a `v` above the
one this build understands is refused outright rather than degraded — see
`docs/PROTOCOL.md`. Everything *behind* the relay is the harness protocol
unchanged, so the version policy below applies to it exactly as before.

The baseline is one constant — `DshCore.PROTOCOL_BASELINE` in
`core/src/main/kotlin/com/labteto/dshmobile/core/DshCore.kt` — and the app shows
it in Settings → About next to its own version.

## Version policy

The baseline says what was tested. It is not a gate, and the app does not warn
when it reaches a harness built from a different commit — it could not say what
would break if it did. The harness releases far more often than this client.

Through 0.1.1 the app could at least *read* the harness's version, from
`host.describe`. It cannot any more: 0.1.2 removed that call and publishes no
version anywhere on the wire. So the connect list, the details panel and
Settings → Harness show the host's home directory — the one host fact the
`ready` frame still carries — and About shows this client's own pinned
baseline, which is a statement about the app rather than about the harness it
is talking to.

What the app does:

- **Degrades on shape, not on version.** Unknown event types, stream frame
  kinds, tool cards and content blocks fall back to passthroughs rather than
  failing, and unknown keys are ignored. A build that composes no such capability
  answers 404, which the client reads as "this build does not offer that" and
  hides the control instead of reporting a failure.
- **Has no version-shaped branch.** There used to be exactly one: through
  0.1.1, `commands/execute` took a required `images` argument only from
  0.1.0-rc.8, and the client chose its shape from a field only rc.8 emitted.
  0.1.2 deleted that signal, and 0.1.3 renamed the argument again — this time
  the client simply moved with it, because a 0.1.3 host refuses the old key
  and a 0.1.2 host refuses the new one, and there is nothing on the wire to
  choose by.
- **Re-checks on each harness release** with the fixture capture tool
  (`tools/capture`), and moves the baseline once the shapes have been verified.

## What 0.1.3 changed

Session format v2, plus generic file uploads. `docs/PROTOCOL.md` carries the
shapes; this is the summary.

- **No durable deltas.** `assistant/chunk` is gone from the log. Each model
  attempt settles as one event — an `assistant/message` when it produced a
  surface message, a new log-only `assistant/attempt` when it did not — and the
  settlement embeds the exact compact stream the attempt produced. The packed
  `chunks` history record 0.1.2 introduced is gone with it: there is nothing
  left to pack.
- **Streaming is opt-in and process-local.** A `session/follow` request that
  sets `assistantStream: true` receives `assistant-stream` frames (`start`,
  `chunk`, `end`) beside the durable events, and its opening snapshot carries an
  `assistantStream` baseline describing any attempt caught mid-stream. These
  frames are presentation, never replayed and never paged; the app folds them
  into a provisional message that the settlement replaces.
- **Every business error code is namespaced.** `attachment-error` became
  `session/attachment-invalid`, `agent-busy` became `session/agent-busy`,
  `session-not-found` became `session/not-found`, and the gateway's own
  refusals — which used to arrive as a bare `internal` — carry codes of their
  own: `gateway/arguments-invalid`, `gateway/input-invalid` (the boundary
  validation of a `request` object), `gateway/bad-request`, `gateway/internal`.
- **Commands carry attachments, not images.** The third argument of
  `commands/execute` is `submittedAttachments`, each member tagged
  `{type: "image", …}` or `{type: "file", receiptId}`, and a descriptor
  declares `input.attachments` where it declared `input.images`.
- **Files.** Any file can be staged for a session, either by streaming its
  bytes to `POST /api/session/uploadFileBinary` or through the
  `fileUploads/upload` Remote, and cited from a prompt or a command by the
  receipt the upload answered with. A stored file appears in the log as a
  `file` content block carrying its name, size and digest.
- **Subagent prompts take prompt parts.** `subagents/prompt` `content` is the
  same `PromptContentPart` vocabulary `session/prompt` takes, so a follow-up to
  a child may carry images (files are refused for a child).
- **The mux heartbeat has a deadline.** The host terminates a socket that
  misses two pings; the platform answers pongs, so nothing changes here.

## What 0.1.2 changed

The whole `/api` surface moved. `packages/host/apiproxy` was deleted upstream
and every operation now belongs to the business service that owns it, so this is
a summary rather than an exhaustive list.

- **Method names.** `domain.method` became `namespace/method`
  (`session.list` → `session/list`). Several calls that took a request object now
  take flat named arguments, because the gateway matches an args object against
  the host method's *parameter names*.
- **Prompts carry an identity.** `session/prompt` and `subagents/prompt` gained a
  required `requestId`, minted by the sender, one per human message; the host
  persists it on the message the prompt is accepted as.
- **One socket.** `/api/events.mux` and `/api/events.host` were replaced by
  `/api/remote.mux`, which multiplexes independently cancellable logical
  streams and, unlike its predecessors, is written to as well as read.
- **Readiness.** `host.describe` is gone. A connection is ready when the
  Gateway-internal `$events` stream yields its opening `ready` frame.
- **Answers.** `/api/respond` is gone. Approvals and questions arrive as
  agent-scoped waterfalls on `$events` and are answered through
  `$events/result`, bound to the generation by its `clientId`.
- **History is a stream.** `session.history` became `session/follow` (a stream
  opening with a complete snapshot) plus `session/page` (a unary read that
  *requires* the follow generation's cursor).
- **Tool cards are the client's.** The host no longer computes render intents;
  terminal, diff, read, search and web cards are derived in the app from the raw
  call, result and durable `meta`.
- **Authentication.** See below.

## Authentication

Since harness 0.1.2 the complete `/api` surface — every call, the mux upgrade,
the session-log download and, since 0.1.3, the file-upload route — is
authenticated against a signed browser-session cookie. The harness prints a
launch token once per process; `GET /?token=…` exchanges it for a host-only,
`HttpOnly`, `SameSite=Strict` cookie. The token is rejected on `/api` paths and
in an `Authorization` header, so the index route is the only exchange point.

A request that clears the `Host`/`Origin` checks but carries no session is
answered **401**, which is a different fact from the **403** those checks
produce, and the app reports them separately: a 403 is about where the request
came from and is fixed on the harness, a 401 is about who is asking and is fixed
by exchanging a token.

Behind a relay this is the relay's business — it holds the harness session and
injects it upstream, and the phone never carries the host's cookie across the
network.

## Loopback-only surfaces

There are none any more.

Through 0.1.1 the harness kept a `PRIVILEGED_METHODS` list — `settings.*`,
`credentials.*`, `llm.discoverModels`, `host.pickDirectory`, `host.openPath`,
and agent-preset authoring — that it refused to non-loopback callers with 403,
and this app presented those surfaces read-only or hidden. 0.1.2 **deleted that
list**. There is one uniform authenticated tier: possession of a browser session
authorizes the complete tool-capable API, which is the same authority the web
app has after creating a session.

This is a real change in posture, not an editorial one. A paired device now
reaches settings and credentials where it previously got a refusal.

Nothing in the app branches on it — these surfaces already degraded on 403 and
404 rather than on a stored flag, so a paired device simply starts getting
answers. What decides who reaches them is now entirely the relay's
`privilegedMethods` policy, which is the relay's own rule rather than a mirror
of the harness's.

## Known differences by harness release

- **Image bounds are the host's.** 0.1.1-rc.2 raised the shipped admission caps
  (per-image 3.5MB → 20MB, per-message 100MB → 200MB, 40M → 64M pixels,
  2000px → 8192px per side). The app enforces whatever the `imageLimits`
  projection says and falls back to those defaults when a host publishes none.
- **Stored images are normalized on ingest.** The attachment reference describes
  the re-encoded stored image: its `mediaType` can differ from the upload's,
  `attachmentId` is the digest of the normalized bytes, animated GIFs flatten to
  one frame, and `originalDimensions` reports the upload's pixel size when
  scaling occurred. The client treats the reference as opaque.
- **Stored files are verbatim.** A file is kept byte for byte with no
  normalization, and its `attachmentId` is the sha256 of exactly those bytes.
  The app renders a file as a chip — name and size — and never downloads it;
  the bytes are for the agent's file tools.
- **Live and replayed transcripts differ in encoding, not content.** A reply
  arrives as `assistant-stream` frames while it is written and as one
  settlement event on reconnect or page; the fold shows the same message either
  way, and a reconnect mid-answer restores the partial text from the baseline.
