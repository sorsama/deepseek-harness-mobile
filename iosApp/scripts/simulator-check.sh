#!/usr/bin/env bash
# Runs the debug app in an iOS simulator against the fixture harnesses and checks what it says.
#
#   simulator-check.sh <simulator udid> <path to "DSH Mobile.app" built for the simulator> <output dir> [fixtures log]
#
# With SIM_IPAD_UDID set to an iPad simulator, the session list scenarios also run there (the list is docked as a pane
# on an iPad); their screenshots go to <output dir>/drawer/, next to the iPhone ones.
#
# The fixtures log is the output of `mock-harness --ci-fixtures`; the pairing scenarios read the relay's code
# and the TLS relay's key from its `ci pairing:` line (default: $SIM_REPORTS/fixtures.log).
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
pair_result="$data/Documents/dsh-pair-result.txt"
probe_result="$data/Documents/dsh-probe-result.txt"
sessions_result="$data/Documents/dsh-sessions-result.txt"
drawer_result="$data/Documents/dsh-drawer-result.txt"
markdown_result="$data/Documents/dsh-markdown-result.txt"
toolcards_result="$data/Documents/dsh-toolcards-result.txt"
fixtures="${4:-${SIM_REPORTS:-build/ios-reports}/fixtures.log}"

shot() {
  xcrun simctl io "$udid" screenshot "$out/$1.png" 2>&1 | tail -n 1
}

