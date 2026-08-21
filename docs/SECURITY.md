# Security

DSH Mobile is a remote control for the **DeepSeek Harness**. Understand the
trust model before using it — and note that the app now offers two, which is why
the connect screen makes you pick one rather than choosing for you.

## The thing to understand first

The harness is a coding agent. It runs shell commands, reads and writes files,
and can install software on the computer it runs on. **Anything that can reach
it can do all of that.** There is no lesser tier of access. Both modes below
grant the same power; they differ only in what has to be true before someone
gets it.

## Local network mode

The harness web server (`dsh web`) serves plain HTTP with a *trust fence*, not
authentication:

- Every `/api` request is accepted only when its `Host` header is loopback or a
  configured trusted authority (LAN IP literals are auto-derived when the server
  binds `0.0.0.0`).
- There are no tokens, cookies, or TLS. Any device on the same network can send
  requests with a trusted `Host` and drive the agent — including running
  commands on the host computer. TLS can be added from outside by fronting the
  harness with a reverse proxy — see **HTTPS through a reverse proxy** below —
  but that encrypts the link without authenticating anyone; only a relay does
  both.

**Consequences:**

- Only bind the harness to `0.0.0.0` on networks you fully trust (home network,
  your own lab). Never on public or guest Wi-Fi.
- DSH Mobile states this on the connect screen whenever local-network mode is
  selected.
- Sensitive surfaces (settings, credentials, agent-preset authoring, host file
  pickers) remain loopback-only by harness design and are shown read-only over
  the network.

## Relay mode

[`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) is a harness
plugin that mounts a second listener beside the harness rather than inside it.
The harness keeps its loopback bind; the relay terminates TLS, authenticates,
and forwards. A relay that fails to start therefore leaves the harness
**unreachable** from the network — never open to it.

What that gets you, and what it does not:

- **A real credential.** The app holds a bearer token issued once, at pairing,
  and sends it on every `/api` call and both WebSocket upgrades. The relay stores
  only a keyed hash of it.
- **Real transport security.** Traffic is encrypted, and the app pins the
  relay's public key by SHA-256 of its DER SubjectPublicKeyInfo. Pinning
  *replaces* CA validation rather than following it, so a self-signed relay is
  verified rather than merely accepted.
- **Revocation you control.** Each device is individually revocable from the
  relay's own device list, and "sign out everywhere" invalidates every token at
  once. Either one surfaces in the app as "pair again" on the next request.
- **It does not reduce what an authenticated client can do.** Signing in grants
  the same power as a shell on that machine. The question the relay answers is
  "is the remote user you", not "how much can the remote user do".

### How the key is established

Two pairing routes, and they do not prove the same thing. The app says which one
happened rather than reporting "paired" twice:

- **Scanning the QR** carries the relay's key in the payload, so the very first
  byte the app sends is verified against it.
- **Typing the code** carries no key — the relay only reveals one in its answer
  to the claim — so the certificate is trusted on first contact. An attacker able
  to answer at that address during pairing would end up holding the enrolment.

Prefer the QR. The typed route exists because it is the one that works when the
camera does not.

### When the key changes

The relay regenerates its certificate — with a **new key** — whenever the set of
addresses it covers changes. A harness laptop that moves between networks
therefore rotates its pin, and paired devices stop connecting until they pair
again. That is indistinguishable from something else answering at the address,
so the app reports it as a changed key and refuses to proceed on its own.

### What is not defended

- **A weak relay password.** Ten characters is the enforced floor, not a
  recommendation.
- **A relay running `tls: off`, or its plain-HTTP compatibility listener.** The
  token and everything you send travel in the clear. The app says so on the
  pairing screen and on the endpoint's card.
- **Source-address grants.** The relay's `compat.addressGrants` accepts requests
  from an address a paired device was last seen on, as a bridge for clients that
  cannot hold a token. A source address is not authentication: it is shared
  behind NAT, reassigned by DHCP, rotated by IPv6 privacy extensions, and
  spoofable on the same Wi-Fi. **This app no longer needs it** — set
  `compat.addressGrants: false` once every client you use has paired.

## HTTPS through a reverse proxy

The harness itself never serves TLS, but the app can reach one behind a
reverse proxy that does (Caddy at `https://agent.home`, say). Typing
`https://…` into the connect screen — or a bare address with port 443 —
makes the whole connection TLS: every `/api` call, both event streams, and
session-log downloads.

Certificate verification is standard Android, with one addition:

- The app trusts the system CA set **plus CAs the user has installed** on the
  phone (`<certificates src="user" />`). A LAN proxy is almost always signed
  by a local CA — Caddy's internal CA, mkcert, a homelab CA — which only
  works if the phone's owner has deliberately installed that CA, an act
  Android itself warns about and marks. The app adds no CAs of its own.
- Verification is never relaxed beyond that. There is no "accept this
  certificate anyway" flow, no hostname-check bypass, and no pinning UI: an
  untrusted certificate fails the connection with a message naming the CA
  install as the fix.
- Relay mode is the exception, and it goes the other way. There the app pins a
  key it was handed at pairing and ignores the CA set entirely — stricter than
  this path, not looser, and never something the user is asked to approve.
- The trust fence still applies through a proxy. The app sends the authority
  it was given as the `Host` header, so the harness must be started with
  `--trusted-host <that name>` (a proxy that preserves `Host`, as Caddy does
  by default, changes nothing about this).

## What DSH Mobile stores

- Remembered endpoints (host, port, whether to use HTTPS, display name, and —
  for a relay — its device id and certificate pin) plus app preferences, in
  app-private storage only.
- **Relay bearer tokens, encrypted.** The key is generated in the Android
  Keystore and never leaves it; only ciphertext reaches DataStore. Forgetting a
  host drops its token in the same act, and Settings → clear data drops all of
  them. Revoking the relay's own record of the device happens on the relay.
- No session content is persisted to disk (chat history lives in memory and is
  re-fetched on connect).
- Cleartext HTTP remains permitted app-wide, alongside user-installed CAs
  (`app/src/main/res/xml/network_security_config.xml`). Cleartext cannot be
  narrowed: a bare harness serves plain HTTP on a LAN address not known at build
  time. The app answers this by naming the transport wherever an endpoint
  appears rather than by pretending every connection is encrypted. A pinned
  relay bypasses that trust store entirely — the pin *is* the trust decision.

## What DSH Mobile connects to

Every connection is to an endpoint you entered, scanned, or picked from a scan,
with one exception:

- **The update check.** On start the app asks `api.github.com` for this
  repository's latest release, over HTTPS, so it can tell you when a newer APK
  exists. It sends no identifying information beyond what any HTTPS request
  carries, and it is the only request that leaves your network. Turn it off in
  **Settings → About → Check for updates**.
- **Scanning** probes only your own device's IPv4 /24 — with a TCP connect
  followed by `host.describe` in local-network mode, or by `/relay/health` in
  relay mode. Relay mode browses mDNS `_dsh._tcp` first and only sweeps if that
  finds nothing.

## Reporting a vulnerability

Report security issues privately, either through **Report a vulnerability** on
the repository's Security tab or by email to **sor@zyphite.com**. Do not open a
public issue for a vulnerability. Include what an attacker can do, the steps to
reproduce it, the app and harness versions, and how the app was connected.

The facts documented above — that the bare harness has no authentication, that
authenticating to a relay grants shell-equivalent access, and that typed pairing
is trust-on-first-use — are the model, not vulnerability reports. A way around
the trust fence, the bearer check, or the certificate pin is.
