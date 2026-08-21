# Protocol notes

What DSH Mobile speaks, in one page. Authoritative shapes live in the harness
repository (`packages/host/apiproxy/src/api/*`); this document records the
subset the app implements.

## Envelopes

All JSON. `rpcId` is a UUID minted by the initiator and echoed.

### Client → server (HTTP POST)

`POST /api/<method>` — unary calls:

```json
{"type":"client-request","rpcId":"<uuid>","method":"session.list","payload":{}}
```

`POST /api/respond` — answers to server-initiated requests (approvals,
questions):

```json
{"type":"client-response","rpcId":"<server rpcId>","result":{"ok":true,"value":{}}}
```

`POST /api/<namespace>/<method>` — typert "Remote" gateway endpoints used by the
GUI (`commands/*`, `goals/*`, `pluginInventory/*`, and — depending on the
build — `messageFeedback/*`, `dynamic/*`).

These share the ordinary envelope: `{"args": …}` is the **payload**, not the
body, and the envelope's `method` must equal the path. Args are a named object
whose keys must match the remote descriptor exactly; a session-addressed method
takes `agentId`.

```json
{"type":"client-request","rpcId":"<uuid>","method":"commands/list",
 "payload":{"args":{"agentId":"<sessionId>"}}}
```

A path no gateway claims answers **404**, and the trust fence answers **403** —
both mean "this build does not offer that", not "the connection is broken", so
the client maps them to `capability-unavailable` / `forbidden` and hides the
feature instead of reporting a failure.

`commands/execute` is the **only** command write path. `session.prompt` does not
inspect its content — a leading-slash prompt reaches the model as ordinary user
text — so the client adjudicates the draft against `commands/list` before sending
and only calls `session.prompt` when the line names no registered command. That
miss is load-bearing: a `/name` line the catalog does not claim is how a *skill*
is invoked, and the host's pre-step boundary resolves it. The remote answers with
no `value` when the line parses to no command; since the codec folds an absent
value into `{}`, the discriminator is the presence of `commandId`.

Its argument shape depends on the harness release, and this is the one call where
that matters:

```json
{"args":{"agentId","line"}}                       // 0.1.0-rc.7 and earlier
{"args":{"agentId","line","images":[…]}}          // 0.1.0-rc.8 and later
```

`images` is a required argument from rc.8, carrying `{mediaType, data, name?}`
per member with `data` as canonical base64 — and the gateway refuses an args
object that does not match its descriptor, a missing key as readily as an
unexpected one. So the shape has to be chosen rather than written once. The
client chooses on the presence of `host.describe.home`, a field rc.8 made
required and rc.7 never sent, latched for the connection during the handshake.

A non-empty batch is only accepted by a command whose `commands/list` descriptor
declares `input.images` — `/goal` and `/plan` at rc.8, nothing else. The executor
enforces that, not the composer, but the client refuses first so the draft and
the pictures survive a refusal. Sub-command grammar stays with the host: `/plan
off` and `/goal pause` answer with an ordinary error result rather than being
adjudicated here.

The `command` slot on `session.prompt`'s response, and the `unknown-command` /
`command-error` codes, are dead schema the host never populates.

### Downloads (no envelope)

`GET /api/session.export?sessionId=<id>[&includeDescendants=true]` streams the
session-log ZIP as an attachment (`Content-Disposition: attachment;
filename="dsh-session-<id>.zip"`). It is answered directly, not through an RPC.

### Server → client

Unary response (HTTP 200):

```json
{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":{}}}
{"type":"server-response","rpcId":"<same>","result":{"ok":false,"error":{"code":"agent-busy","message":"...","details":{}}}}
```

Respond receipt:

```json
{"accepted":true} | {"accepted":false,"reason":"not-pending"|"bad-response"}
```

### Answering a question request

`question/requested` is settled through `/api/respond`, and the host holds the
answer to the exact request it resolves. `custom` rides the **answer item**, not
the batch:

```json
{"sessionId":"…","answer":{"answers":[
  {"id":"approach","selected":["Rewrite (Recommended)"]},
  {"id":"notes","selected":[],"custom":"ship it on Friday"}]}}
```