# Starts the app with the given arguments; its console output goes to console.log, tagged.
launch() {
  rm -f "$result" "$pair_result" "$probe_result" "$sessions_result" "$drawer_result" "$markdown_result" "$toolcards_result"
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

# Like `expect`, for the pairing result line (Documents/dsh-pair-result.txt): `DSH_PAIR_RESULT paired …` when a
# relay has answered the claim, `DSH_PAIR_RESULT failed kind=…` when it has not. A pairing that works is then also
# checked with `expect` on the connection it starts, which proves the token was stored and presented.
expect_pair() {
  local label="$1" pattern="$2" line
  for _ in $(seq 1 45); do
    [ -s "$pair_result" ] && break
    sleep 1
  done
  line="$(cat "$pair_result" 2>/dev/null || echo '<no pairing result within 45 s>')"
  echo "[$label] $line" | cut -c1-400
  if ! grep -q -- "$pattern" <<<"$line"; then
    echo "::error::[$label] expected a pairing result matching: $pattern"
    failures=$((failures + 1))
  fi
  sleep 3
}

# Like `expect`, for the probe lines (Documents/dsh-probe-result.txt, written when the launch asks for reports): one
# `DSH_PROBE authority=… outcome=…` per remembered host, appended as each probe finishes. Passes when any line matches,
# since a launch probes every host it remembers (and again when the app comes back to the foreground).
expect_probe() {
  local label="$1" pattern="$2" lines=""
  for _ in $(seq 1 45); do
    lines="$(cat "$probe_result" 2>/dev/null || true)"
    grep -q -- "$pattern" <<<"$lines" && break
    sleep 1
  done
  echo "[$label] ${lines:-<no probe line within 45 s>}" | tr '\n' ' ' | cut -c1-400
  echo
  if ! grep -q -- "$pattern" <<<"$lines"; then
    echo "::error::[$label] expected a probe line matching: $pattern"
    failures=$((failures + 1))
  fi
  sleep 3
}

# Like `expect`, for the session list call the app makes once it is connected (Documents/dsh-sessions-result.txt):
# `DSH_SESSIONS_RESULT listed count=…` (the session store's list), or `DSH_SESSIONS_RESULT failed reason=…`.
expect_sessions() {
  local label="$1" pattern="$2" line
  for _ in $(seq 1 45); do
    [ -s "$sessions_result" ] && break
    sleep 1
  done
  line="$(cat "$sessions_result" 2>/dev/null || echo '<no sessions result within 45 s>')"
  echo "[$label] $line" | cut -c1-400
  if ! grep -q -- "$pattern" <<<"$line"; then
    echo "::error::[$label] expected a sessions result matching: $pattern"
    failures=$((failures + 1))
  fi
  sleep 3
}

# Like `expect`, for the session list being on screen (Documents/dsh-drawer-result.txt, written with `-DshOpenDrawer`):
# `DSH_DRAWER_RESULT shown docked=… sessions=…` once the list is visible with sessions in it. No line means no list: the
# scenario fails, whatever the screenshot shows.
expect_drawer() {
  local label="$1" pattern="$2" line
  for _ in $(seq 1 45); do
    [ -s "$drawer_result" ] && break
    sleep 1
  done
  line="$(cat "$drawer_result" 2>/dev/null || echo '<no drawer result within 45 s>')"
  echo "[$label] $line" | cut -c1-400
  if ! grep -q -- "$pattern" <<<"$line"; then
    echo "::error::[$label] expected the session list on screen: $pattern"
    failures=$((failures + 1))
  fi
  # The rows arrive with a short stagger; let them settle before the screenshot.
  sleep 4
}

# Readiness, not a scenario (see "0. A warm-up" below): unasserted launches until one connects to 3181, at most three,
# each line with its time. $1 labels the lines. A device on which none connects gets a warning, and the scenarios that
# follow then report whatever is wrong with it themselves.
warm_up() {
  local label="$1" attempt start line
  for attempt in 1 2 3; do
    launch -DshReset YES -DshConnectAddress "http://127.0.0.1:3181" -DshAutoConnect YES
    start=$(date +%s)
    for _ in $(seq 1 90); do
      [ -s "$result" ] && break
      sleep 1
    done
    line="$(cat "$result" 2>/dev/null || echo '<no result within 90 s>')"
    echo "[$label] attempt $attempt: $line after $(( $(date +%s) - start )) s" | cut -c1-300
    if grep -q "connected authority=127.0.0.1:3181" <<<"$line"; then
      sleep 2
      return 0
    fi
  done
  echo "::warning::[$label] no warm-up launch connected; the scenarios that follow run on a device that has not connected yet"
  sleep 2
}

# Whether the fixture harnesses still accept connections on the given ports, from the Mac (which the simulators share
# 127.0.0.1 with). A port that does not is a fixture that went away, which would fail every scenario on it. $1 labels it.
fixtures_listening() {
  local label="$1" port report=""
  shift
  for port in "$@"; do
    if nc -z -G 3 127.0.0.1 "$port" 2>/dev/null; then
      report+=" $port=yes"
    else
      report+=" $port=no"
      echo "::error::[$label] nothing accepts connections on 127.0.0.1:$port"
      failures=$((failures + 1))
    fi
  done
  echo "[$label] fixtures listening:$report"
}

# The session list (shared with Android since iOS M4b) against the demo harness, opened by `-DshOpenDrawer YES`: light,
# dark, French and Arabic (right to left). $1 names the device in the screenshots, $2 is whether the list is docked as a
# pane there (an iPad) rather than a drawer (an iPhone in portrait).
drawer_scenarios() {
  local device="$1" docked="$2" look
  mkdir -p "$out/drawer"
  for look in light dark fr ar; do
    local extra=()
    case "$look" in
      dark) xcrun simctl ui "$udid" appearance dark ;;
      *) xcrun simctl ui "$udid" appearance light ;;
    esac
    case "$look" in
      fr) extra=(-AppleLanguages "(fr)" -AppleLocale fr_FR) ;;
      ar) extra=(-AppleLanguages "(ar)" -AppleLocale ar_SA) ;;
    esac
    launch -DshReset YES -DshConnectAddress "$demo_url" -DshAutoConnect YES -DshOpenDrawer YES ${extra[@]+"${extra[@]}"}
    expect "drawer-$device-$look-connected" "DSH_CONNECT_RESULT connected authority=127.0.0.1:3190"
    expect_drawer "drawer-$device-$look" "DSH_DRAWER_RESULT shown docked=$docked sessions=$demo_count$"
    shot "drawer/$device-$look"
  done
  xcrun simctl ui "$udid" appearance light
}

