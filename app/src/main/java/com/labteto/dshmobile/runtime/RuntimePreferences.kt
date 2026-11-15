package com.labteto.dshmobile.runtime

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The built-in runtime's own switches, kept across app restarts.
 *
 * Plain key-value storage rather than the app's DataStore: the controller reads them synchronously
 * when it is built (the wake lock is decided the moment the service starts, the module pack at
 * every start) and the engine is not a place to suspend on a settings read.
 *
 * - [holdWakeLock]: a partial wake lock while the harness runs or the runtime installs. On by default.
 * - [modulePack]: "fast start", the runtime's module pack. On by default; off sets
 *   [ProotCommand.MODULE_PACK_ENV]`=0` in the guest on the next start.
 * - [commandPermission]: what new sessions do when the agent wants to run a command. Only a choice
 *   the person made is stored, so someone who never chose follows [CommandPermission.DEFAULT].
 * - [commandPermissionExplained]: the person has seen the one-time explanation of that choice.
 */
class RuntimePreferences(private val store: KeyValue) {

    /** The calls this class needs from a key-value store, so a test can hold it in a map. */
    interface KeyValue {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    private val _holdWakeLock = MutableStateFlow(store.getBoolean(KEY_WAKE_LOCK, true))
    val holdWakeLock: StateFlow<Boolean> = _holdWakeLock.asStateFlow()

    private val _modulePack = MutableStateFlow(store.getBoolean(KEY_MODULE_PACK, true))
    val modulePack: StateFlow<Boolean> = _modulePack.asStateFlow()

    private val _commandPermission = MutableStateFlow(CommandPermission.fromStored(store.getString(KEY_COMMAND_PERMISSION)))
    val commandPermission: StateFlow<CommandPermission> = _commandPermission.asStateFlow()

    private val _commandPermissionExplained = MutableStateFlow(store.getBoolean(KEY_COMMAND_PERMISSION_EXPLAINED, false))
    val commandPermissionExplained: StateFlow<Boolean> = _commandPermissionExplained.asStateFlow()

    fun setHoldWakeLock(enabled: Boolean) {
        store.putBoolean(KEY_WAKE_LOCK, enabled)
        _holdWakeLock.value = enabled
    }

    fun setModulePack(enabled: Boolean) {
        store.putBoolean(KEY_MODULE_PACK, enabled)
        _modulePack.value = enabled
    }

    fun setCommandPermission(choice: CommandPermission) {
        store.putString(KEY_COMMAND_PERMISSION, choice.envValue)
        _commandPermission.value = choice
    }

    fun setCommandPermissionExplained(seen: Boolean) {
        store.putBoolean(KEY_COMMAND_PERMISSION_EXPLAINED, seen)
        _commandPermissionExplained.value = seen
    }

    companion object {
        const val FILE = "runtime_prefs"
        private const val KEY_WAKE_LOCK = "hold_wake_lock"
        private const val KEY_MODULE_PACK = "module_pack"
        private const val KEY_COMMAND_PERMISSION = "command_permission"
        private const val KEY_COMMAND_PERMISSION_EXPLAINED = "command_permission_explained"

        fun over(prefs: SharedPreferences): RuntimePreferences = RuntimePreferences(
            object : KeyValue {
                override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
                override fun putBoolean(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String) {
                    prefs.edit().putString(key, value).apply()
                }
            },
        )
    }
}
