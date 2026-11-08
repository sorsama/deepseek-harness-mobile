package com.labteto.dshmobile.ios

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * One generic-password item in the iOS Keychain, holding a string.
 *
 * The Keychain is where iOS keeps secrets: it is encrypted, kept out of the app's backups' readable
 * data and survives nothing the user has not asked for. The only secret the Connect screen handles
 * is the browser session a harness issues when a launch token is exchanged, so that is all this
 * stores (see [KeychainSessionStore]).
 *
 * Reachable after the first unlock since a reconnect can happen with the screen locked.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class KeychainItem(private val service: String, private val account: String) {

    fun read(): String? = withQuery(extra = mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    fun write(value: String): Boolean {
        delete()
        val bytes = value.encodeToByteArray()
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        val retainedData = CFBridgingRetain(data)
        try {
            return withQuery(
                extra = mapOf(
                    kSecValueData to retainedData,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
                ),
            ) { attributes ->
                val status = SecItemAdd(attributes, null)
                // Printed, not thrown: the console of a simulator run is where an unsigned build (no application
                // identifier, so errSecMissingEntitlement) shows up.
                if (status != errSecSuccess) println("DSH_KEYCHAIN add failed, OSStatus=$status")
                status == errSecSuccess
            }
        } finally {
            CFRelease(retainedData)
        }
    }

    fun delete() {
        withQuery(extra = emptyMap()) { query -> SecItemDelete(query) }
    }

    /** Builds `class + service + account + [extra]` as a CFDictionary, runs [block], and releases what it retained. */
    private fun <T> withQuery(extra: Map<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        val retainedService = CFBridgingRetain(service)
        val retainedAccount = CFBridgingRetain(account)
        try {
            val entries: Map<CFStringRef?, CFTypeRef?> = mapOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to retainedService,
                kSecAttrAccount to retainedAccount,
            ) + extra
            return memScoped {
                val keys = allocArrayOf(*entries.keys.toTypedArray())
                val values = allocArrayOf(*entries.values.toTypedArray())
                val dictionary = CFDictionaryCreate(
                    kCFAllocatorDefault,
                    keys.reinterpret(),
                    values.reinterpret(),
                    entries.size.convert(),
                    null,
                    null,
                )
                try {
                    block(dictionary)
                } finally {
                    CFRelease(dictionary)
                }
            }
        } finally {
            CFRelease(retainedService)
            CFRelease(retainedAccount)
        }
    }
}

/**
 * The harness browser sessions this device holds, one per remembered host, in one Keychain item.
 *
 * The same shape as Android's `HarnessSessionStore` (a map from host id to the `Cookie` value),
 * with the difference the platform makes: on Android the map is in app storage, here it is in the
 * Keychain. Forgetting a host forgets its session.
 */
internal class KeychainSessionStore(
    service: String = "com.labteto.dshmobile.harness-sessions",
) {
    private val item = KeychainItem(service, account = "sessions")
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * What this process holds: the Keychain's contents the first time they are asked for, and every change
     * after. A Keychain that refuses a write (an app that is not signed has no application identifier, so
     * the system will not give it a Keychain) therefore costs the person their session at the next launch,
     * not the sign-in they have just made.
     */
    private var memory: Map<String, String>? = null

    suspend fun cookie(hostId: String): String? = mutex.withLock { read()[hostId] }

    suspend fun put(hostId: String, cookie: String) = mutex.withLock { write(read() + (hostId to cookie)) }

    suspend fun remove(hostId: String) = mutex.withLock {
        val current = read()
        if (hostId in current) write(current - hostId)
    }

    suspend fun clear() = mutex.withLock { wipe() }

    /** [clear] for a caller that is not a coroutine: the debug launch argument that resets the app. */
    fun wipe() {
        memory = emptyMap()
        item.delete()
    }

    private fun read(): Map<String, String> = memory ?: load().also { memory = it }

    private fun load(): Map<String, String> {
        val raw = item.read() ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private fun write(next: Map<String, String>) {
        memory = next
        item.write(WireJson.encodeToString(serializer, next))
    }
}