# Every scenario but the two about what is remembered starts from `-DshReset YES`, which forgets the
# remembered harnesses, the settings and the Keychain, so none depends on the one before it. The shared
# Connect screen is the same Compose code the Android app runs; what is different here is only what the
# platform offers (one mode, no sweep, no Settings; see "The shared UI" in docs/IOS.md).

# 0. A warm-up, not asserted. The first network call a freshly installed app makes on a cold runner can take longer than
#    the connect probe's few seconds (CI saw a TIMEOUT on the very first connect once, and the same command passed
#    moments later), which is the runner's network stack starting up and not something a scenario is about. This launch
#    takes that hit, and its line and time are in the log so a slow start is visible. The iPad pass below needs the same:
#    without it the first connect on the freshly booted iPad timed out in every run (the probe's 4 s on 127.0.0.1, a
#    fixture that served the iPhone minutes before), and every later launch on the same iPad connected. Each launch is
#    a new process started with `-DshReset`, so what fails only once per device is the device warming up, not the app.
warm_up warm-up

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
launch -DshAutoReconnect YES -DshReport YES
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

# 10b. A new launch, nothing passed: the harness that wanted a session is the last one used, and the app
#     reconnects to it with the session it kept in the Keychain. Without one it would be told 401 again.
launch -DshAutoReconnect YES -DshReport YES
expect keychain-session "DSH_CONNECT_RESULT connected authority=127.0.0.1:3182"
shot "10b-signed-in-after-relaunch"

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

# ---- Pairing with a relay (the shared Pair screen) -------------------------------------------------------
#
# A simulator has no camera, so a scan is a debug launch argument that hands the Pair controller the text a QR
# would have decoded (`-DshPairPayload`, hex-encoded because launch arguments are parsed as property lists);
# typing is `-DshPairUrl`, `-DshPairCode` and `-DshPairSubmit`. Both go down the code a person's hands do. The
# relays are the fixtures' pairing relays: 3186 over plain HTTP and 3187 over TLS with a self-signed key, whose
# fingerprint the fixtures print. Each scenario starts in Relay mode, as a person who is about to pair would.
pairing_line="$(grep -m1 '^ci pairing:' "$fixtures" 2>/dev/null || true)"
pair_code="$(sed -n 's/.* code=\([0-9]*\).*/\1/p' <<<"$pairing_line")"
lockout_code="$(sed -n 's/.*rateLimitedCode=\([0-9]*\).*/\1/p' <<<"$pairing_line")"
plain_url="$(sed -n 's/.* plain=\([^ ]*\).*/\1/p' <<<"$pairing_line")"
tls_url="$(sed -n 's/.* tls=\([^ ]*\).*/\1/p' <<<"$pairing_line")"
tls_pin="$(sed -n 's/.* fingerprint=\([^ ]*\).*/\1/p' <<<"$pairing_line")"
refusing_url="$(sed -n 's/.* refusing=\([^ ]*\).*/\1/p' <<<"$pairing_line")"
echo "pairing fixtures: plain=$plain_url tls=$tls_url code=$pair_code lockout=$lockout_code pin=${tls_pin:0:12}…"
if [ -z "$pair_code" ] || [ -z "$plain_url" ] || [ -z "$tls_url" ] || [ -z "$tls_pin" ] || [ -z "$refusing_url" ] || [ "$refusing_url" = "null" ]; then
  echo "::error::the fixtures log has no usable 'ci pairing:' line ($fixtures)"
  failures=$((failures + 1))
