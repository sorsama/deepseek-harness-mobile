# Security

DSH Mobile is a remote control for the **DeepSeek Harness**. Understand the
trust model before using it — and note that the app offers three, which is why
the connect screen makes you pick one rather than choosing for you.

## The thing to understand first

The harness is a coding agent. It runs shell commands, reads and writes files,
and can install software on the computer it runs on. **Anything that can reach
it can do all of that.** There is no lesser tier of access. All three modes
below grant the same power; they differ only in what has to be true before
someone gets it.

## This phone (Termux) mode

The harness runs on the phone itself, inside Termux, and the app reaches it at
`127.0.0.1`. Nothing leaves the device: the harness keeps its loopback bind,
loopback clears its trust fence without configuration, and the sign-in cookie
the app holds is the same one it would hold for `adb reverse` (see **What DSH
Mobile stores**). Whoever holds the phone unlocked holds the agent — which is
already true of the Termux session next to it.

Two things are new in this mode, both opt-in:

- **The app can run commands in Termux.** Start, Stop and Install via Termux go
  through Termux's `RunCommandService`, behind a permission Termux itself
  declares (*Run commands in Termux environment*). Granting it lets DSH Mobile
  run anything the Termux user can; the app asks for it only when Start, Stop or
  Install is tapped, never on launch, and runs nothing while it is in the
  background. What it runs is fixed and documented in
  [`harness/TERMUX.md`](../harness/TERMUX.md): a start script (wake lock,
  `dsh web --no-open --port …`, wait for the readiness line, print it back), a
  stop script that signals only the process it recorded, and a download script
  for updates. The scripts ship inside the app; nothing is installed in Termux,
  and the only values spliced into them are the port and the release URLs, both
  validated and quoted. Termux additionally requires `allow-external-apps = true`
  in its own `termux.properties` before it accepts any of this. Revoke the
  permission in Android's app settings to withdraw it all.
- **The launch token is read from the harness's own output.** Since harness
  0.1.2 a direct client must present the launch token the harness prints once per
  process. When the app starts the harness, it reads that line from the process
  it launched and exchanges the token at once — the same exchange it performs
  when you paste the line, with the same cookie stored in the same place. The
  token never leaves the phone and is never stored.

A harness you start yourself in Termux is treated exactly as any other direct
harness: found by probing loopback, signed in by pasting its startup line once.

## This phone (built-in harness)

The app can run the harness itself, with no computer and no Termux. This section covers all of
it: what is downloaded and how it is checked, what runs and with what reach, commands, API
keys, updates, the background service, and project files.

The trust model is the Termux one above: the harness binds `127.0.0.1`, and whoever holds the
phone unlocked holds the agent. What is different is that the app downloads and runs the
software, and that a phone has no kernel sandbox for the agent's commands.

### What is downloaded, and how it is checked

- **What.** A Debian base with Node.js and the DeepSeek Harness, as two layer archives from the
  releases of [`sorsama/dsh-mobile-runtime`](https://github.com/sorsama/dsh-mobile-runtime),
  over HTTPS. About 155 MB the first time on an arm64 phone; an update usually fetches only the
  harness layer (about 85 MB). Nothing is downloaded until you tap Set up or Update, except the
  small signed manifest the app reads to see whether there is an update (see **What DSH Mobile
  connects to**).
- **The signature.** Each release has a `manifest.json` signed with an Ed25519 key whose public
  half is compiled into the app (`ManifestVerifier`). The signature is checked over the
  manifest's exact bytes before any of it is read; a manifest signed by anything else is
  refused. The manifest names every layer's size and SHA-256, and a downloaded layer that does
  not match is deleted before it is unpacked. The signing key's private half exists only in the
  runtime repository's secrets and an offline backup; replacing it needs an app update first.
- **No replay.** A release with a lower sequence number than the one installed is refused, so
  an old release cannot be pushed back onto the phone. The one exception is **Go back**, which
  you tap yourself: it installs the release the installed manifest names as `previous`, after
  checking that manifest with the same key and that it is the release named.
- **Unpacking.** Files, folders and symlinks only; an entry that would land outside the
  runtime's directory (absolute names, `..`, a path through a symlink) stops the unpack. Each
  layer unpacks into a staging folder and is renamed into place, and `installed.json` is written
  last, so a phone that dies mid-update keeps the release it had.

### What runs, and what it can reach

- `proot` and its loader ship inside the APK (`jniLibs`, built from `termux/proot` by the
  runtime repository, hashes recorded in `app/third_party/proot`) and run the harness inside the
  downloaded Debian tree. They run as this app's own Linux user, inside the app's sandbox. proot
  fakes root inside the tree; it gives nothing outside it.
- **Android still keeps apart:** your photos, files in shared storage, contacts, messages, and
  other apps and their data. The harness cannot reach them, whatever its commands do.
- **Android does not keep apart:** the network (a command can download and upload anything),
  and everything the app itself owns: the projects, the harness's home with your sessions,
  settings and stored API keys, and the runtime's own files. A command can read, change or
  delete any of that.
- Everything lives under the app's no-backup directory, so it is never part of a device backup.
  Removing the runtime keeps the harness's home and projects unless you choose **Delete
  everything**; uninstalling the app deletes it all.
- **The loopback port is shared.** Any app on the phone can connect to `127.0.0.1:<port>`. The
  guard is the launch token: the harness prints it once per start, the app reads it from the
  process it started and exchanges it at once for the session cookie, like the Termux path. The
  token is never written to disk or to the system log (the harness log on the control page has
  it masked), and without it or the cookie every `/api` call answers 401.

### Commands

On a computer the harness runs the agent's shell commands in a kernel sandbox (bubblewrap or
Landlock). Android allows neither inside an app, so upstream's default mode would refuse every
command. The runtime adds two settings of its own, and the app shows them as a choice for new
chats ("When the agent wants to run a command"):

