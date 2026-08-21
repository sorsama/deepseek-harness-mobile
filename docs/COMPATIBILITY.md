# Compatibility

DSH Mobile speaks the DeepSeek Harness **web client protocol** (the JSON-RPC
surface the harness GUI itself consumes over `/api`). That protocol is internal
to the harness and is not versioned on the wire, so this app pins a protocol
baseline: the harness release its DTOs and call shapes were ported from and
checked against.

| DSH Mobile | Harness version | Status |
|---|---|---|
| 0.8.0 | 0.1.1-rc.2 | Supported baseline |
| 0.7.0 | 0.1.1-rc.2 | |
| 0.6.0 | 0.1.1-rc.2 | |
| 0.5.0 | 0.1.0-rc.8 | Previous baseline |
| 0.4.0 | 0.1.0-rc.7 | |
| 0.1.0 – 0.3.1 | 0.1.0-rc.5 | |

## Relay

Reaching a harness through [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay)
is a separate contract with its own version, because the relay is a plugin
mounted beside the harness rather than part of it.

| DSH Mobile | dsh-relay | Notes |
|---|---|---|
| 0.8.0 | 0.1.1 | Pairing payload `v: 1`; mDNS TXT `v: 1` |

Relay 0.1.1 registers `/relay` on the harness's **own** web server and redirects
it to its listener. That makes the harness address — the one this app has shown
people for five releases — a usable thing to type on the pairing screen, and the
app resolves that redirect itself rather than letting the HTTP layer follow it:
the target names a different scheme and port, which decides both the key to pin
and what gets remembered, and a 302 would rewrite the claim's POST into a GET.
It is reachable only where the harness's own port is, which with a relay running
means loopback — `adb reverse`, or a harness on the phone itself.

Both versions are checked, unlike the harness baseline. A pairing payload is a
credential exchange, so a `kind` other than `dsh-relay-pair` or a `v` above the
one this build understands is refused outright rather than degraded — see
`docs/PROTOCOL.md`. Everything *behind* the relay is the harness protocol
unchanged, so the version policy below applies to it exactly as before.

Relay 0.1.0 keeps `compat.addressGrants` on by default, as a bridge for clients
that cannot present a credential. DSH Mobile 0.8.0 can, so that bridge should be
turned off — see `docs/SECURITY.md`.

The baseline is one constant — `DshCore.PROTOCOL_BASELINE` in
`core/src/main/kotlin/com/labteto/dshmobile/core/DshCore.kt` — and the app shows
it in Settings → About next to its own version.

## Version policy

The baseline says what was tested. It is not a gate, and the app does not compare
it against `host.describe.version` or warn when the two differ — it could not say
what would break if it did. The harness releases far more often than this client.
Some of those releases leave the client surface untouched — rc.5 → rc.7 added no
RPC method, no event type, no projection key and no slash command — and some do
not: rc.7 → rc.8 added a required field to `host.describe`, a required key to the
`imageLimits` projection, and a required third argument to `commands/execute`.
And a release can change what the wire *means* without changing a single shape:
rc.8 → 0.1.1-rc.2 touched no RPC schema, event type, projection key or slash
command, but raised every shipped image bound and began normalizing stored
images, so the same attachment reference now describes a re-encode rather than
the upload. A banner firing on every harness that is not one exact string would
be noise on every session, and would still be silent about which of those a
release was.

What the app does instead:

- **Degrades on shape, not on version.** Unknown event types, frame kinds, tool
  cards and content blocks fall back to passthroughs rather than failing, and
  unknown keys are ignored. A build that composes no such capability answers 404
  or 403, which the client reads as "this build does not offer that" and hides
  the control instead of reporting a failure.
- **Reads capability off the wire when it has to choose what to _send_.** rc.8
  gave `commands/execute` a required `images` argument, and the harness gateway
  matches an args object against its descriptor exactly — it refuses a missing
  key as readily as an unexpected one, so "ignore what you do not understand" has
  nothing to offer here. The client decides from a field only rc.8 emits
  (`host.describe.home`) rather than from a version comparison, so a fork, a
  pre-release or a downstream build is judged on what it actually sends. This is
  the one such branch in the client, and it exists because that gateway leaves no
  third option — not because host-shape branching is a pattern to reach for.
- **Shows the harness's own version** wherever a host appears — the connect list,
  the details panel, and Settings → Harness — so a mismatch is visible where it
  is useful rather than announced as an alarm.
- **Re-checks on each harness release** with the fixture capture tool
  (`tools/capture`), and moves the baseline once the shapes have been verified.

## Known differences by harness release

- **Slash commands with images need rc.8.** `/goal` and `/plan` accept composer
  attachments there; every other command refuses them, and against an rc.7 host
  so does this client, because that release has no wire slot to carry them.
- **Image bounds are the host's.** 0.1.1-rc.2 raised the shipped admission caps
  (per-image 3.5MB → 20MB, per-message 100MB → 200MB, 40M → 64M pixels,
  2000px → 8192px per side) because the host now normalizes stored images down
  to its own working size after admission. The app enforces whatever the
  `imageLimits` projection says and falls back to 0.1.1-rc.2's defaults when a
  host publishes none — so against an older host that publishes no projection,
  the client may accept an image the host then refuses, and the host's own
  admission error is what gets shown.
- **Stored images are normalized from 0.1.1-rc.2.** The attachment reference
  describes the re-encoded stored image: its `mediaType` can differ from the
  upload's, `attachmentId` is the digest of the normalized bytes, animated GIFs
  flatten to one frame, and `originalDimensions` reports the upload's pixel size
  when scaling occurred. Older hosts echo the upload's facts unchanged; the
  client treats the reference as opaque either way.
- **Text-only models are selectable on image-bearing sessions from 0.1.1-rc.2.**
  Earlier hosts refuse `session.selectModel` with `model-unavailable` there;
  the client just surfaces whichever answer the host gives.

## Loopback-only surfaces (by harness design)

These methods are refused for LAN clients (403) and are presented read-only
or hidden:

- `settings.*`, `credentials.*`, `llm.discoverModels`
- `host.pickDirectory`, `host.openPath`
- agent-preset authoring (`agentPreset.read/copy/openDocument/remove`)

Behind a relay this depends on how the operator configured it. `dsh-relay` keeps
its own copy of that list — the `Host` rewrite would otherwise lift the harness's
pin — and `privilegedMethods` decides who reaches it: `allow-authenticated` (the
default) serves them to a paired device, `loopback-only` keeps them on the host
machine, and an address-granted client never reaches them regardless.

Nothing in the app branches on this. These surfaces already degrade on 403 and
404 rather than on a stored flag, so a paired device simply starts getting
answers where it previously got refusals.
