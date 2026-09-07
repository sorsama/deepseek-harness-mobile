# The harness on the phone itself (Termux) — companion setup for DSH Mobile

The DeepSeek Harness is a Node program, and Termux runs Node, so the harness can run on the
same phone as the app. That is the zero-configuration setup: no LAN patch, no relay, no
firewall, nothing leaves the device. The harness binds `127.0.0.1:3080` by default and trusts
loopback without being told to, and the app's **This phone** mode talks to exactly that address.

What the app adds on top, when Termux is installed:

- **Start harness / Stop harness** on the connect screen and in Settings, run inside Termux.
- **Automatic sign-in.** Since harness 0.1.2 every direct connection has to present the launch
  token the harness prints once per process. When the app starts the harness, it reads that
  line itself and signs in — the one manual step of every other direct setup is gone.
- **Install via Termux** in the update dialog: Termux downloads the release APK, checks it
  against the published `SHA256SUMS.txt`, and opens the package installer.

Everything below is run **in Termux**.

## Install

1. Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or from
   [GitHub releases](https://github.com/termux/termux-app/releases). Not the Play Store
   build: it is years out of date and cannot run current Node. Termux 0.118 or newer.

2. Node.js 22.19 or newer, then the harness CLI:

   ```sh
   pkg update && pkg install nodejs
   npm install -g @deepseek-ai/dsh
   ```

3. Run it once by hand to see that it works:

   ```sh
   dsh web --no-open
   ```

   It prints the readiness line — `dsh web: http://127.0.0.1:3080/?token=…` — and stays in the
   foreground. `--no-open` matters on Android: without it the harness tries to spawn a browser
   the way it would on a computer. Set the model and credentials through the web GUI (open the
   printed link in the phone's browser) or through the app's Settings once it is connected.

## Let the app start and stop it

1. Allow other apps to run commands in Termux. One line, once:

   ```sh
   mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings
   ```

2. In DSH Mobile choose **This phone**, then tap **Start harness**. Android asks whether the
   app may *Run commands in Termux environment*; that permission is defined by Termux, is only
   ever requested from a tap, and can be revoked in Android's app settings at any time.

3. The card goes through *Starting the harness in Termux…* to *Harness started and signed in*,
   and the app connects. From then on the harness is remembered under **This phone** and
   auto-connect finds it whenever it is up.

What the app actually runs, so there are no surprises:

- **Start** hands `bash` a script that checks for `node` and `dsh`, takes a Termux wake lock
  (`termux-wake-lock`, which is what keeps Termux — and every process it started — alive once
  the script returns), launches `dsh web --no-open --port <port>` with its output in
  `~/.dsh/dsh-mobile-web.log` and its pid in `~/.dsh/dsh-mobile-web.pid`, waits up to a minute
  for the readiness line, and prints that line back. The app exchanges the token on it for a
  session cookie and stores the cookie, as it would for a line you pasted.
- **Stop** sends `SIGTERM` to the process the pid file names (and its process group), waits up
  to six seconds, then `SIGKILL`, removes the pid file and releases the wake lock. It never
  guesses at a process it did not start: a `dsh web` you ran in a Termux session is yours to
  stop.
- **Start the harness in Termux when the app opens** (Settings → Harness on this phone) does
  the start above on launch, only when the harness is down and only after the permission has
  already been granted from a tap.
- Nothing runs on launch otherwise, and nothing runs while the app is in the background.

A harness you start yourself is found all the same: the card reads *Running — sign in needed*
until you paste the startup line once (**Sign in**), and the cookie it obtains survives harness
restarts. The port field on the card follows a `--port` you chose.

## Keeping it alive

Android is keen to kill background work, and Termux is background work.

- **Battery optimisation.** Exempt Termux (Android Settings → Apps → Termux → Battery →
  Unrestricted). The wake lock the start script takes shows as a Termux notification; that
  notification is what keeps the harness running with the screen off.
- **Android 12 and later kill "phantom" processes** — anything forked by an app that is not
  itself an app — once there are more than 32 of them, and the harness is several. Termux's
  wiki covers the fix, which needs `adb` from a computer once:

  ```sh
  adb shell "settings put global settings_enable_monitor_phantom_procs false"
  ```

  On Android 14 that key is enough on most devices; on 12 and 13 the
  [Termux wiki](https://github.com/termux/termux-app/issues/2366) lists the `device_config`
  form and the per-vendor exceptions.
- Some vendors (Xiaomi, Huawei, Samsung with aggressive sleep) need their own exemption;
  [dontkillmyapp.com](https://dontkillmyapp.com/) is the reference.

## Updating the app from Termux, or over SSH

**From the app.** When a newer release exists the update dialog offers **Install via Termux**.
Termux downloads `app-release.apk` into `~/downloads`, verifies it against `SHA256SUMS.txt`
when the release published one, and opens the package installer with `termux-open`. Android's
installer prompt appears on the phone screen as it would for a browser download. **Copy install
command** puts the same `curl … && termux-open …` line on the clipboard.

**Over SSH.** A phone that is mostly a server can be administered from a computer:

```sh
pkg install openssh
passwd            # set a password for the Termux user
sshd              # listens on port 8022
whoami            # the user name to log in as, e.g. u0_a231
```

From the computer (`ssh-copy-id -p 8022 u0_a231@<phone-ip>` first, if you prefer keys):

```sh
scp -P 8022 app-release.apk u0_a231@<phone-ip>:~/
ssh -p 8022 u0_a231@<phone-ip> 'termux-open ~/app-release.apk'
```

`termux-open` hands the file to the package installer through Termux's own content provider;
the confirmation still has to be tapped on the phone. The install command the app copies works
verbatim in that SSH session too. To start the harness over SSH so that the app can sign in,
use the app's **Start harness** afterwards, or run `dsh web --no-open` under `tmux`/`nohup` and
paste the printed line into the app once.

## Troubleshooting

The app names what it found; find that line here.

### "Not running" / "Nothing is listening on port 3080"

Nothing is bound to the port. Start the harness from the card, or in Termux run
`dsh web --no-open` and watch for the readiness line. If the port field says something other
than 3080, the harness has to be started with the same `--port`.

### "Running — sign in needed"

The harness is up and answering 401: it has no session for this app. Either paste its startup
line (**Sign in**), or stop it and let the app start it, which signs in on its own. A harness
the app started that still says this printed no token — an older harness than 0.1.2 — and needs
the line pasted once.

### "Termux refused the command: allow-external-apps is off"

Step 1 of *Let the app start and stop it* was skipped. The card shows the exact line to run,
with a Copy button. `termux-reload-settings` applies it without restarting Termux.

### "Termux commands are not allowed"

The permission was refused, or revoked in Android settings. Android Settings → Apps → DSH
Mobile → Permissions → *Additional permissions* → allow, then tap Start again. A phone without
Termux installed never shows this: the permission does not exist there.

### "dsh is not installed in Termux" / "Node.js is not installed in Termux"

`command -v dsh` and `command -v node` came up empty in the Termux environment the app runs
commands in. `pkg install nodejs && npm install -g @deepseek-ai/dsh`; if you installed Node
another way, make sure it is on Termux's `PATH` (`$PREFIX/bin`), because a command run from
another app gets no login shell.

### "The harness exited before it was ready"

`dsh web` started and died. The card shows the last lines of `~/.dsh/dsh-mobile-web.log`; the
usual causes are a port already in use, a Node older than 22.19, or a harness not yet configured.
Run `dsh web --no-open` in a Termux session to see the whole output.

### "The harness did not become ready within a minute"

It is still starting, or it is stuck. A first start on a slow phone can take longer than the
script waits; the card keeps probing, so if the harness comes up a moment later it shows
*Ready* on its own. If it never does, the log says why.

### "Termux did not answer"

Termux's command service could not be started or never reported back. Open Termux once so it
is running, check that the build is 0.118 or newer, and try again. On Android 12+ this is also
what a phantom-process kill mid-start looks like — see *Keeping it alive*.

### "Port 3080 is taken by something that is not a harness"

Something else answers there. `ss -ltnp` in Termux (`pkg install iproute2`) names it; change
the port on the card and start the harness on that port instead.

### The harness dies when the screen goes off

Battery optimisation or the phantom-process killer, in that order. See *Keeping it alive*.
