package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface UsageSource {
    suspend fun status(): SubscriptionRoster?
    suspend fun quota(provider: String, account: String): ProviderQuota?
    suspend fun wallet(): AccountWallet?
}
data class UsageSnapshot(val rows: List<UsageRow> = emptyList(), val loading: Boolean = false, val checkedAt: Long? = null)
/** Bind and refresh MUST share one dispatcher (Android Main). No selector leaves refresh or persists. */
class UsageRepository(private val now: () -> Long) {
    private val mutable = MutableStateFlow(UsageSnapshot())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var identity: String? = null
    private var epoch = 0L
    private val checked = mutableMapOf<String, Long>()
    fun bind(identity: String?) {
        if (this.identity == identity) return
        this.identity = identity; epoch++; checked.clear(); mutable.value = UsageSnapshot()
    }
    private suspend fun <T> safe(block: suspend () -> T): T? = try { withTimeoutOrNull(8_000) { block() } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    suspend fun refresh(identity: String, source: UsageSource, provider: String? = "codex", includeAll: Boolean = false, recheck: Boolean = false) = mutex.withLock {
        if (identity != this.identity) return@withLock
        val coverage = if (includeAll) "all" else provider ?: "unknown"
        val stamp = checked[coverage] ?: checked["all"]
        if (!recheck && stamp?.let { now() >= it && now() - it < 60_000 } == true) return@withLock
        val ticket = epoch
        mutable.value = mutable.value.copy(loading = true)
        try {
            val providers = if (includeAll) subscriptionProviders else setOfNotNull(provider).intersect(subscriptionProviders)
            val roster = if (providers.isNotEmpty()) safe { source.status() } else null
            val rows = providers.map { name ->
                val key = roster?.providers?.get(name)?.let(::defaultAccount)
                name to (key to if (key == null) null else safe { source.quota(name, key) })
            }
            val after = if (roster != null) safe { source.status() } else null
            val display = rows.map { (name, data) ->
                val (key, quota) = data
                if (key != null && quota != null && after?.providers?.get(name)?.let(::defaultAccount) == key)
                    quotaRow(name, quota, now()) else UsageRow(name)
            }.toMutableList()
            if (includeAll || provider == "deepseek-account") display.add(walletRow("deepseek-account", safe { source.wallet() }, now()))
            if (provider != null && display.none { it.provider == provider }) display.add(UsageRow(provider))
            if (ticket == epoch && identity == this.identity) {
                checked[coverage] = now()
                val replaced = display.map { it.provider }.toSet()
                mutable.value = UsageSnapshot(mutable.value.rows.filter { it.provider !in replaced } + display, checkedAt = now())
            }
        } finally {
            if (ticket == epoch) mutable.value = mutable.value.copy(loading = false)
        }
    }
}
