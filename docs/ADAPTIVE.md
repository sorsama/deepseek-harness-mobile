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

## ChromeOS and desktop windowing

A Chromebook runs the Android app in a freely resizable window, usually with a keyboard and a
trackpad and often with no touchscreen use at all. Android's desktop mode (on tablets and some
phones) is the same thing. Nothing here is a second code path: the adaptive shell above already
lays itself out from the window it is given, and this section is what makes the *input* and the
*install* work the way Google's ChromeOS and large-screen quality guidelines ask.

### Manifest

- `android.hardware.touchscreen` is `required="false"`, and so is every other feature the manifest
  or one of our permissions implies: camera (and its autofocus and front variants), Wi-Fi, and both
  screen orientations. Play hides an app from every device that lacks a required feature, and
  `CAMERA` and `ACCESS_WIFI_STATE` imply features when they are not stated, so each is declared
  optional. On the built APK, `aapt2 dump badging` lists eight features (camera, its autofocus,
  flash and front variants, Wi-Fi, touchscreen, portrait, landscape), every one as
  `uses-feature-not-required`, and no `uses-implied-feature` and no required feature at all.
- `resizeableActivity` is on, no `screenOrientation` is pinned, and `configChanges` keeps the
  activity alive through resizes, keyboard attach and detach (see "Continuity" above).
- `<layout>` on `MainActivity`: 900x700dp default size, centred, and a 320x360dp floor. A freeform
  window opens at the default and cannot be dragged below the floor. It does nothing on a phone.

### Windows

Dragging an edge, maximizing and snapping to half the screen are all configuration changes the
activity absorbs. The shell re-plans from the new size, the chat and the list move (not rebuild)
between layouts, and the draft, the attachment tray, the transcript's scroll and any open sheet
survive. A window 1400x500dp gets three panes (the height class is medium); under 480dp tall it
drops to list plus chat; under 600dp wide it is the phone layout, down to the 320x360dp floor.

The title bar is the system's, drawn over the top of the window while the app is edge to edge
beneath it (the panes pad for it: `captionBar` is part of `systemBars`). It shows the app's colour and
picks light or dark buttons from the task description, and left alone the buttons followed the
system theme and were white on the app's pale background. `CaptionBarStyle` (`ui/adaptive/CaptionBar.kt`)
gives the task the app's background as its primary, background and status bar colour and sets
`APPEARANCE_LIGHT_CAPTION_BARS` to match. The task description's *status bar colour* is what the
desktop shell reads to choose the button colour (its background colour alone was not enough on the
Android desktop image). Do not "fix" this in the theme: `android:colorBackground` also colours the
platform's text selection toolbar (Copy, Select all), and a pale value there gives white text on a
pale plate.

### Keyboard

| Key | Does |
|---|---|
| Enter | Sends, with a hardware keyboard. |
| Shift+Enter, Alt+Enter | Adds a line. |
| Ctrl+Enter, Cmd+Enter | Sends (always did). |
| Esc | Closes the top-most sheet, dialog or full-screen editor; with none open, the sessions drawer or the Details overlay. |
| Tab, Shift+Tab | Move focus in reading order; the focused control has an accent ring (a ring drawn by the theme's indication for every `clickable`, and `rememberFocusBorder` for the buttons, chips and pills built on Material's `Surface`, whose own ripple the indication never reaches). |
| Arrow keys, Enter | Move through and open rows of the session list (focus traversal, rows are clickable). |
| Ctrl+N | New session (opens the picker). |
| Ctrl+F | Search sessions. |
| Ctrl+K, Ctrl+/ | Move to the message box. |
| Ctrl+B | Show or hide the session list. |
| Ctrl+, | Open settings. |
| Ctrl+C, V, X, A | Copy, paste, cut, select all, in every field (the text field's own). |

The Enter rule is `decideComposerKey` (`ui/input/ComposerKeys.kt`), a plain function with a unit
test. It only answers an event that did not come from the on-screen keyboard
(`FLAG_SOFT_KEYBOARD`) while the configuration reports a keyboard, so on a phone the return key
keeps adding a line and nothing about the soft keyboard changed. The full-screen editor is for long
text: there Enter adds a line and only the send chord sends.

The shortcuts are `AppShortcut` (`ui/input/AppShortcuts.kt`). The main screen reads them from its
own key events and the activity reads the ones nothing took (`onKeyShortcut`, for when no Compose
node has focus yet); both post to a `ShortcutRouter`, and the part of the screen that owns the
command acts on it. They are also declared to the platform's shortcut panel
(`onProvideKeyboardShortcuts`, `ShortcutCatalog`), which is what Search+/ on a Chromebook and Meta+/
elsewhere show, in the app's own language. A unit test holds the catalog and the matcher together.

Escape is `Modifier.dismissOnEscape`, installed once in each window the app opens (`DsWindow`,
`DsBottomSheet`). Android gives a dialog no Escape handling of its own.

### Mouse and trackpad

- The hand cursor over anything clickable: `Modifier.clickable` in `:sharedUi` (`Pointer.kt`) is
  foundation's with `pointerHoverIcon(PointerIcon.Hand)` added, and files opt in by importing it in
  place of foundation's. Text fields already show the I-beam. A disabled control keeps the arrow.
- Hover: Material's indication tints a hovered row or button, and `DsIconButton` has its own hover
  fill.
- Right click (and a two-finger click) opens the menu a long press opens: `holdable` and
  `clickableWithHold` take `onSecondaryClick`, which also consumes the press so the row does not
  open. Session rows, messages, tool calls, files, dock items and the send button all go through
  them.
- Icon-only buttons show a tooltip (their content description) after the pointer rests on them for
  half a second: `HoverTooltip`. It is driven by the pointer's hover, not Material's long-press, so
  a touch hold never shows one.
- The wheel scrolls everything that scrolls; a vertical notch on a sideways strip (the attachment
  tray, the reference chips) scrolls it sideways (`horizontalWheelScroll`).

### Drag and drop, paste

Dropping files on the chat attaches them (images through the photo path, with its size limits,
everything else as an upload, exactly as picking does) and dropped text goes into the draft at the
caret. The whole chat pane is the target (`attachmentDropTarget`), with an accent outline and a
"Drop files here" plate while a drag is over it. The drop's read permission is requested before the
files are touched (`requestDragAndDropPermissions`) and kept for the uploads that follow.
Ctrl+V with an image or file on the clipboard (and no text) attaches it the same way; text pastes as
usual. A child (subagent) session takes images but not files, as the + sheet does.

### Verifying

There is no ChromeOS emulator. What was used: the `android-desktop` system image (API 34) for
desktop windowing with a keyboard and a mouse, and the tablet AVD with freeform enabled
(`settings put global enable_freeform_support 1`, `force_resizable_activities 1`,
`am start --windowingMode 5`, `am task resize <id> l t r b`). See the emulator notes in the
repository's memory for the exact commands. Not verified: a real Chromebook, ARC++ and ARCVM
specifics (the container's own window and input translation), stylus input, and the Play Console's
device catalogue (which is what decides listing on Chromebooks).

Tests: `ComposerKeysTest`, `AppShortcutsTest` and `ClipContentTest` (unit) and `DesktopInputTest`
(Compose: Enter, Shift+Enter and Ctrl+Enter in the composer, Escape on a sheet and a dialog, tooltip
on hover and not on a touch hold, right click, the wheel on a sideways list, Tab order and the focus
ring, the drop highlight).
