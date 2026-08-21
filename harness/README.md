# LAN mode for the DeepSeek Harness (companion setup for DSH Mobile)

> **Consider the relay instead.** This page rebinds the harness to every
> interface with **no authentication of any kind** — anyone on the same Wi-Fi can
> drive the agent, which means running commands on your computer.
> [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) leaves the
> harness on loopback and puts an authenticated TLS listener in front of it,
> which is the only arrangement here that asks who you are. (Your own reverse
> proxy, below, encrypts the link but authenticates nobody: whoever reaches the
> proxy reaches the agent.)
>
> ```sh
> dsh plugin --profile web add dsh-relay
> dsh web
> ```
>
> Then pair the phone from **Relay → Pair a relay**. The relay refuses to start
> at all if it finds the harness already bound to `0.0.0.0`, so remove the patch
> below before installing it. Everything from here down is the unauthenticated
> path, kept for setups that cannot run the plugin.

The DeepSeek Harness web server binds to `127.0.0.1` by default, and the
`dsh web --host 0.0.0.0` flag is intentionally blocked for safety (the
harness has no authentication layer yet). To use DSH Mobile over Wi-Fi without
the relay you enable LAN serving through the harness **user patch layer** — the
supported configuration seam.

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

- **Security**: there is no authentication. Anyone on your LAN can reach the
  harness while it binds `0.0.0.0`. Only use LAN mode on networks you trust, and
  prefer the relay described at the top of this page.
  See [../docs/SECURITY.md](../docs/SECURITY.md).
- **Privileged features**: settings, credentials, host directory pickers and
  agent-preset authoring stay loopback-only by design; the app shows them
  read-only with a banner when connected over the network.
- **Revert**: delete the patch row and restart to return to loopback-only.
- **Same device**: to drive a harness running on the phone itself (e.g. via
  Termux) or via `adb reverse tcp:3080 tcp:3080`, just connect to
  `127.0.0.1:3080` — no patch needed.