- **Run it** (the runtime's "Full access", the default): commands and file changes run without
  asking. Everything in the list above that Android does not keep apart is within reach.
- **Ask me first** (the runtime's "Ask before commands"): every shell command, every program the
  agent's code mode wants to run, and every file change outside the chat's project waits for
  Allow or Reject, with the whole text shown in a selectable, scrolling block. A command that
  is too long to show (over 256 KB) is not asked about and does not run.

"Ask me first" is a question, not a sandbox. A command you allow has the same reach as under
"Run it", and the file tools can still change anything inside the project without asking. A
helper agent (subagent) cannot ask anyone, so in an "Ask me first" chat its commands are
refused, and the chat says so. The runtime's permissions version 1 (release `2026.11.15-1`) cut
the question's text at 2,000 characters; the app says to update the harness before relying on
"Ask me first" there. Each chat keeps the setting it started with and can be switched from its
permission button. The full rules, including what is not gated (the web page's own terminal,
MCP servers you add), are in the runtime README, "What Ask before commands gates".

Separately, a project you clone is cloned by the app itself (see **Getting projects in and
out**); you typed that command, so it is not asked about.

### API keys

The Models screen sends a key to the harness once (`credentials/set`) and keeps nothing: the key
lives in the text field while the screen is open, is never saved, logged or copied, and the
screen is a `FLAG_SECURE` window (blank in screenshots and in Recents) with copy and cut removed.
The harness stores the key in its home on the phone. A command, or the harness's own `read`
tool, can read that file in either command setting, as on a computer. A key sent to a harness
over plain HTTP on another device asks first.

### Updates of the harness

The app looks for a new runtime release when it opens (at most every four hours) and once a day
in the background while a runtime is installed, if **Check for updates** is on. A new release is
announced once, on the "Harness updates" notification channel. It is installed when you tap
Update (asking first on a metered network), or by itself only if you turned on **Install updates
by themselves**: then only on an unmetered network and only while no chat is in a turn. Every
update is on trial until the new release has started once; if it does not, the previous release
is put back by itself. A release you went back from, or one that failed to start here, is never
installed by itself.

### In the background

`HarnessRuntimeService` keeps the app in the foreground (with a notification and a Stop action)
while the runtime installs or runs, and holds a partial wake lock unless you turn **Keep the
phone awake** off. Both foreground services are asked for so that Android cannot kill the app
for a slow start, and a start Android refuses is tried again when the app is next on screen.
Nothing else runs in the background.

### Projects in the Files app

The built-in harness's project folder (`workspaces`, the guest's `/workspaces`) is a
storage location in the system Files app and in every app's file picker, named after the
app (`WorkspacesProvider`, a `DocumentsProvider`).

- **What it exposes:** that one folder and what is in it, nothing else. Not the runtime's
  root file system, not the harness home (`/root`, where the API keys and session history
  live), not the app's other files. It works with the harness stopped: it reads the folder
  directly.
- **Who can reach it:** the provider is protected by `MANAGE_DOCUMENTS`, a permission only
  the system's document UI holds, so no app can query it on its own. Another app gets
  access only through the system picker, when the person picks a file there (one document)
  or grants a folder (that folder's tree). This is the platform's rule for document
  providers; the app adds no other way in. The person holding the phone unlocked can browse,
  copy, change and delete project files from the Files app, which is the point.
- **Paths:** a document id is `workspaces/<path>`. It is never used as a path as it is:
  `WorkspaceTree` splits it into names, refuses an empty name, `.`, `..` and NUL, and walks
  down one name at a time, requiring a real folder at every step (never a symlink). Before a
  file descriptor is handed out, its real path (`/proc/self/fd`) is checked once more to be
  inside the folder. `WorkspaceTreeTest` tries to escape (`..`, absolute ids, ids through a
  link, links to the API key).
- **Symlinks** are never followed out of the folder and never walked through. A link to a
  regular file inside the folder (relative, or an absolute guest path under `/workspaces`)
  is shown as that file and opened as its target. Every other link is skipped: links to
  folders (they would show a tree twice or loop), links out of the folder, links to links,
  broken links. Deleting or renaming a shown link changes the link, never its target.
- **Not covered:** the agent can write anything into a project, including a file another
  app later opens; what a file contains is the project's business. Files changed through
  the Files app are seen by the agent at once (one folder).

### Getting projects in and out

- **Clone** runs `git clone` inside the runtime through the app's own guest-command runner
  (`GuestCommandRunner`), not through the harness and not through a shell: the URL, branch
  and folder are separate arguments after `--`. Only `https://` and `http://` are allowed,
  also for submodules and redirects (`protocol.allow=never` with those two allowed), so a
  `file://` URL cannot copy the harness home into a project. No exported component runs a
  guest command; it is not a way for another app to run anything. The person typed the
  command, so the command choice ("Ask before commands") does not ask about it.
- **A token for a private repository** is used for that clone only. It goes to git as an
  `Authorization` header through git's environment (`GIT_CONFIG_*`), scoped to the
  repository's own origin; never on a command line (where `ps` would show it), never in the
  URL (git would write it into `.git/config`), never in the app's preferences or logs, and
  `credential.helper` is emptied so no helper stores it. The field is a password field in a
  `FLAG_SECURE` window, with copy and cut removed, as on the Models screen. While the clone
  runs, the environment of its processes is readable by this app's own user id only (the
  harness's processes included), which is the same boundary as the stored API key.
- **Zips** are checked before anything is written: an entry that is absolute, has a drive
  letter or a `..` refuses the whole zip; an entry that inflates past its declared size stops
  the import; links are made last and only when they stay inside the project. Imports and
  clones are built in a staging folder and moved into place when complete; a failed or
  cancelled one leaves nothing.
- **Exports** (share as zip, save a copy) leave links out and, by default, dependency
  folders, caches, ignored build output and `.git`. The share sheet gets read access to one
  zip in the app's cache through a `FileProvider` that serves that folder only.

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
  pickers) are **no longer loopback-only**. Harness 0.1.2 deleted that method
  tier and replaced it with one uniform rule: whoever holds a browser session
  reaches the complete tool-capable API. Behind a relay, what gates those
  surfaces is the relay's own `privilegedMethods` policy — set it to
  `loopback-only` if a paired phone should not reach them.

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

## File attachments

Since harness 0.1.3 the app can attach any file to a message. The bytes are
streamed to the harness the moment the file is picked — over whichever
connection is in use, with the same credential and the same encryption, or
lack of it, as everything else — and the harness writes them **verbatim** into
its attachment store on the computer it runs on, where the agent's file tools
can read them. A file you attach is therefore a file you have copied onto that
computer, into a directory the agent reads. In local-network mode that copy
travels in the clear.

The app keeps no copy: it reads the picked document once, at upload time, and
holds only the receipt the harness answered with until the message is sent.

## Updating inside the app

The update dialog (and **Settings → About**) can download and install a new
release without a browser. Nothing is downloaded until you tap
**Download and install**. What happens then, in order:

- **What is downloaded.** The release APK (`app-release.apk`) and its
  `SHA256SUMS.txt`, both attached to the latest release of
  `sorsama/deepseek-harness-mobile`, as GitHub's own release API lists them.
  Nothing else is fetched, and nothing is uploaded.
- **From where.** HTTPS only, and only from `api.github.com`, `github.com` and
  the hosts GitHub serves release files from (`objects.githubusercontent.com`,
  `release-assets.githubusercontent.com`, `github-releases.githubusercontent.com`),
  on the default port. A release file answers with a redirect to a signed,
  short-lived URL on one of those hosts; the app follows redirects itself and
  checks every hop, so a redirect anywhere else, or down to plain HTTP, stops the
  download before a byte is read from it. Certificates are verified exactly as
  for any other HTTPS request (see **HTTPS through a reverse proxy**).
- **Where it is kept.** In the app's private cache (`cache/updates/`), which no
  other app can read or write. A partial download is resumed with an HTTP range
  request guarded by `If-Range`, so a file that changed on the server starts
  over instead of being stitched together. Old downloads are deleted on the next
  launch, and the system may clear the cache under storage pressure.
- **What is checked before Android sees it.** The file's SHA-256 against the
  release's `SHA256SUMS.txt`; a mismatch deletes it. A release without a
  checksum file (or one that does not list the APK) is not installed unless you
  choose **Install anyway** after being told it cannot be verified. Then the
  package manager reads the APK, and the app refuses it if its package name is
  not this app's, its version code is not newer than the installed one, or none
  of its signing certificates matches the installed app's (a key rotated through
  an APK Signature Scheme v3 lineage still matches). A debug build is told a
  release cannot replace it, since it has another application id and key.