else
  # A pairing QR's text for a relay, expiring [offset] seconds from now (negative: already expired).
  pair_payload() {
    local expires=$(( ($(date +%s) + ${4:-600}) * 1000 )) key=""
    [ -n "${3:-}" ] && key=",\"fingerprint\":\"$3\""
    printf '{"v":1,"kind":"dsh-relay-pair","url":"%s","code":"%s","expiresAt":%s%s}' "$1" "$2" "$expires" "$key"
  }
  hex() { printf '%s' "$1" | xxd -p | tr -d '\n'; }
  wrong_pin="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

  # 15. Relay mode is on, and with nothing paired it offers to pair.
  launch -DshReset YES -DshConnectMode relay
  sleep 8
  shot "15-relay-mode-nothing-paired"

  # 16. The Pair screen: the scan button, the two steps and the form to type the address and code.
  launch -DshReset YES -DshConnectMode relay -DshOpenPair YES
  sleep 8
  shot "16-pair-screen"

  # 17. The camera sheet. Here it is the "no camera" notice, with the typed code as the way on.
  launch -DshReset YES -DshConnectMode relay -DshPairScanner YES
  sleep 8
  shot "17-pair-scanner-without-a-camera"

  # 18. The address and code typed, a relay over plain HTTP: the claim, the token into the Keychain, and the
  #     connection that follows, which carries it as a bearer (the fixture refuses anything else with 403).
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$plain_url" -DshPairCode "$pair_code" -DshPairSubmit YES
  expect_pair paired-typed "DSH_PAIR_RESULT paired authority=127.0.0.1:3186 tls=false pinned=false"
  expect connected-typed "DSH_CONNECT_RESULT connected authority=127.0.0.1:3186"
  shot "18-paired-typed"

  # 19. A new launch, nothing passed: the relay is the last one used, and the app reconnects to it with the
  #     token it kept in the Keychain.
  launch -DshAutoReconnect YES -DshReport YES
  expect reconnect-relay "DSH_CONNECT_RESULT connected authority=127.0.0.1:3186"
  shot "19-relay-reconnected-from-the-keychain"

  # 20. Relay mode with that relay paired: its card, with the live status of a probe made with the token.
  launch -DshAutoReconnect NO
  sleep 9
  shot "20-relay-mode-paired"

  # 21. The same pairing from a scanned code (no key in it: the relay is plain HTTP).
  launch -DshReset YES -DshConnectMode relay -DshPairPayload "$(hex "$(pair_payload "$plain_url" "$pair_code")")"
  expect_pair paired-scanned "DSH_PAIR_RESULT paired authority=127.0.0.1:3186 tls=false pinned=false"
  expect connected-scanned "DSH_CONNECT_RESULT connected authority=127.0.0.1:3186"
  shot "21-paired-scanned"

  # 22. The relay refuses the code (a wrong passcode): the reason, and nothing stored.
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$plain_url" -DshPairCode 12345678 -DshPairSubmit YES
  expect_pair wrong-code "DSH_PAIR_RESULT failed kind=REJECTED"
  shot "22-pair-wrong-passcode"

  # 23. The same refusal in the dark scheme, and in Arabic (right to left).
  xcrun simctl ui "$udid" appearance dark
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$plain_url" -DshPairCode 12345678 -DshPairSubmit YES
  expect_pair wrong-code-dark "DSH_PAIR_RESULT failed kind=REJECTED"
  shot "23-pair-wrong-passcode-dark"
  xcrun simctl ui "$udid" appearance light
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$plain_url" -DshPairCode 12345678 -DshPairSubmit YES -AppleLanguages "(ar)" -AppleLocale ar_SA
  expect_pair wrong-code-ar "DSH_PAIR_RESULT failed kind=REJECTED"
  shot "24-pair-wrong-passcode-arabic"

  # 25. A lockout: the relay says how long to wait.
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$plain_url" -DshPairCode "$lockout_code" -DshPairSubmit YES
  expect_pair lockout "DSH_PAIR_RESULT failed kind=RATE_LIMITED"
  shot "25-pair-rate-limited"

  # 26. A scanned code that expired before it was sent: nothing goes on the wire.
  launch -DshReset YES -DshConnectMode relay -DshPairPayload "$(hex "$(pair_payload "$plain_url" "$pair_code" "" -600)")"
  expect_pair expired "DSH_PAIR_RESULT failed kind=EXPIRED"
  shot "26-pair-expired-code"

  # 27. Nothing listens at the address, and a harness that is not a relay.
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "http://127.0.0.1:3189" -DshPairCode "$pair_code" -DshPairSubmit YES
  expect_pair unreachable "DSH_PAIR_RESULT failed kind=UNREACHABLE"
  shot "27-pair-relay-unreachable"
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "http://127.0.0.1:3181" -DshPairCode "$pair_code" -DshPairSubmit YES
  expect_pair not-a-relay "DSH_PAIR_RESULT failed kind=NOT_A_RELAY"
  shot "28-pair-not-a-relay"

  # 29. A QR that is not a pairing code at all.
  launch -DshReset YES -DshConnectMode relay -DshPairPayload "$(hex "https://example.com/not-a-pairing-code")"
  expect_pair not-a-code "DSH_PAIR_RESULT failed kind=NOT_A_PAIRING_CODE"
  shot "29-pair-not-a-pairing-code"

  # 30. TLS, scanned: the QR names the relay's key, so the claim is made through a client that enforces it
  #     (DarwinPinning's pinned handler), and the mux socket that follows is pinned to the same key.
  launch -DshReset YES -DshConnectMode relay -DshPairPayload "$(hex "$(pair_payload "$tls_url" "$pair_code" "$tls_pin")")"
  expect_pair paired-pinned "DSH_PAIR_RESULT paired authority=.*127.0.0.1:3187 tls=true pinned=true"
  expect connected-pinned "DSH_CONNECT_RESULT connected authority=.*127.0.0.1:3187"
  shot "30-paired-pinned-over-tls"

  # 31. A new launch: the pinned relay is reconnected to from the Keychain's token and the stored key.
  launch -DshAutoReconnect YES -DshReport YES
  expect reconnect-pinned "DSH_CONNECT_RESULT connected authority=.*127.0.0.1:3187"
  shot "31-pinned-relay-reconnected"

  # 32. TLS, scanned, with a key that is not the relay's: the pin is refused and nothing is stored. This is the
  #     case the pin exists for, and it is "don't pair", not "try anyway".
  launch -DshReset YES -DshConnectMode relay -DshPairPayload "$(hex "$(pair_payload "$tls_url" "$pair_code" "$wrong_pin")")"
  expect_pair pin-mismatch "DSH_PAIR_RESULT failed kind=CERTIFICATE_PIN"
  shot "32-pair-key-does-not-match"

  # 33. TLS, typed: no key to check against, so the client learns the one it is shown (trust on first use) and
  #     the relay repeats it in its answer, which is what the connection afterwards is pinned to.
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$tls_url" -DshPairCode "$pair_code" -DshPairSubmit YES
  expect_pair paired-first-use "DSH_PAIR_RESULT paired authority=.*127.0.0.1:3187 tls=true pinned=true"
  expect connected-first-use "DSH_CONNECT_RESULT connected authority=.*127.0.0.1:3187"
  shot "33-paired-trust-on-first-use"

  # 34. A relay running `privilegedMethods: loopback-only` (the fixture on 3188) refuses the directory listing a
  #     Recent row used to read the host's home from, and the row said "Unreachable" and was not reconnected to
  #     although the relay was up and a tap connected. Pair with it, then relaunch: the app must reconnect by itself
  #     (the launch-time probe counted it reachable) and say so in its probe line.
  launch -DshReset YES -DshConnectMode relay -DshPairUrl "$refusing_url" -DshPairCode "$pair_code" -DshPairSubmit YES
  expect_pair paired-refusing "DSH_PAIR_RESULT paired authority=127.0.0.1:3188 tls=false pinned=false"
  expect connected-refusing "DSH_CONNECT_RESULT connected authority=127.0.0.1:3188"
  launch -DshAutoReconnect YES -DshReport YES
  expect reconnect-refusing "DSH_CONNECT_RESULT connected authority=127.0.0.1:3188"
  expect_probe probe-refusing "DSH_PROBE authority=127.0.0.1:3188 outcome=Reachable"
  # With reconnecting off the Connect screen stays up, and the row is the thing to look at.
  launch -DshAutoReconnect NO -DshReport YES
  expect_probe probe-refusing-listed "DSH_PROBE authority=127.0.0.1:3188 outcome=Reachable"
  shot "34-relay-refusing-the-listing-reads-reachable"
