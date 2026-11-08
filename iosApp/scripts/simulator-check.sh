#!/usr/bin/env bash
# Runs the debug app in an iOS simulator against the fixture harnesses and checks what it says.
#
#   simulator-check.sh <simulator udid> <path to "DSH Mobile.app" built for the simulator> <output dir>
#
# The fixtures are `mock-harness --ci-fixtures` (see CiFixtures in :mock-harness), listening on
# 127.0.0.1, which a simulator shares with the Mac. The app is driven by the debug-build launch
# arguments (`-DshConnectAddress`, `-DshAutoConnect`, `-DshSignInToken`, ...; see DSHMobileApp.swift), which
# go down the same path as typing the address and tapping. Each scenario waits for the one result line the app writes to
# Documents/dsh-connect-result.txt (a file, so nothing depends on how the console is buffered),
# asserts on it, and takes a screenshot into the output directory.
#
# Every scenario runs even if an earlier one failed, so one run reports everything; the script exits
# non-zero at the end if any did. The app's console output (`println`, uncaught Kotlin exceptions) is
# kept in <output dir>/console.log, and the crash reports of this app, if the Mac wrote any, are
# copied next to it.
set -uo pipefail

udid="$1"
app="$2"
out="$3"
bundle="com.labteto.dshmobile"
failures=0

mkdir -p "$out"
: > "$out/console.log"
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b > /dev/null
xcrun simctl ui "$udid" appearance light
xcrun simctl uninstall "$udid" "$bundle" 2>/dev/null || true
xcrun simctl install "$udid" "$app"
data="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
result="$data/Documents/dsh-connect-result.txt"

shot() {
  xcrun simctl io "$udid" screenshot "$out/$1.png" 2>&1 | tail -n 1
}

# Starts the app with the given arguments; its console output goes to console.log, tagged.
launch() {
  rm -f "$result"
  xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
  echo "=== launch: $*" >> "$out/console.log"
  xcrun simctl launch --console "$udid" "$bundle" "$@" >> "$out/console.log" 2>&1 &
  sleep 2
}

# Waits for the result line, checks it against the pattern, and gives the screen a moment to draw it.
expect() {
  local label="$1" pattern="$2" line
  for _ in $(seq 1 45); do
    [ -s "$result" ] && break
    sleep 1
  done
  line="$(cat "$result" 2>/dev/null || echo '<no result within 45 s>')"
  echo "[$label] $line" | cut -c1-400
  if ! grep -q -- "$pattern" <<<"$line"; then
    echo "::error::[$label] expected a result matching: $pattern"
    failures=$((failures + 1))
  fi
  sleep 3
}

# Every scenario but the two about what is remembered starts from `-DshReset YES`, which forgets the
# remembered harnesses, the settings and the Keychain, so none depends on the one before it. The shared
# Connect screen is the same Compose code the Android app runs; what is different here is only what the
# platform offers (one mode, no sweep, no Settings; see "The shared UI" in docs/IOS.md).

# 1. Address filled in, nothing sent yet: what a person sees on first launch before they tap Connect.
launch -DshReset YES -DshConnectAddress "http://127.0.0.1:3181"
sleep 8
shot "01-before-connect"
if [ -e "$result" ]; then
  echo "::error::the app connected without being asked to"
  failures=$((failures + 1))
fi

# 2. The same address, connected: a unary probe and the WebSocket handshake, both over NSURLSession.
launch -DshReset YES -DshConnectAddress "http://127.0.0.1:3181" -DshAutoConnect YES
expect connected "DSH_CONNECT_RESULT connected authority=127.0.0.1:3181 home=C:.Users.demo protocol="
shot "02-connected"

# 3. The harness it just connected to, remembered (NSUserDefaults), shown in the Recent list with its
#    live status. "Reconnect to the last harness" is off for this launch so the list is what is on screen.
launch -DshAutoReconnect NO
sleep 9
shot "03-recent"
if [ -e "$result" ]; then
  echo "::error::the app connected without being asked to"
  failures=$((failures + 1))
fi

