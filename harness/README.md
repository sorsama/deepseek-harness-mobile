# LAN setup for DSH Mobile

This guide targets harness `0.2.0-rc.1`, and still applies to `0.1.7-rc.x` and
`0.1.6-alpha.x`.

The harness authenticates every API call and WebSocket stream. On startup it prints a
root URL containing a launch token. In DSH Mobile, connect to the host, choose **Sign in**,
and paste that startup link or its token. The app exchanges it for the host's browser
session cookie through the existing transport. A token is accepted only by the root
exchange; it is not an API bearer token. Keep the startup link private.

Authentication does not encrypt plain HTTP. Use a trusted LAN, a TLS reverse proxy,
or the separately maintained [dsh-relay](https://github.com/sorsama/deepseek-harness-relay).
A TLS proxy encrypts traffic; the harness still authenticates it. The relay has its own
pairing and credential policy. Current-master relay validation is recorded separately
in [the validation report](../docs/VALIDATION-0.11.0.md).

For USB or an emulator, keep the default loopback listener and run
`adb reverse tcp:3080 tcp:3080`, then connect to `127.0.0.1:3080` and sign in.
No LAN patch is needed.

The web listener defaults to `127.0.0.1`; `dsh web --host 0.0.0.0` remains unsupported.
For LAN serving, use the host's user patch layer below. Host/Origin checks are separate
from authentication: an untrusted authority gets 403, while a trusted authority without
a valid browser session gets 401.

## Steps

1. Locate your harness home: `$DSH_HOME` or `~/.dsh` (on Windows typically
   `C:\Users\<you>\.dsh`). The web profile lives at
   `<harness-home>/profiles/web/cordis.patch.yml`.

2. If the file does not exist, create it. Append (or merge) the row from
   [`cordis.patch.lan.yml`](./cordis.patch.lan.yml) — it restates the
   `webserver` row to bind all interfaces:

   ```yaml
   - id: webserver
     name: '@deepseek-ai/dsh-host-webserver'
     inject: [webStartup]
     config:
       host: '0.0.0.0'
       port: 3080
   ```

3. Restart the harness web profile:

   ```sh
   dsh web
   ```

   The URL line now prints a LAN address:

   ```
   dsh web: http://127.0.0.1:3080 (LAN: http://192.168.1.20:3080)
   ```

   When bound to all interfaces, the harness automatically trusts its own
   LAN IP literals (the `/api` trust fence derives them from the bind host),
   so no `--trusted-host` entry is needed for a plain IP connection. If you
   reach the harness through a hostname instead, add it explicitly:

   ```sh
   dsh web --trusted-host myhost.local
   ```

4. In DSH Mobile, make sure **Local network** is selected on the connect
   screen, then tap **Scan network** or enter `192.168.1.20` / `3080` manually.
   Relay mode will not find a harness patched this way — it looks for relays.

## HTTPS via your own reverse proxy

The harness never serves TLS itself, but the app can connect through a
reverse proxy that does — Caddy, nginx, Traefik — for setups like
`https://agent.home` on a homelab. What has to be true:

1. **The proxy terminates TLS and forwards to the harness** (loopback or the
   LAN bind above). With Caddy that is the whole Caddyfile:

   ```
   agent.home {
       reverse_proxy 127.0.0.1:3080
   }
   ```

2. **The harness trusts the proxy's hostname.** The app (like a browser)
   sends `Host: agent.home`, and Caddy forwards it unchanged, so:

   ```sh
   dsh web --trusted-host agent.home
   ```

3. **The phone trusts the certificate.** A LAN proxy is usually signed by a
   local CA (Caddy's internal CA, mkcert); install that CA certificate on the
   phone (Android: Settings → Security → Install a certificate → CA
   certificate). A certificate the phone does not trust fails the connect
   with the HTTPS message below.

4. In the app, enter `https://agent.home` in the host field — or just the
   hostname with port 443, which the app reads as HTTPS. A port typed inside
   the host field (`https://agent.home:8443`) wins over the port field, so
   pasting the proxy's URL as-is works.

## Troubleshooting

The app names the failure it hit; find that heading below. Everything here is run on the
**computer** — the phone is the device that reports the problem, not the one that fixes it.

### "Nothing answered" / the connect stalls and times out

The packets are being dropped, not refused. Almost always the computer's firewall: Windows puts an
unrecognised network into the **Public** profile, which blocks inbound TCP. In an elevated
PowerShell:

```powershell
New-NetFirewallRule -DisplayName "DeepSeek Harness (dsh web)" -Direction Inbound `
    -Action Allow -Protocol TCP -LocalPort 3080 -Profile Private,Domain
```

Then set the Wi-Fi/Ethernet connection to **Private** (Settings → Network & internet → *your
network* → Network profile type). If it still times out, the router is the suspect: many have
*AP isolation* / *client isolation* that stops wireless clients reaching wired ones, and guest
SSIDs almost always do. Check that the phone's IP and the computer's IP share their first three
octets.

### "Refused the connection"

The computer is reachable and nothing is listening on that port — the harness is still bound to
loopback. Confirm:

```powershell
netstat -ano | findstr 3080
```

`127.0.0.1:3080` means the LAN patch is not in effect; `0.0.0.0:3080` means it is. Re-check
`<harness-home>/profiles/web/cordis.patch.yml` against the steps above and restart `dsh web`.
If you changed the port, make sure the app's Port field matches.

### "The harness rejected this address" (HTTP 403)

The trust fence only auto-trusts the IP literals it derives from the bind host. Connect using the
IP address rather than a hostname, or start the harness with `dsh web --trusted-host myhost.local`.

### "Not on this phone's network"

The address you typed is outside the phone's own /24, so nothing on the phone can route to it — and
**Scan network** cannot find it either, since the sweep only walks the phone's own subnet. Different
bands of one SSID (2.4 GHz vs 5 GHz) are normally the same subnet and are fine; a *guest* SSID
usually is not. Compare `ipconfig` on the computer with the address the app reports.

### "The secure (HTTPS) connection failed"

The socket opened but the TLS handshake did not survive it. Two causes, in order of likelihood:

- **The phone does not trust the certificate.** Install the CA that signed the proxy's
  certificate on the phone (step 3 above). Caddy's internal root lives at
  `~/.local/share/caddy/pki/authorities/local/root.crt` on the proxy host.
- **The address does not actually serve HTTPS.** `https://` typed at a plain-HTTP harness port
  gets an answer TLS cannot read. Connect without `https://` — the harness's own port speaks
  plain HTTP, always.

### "Its event stream would not open"

The `/api` calls succeed but the WebSocket upgrade does not. A VPN, private DNS, or an HTTP proxy on
the phone is the usual cause — turn it off and retry.

### Finding the computer's LAN IP

```sh
ipconfig                      # Windows
ip addr                       # Linux
ipconfig getifaddr en0        # macOS (Wi-Fi)
```

## Notes

- **Security**: every API request requires a valid browser session. LAN HTTP still
  transmits that session and all content without encryption. Only expose the listener
  on a trusted network, and see [../docs/SECURITY.md](../docs/SECURITY.md).
- **Privileged features**: since harness 0.1.2 there is no loopback-only tier
  — a device that has exchanged the startup link reaches settings, credentials,
  host directory pickers and agent-preset authoring like the web GUI does. Only
  a relay's own `privilegedMethods` policy narrows that (see
  [../docs/SECURITY.md](../docs/SECURITY.md)).
- **File attachments**: a file attached in the app is copied verbatim onto this
  computer, into the harness's attachment store, over the same unencrypted link.
- **Revert**: delete the patch row and restart to return to loopback-only.
- **Same device**: a harness running on the phone itself (in Termux) or
  forwarded to it with `adb reverse tcp:3080 tcp:3080` needs none of this
  page. Pick **This phone** in the app; with Termux it can also start and stop
  the harness and sign in on its own. See [`TERMUX.md`](./TERMUX.md).
