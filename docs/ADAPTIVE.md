# Adaptive layout: tablets, foldables, flips

The Android app lays itself out from the window it is given, not from the device it is on. The
rules live in one pure function, `planShell` (`app/.../ui/adaptive/ShellPlan.kt`), and the
composables draw whatever plan it returns. Nothing in the plan reads an aspect ratio, so a 22:9 Flip
inner screen and a 16:10 tablet go through the same code.

## Inputs

- The window's size in dp, from the `BoxWithConstraints` at the root of `MainScreen`. The keyboard
  does not shrink it: insets are applied by the panes, not by the root.
- The fold or hinge that crosses the window, from Jetpack WindowManager's `WindowInfoTracker`
  (`rememberHinge`, `ui/adaptive/WindowFold.kt`), copied into a plain `Hinge` in the window's own dp.
- The layout direction.

Window size classes are Material's, from `androidx.window:window-core` 1.3.0: width compact under
600dp, medium under 840dp, expanded from there; height compact under 480dp, medium under 900dp.

## Rules

| Window | Shell | What is on screen |
|---|---|---|
| Under 600dp wide (a phone, a Flip's inner and cover screens, a narrow split-screen half) | Compact | Exactly the phone layout: chat list in a modal drawer, Details as a sheet. |
| Wide enough that a 300dp list leaves the chat 320dp (about 620dp) up to 1180dp | ListDocked | Chat list pane (36% of the width, held to 300 to 360dp) beside the chat. Details stays a sheet over the chat. The top bar's first button hides and shows the list. |
| 1180dp and wider, 480dp tall or more | ThreePane | List pane, chat, and a 340dp Details pane the reader can close. |
| Wide, but under 480dp tall (a landscape phone) | ListDocked | Never more than list plus chat: three panes across a 360dp-high window is a worse reading column than the phone layout. |

Conversation and composer are held to 780dp (`ReadingWidthMax`) and centred inside the chat pane. The
lists keep the whole pane to scroll in and take the margin as padding.

### Folds

- **Book** (a vertical hinge that separates or occludes the window): the pane boundary moves onto
  the hinge. The chat list is the whole of one half, the chat (and Details, when that half is at
  least 820dp) the other, and a hinge with width stays an empty gap. The list cannot be hidden
  there, because hiding it would put the chat across the crease. If either half is too narrow for
  its pane (list under 240dp, chat under 320dp) the hinge is ignored and the unsplit sizes apply.
- **Tabletop** (a horizontal hinge that separates or occludes): one pane. The transcript ends at
  the hinge with a hairline on it, and everything the reader acts on (docks, an approval, the
  composer) sits below it. When the keyboard leaves the lower half less than 150dp, the transcript
  yields and the composer sits above the keyboard, because a composer across the crease is better
  than none. Docked panes are dropped in this posture: a full-height pane would run across the crease.
- A seamless flat crease (`isSeparating` false, nothing occluded, as a Pixel Fold lying open
  reports) is not a reason to lay anything out differently.
- Right-to-left mirrors the panes, and a hinge's physical sides are mapped to logical start and end
  before the plan is made.

### Continuity

- `MainActivity` handles `orientation|screenSize|smallestScreenSize|screenLayout|keyboard|
  keyboardHidden|navigation` itself, so folding, rotating, resizing a split-screen window and
  attaching a hardware keyboard do not restart it. `resizeableActivity` is declared and no
  `screenOrientation` is pinned. Density, `uiMode` and font scale are left to the system, which
  rebuilds the activity; the status bar style is set once in `onCreate`.
- `AdaptiveShell` holds the chat list and the chat in `movableContentOf`, so when the plan changes
  they move to their new parent with their state: transcript scroll, an open sheet, the list's search
  and folds.
- Drafts, the attachment tray and the selected session live in `SessionStore`, not in a composition,
  so they also survive the process being killed.
- The pane switches (`listShown`, `detailsShown`, `detailsOpen`) are `rememberSaveable`.

## Verifying on emulators

`avdmanager` profiles used here: `pixel_tablet` (DSH_Tablet, API 35 tablet image), `pixel_fold`
(DSH_Fold) and `6.7in Foldable` (DSH_Flip). The API 35 tablet image does not carry the foldable
feature, so the foldable AVDs ran on the API 37 `google_apis_playstore_ps16k` image.

Headless fold controls that worked:

- `adb emu posture 1|2|3`: closed, half open, opened. `adb emu fold` and `adb emu unfold` go to
  closed and opened. `adb shell cmd device_state state <n>` does the same (the numbers are the
  AVD's own, see `cmd device_state print-states`).
- Tabletop: rotate first so the hinge is horizontal (`adb shell cmd window user-rotation lock 1`),
  then half open. On the Fold the natural orientation is landscape with a vertical hinge (book).
- Screenshots of a two-display AVD need the display id:
  `adb exec-out screencap -p -d <id>` (`dumpsys SurfaceFlinger --display-id`). Closing the Fold
  moves the app to the outer display and locks the device.
- Cover screen: `adb shell wm size 720x748` and `wm density 306` (about 376x391dp), then
  `wm size reset` and `wm density reset`.
- Split screen without the UI: `am start --windowingMode 6` for each app, then
  `am task resize <taskId> l t r b` to put them side by side; resize again to simulate dragging the
  divider.

Tests: `ShellPlanTest` (unit: size class and posture to plan, hinge to pane boundaries, the tabletop
column and the reading gutter), `AdaptiveShellTest` (Compose, fixed-size containers at compact,
medium and expanded, with hinges, RTL and a plan change that must keep pane state) and
`WindowFoldTest` (a `FoldingFeature` published through `WindowLayoutInfoPublisherRule` reaching the
planner).