fi

# 35. The demo harness (3190), which has sessions in it (DemoHarness in :mock-harness). Once connected the app makes one
#     session list call and says how many came back, and the Connected page shows "Sessions 12" under the home line.
#     The count to expect is the one the fixtures print on their `ci demo:` line.
demo_line="$(grep -m1 '^ci demo:' "$fixtures" 2>/dev/null || true)"
demo_url="$(sed -n 's/.* url=\([^ ]*\).*/\1/p' <<<"$demo_line")"
demo_count="$(sed -n 's/.* sessions=\([0-9]*\).*/\1/p' <<<"$demo_line")"
if [ -z "$demo_url" ] || [ "$demo_url" = "null" ] || [ -z "$demo_count" ]; then
  echo "::error::the fixtures log has no usable 'ci demo:' line ($fixtures)"
  failures=$((failures + 1))
else
  launch -DshReset YES -DshConnectAddress "$demo_url" -DshAutoConnect YES
  expect connected-demo "DSH_CONNECT_RESULT connected authority=127.0.0.1:3190"
  expect_sessions sessions-demo "DSH_SESSIONS_RESULT listed count=$demo_count$"
  shot "35-connected-shows-the-session-count"

  # 36. A reconnect: once the list has been read, `-DshReconnectOnce YES` makes the app start a fresh connection,
  #     the call it makes when it comes back from the background (a script cannot bring a running app back to the
  #     front: `simctl launch` restarts it). The session store reads its list again on the new connection, so a
  #     second sessions line follows the first; with this argument the app keeps both.
  launch -DshReset YES -DshConnectAddress "$demo_url" -DshAutoConnect YES -DshReconnectOnce YES
  expect connected-demo-again "DSH_CONNECT_RESULT connected authority=127.0.0.1:3190"
  lines=""
  for _ in $(seq 1 45); do
    lines="$(cat "$sessions_result" 2>/dev/null || true)"
    [ "$(grep -c "^DSH_SESSIONS_RESULT listed count=$demo_count$" <<<"$lines")" -ge 2 ] && break
    sleep 1
  done
  echo "[sessions-after-reconnect] ${lines:-<no sessions lines within 45 s>}" | tr '\n' ' ' | cut -c1-400
  echo
  if [ "$(grep -c "^DSH_SESSIONS_RESULT listed count=$demo_count$" <<<"$lines")" -lt 2 ]; then
    echo "::error::[sessions-after-reconnect] expected two lines 'DSH_SESSIONS_RESULT listed count=$demo_count', one per connection"
    failures=$((failures + 1))
  fi
  shot "36-session-count-after-a-reconnect"

  # 37 to 40. The session list on this iPhone: a drawer over the chat pane, opened by the launch argument.
  drawer_scenarios iphone false