The payload is parsed with a schema that **strips** undeclared keys rather than
rejecting them, so a misplaced field does not fail — it vanishes, and the answer
is accepted without it. Then every clause below must hold, or the whole batch
comes back `bad-response` with the wait still open and the tool call still
blocked:

- one answer per question, **in request order**; the host pairs them by position
  and compares each `id`, so a partial or reordered batch is refused;
- no duplicate labels, and every label must be one the question itself offered
  (a question with no options can carry no selection);
- `custom` omitted when there is none — a present-but-blank one is a refusal;
- on a **single-select** question, `custom` and a non-empty `selected` are
  mutually exclusive, and `selected` may carry at most one label;
- a skipped question is still answered, with `selected: []` and no `custom`.

Dismissing the request is a different message — `ok: false`, and only this code
is accepted:

```json
{"type":"client-response","rpcId":"…","result":{"ok":false,
 "error":{"code":"cancelled","message":"the user closed this question request","details":{}}}}
```

Answering every item with an empty selection is *not* a dismissal: it is a valid
answer, and the model reads it as no preference.

### Event streams (WebSocket, downlink-only)

`/api/events.mux` (session events, approvals, questions, queues, jobs,
projections) and `/api/events.host` (session/workspace registry frames).
The client must never send data — doing so closes the socket (1008).

```json
{"type":"server-request","rpcId":"<uuid>","method":"session/event","payload":{"sessionId":"...","event":{"type":"turn/end","seq":4,"time":5,"data":{"turn":1,"reason":{"kind":"completed"}}}}}
```

## Session projections

Several facts the UI needs never arrive as an RPC result — they are pushed as
`session/projection` frames and repeated in the `session.history` tail block:

| Key | Carries |
|---|---|
| `permissions` | `{options:[{value,name,description?}], currentValue}` — the preset picker, read-only; the write side is `/permission <value>` |
| `sessionStats` | `{turns, steps, llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens}` — `ttftMs` is a **sum** over `ttftSteps`, and throughput must be derived from `decodeTokens / decodeMs` |
| `tokenUsage` | `{uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}` |
| `contextPressure` / `contextBreakdown` | context-window occupancy, and what fills it |
| `imageLimits` | `{maxImageBytes, maxImagesPerMessage, maxMessageImageBytes, maxImagePixels, maxImageDimension, mediaTypes}` — the host's own attachment bounds, all of them enforced before upload; `maxImageDimension` is a per-side cap added in harness 0.1.0-rc.8, and 0.1.1-rc.2 raised every shipped bound (20MB per image, 200MB per message, 64M pixels, 8192px per side) |
| `goal`, `todos`, `plan`, `title`, `sessionListMetadata` | the docks and list metadata |

An absent key means the harness composes no such service; clients hide the
control rather than showing a dead one.

## Image attachments are normalized on ingest

Since harness 0.1.1-rc.2 the host re-encodes stored images down to its own
working size after admitting them, so the durable reference
(`{attachmentId, mediaType, bytes, width, height, name?, originalDimensions?}`)
describes the **stored** image, not the upload: `mediaType` may differ from what
the client sent, `attachmentId` is the digest of the normalized bytes, and an
animated GIF flattens to one frame. When scaling occurred, `originalDimensions`
carries the upload's pixel size. Byte-identical passthrough happens only for a
clean single-frame image already inside the normalization bounds. The client
treats `attachmentId` as opaque and renders what `session.attachment` returns,
so nothing here needs a branch — but nothing may assume the ref echoes the
upload either.

## Handshake & liveness

Connect = both streams open **and** `host.describe` succeeds. On loss:
exponential backoff (500 ms × 2, cap 10 s, jitter), then resync
(`session.list` + per-session history tails). A `stream/error` frame ends
the current generation.

## Trust fence

`Host` header must be loopback or a trusted authority; the app sends no
`Origin`. HTTP 403 = fence rejection (see `docs/COMPATIBILITY.md`). It is not an
authentication layer, and the harness says so itself — see **Relay** below for
the layer that is.

## Relay

