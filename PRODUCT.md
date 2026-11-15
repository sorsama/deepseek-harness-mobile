# Product

## Register

product

## Users

Two groups, served by the same app:

- DeepSeek Harness operators using an Android phone to follow sessions, send prompts,
  review approvals and plans, and manage their host while away from its desktop.
- People with only a phone. The app runs the harness on the phone itself (the built-in
  harness), so someone with no computer, no Termux and no command line can set it up,
  add a model key, bring in a project and have the agent work on it.

## Product Purpose

Provide a native mobile companion to the Harness web GUI, with reliable live state,
reconnection, and access over local networks or the authenticated relay; and, where the
phone supports it, the harness itself, running inside the app, so the phone needs no
computer at all. A phone-only user should never need a terminal: setting up, models,
commands, projects, files and updates all have a screen.

## Brand Personality

Direct, restrained, tactile. "Ink": a near-black canvas and warm-paper light scheme,
one accent colour, Geist type, generous corner radii, and the mascot as the only
expressive surface. The theme is customizable — scheme, accent, OLED black, text size
and haptics — so the app can match the operator's device instead of prescribing one
look. Motion explains a change; it never decorates one.

## Anti-references

Avoid decorative dashboards, duplicated status cards, and raw protocol details in
ordinary workflows when an existing native control can express the same action.

## Design Principles

- Keep host-owned facts authoritative and distinguish pending work from completion.
- Use progressive disclosure for tool details while keeping the conversation readable.
- Preserve drafts and navigation context across interruptions.
- Follow established mobile components and the Ink tokens rather than inventing one-off styles.
- Never hide a control below a fold or behind a clipped edge: list, wrap or scroll instead.
- Touch feedback is part of the interface — haptics and press states confirm what happened.

## Accessibility & Inclusion

Maintain the existing 11-language resources, RTL support, scalable text, meaningful
control labels, and usable narrow-screen layouts.