# 4. And with it on, which is the default: the app comes back to the harness it remembers by itself.
launch -DshReport YES
expect reconnected-last "DSH_CONNECT_RESULT connected authority=127.0.0.1:3181"
shot "04-reconnected-last"

# 5. The same screen in the dark scheme.
xcrun simctl ui "$udid" appearance dark
launch -DshReset YES -DshConnectAddress "http://127.0.0.1:3181" -DshAutoConnect YES
expect connected-dark "DSH_CONNECT_RESULT connected"
shot "05-connected-dark"

# 6. A port nothing listens on: the honest failure, classified by what NSURLSession reported, in the dark.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3189" -DshAutoConnect YES
expect refused-dark "DSH_CONNECT_RESULT failed kind=REFUSED"
shot "06-refused-dark"
xcrun simctl ui "$udid" appearance light

# 7. The same refusal in the light scheme.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3189" -DshAutoConnect YES
expect refused "DSH_CONNECT_RESULT failed kind=REFUSED"
shot "07-refused"

# 8. A harness that wants a browser session this device does not have (HTTP 401): the lost surface offers Sign in.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3182" -DshAutoConnect YES
expect unauthenticated "DSH_CONNECT_RESULT failed kind=UNAUTHENTICATED"
shot "08-unauthenticated"

# 9. Sign in opens the launch-token sheet.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3182" -DshAutoConnect YES -DshOpenSignIn YES
expect signin-sheet "DSH_CONNECT_RESULT failed kind=UNAUTHENTICATED"
shot "09-signin-sheet"

# 10. And signing in with the fixture's token: the exchange runs over NSURLSession, the session goes to the
#     Keychain, and the connection that follows carries it, so the harness that said 401 now says ready.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3182" -DshAutoConnect YES -DshSignInToken launch-token-test
for _ in $(seq 1 45); do
  grep -q "connected authority=127.0.0.1:3182" "$result" 2>/dev/null && break
  sleep 1
done
echo "[signed-in] $(cat "$result" 2>/dev/null | cut -c1-300)"
if ! grep -q "connected authority=127.0.0.1:3182" "$result" 2>/dev/null; then
  echo "::error::[signed-in] expected the harness that wanted a session to connect after the token was submitted"
  failures=$((failures + 1))
fi
sleep 3
shot "10-signed-in"

# 11. Something that answers 403 before the harness does (a relay without its token).
launch -DshReset YES -DshConnectAddress "127.0.0.1:3183" -DshAutoConnect YES
expect fence "DSH_CONNECT_RESULT failed kind=TRUST_FENCE"
shot "11-fence"

# 12. The same refusal in French and in Arabic (right to left), to see the shared string resources pick the
#     device language and the screen mirror.
launch -DshReset YES -DshConnectAddress "127.0.0.1:3189" -DshAutoConnect YES -AppleLanguages "(fr)" -AppleLocale fr_FR
expect refused-fr "DSH_CONNECT_RESULT failed kind=REFUSED"
shot "12-refused-french"
launch -DshReset YES -DshConnectAddress "127.0.0.1:3189" -DshAutoConnect YES -AppleLanguages "(ar)" -AppleLocale ar_SA
expect refused-ar "DSH_CONNECT_RESULT failed kind=REFUSED"
shot "13-refused-arabic"

# 13. The form in French, in the light scheme, with the address typed (the labels, the notice, the switch).
launch -DshReset YES -DshConnectAddress "http://127.0.0.1:3181" -AppleLanguages "(fr)" -AppleLocale fr_FR
sleep 8
shot "14-form-french"

xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
wait 2>/dev/null || true

# Whatever the Mac recorded about this app dying.
mkdir -p "$out/crash-reports"
find "$HOME/Library/Logs/DiagnosticReports" -maxdepth 1 \( -iname 'DSH*' -o -iname 'DshMobile*' \) -exec cp {} "$out/crash-reports/" \; 2>/dev/null || true
ls -la "$out/crash-reports" || true

echo "--- app console (first 80 lines)"
head -n 80 "$out/console.log" | cut -c1-300

if [ "$failures" -ne 0 ]; then
  echo "::error::the simulator check failed $failures time(s)"
  exit 1
fi
echo "simulator check passed"