- **Android's own check is the final guard.** The APK is handed to the system's
  `PackageInstaller`, which verifies the signature again and refuses to replace
  an app with one signed by a different key. Everything above exists to give a
  clear message before that point, not to replace it. Note what the checksum
  does and does not prove: it shows the file is the one published in the
  release, so it guards against damage and against a tampered mirror, but
  someone able to publish a release could publish a matching checksum too. The
  signature check is what ties an update to the key the app was first
  installed with.
- **The install permission.** The app declares `REQUEST_INSTALL_PACKAGES`.
  Android does not grant it at install: the first time an update is installed,
  the app opens Android's own **Install unknown apps** page for this app, and you
  decide there. You can switch it off again at any time; the app then cannot
  install anything. On Android 12 and newer the session asks for
  `USER_ACTION_NOT_REQUIRED`, which Android honours only once this app is the
  installer of record of itself, so the first in-app update always shows the
  system's confirmation and later ones may not (this also needs the normal
  permission `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, which the app declares). The
  app only ever installs an APK of itself. The phone's own app verifier (Play
  Protect on most phones) still checks every install and can refuse it.
- **Debug builds only:** a file in the app's private storage can point the
  updater at a local test feed (see `app/src/debug/.../UpdateFeedOverride.kt`).
  It accepts only loopback and the emulator's host alias, for the feed and for
  every download. Release builds compile a stub that always returns GitHub.

## What DSH Mobile stores

- Remembered endpoints (host, port, whether to use HTTPS, display name, and —
  for a relay — its device id and certificate pin) plus app preferences, in
  app-private storage only.
- **Relay bearer tokens, encrypted.** The key is generated in the Android
  Keystore and never leaves it; only ciphertext reaches DataStore. Forgetting a
  host drops its token in the same act, and Settings → clear data drops all of
  them. Revoking the relay's own record of the device happens on the relay.
- With the built-in harness: the runtime and the harness's own state, in the
  app's no-backup directory (see **This phone (built-in harness)**).
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
  carries. Apart from the built-in harness's own update check (below), which the
  same switch controls, it is the only request the app itself makes that leaves
  your network. Turn both off in **Settings → About → Check for updates**. With
  **Notify me about new app versions** on (off by default), the app's request is
  also made in the background, at most once a day.
- **Download and install**, when you tap it: the release APK and its checksum
  file from GitHub, as described in **Updating inside the app**.
- **Install via Termux**, when you tap it. Termux — not the app — downloads the
  release APK and `SHA256SUMS.txt` from `github.com` with `curl`, verifies the
  file, and opens Android's package installer, which asks you before anything
  is installed. Nothing is downloaded unless you tap the button.
- **The built-in harness runtime.** While a runtime is installed and **Check for
  updates** is on, the app reads the latest release's signed manifest from
  `github.com` when it opens (at most every four hours) and once a day in the
  background. The layer archives (GitHub's release CDN) are fetched only when you
  set up, update or go back, or by themselves if you turned on automatic installs.
  The harness it runs then makes the requests you ask it to, like any harness:
  the model provider you configured, `git clone` addresses you type, and whatever
  the agent's commands fetch.
- **Scanning** probes only your own device's IPv4 /24 — with a TCP connect
  followed by one argument-free `session/canOpenWorkspacePath` call in
  local-network mode, or by `/relay/health` in relay mode. Relay mode browses mDNS `_dsh._tcp` first and only sweeps if that
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
