<!--
Thanks for contributing to DSH Mobile. Delete any section that does not apply.
Conventions live in CONTRIBUTING.md; the deeper documents are in docs/.
-->

## What and why

<!-- What changes, and what problem it solves. If it fixes an issue: Fixes #123 -->

## How it was verified

<!--
Say what you actually ran, not what should work. For example:
- Real harness 0.1.0-rc.8 over adb reverse, Pixel 6a / Android 14
- Real harness over Wi-Fi LAN mode
- mock-harness only
- Unit tests only
-->

- [ ] Against a real harness (version: …, connected over: …)
- [ ] Against `mock-harness`
- [ ] Tests only

## Checklist

- [ ] `./gradlew :core:test :mock-harness:test :app:testDebugUnitTest` passes
- [ ] `./gradlew :app:lintDebug` is clean
- [ ] Any new user-visible text is in `values/strings.xml` **and all ten translation
      directories** — `MissingTranslation` is a lint error, which is what keeps the
      eleven-language claim true
- [ ] No hardcoded UI strings
- [ ] Wire-layer changes still parse leniently: unknown keys, event types and tool cards
      fall back to a passthrough rather than failing
- [ ] Behaviour that depends on the harness build degrades by hiding the control, not by
      reporting a broken connection (404 → capability unavailable, 403 → forbidden)
- [ ] `CHANGELOG.md` updated if the change is user-visible
- [ ] Screenshots or a screen recording below, for anything that changes the UI

## Screenshots

<!-- Before / after, and both light and dark if the change touches theming. -->

## Notes for the reviewer

<!-- Anything you are unsure about, deliberately left out, or want a second opinion on. -->
