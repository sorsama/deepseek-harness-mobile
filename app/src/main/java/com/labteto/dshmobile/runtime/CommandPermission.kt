package com.labteto.dshmobile.runtime

/**
 * What the built-in harness does when the agent wants to run a command: the choice behind
 * "When the agent wants to run a command" on the set-up and control pages.
 *
 * Each value is one of the two presets the runtime adds on Android (README "Shell commands and
 * permissions on Android"), and reaches the guest as `DSH_RUNTIME_DEFAULT_PERMISSION=<envValue>` on
 * the next start. It is the preset new sessions start on; a session keeps the one it has.
 */
enum class CommandPermission(
    /** The value of the runtime's `defaultEnv` variable (`runtime.permissions.defaultEnvValues`). */
    val envValue: String,
    /** The preset id the running harness reports for it. */
    val presetId: String,
) {
    /** "Run it": the Full access preset. Commands run straight away. */
    RunIt("full", FULL_ACCESS_PRESET_ID),

    /** "Ask me first": the Ask before commands preset. Each command waits for Allow or Reject. */
    AskFirst("ask", ASK_COMMANDS_PRESET_ID),
    ;

    companion object {
        /**
         * The choice for someone who has never made one. The owner may flip this to [AskFirst]:
         * it is the only line to change, people who picked an option keep theirs (only a choice
         * that was made is stored), and `CommandPermissionTest` names the value so the flip shows
         * up there too.
         */
        val DEFAULT: CommandPermission = RunIt

        /** The stored value back as a choice; nothing stored (or something unknown) is [DEFAULT]. */
        fun fromStored(value: String?): CommandPermission = entries.firstOrNull { it.envValue == value } ?: DEFAULT

        /** The choice a preset id stands for, or null for any other preset. */
        fun ofPreset(presetId: String?): CommandPermission? = entries.firstOrNull { it.presetId == presetId }
    }
}

/** The ids the runtime's two Android presets report (`runtime.permissions.presets[].id`). */
const val FULL_ACCESS_PRESET_ID = "danger-full-access"
const val ASK_COMMANDS_PRESET_ID = "ask-commands"

/**
 * The guest variable that carries [choice], or null when the installed release has no
 * `runtime.permissions` (an older runtime would ignore it, so nothing is passed that suggests
 * otherwise) or does not list the value among the ones it takes.
 */
fun commandPermissionEnvironment(permissions: RuntimeManifest.Permissions?, choice: CommandPermission): Pair<String, String>? {
    if (permissions == null) return null
    val accepted = permissions.defaultEnvValues.ifEmpty { permissions.presets.map { it.envValue } }
    if (choice.envValue !in accepted) return null
    return permissions.defaultEnv to choice.envValue
}

/**
 * The preset the chat's sandbox notice offers to switch a session to, or null to only explain.
 *
 * On the built-in harness it is the person's own command choice, when the harness offers that
 * preset (an older runtime, or one started without its Android patch, does not). On any other
 * harness nothing is offered: the app's choice is about the harness on this phone, and which of a
 * desktop harness's presets suits its computer is not the app's call.
 *
 * @param offered the preset ids the session's permission control lists.
 */
fun sandboxNoticeTarget(onBuiltIn: Boolean, chosen: CommandPermission, offered: Collection<String>): String? =
    chosen.presetId.takeIf { onBuiltIn && it in offered }

/**
 * Whether the control page (or the set-up page) offers a restart after a change of the choice:
 * the harness is up, on a release that reads the choice, and was started with another one. A
 * stopped harness picks the new choice up at its next start by itself, so there is nothing to offer.
 *
 * @param startedWith the choice the running harness was started with; null when it was started
 *   on a release without the presets, or not by this app process.
 */
fun offersCommandPermissionRestart(runtime: RuntimeState, startedWith: CommandPermission?, chosen: CommandPermission): Boolean {
    val running = runtime as? RuntimeState.Running ?: return false
    if (!running.installed.commandPermissions) return false
    return startedWith != null && startedWith != chosen
}
