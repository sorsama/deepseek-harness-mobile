package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One read of a host RPC, with "this host has no such method" kept apart from "it did not answer". */
sealed interface UsageFetch<out T> {
    data class Ok<out T>(val value: T) : UsageFetch<T>
    /** The host answered that it does not implement the method. Remembered per host connection. */
    data object Unsupported : UsageFetch<Nothing>
    /** A timeout, a carrier error or a refusal: nothing is learned about what the host supports. */
    data object Failed : UsageFetch<Nothing>
}

/** Error codes that mean the host has no such method, as opposed to a method that failed. */
private val missingMethodCodes = setOf(
    "capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable",
    "method-not-found", "-32601",
)
fun <T> RpcResult<T>.toUsageFetch(): UsageFetch<T> = when (this) {
    is RpcResult.Ok -> UsageFetch.Ok(value)
    is RpcResult.Err -> if (error.code in missingMethodCodes) UsageFetch.Unsupported else UsageFetch.Failed
}

interface UsageSource {
    suspend fun status(): UsageFetch<SubscriptionRoster>
    suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota>
    /** `Ok(null)` is a signed-out account, which is not an error. */
    suspend fun wallet(): UsageFetch<AccountWallet?>
}

/** What the DeepSeek account balance row shows. */
sealed interface BalanceState {
    /** Signed out, no account service on this host, or nothing usable: the row is not drawn. */
    data object Hidden : BalanceState
    /** The host reported `{status: 'failed'}` for the lookup. */
    data object Failed : BalanceState
    data class Ready(val row: UsageRow) : BalanceState
}

data class UsageSnapshot(
    /** Quota-window rows the host reported. Only rows that have windows exist; no placeholders. */
    val rows: List<UsageRow> = emptyList(),
    val balance: BalanceState = BalanceState.Hidden,
    /** `true` once the host answered a subscription call, `false` once it said it has none, `null` until known. */
    val subscriptions: Boolean? = null,
    val loading: Boolean = false,
    val checkedAt: Long? = null,
)

/**
 * Bind and refresh MUST share one dispatcher (Android Main). No selector leaves refresh or persists.
 *
 * [bind] takes two keys. The identity names what the card describes (host, session, provider) and
 * clears the rows when it changes. The host key names the connection: the DeepSeek balance belongs
 * to the account rather than to a session, and "this host has no `subscriptions-auth`" belongs to
 * the connection, so both survive a change of session or provider and are dropped only with it.
 */
class UsageRepository(private val now: () -> Long) {
    private val mutable = MutableStateFlow(UsageSnapshot())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var identity: String? = null
    private var hostKey: String? = null
    private var epoch = 0L
    private val checked = mutableMapOf<String, Long>()
    private var subscriptionsMissing = false
    private var walletMissing = false

    fun bind(identity: String?, host: String? = identity) {
        if (this.identity == identity && this.hostKey == host) return
        val sameHost = host != null && host == this.hostKey
        this.identity = identity; this.hostKey = host; epoch++; checked.clear()
        if (sameHost) {
            val kept = mutable.value
            mutable.value = UsageSnapshot(balance = kept.balance, subscriptions = kept.subscriptions)
        } else {
            subscriptionsMissing = false; walletMissing = false
            mutable.value = UsageSnapshot()
        }
    }
    private suspend fun <T> safe(block: suspend () -> UsageFetch<T>): UsageFetch<T> = try { withTimeoutOrNull(8_000) { block() } ?: UsageFetch.Failed }
        catch (e: CancellationException) { throw e } catch (_: Exception) { UsageFetch.Failed }

    private class Subscriptions(val rows: List<UsageRow>, val queried: Set<String>, val supported: Boolean?, val missing: Boolean)

    private suspend fun subscriptions(source: UsageSource, provider: String?, includeAll: Boolean): Subscriptions {
        val roster = when (val first = safe { source.status() }) {
            is UsageFetch.Ok -> first.value
            UsageFetch.Unsupported -> return Subscriptions(emptyList(), emptySet(), supported = false, missing = true)
            UsageFetch.Failed -> return Subscriptions(emptyList(), emptySet(), supported = null, missing = false)
        }
        // The host's roster decides which providers have a quota; nothing here lists them.
        val names = (if (includeAll) roster.providers.keys else setOfNotNull(provider).filter { it in roster.providers }).toList()
        var missing = false
        val fetched = names.map { name ->
            val key = roster.providers[name]?.let(::defaultAccount)
            val quota = if (key == null) null else safe { source.quota(name, key) }
            if (quota == UsageFetch.Unsupported) missing = true
            Triple(name, key, (quota as? UsageFetch.Ok)?.value)
        }
        val after = (if (fetched.any { it.third != null }) safe { source.status() } else null) as? UsageFetch.Ok
        val rows = fetched.mapNotNull { (name, key, quota) ->
            if (key != null && quota != null && after?.value?.providers?.get(name)?.let(::defaultAccount) == key)
                quotaRow(name, quota, now()).takeIf { it.windows.isNotEmpty() } else null
        }
        return Subscriptions(rows, names.toSet(), supported = if (missing) false else true, missing = missing)
    }

    private fun balanceOf(fetch: UsageFetch<AccountWallet?>, previous: BalanceState): BalanceState = when (fetch) {
        // A blip must not blank a balance the reader just saw; its own timestamp ages it out.
        UsageFetch.Failed -> previous.takeIf { it is BalanceState.Ready } ?: BalanceState.Hidden
        UsageFetch.Unsupported -> BalanceState.Hidden
        is UsageFetch.Ok -> when (fetch.value?.status) {
            "ready" -> walletRow(DEEPSEEK_ACCOUNT, fetch.value, now()).let {
                if (it.wallets.isEmpty() && it.bonusWallets.isEmpty()) BalanceState.Hidden else BalanceState.Ready(it)
            }
            "failed" -> BalanceState.Failed
            else -> BalanceState.Hidden
        }
    }

    suspend fun refresh(identity: String, source: UsageSource, provider: String? = null, includeAll: Boolean = false, recheck: Boolean = false) = mutex.withLock {
        if (identity != this.identity) return@withLock
        val coverage = if (includeAll) "all" else provider ?: "unknown"
        val stamp = checked[coverage] ?: checked["all"]
        if (!recheck && stamp?.let { now() >= it && now() - it < 60_000 } == true) return@withLock
        val ticket = epoch
        mutable.value = mutable.value.copy(loading = true)
        try {
            val subs = if (subscriptionsMissing) null else subscriptions(source, provider, includeAll)
            val wallet = if (walletMissing) UsageFetch.Unsupported else safe { source.wallet() }
            if (ticket == epoch && identity == this.identity) {
                if (subs?.missing == true) subscriptionsMissing = true
                if (wallet == UsageFetch.Unsupported) walletMissing = true
                checked[coverage] = now()
                val current = mutable.value
                val rows = if (subs == null) emptyList() else current.rows.filter { it.provider !in subs.queried } + subs.rows
                mutable.value = UsageSnapshot(
                    rows = if (subscriptionsMissing) emptyList() else rows,
                    balance = balanceOf(wallet, current.balance),
                    subscriptions = if (subs == null) false else subs.supported ?: current.subscriptions,
                    checkedAt = now(),
                )
            }
        } finally {
            if (ticket == epoch) mutable.value = mutable.value.copy(loading = false)
        }
    }
}
