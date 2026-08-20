# Changelog

All notable changes to DSH Mobile are documented here. Format based on
[Keep a Changelog](https://keepachangelog.com/); the project uses SemVer.

## [0.5.0] - 2026-08-20

The baseline moves to harness **0.1.0-rc.8**, and this one is a migration rather
than a re-verification. The previous move, rc.5 → rc.7, touched nothing on the
wire at all; this one changes a call's arguments, adds a required field to two
shapes, and gives slash commands something they never had — the ability to carry
a picture.

### Fixed

- **Every slash command was about to stop working.** rc.8 gave
  `commands/execute` a third argument, `images`, and the harness's gateway
  matches an args object against the method it is calling *exactly* — it refuses
  a missing key as readily as an unexpected one. This client sent two arguments,
  so against an rc.8 harness `/compact`, `/plan`, the permission picker and the
  feedback buttons would each have failed, and — because an unrecognised error
  code is treated as a broken link — each failure would also have raised the red
  connection banner over a session that was perfectly healthy. The client now
  sends the shape the host in front of it actually declares. It works out which
  by looking for `host.describe.home`, a field rc.8 made required and rc.7 never
  sent, rather than by comparing version strings: the app has never been willing
  to branch on a version string, and a fork or a downstream build deserves to be
  judged on what it sends. Both releases keep working.
- **A stopped answer used to vanish.** Tapping stop mid-reply discarded every
  word the model had already written: nothing was ever committed for a cancelled
  step, so the transcript closed over the gap as if the turn had never spoken.
  rc.8 finalises that prefix as a real message and marks it, and the client now
  reads the mark. It also stops the last complete message of a multi-step turn
  from being labelled interrupted when it was the *next* step that got cut.
- **Attaching several images made several messages.** The prompt call takes a
  list of content parts and the harness admits that list as one batch — this
  client was sending one call per picture, so four images became four separate
  user messages, and the host's own per-message limits on image count and total
  size could never fire at all. One message is now one call.
- `web_search` rows in the transcript went blank. rc.8 replaced the tool's single
  `query` with a `queries` array of up to four, and a row reading only the old key
  found nothing left to show; it now reads either, and lists them.

### Added

- **`/goal` and `/plan` take image attachments.** Type the command with pictures
  attached and they ride along — into the goal's objective, or into the message
  that opens plan mode.
- **A command that cannot take your pictures now says so.** Before, attaching an
  image to `/compact` silently demoted the whole line to a prompt: the literal
  text `/compact` went to the model, the command never ran, and nothing on screen
  explained why. It is refused instead, with the draft and the images both left
  where they were so the refusal is something you can act on.
- **Images are checked against every bound the host publishes, before upload.**
  The count, the total size, the file size, the resolution and — new in rc.8 —
  the 2000px per-side limit. Three of those five were being received and ignored,
  and the per-side one did not exist. A refused picture now names the limit it
  crossed rather than reading "Could not attach that image", and so does a
  refusal that still comes back from the harness: the same sentences serve both,
  so it does not matter to you which side said no.
- The picker also catches a file whose contents contradict its extension — a JPEG
  named `.png` — which the harness would have rejected after the round trip.

### Changed

- Protocol baseline moves to harness **0.1.0-rc.8**. What moved on the wire:
  `host.describe` gained a required `home`; the `imageLimits` projection gained a
  required `maxImageDimension`; `commands/execute` gained a required `images`
  argument; command descriptors gained an optional `input.images`;
  `assistant/message` gained an optional `interrupted`; `web_search` swapped
  `query` for `queries`. Four `team/*` event types were added for an experimental
  feature no shipped harness composes, and they pass through as unknown events
  the way anything unfamiliar does.
- The shipped per-image limit is **3.5MB**, down from 5MB, following the harness's
  own default. It applies only when a harness publishes no limits of its own; one
  that does is still obeyed. On an older harness that publishes none, a 4MB image
  it would have accepted is now turned away — the reverse mistake costs a round
  trip and a failed turn, so this is the direction to be wrong in.
- The mock harness answers a mismatched argument object the way the real gateway
  does, instead of accepting whatever arrives. That is what would have caught the
  `commands/execute` break from this side, and now it does.

## [0.4.0] - 2026-08-18

Two threads run through this release. The first is the question the agent asks
you: the card it arrives in can now be folded away while you decide, and — less
happily — the answer you type into it now actually reaches the model, which until
this release it did not. The second is chips, which turn out to have been
invisible on a light background since the beginning: the reasoning tiers in the
model picker, and every other plain chip in the app.

### Fixed

- **Every free-text answer this app has ever sent was discarded in transit.** The
  harness puts `custom` on the answer it belongs to; this client wrote it one
  level out, beside the list of answers rather than on one of them. The host
  parses that payload with a schema that *strips* keys it does not declare rather
  than objecting to them, so nothing failed: the answer went out, came back
  accepted, and reached the model with the typed text simply gone. Nobody could
  have noticed from this end. The batch is now built from a type whose shape is
  the wire's, and the mock harness enforces the host's real acceptance rules
  rather than acknowledging whatever arrives — which is what would have caught it.
- Even had it arrived, only one of them would have. A batch carried a single
  `custom` for all its questions, taken from whichever one happened to be
  answered first, so a second free-text answer overwrote nothing and went
  nowhere. Each question now carries its own.
- Paging back to check an earlier answer showed it blank, and paging forward
  again overwrote the real one with the blank. The panel kept its selection
  keyed on the page number, so leaving the page discarded it; the batch is now
  one list of drafts that paging only moves a cursor through.
- A batch that contained a plan review **alongside other questions** answered
  only the plan review and left the rest unsent. The host compares an answer
  batch against the request it resolves and refuses one of a different length
  outright, so the response was rejected, the harness's wait stayed open, and the
  `ask_user_question` call never unblocked — a hung session with nothing on
  screen to explain it. The decision card now claims a request only when it can
  answer all of it: one question, a plan to show, a binary choice, and an approve
  label naming a real option. Everything else takes the ordinary flow, where
  every answer is still reachable.
- Dismissing a question was not a dismissal. **Cancel** answered every question
  with an empty selection, which is a perfectly valid answer that the model reads
  as "no preference". It now fails the request the way the harness's own client
  does, and the host settles the tool call as cancelled.
- **Chat about it** on a plan review answered *Decline* and then cleared the
  draft, which told the agent something you had not said. It dismisses the
  request instead — wanting to talk it over first is not one of the options on
  offer. A plan review that offers no second option no longer draws a Decline
  button that had nothing to send.
- An option the model marks as its recommendation arrives with `(Recommended)`
  appended to the label — the tool's own schema tells it to write that — and the
  card showed the marker as part of the choice. It is now a badge beside the
  label, in both the English and Chinese forms and both widths of parenthesis,
  while the wire keeps the label whole, because the host checks a selection
  against the labels it sent.
- A question's supporting detail rendered as plain text, so a plan or a table in
  it arrived as markup.
- **Every plain chip in the app was invisible in light mode.** `DsPill` fills
  itself with `bgLayer2`, faithfully to the harness — but in the harness's light
  theme `bg-base` and all three `bg-layer` rungs are the same pure white, so a chip
  on any of them is white on white. The web never notices: `:hover` paints the chip
  the moment a pointer nears it. A touchscreen has no pointer to near it with, so
  the model and preset triggers in the details panel, the subagent counts in the
  drawer, the goal phase, the workflow status and the suggestion chips on the empty
  session were all just runs of grey text, two of them tappable with nothing to say
  so. Chips now rest on `bgModulePlatform`, which steps off every surface in both
  themes; a chip that does something takes a hairline as well, that being what is
  left to distinguish a trigger from a badge once both have a fill. This is the
  third time this app has had to relearn that a hover-revealed affordance is an
  invisible one — the chat-bar chips and the disclosure chevron were the first two.

### Added

- **The question card folds up.** A chevron in its header collapses it to the
  title strip, so you can read the conversation you are being asked about and
  then come back to it; the draft, the choice and the position in the batch all
  survive. This is the harness's own rc.7 addition, and it earns its place twice
  over here: the web card replaces the input bar in a fixed-height column, while
  this one sits between the transcript and the composer, where a question with a
  long detail and six options otherwise buries everything above it.
- Options are numbered on a single choice and carry a check box on a multiple
  one, so which kind of question you are looking at is visible before you tap.
- The card says when an answer is incomplete, and jumps to the question that is
  missing, rather than silently submitting empty answers. If the harness refuses
  the answer outright it now says so; before, the card simply stayed put.

### Changed

- The model picker reads as a set of choices rather than a list of words. Each
  model is a card now, the live one carrying the accent wash and border instead of
  only a blue name and a tick stranded at the far edge, and the reasoning tiers sit
  **inside** that card under a label that says what they set. They used to appear
  under every model in the list — a dead control beneath each row nobody had
  chosen, tripling the height of the sheet — and they were drawn as pills whose
  unselected fill is `bgLayer2`, which is the sheet's own colour, so three of the
  four tiers were invisible and the row read as a caption rather than a control.
  The tiers now use the segmented track the Chat / Trajectory tabs already use,
  lifted into `DsSegmented` so there is one such control rather than two. It gained
  an outline on the way: the track's fill is a step off `bgLayer1`, but in dark mode
  it is the *same* colour as `bgLayer2`, so on a sheet the fill alone showed nothing.
- The card no longer grows without limit. It takes at most a fixed share of the
  column and scrolls its options inside that, keeping the header and the actions
  reachable. It was previously measured before the composer below it, so a long
  batch could push the composer off the bottom of the screen entirely.
- Protocol baseline moves to harness **0.1.0-rc.7**. Nothing on the wire moved
  between rc.5 and rc.7 — no method, no event type, no projection key, no slash
  command — so this is a re-verification rather than a migration. The one label
  that did change: the `code` agent preset is **PTC mode** in English now, as it
  already was in Chinese. The preset's id is untouched.
- `docs/COMPATIBILITY.md` stops claiming the app compares the harness's version
  against the baseline and warns on a mismatch. It never did, and it should not:
  the harness releases far more often than this client and nearly always without
  touching the client surface, so the warning would fire on almost every session
  while still saying nothing about the changes that matter. The document now
  describes what the app actually relies on, which is degrading on shape.
- `docs/PROTOCOL.md` records how a question request is settled, including the
  rules the host checks an answer against. It is the one shape in this protocol
  where getting it wrong is silent.

## [0.3.1] - 2026-08-17

### Fixed

- Only the first button in any row was drawn. `DsButton` laid its content out
  with `fillMaxSize`, so the content claimed the whole width on offer and took
  the button with it, leaving nothing for whatever came next — the details
  panel showed **Rename** but not Fork or Archive, the export row showed
  **Download session log** but not Copy, the disconnect dialog showed no
  Cancel, and the update dialog added in 0.3.0 showed no **Later**. The content
  now fills only the height; a button that wants to span its parent still says
  so through its own modifier, as several already did.

## [0.3.0] - 2026-08-17

The theme of this release is the difference between a control that exists and a
control you can find: a scan that finishes, a search that answers, a session list
you can navigate, and buttons that look like buttons. It also fixes a crash that
took the app down on any session with a long log.

### Added

- Settings gained a **Plugins** section: one row carrying the count, opening a
  sheet that lists the harness's composed plugins by short module name with
  their enabled state and mount phase, the raw loader entry id behind a
  disclosure, and a filter. A sheet rather than an inline list because a real
  deployment mounts a hundred and fifty of them, which no settings page should
  try to hold. Read-only, because that is the whole of what the harness offers a
  client — `pluginInventory/list` has no counterpart that changes anything, and
  the `settings.*` calls behind the web UI's plugin configuration are
  loopback-pinned and answer 403 over a network.
- The app offers a new release when GitHub has one: a dialog naming the version,
  a link to the release page, and nothing else — it cannot install anything
  itself. Offered once per release; declining it stays declined until a later
  one appears. This is the only request the app makes to anything other than the
  harness, so Settings → About can switch it off.
- Subagent sessions nest under the session that spawned them in the chat list,
  each parent collapsible and carrying a count, to whatever depth the run went.
  They were previously dumped into one flat "Subagents" heading per workspace,
  which said nothing about which run produced which.
- The details panel can now change the model and the agent preset, and shows
  the current model at all.
- A sweep can be cancelled while it runs, and hosts appear as they are found
  rather than all at once when it finishes.
- A harness that is running but rejects this device is now listed and explained
  rather than dropped, since it is the most recoverable thing a scan can find.

### Changed

- **Scan network** is roughly an order of magnitude faster. The sweep now knocks
  each address with a bare TCP connect and only pays for `host.describe` where a
  socket opens, with a flat 128-wide fan-out over every address/port pair. It
  previously sent a full HTTP request to all 254 addresses, tried known ports in
  series, and synchronised every 32 probes so each batch cost its slowest member
  — the better part of a minute on one port, and minutes across several.
- The model, preset and subagent chips in the chat bar are drawn as pills rather
  than bare text. The harness's own triggers are transparent because they have a
  hover state; a touch screen does not, so nothing indicated they were tappable.
- The session-order control names the order it is in and offers the other one,
  instead of being an unlabelled ⇅ icon. The choice now persists.
- Plan mode is a labelled switch in the details panel rather than a card whose
  title stated one state and whose button stated the other.
- Loading earlier messages no longer fights the reader. Two things were wrong:
  decoding a page and re-folding the transcript ran on the main thread, because
  the call was launched from a composition scope and nothing moved it off; and
  the auto-scroll was keyed on the *item count*, so a page arriving at the top
  threw the view down to the newest message — the opposite of what asking for
  older messages means. The work now runs on a background dispatcher, and the
  scroll follows the newest `seq` instead, so only growth at the tail moves the
  view.
- The language picker is a dropdown instead of a grid of twelve cells. The grid
  spent four rows of the settings page on a choice made once, and at three per
  row the longer endonyms had to be ellipsised — so it was both the largest
  thing on the screen and unable to spell out its own options.

### Fixed

- The app ran out of memory and died shortly after opening a session with a long
  log. Two causes, both of which grew with the length of the session:
  - The transcript pulled history without limit. Automatic paging ran while the
    list was shorter than the screen, but a page is counted in *events* and most
    events — chunk deltas, tool traffic, turn boundaries — render nothing, so a
    session whose log is mostly machinery never filled the screen however much
    was loaded. It pulled four thousand events at a time until the heap gave
    out. The fill is now worth one page, after which the head of the list offers
    to fetch more; scrolling to the top still pages back as far as wanted.
  - Every streamed event re-folded the whole transcript and republished it. A
    turn arrives as a long run of deltas, so this was quadratic in the length of
    the session and allocated hundreds of megabytes a second. Rebuilds are now
    coalesced to one per display frame, and an in-order event no longer re-sorts
    the event list.
- Changing the app language flashed a black screen. Applying a locale is a
  configuration change, and the default response is to destroy and rebuild the
  activity — between the two there is no window at all, so the screen showed
  what is behind one, which is black. `MainActivity` now declares
  `configChanges="locale|layoutDirection"`, so the framework delivers the change
  instead of tearing the activity down: Compose re-reads its resources, the text
  swaps in place, and the transcript and scroll position survive. Verified by
  sampling frames through a switch — the frame that used to come back pure black
  no longer occurs, and right-to-left still mirrors correctly in Arabic.
- Two smaller things the same investigation turned up, both of which would have
  shown as a flash of the wrong colour once the black one was gone:
  `android:windowBackground` was transparent and the launch theme's background
  was a hardcoded white, and both now use one token with a `values-night`
  variant; and that token resolved against the *device's* dark-mode setting
  rather than the app's own Appearance, so an app set to Dark on a light phone
  had a white window behind it. The scheme is now applied to the resource layer
  from `Application.onCreate`, where it costs no extra activity restart.
- The chat bar named the session's agent preset with its raw wire id
  (`standard`) rather than a readable name, because the preset roster is
  host-scoped and nothing fetched it until the chip was tapped. It is now
  fetched on connect, and a shipped preset id resolves to its localized name
  even before the roster lands.
- Search did nothing. Its only source of results was `session.search`, which is
  full-text over message *content* and is off in the shipped harness
  configuration (`session-query-sqlite` at `openAt: never`) — so the call failed,
  the drawer swallowed the error behind itself, and the list never changed.
  Session titles and workspace names are now matched locally, as the harness's
  own sidebar does under the same configuration, with content hits merged in
  where the host provides them. When content search is unavailable the drawer
  says so once, quietly, instead of failing.
- Built-in agent presets displayed in Chinese whatever language the app was set
  to. The harness reads their names from `preset.yml` files written in Chinese
  and its web client overrides them with its own translations; this client
  trusted the wire name. The four shipped presets now read Standard / Code /
  Minimal / Creator mode in all eleven languages.
- The per-app language did not reach bottom sheets and dialogs on Android 12 and
  below. The app manages its own locale storage but only applied it after
  `onCreate`, by which point windows built from an earlier context had already
  taken the device language. AppCompat's `autoStoreLocales` now restores it in
  `attachBaseContext`, and `android:localeConfig` declares the shipped set.
- Plan mode could be turned on but never off: both directions of the toggle sent
  `/plan`, which only ever enters plan mode. Leaving requires `/plan off`.
- The user message bubble was still hard to see. It now sits a step darker than
  the web token with a stronger edge, and its width tracks the screen the way
  the harness's `min(525px, 82%)` does rather than a flat 320dp.
- A malformed session lineage could make a subagent its own parent, rendering
  neither it nor its children.

### Security

- `docs/SECURITY.md` gained a "What DSH Mobile connects to" section. The update
  check is the first request the app makes to anything other than the harness,
  so the document no longer claims every connection is a user-initiated LAN
  endpoint, and it names the switch that turns the check off.

## [0.2.0]

### Added

- History pages itself: scrolling back through a transcript fetches the next
  page automatically instead of asking for a tap, and a session that opens on
  fewer messages than the screen holds keeps pulling until it is full.
- "Connect manually" reports what it is doing — checking the address, reaching
  the host, opening the event streams, verifying the harness — rather than
  greying the button out and saying nothing.
- A failed connection now names its cause and the fix: a dropped connection
  (firewall or router client-isolation), a refused one (harness still bound to
  loopback), a trust-fence rejection, a name that does not resolve, a port
  serving something that is not a harness, or an address outside the phone's
  own subnet — which is checked before probing, and also explains why
  **Scan network** finds nothing.
- `harness/README.md` gained a Troubleshooting section covering each of those,
  including the Windows firewall rule and how to confirm the harness is bound
  to `0.0.0.0` rather than `127.0.0.1`.
- Cancel a connection attempt that is backing off and retrying.

### Fixed

- User messages rendered as plain text. The bubble was drawn every time and was
  invisible: its fill sits at a 1.06:1 contrast ratio against the white
  transcript background. It now carries a hairline border in both themes.
- A failed connection left the Connect button disabled indefinitely with no
  error. The failure watchdog polled for a connection phase the loop leaves
  within milliseconds of starting, so it could never fire.
- The connect pre-flight probe advertised a 700 ms budget that the transport
  discarded, so a manual connect could block for 30 seconds — and a subnet
  sweep for minutes — before reporting anything.
- A trust-fence rejection (HTTP 403) was reported as "could not reach a
  harness", sending people after a network problem while the harness was
  running and healthy. Rejections of the WebSocket upgrade were likewise
  unclassifiable.
- The address typed into the manual fields was lost on rotation.
- A validation failure reported the empty field rather than the address tried.
- A user turn whose content arrives as a bare string, or in a block kind this
  client does not recognise, no longer disappears from the transcript.

## [0.1.0] - unreleased

Initial release.

### Added

- Connection to a DeepSeek Harness (v0.1.0-rc.5) over the web `/api` protocol
  (HTTP unary + dual WebSocket event streams, reconnect with backoff).
- Discovery: manual host entry, active Wi-Fi subnet scan, remembered hosts,
  loopback (same-device) connection, auto-connect toggles.
- Discord-style navigation: swipe from the left edge opens the workspace-
  grouped chat list; right-edge swipe opens the session details panel.
- Chat: streamed turns, reasoning disclosure, markdown, tool cards
  (terminal/diff/read/search/web/generic), queue dock (edit/remove/steer),
  history paging, image attachments.
- Feature modules: goals, plan mode + plan review, approvals, user
  questions, todo dock, subagents, background jobs, workflow runs, skills,
  model selection, agent presets, settings (read-only over LAN), trajectory
  ledger, session export, message feedback.
- Notifications: turn complete, goal complete/blocked, review/question
  requested; foreground service for background connection.
- DeepSeek Harness visual design system (colors, typography, radii,
  components) with light/dark/system themes.
- Localization: en, zh-Hans, hi, es, fr, ar, bn, pt, ru, ur, th (RTL aware).
- Harness-side LAN companion (`harness/`) and developer tooling
  (`mock-harness/`, `tools/capture/`).
