# Compatibility

DSH Mobile speaks the DeepSeek Harness **web client protocol** (the JSON-RPC
surface the harness GUI itself consumes over `/api`). That protocol is internal
to the harness and is not versioned on the wire, so this app pins a protocol
baseline: the harness release its DTOs and call shapes were ported from and
checked against.

| DSH Mobile | Harness version | Status |
|---|---|---|
| 0.5.0 | 0.1.0-rc.8 | Supported baseline |
| 0.4.0 | 0.1.0-rc.7 | Previous baseline |
| 0.1.0 – 0.3.1 | 0.1.0-rc.5 | |

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
`imageLimits` projection, and a required third argument to `commands/execute`. A
banner firing on every harness that is not one exact string would be noise on
every session, and would still be silent about which of those two a release was.

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
- **Image bounds are the host's.** rc.8 lowered the shipped per-image cap from
  5MB to 3.5MB and added a 2000px per-side cap. The app enforces whatever the
  `imageLimits` projection says and falls back to rc.8's defaults when a host
  publishes none, which can refuse a 4MB image an rc.7 host would have taken.

## Loopback-only surfaces (by harness design)

These methods are refused for LAN clients (403) and are presented read-only
or hidden:

- `settings.*`, `credentials.*`, `llm.discoverModels`
- `host.pickDirectory`, `host.openPath`
- agent-preset authoring (`agentPreset.read/copy/openDocument/remove`)