fi

# 41 to 44. The chat's Markdown (shared with Android since iOS M5a) on a debug page, `-DshMarkdownSample`: one fixed
#     answer with every block kind, at its top and scrolled to its foot, light and dark. No harness: no iOS screen shows
#     a transcript yet. No result line means the page never laid out, and the scenario fails whatever the screenshot shows.
mkdir -p "$out/markdown"
for look in light dark; do
  xcrun simctl ui "$udid" appearance "$look"
  for at in top end; do
    launch -DshMarkdownSample "$at"
    for _ in $(seq 1 30); do
      [ -s "$markdown_result" ] && break
      sleep 1
    done
    line="$(cat "$markdown_result" 2>/dev/null || echo '<no markdown result within 30 s>')"
    echo "[markdown-$look-$at] $line" | cut -c1-300
    if ! grep -q -- "^DSH_MARKDOWN_RESULT shown at=$at height=" <<<"$line"; then
      echo "::error::[markdown-$look-$at] expected the Markdown sample on screen"
      failures=$((failures + 1))
    fi
    sleep 2
    shot "markdown/iphone-$look-$at"
  done
done
xcrun simctl ui "$udid" appearance light

# 45 to 48. The chat's tool cards (shared with Android since iOS M5b) on a debug page, `-DshToolCardsSample`: every card
#     kind open, the three ways a call does not run, two system lines and a running and a stopped command, at the top
#     and scrolled to the foot, light and dark. No harness, as for the Markdown page. No result line, no pass.
mkdir -p "$out/toolcards"
for look in light dark; do
  xcrun simctl ui "$udid" appearance "$look"
  for at in top end; do
    launch -DshToolCardsSample "$at"
    for _ in $(seq 1 30); do
      [ -s "$toolcards_result" ] && break
      sleep 1
    done
    line="$(cat "$toolcards_result" 2>/dev/null || echo '<no tool cards result within 30 s>')"
    echo "[toolcards-$look-$at] $line" | cut -c1-300
    if ! grep -q -- "^DSH_TOOLCARDS_RESULT shown at=$at cards=19 height=" <<<"$line"; then
      echo "::error::[toolcards-$look-$at] expected the tool card sample on screen"
      failures=$((failures + 1))
    fi
    sleep 2
    shot "toolcards/iphone-$look-$at"
  done
