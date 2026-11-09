package com.labteto.dshmobile.connection

/** Read endpoint-specific history first, retaining the pre-prefix key as an upgrade fallback. */
internal fun HostConfig.rememberedSession(saved: Map<String, String>): String? =
    saved[baseUrl] ?: saved["$host:$port"]

/** App-level persisted settings (DataStore). */
data class AppSettings(
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    /**
     * In "this phone" mode, start the harness in Termux when the app opens and finds it down.
     *
     * Off by default and only ever acted on after the Termux permission has been granted from a
     * tap: an app that fires commands into Termux on launch, before anyone asked it to, is the
     * behaviour the permission exists to prevent.
     */
    val autoStartLoopbackHarness: Boolean = false,
    /** Relay mode's counterpart to [autoConnectLan]: connect to a paired relay that mDNS finds. */
    val autoConnectRelay: Boolean = false,
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    /**
     * Which way the user chose to reach a harness: `loopback`, `lan` or `relay`.
     *
     * Not a detected value. The paths have different trust models — a harness on this device,
     * an unauthenticated LAN harness, a credentialed relay — and auto-connect never crosses
     * between them, so the app connects only the way that was actually picked. Defaults to `lan`
     * so an install that predates relay support comes back where it was; a fresh install with
     * Termux on the phone is started on `loopback` once, by [HostsStore.setConnectModeIfUnset].
     */
    val connectMode: String = ConnectMode.LAN,
    val themePreference: String = "system", // light | dark | system

    /**
     * Which accent the customizable theme paints with. A key, not a colour: the accent has a dark
     * and a light ramp (see `AccentPalette`), and storing the resolved ARGB would freeze one scheme's
     * value into the other's. Unknown keys fall back to the Ink blue default.
     */
    val themeAccent: String = "ink",
    /**
     * The user's own accent colour as `#RRGGBB`, the colour that was *picked* — the dark and light
     * ramps are derived from it at read time (see `deriveAccentRamp`), never stored. It applies
     * only while [themeAccent] is `custom`, and is kept when a preset is chosen so the Custom
     * swatch still shows it. Absent or unparseable means no custom colour; the theme then falls
     * back to the preset named by [themeAccent], or Ink.
     */
    val themeCustomAccent: String? = null,
    /** True-black canvas for OLED panels. Only consulted in the dark scheme. */
    val amoledBlack: Boolean = false,
    /** App-level text size multiplier key (`compact|default|large|larger`), on top of the system scale. */
    val fontSize: String = "default",
    /** Touch feedback for controls and decisions. */
    val hapticsEnabled: Boolean = true,
    /** How hard the haptic effects hit: `light`, `normal` or `strong`. Unknown keys read as normal. */
    val hapticStrength: String = "normal",
    val localeOverride: String? = null, // null = system
    val knownPorts: List<Int> = listOf(3080),
    /**
     * Whether to ask GitHub for the latest release on start.
     *
     * The only request this app makes to anything other than the harness the user pointed it at,
     * so it is worth being able to switch off — on a restricted network, or by anyone who would
     * rather it stayed local-only.
     */
    val updateCheckEnabled: Boolean = true,
    /** A release the user has already declined, so it is offered once rather than every launch. */
    val dismissedUpdate: String? = null,
    /**
     * Whether the transcript follows a reply as it is written.
     *
     * Dynamic, not a plain auto-scroll: following is switched off when the reader scrolls away
     * from the newest row and comes back on when they return to it. Off, new rows land below the
     * viewport and the jump pill reports them instead of pulling the page down.
     */
    val followStreamingReply: Boolean = true,
    /** Default delivery for prompts sent while a turn is running: `queue` or `steer`. */
    val defaultDeliveryMode: String = "queue",
)