`dsh-relay` is a harness plugin, not a different protocol. It terminates TLS,
authenticates, and forwards to the same loopback harness — so the envelopes, the
method set, both downlinks and the 3-second handshake budget are unchanged.
Three things differ on the wire.

**1. Every request carries a credential.**

```
Authorization: Bearer <token>
```

On `POST /api/*`, on `GET /api/session.export`, and on **both WebSocket
upgrades**. The upgrade is the one that bites: it has no later request to carry
a credential, a relay refuses it at the handshake, and the loop can only report
that as a stream that would not open.

**2. TLS is pinned, not merely verified.**

`https://` itself is nothing new — the app has spoken it since 0.7.0, for a
harness behind a reverse proxy, where an ordinary CA decides. A relay is the
other way round. When the pairing payload carries a `fingerprint`, the app pins
it: SHA-256 over
the leaf certificate's DER SubjectPublicKeyInfo, base64 — byte-for-byte what the
relay publishes. Pinning replaces CA validation rather than following it
(`core/wire/RelayTls.kt`), because the relay's default posture is a self-signed
certificate the platform store would reject before a pin was ever consulted.

The relay mints a **new key** whenever the addresses its certificate covers
change, so a harness machine that moves networks rotates the pin. The app
reports that as a changed certificate, never as a transport failure, and never
falls back to CA validation.

**3. Pairing, once.**

Two calls outside `/api`, neither of which is forwarded upstream:

| Call | Auth | Answer |
|---|---|---|
| `GET /relay/health` | none | `{"service":"dsh-relay","ok":true}` |
| `POST /relay/pair` | none | `{deviceId, token, expiresAt, fingerprint?}`, or 403 `{"error":"pairing-failed"}` |

The claim body is `{"code","name"}` and **must** be sent with
`Content-Type: application/json`; without it the relay answers with an HTML page
rather than a token. It must go to the payload's `url` — the plain-HTTP
compatibility listener serves no relay route but `/relay/health`.

Neither call follows redirects. Relay 0.1.1 registers `/relay` on the harness's
own web server and redirects it to the relay's listener, so `/relay/health` is
also how the app resolves *where* a relay is: a 3xx naming the same path is the
harness pointing at it. Reading that answer rather than chasing it is what keeps
the recorded origin right — the target carries a different scheme and port, and
a 302 rewrites a POST into a GET, which would deliver the claim as a page view.

The QR payload is a single UTF-8 JSON object. `v` and `kind` are always present;
`plainUrl` and `fingerprint` are not. The app refuses any `kind` other than
`dsh-relay-pair` and any `v` above 1 — the one place it is strict rather than
lenient, because acting on a payload hands over a credential.

```json
{"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.5:3443",
 "plainUrl":"http://192.168.1.5:3444","fingerprint":"<base64 SPKI>",
 "code":"48213977","expiresAt":1755500000000}
```

### Status codes behind a relay

A relay answers **403, never 401**, for every unauthorized case — missing,
expired, revoked, an untrusted `Host`, a cross-site marker, or a privileged
method that credential may not reach.

| Code | Meaning | What the app does |
|---|---|---|
| 403 | no usable credential | Stops the loop and says "pair again". No backoff — there is nothing to wait for. |
| 404 | path not proxied, or the harness lacks the capability | Existing `capability-unavailable` handling |
| 429 | rate limited or locked out | Backs off for `Retry-After`, defaulting to 60s when the header is absent — older relays omitted it on the lockout paths |
| 502 | the harness behind the relay is not answering | Existing reconnect handling |

403 is ambiguous with the harness's own `Host` fence, and no header on a rejected
upgrade disambiguates it. The app decides from what it already knows: an address
it holds a device token for reads 403 as "pair again", any other address reads it
as the trust fence.

### Discovery

The relay advertises `_dsh._tcp` over mDNS with TXT records `v=1`,
`relay=dsh-relay`, `tls=self-signed|files|off`, `plain=<port>` and
`pin=<base64 SPKI>`. The service port is the primary listener's. Nothing depends
on it: relay mode browses first and falls back to knocking 3443 and 3444 across
the phone's own /24.