done
xcrun simctl ui "$udid" appearance light

# 49 to 52. The same list on an iPad, where it is a pane beside the chat (the Android tablet layout: planShell docks
# the list from 600dp wide). Same app, installed on the second simulator.
if [ -n "${SIM_IPAD_UDID:-}" ] && [ -n "$demo_url" ] && [ "$demo_url" != "null" ] && [ -n "$demo_count" ]; then
  xcrun simctl terminate "$udid" "$bundle" 2>/dev/null || true
  # The iPhone is done with. Shut it down, so the iPad does not boot beside a second running simulator: with both up
  # the iPad took 10 to 12 minutes to boot, and a screenshot of it over a minute. The times are in the log.
  xcrun simctl shutdown "$udid" 2>/dev/null || true
  udid="$SIM_IPAD_UDID"
  ipad_start=$(date +%s)
  xcrun simctl boot "$udid" 2>/dev/null || true
  xcrun simctl bootstatus "$udid" -b > /dev/null
  ipad_booted=$(date +%s)
  xcrun simctl uninstall "$udid" "$bundle" 2>/dev/null || true
  xcrun simctl install "$udid" "$app"
  echo "[ipad] booted in $(( ipad_booted - ipad_start )) s, the app installed in $(( $(date +%s) - ipad_booted )) s"
  data="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
  result="$data/Documents/dsh-connect-result.txt"
  pair_result="$data/Documents/dsh-pair-result.txt"
  probe_result="$data/Documents/dsh-probe-result.txt"
  sessions_result="$data/Documents/dsh-sessions-result.txt"
  drawer_result="$data/Documents/dsh-drawer-result.txt"
  markdown_result="$data/Documents/dsh-markdown-result.txt"
  toolcards_result="$data/Documents/dsh-toolcards-result.txt"
  fixtures_listening ipad 3181 3190
  warm_up warm-up-ipad
  drawer_scenarios ipad true
elif [ -z "${SIM_IPAD_UDID:-}" ]; then
  echo "SIM_IPAD_UDID is not set: the iPad session list scenarios did not run"
fi

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
