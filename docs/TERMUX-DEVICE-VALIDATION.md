# Termux same-device validation — 2026-10-09

Branch: `termux-device-validation`, based on upstream `f54595c` (includes PR #54).
Old PR #16 commit `3b80f1e` was ported, preserving current 0.15.0 build version,
protocol DTOs, recent connection fixes, and manual/resume update checking.

## Device and runtimes

Realme RMX3998, Android device connected via ADB, arm64.

1. Initially installed `com.termux` version `googleplay.2026.06.21`.
   It did **not** declare `com.termux.permission.RUN_COMMAND`, so the draft's
   RunCommandService bridge could not operate. With explicit user approval this
   installation was removed (including its files) and replaced by the official
   GitHub v0.118.3 arm64 APK. APK digest was checked against the release checksum list.
2. Official GitHub Termux declares the permission and provides the exported service.
   The app permission was first granted through ADB. The in-app permission dialog was
   tested later, from Stop after the permission had been revoked (see below).
3. Native Termux Node 26.4.0 with Python, CMake, Clang and Make could not install the
   current harness: `koffi` fails compiling its `statx` call against Bionic headers.
   Before upgrading bootstrap libraries, Node also hit an OpenSSL symbol mismatch.
4. Debian trixie via proot-distro, with Linux arm64 Node 24.12.0, successfully
   installed `@deepseek-ai/dsh`. Debian's packaged Node 20 was below the harness's
   supported version, so a supported Node 24 runtime was installed explicitly.

## Explicit device-only adapter

A Termux `$PREFIX/bin/dsh` wrapper invokes `proot-distro login debian`, enters
`/root/dsh-mobile-test-workspace`, sets the Linux Node PATH, and execs Debian's dsh.
This wrapper is **not a shipped automatic installer or a native-Termux success**.
It allowed the existing app bridge/start scripts to be validated against a working
runtime without adding implicit distro selection to the app.

Termux `allow-external-apps=true` was enabled. Tests touched only the throwaway
phone workspace `/root/dsh-mobile-test-workspace`. No real user project was used.

## Verified through the actual app

- This phone mode and Start harness controls appeared.
- Start called Termux RunCommandService and received its result callback.
- The harness printed its localhost readiness line and token.
- The app exchanged that token and connected **without manual token entry**.
- Settings showed Connected to `127.0.0.1:3080` and “Harness started and signed in”.
- Unauthenticated local API requests returned 401.
- An authenticated `session/create` succeeded for the throwaway workspace.
- Stop from Settings removed the managed PID file and closed the listening service.
- Restart from Settings created a new PID and produced a fresh readiness line.
- After unlocking, Settings still showed Connected and “Harness started and signed in”.
- Revoking the command permission exposed a bug: Stop bypassed the permission launcher.
  Both connect/settings Stop controls were fixed to request permission and resume Stop,
  not Start. The actual system “execute arbitrary commands within Termux” dialog was
  captured, Allow was tapped, and the PID disappeared; the UI settled to Not running.
- After readiness, six probes over a full minute with the screen off each returned
  401 (server alive and enforcing authentication). Android power state showed
  `PARTIAL_WAKE_LOCK 'termux:service-wakelock'` held by Termux.

Core tests, mock-harness tests, app unit tests, lintDebug and assembleDebug passed
on the initial port. App tests, lintDebug and assembleDebug passed again after the
Stop permission fix.

## Not yet established

- Long-duration permission/restart cycles beyond the checks described above.
- Overnight survival, Doze, vendor process killer behavior, or reboot autostart.
- Model/tool execution: no model credentials were installed into the phone harness.
- Release APK installer/download/checksum flow: a debug APK cannot be replaced by
  a differently signed release APK without an uninstall; not exercised.
- Proot support as a supported product feature: requires documented setup and
  explicit runtime selection/design, not just the test wrapper.

## Required documentation correction

The old draft's “Termux runs Node, therefore npm install works” instructions are
not sufficient for current Android native dependencies. Native installation failed
on this device even with build prerequisites. Docs must explain that limitation
and distinguish native Termux from the validated proot fallback. Play Termux is not
necessarily obsolete, but **this tested Play build lacks the integration contract**.

Do not mark PR #16 fully validated based on this report alone.
