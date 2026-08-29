# Compatibility

DSH Mobile speaks the DeepSeek Harness **web client protocol** (the JSON-RPC
surface the harness GUI itself consumes over `/api`). That protocol is internal
to the harness and is not versioned on the wire, so this app pins a protocol
baseline: the harness release its DTOs and call shapes were ported from and
checked against.

| DSH Mobile | Harness version | Status |
|---|---|---|
| 0.9.3 | 0.1.2-alpha.1 | Supported baseline |
| 0.9.2 | 0.1.2-alpha.1 | Cannot send messages |
| 0.9.1 | 0.1.2-alpha.1 | Cannot send messages |
| 0.9.0 | 0.1.2-alpha.1 | Cannot send messages |
| 0.8.0 | 0.1.1-rc.2 | Previous baseline |
| 0.7.0 | 0.1.1-rc.2 | |
| 0.6.0 | 0.1.1-rc.2 | |
| 0.5.0 | 0.1.0-rc.8 | |
| 0.4.0 | 0.1.0-rc.7 | |
| 0.1.0 – 0.3.1 | 0.1.0-rc.5 | |

**0.9.0 does not speak the 0.1.1 protocol.** Every prior baseline move left the
older wire working; this one does not, because 0.1.2 replaced the surface rather
than extending it. A 0.9.0 app cannot connect to a 0.1.1 harness, and a 0.8.0
app cannot connect to a 0.1.2 one. Both directions fail at the handshake rather
than partway through a session.

## Relay

Reaching a harness through [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay)
is a separate contract with its own version, because the relay is a plugin
mounted beside the harness rather than part of it.

| DSH Mobile | [dsh-relay](https://github.com/sorsama/deepseek-harness-relay) | Notes |
|---|---|---|
| 0.9.2 | 0.2.1 | Pairing payload `v: 1`; mDNS TXT `v: 1` |
| 0.9.1 | 0.2.1 | |
| 0.9.0 | 0.2.0 | |
| 0.8.0 | 0.1.1 | |

A relay older than 0.2.0 cannot serve a 0.1.2 harness at all. The harness now
authenticates its whole `/api` surface against a browser-session cookie, and the
relay deliberately strips the client's `Cookie` header before forwarding — so
every proxied request is answered 401 until the relay supplies a harness session
of its own. That is a relay change, not an app one.

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
Settings → Harness show the host's home directory — the one host fact 0.1.2
still carries — and About shows this client's own pinned baseline, which is a
statement about the app rather than about the harness it is talking to.

What the app does:

- **Degrades on shape, not on version.** Unknown event types, stream frame
  kinds, tool cards and content blocks fall back to passthroughs rather than
  failing, and unknown keys are ignored. A build that composes no such capability
  answers 404, which the client reads as "this build does not offer that" and
  hides the control instead of reporting a failure.
- **Has no version-shaped branch left.** There used to be exactly one: through
  0.1.1, `commands/execute` took a required `images` argument only from
  0.1.0-rc.8, and the gateway refuses a missing key as readily as an unexpected
  one, so the client chose its shape from a field only rc.8 emitted
  (`host.describe.home`). 0.1.2 declares that parameter unconditionally and
  deleted `host.describe`, so both the branch and the signal it read are gone.
- **Re-checks on each harness release** with the fixture capture tool
  (`tools/capture`), and moves the baseline once the shapes have been verified.

## What 0.1.2 changed

The whole `/api` surface moved. `packages/host/apiproxy` was deleted upstream
and every operation now belongs to the business service that owns it, so this is
a summary rather than an exhaustive list; `docs/PROTOCOL.md` carries the shapes.

- **Method names.** `domain.method` became `namespace/method`
  (`session.list` → `session/list`). Several calls that took a request object now
  take flat named arguments, because the gateway matches an args object against
  the host method's *parameter names*.
- **Prompts carry an identity.** `session/prompt` and `subagents/prompt` gained a
  required `requestId`, minted by the sender, one per human message; the host
  persists it on the message the prompt is accepted as. It is checked inside the
  request object rather than beside the other args, so leaving it out refuses
  every send while the rest of the surface goes on working.
- **One socket.** `/api/events.mux` and `/api/events.host` were replaced by
  `/api/remote.mux`, which multiplexes independently cancellable logical streams
  and, unlike its predecessors, is written to as well as read.
- **Readiness.** `host.describe` is gone. A connection is ready when the
  Gateway-internal `$events` stream yields its opening `ready` frame.
- **Answers.** `/api/respond` is gone. Approvals and questions arrive as
  agent-scoped waterfalls on `$events` and are answered through
  `$events/result`, bound to the generation by its `clientId`.
- **History is a stream.** `session.history` became `session/follow` (a stream
  opening with a complete snapshot) plus `session/page` (a unary read that
  *requires* the follow generation's cursor). History pages can also carry
  losslessly **packed** runs of assistant deltas, which the client expands.
- **Tool cards are the client's.** The host no longer computes render intents;
  terminal, diff, read, search and web cards are derived in the app from the raw
  call, result and durable `meta`.
- **Authentication.** See below.

## Authentication

Harness 0.1.2 authenticates the complete `/api` surface — every call, the mux
upgrade, and the session-log download — against a signed browser-session cookie.
The harness prints a launch token once per process; `GET /?token=…` exchanges it
for a host-only, `HttpOnly`, `SameSite=Strict` cookie. The token is rejected on
`/api` paths and in an `Authorization` header, so the index route is the only
exchange point.

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
- **Live and replayed transcripts differ in encoding, not content.** Packed
  delta runs appear only in history pages and follow snapshots; the live tail is
  always scalar. The same turn therefore arrives one way while streaming and
  another way after a reconnect, and folds to the same result either way.
