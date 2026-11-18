package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AccountBonus
import com.labteto.dshmobile.core.wire.dto.AccountBonusBatch
import com.labteto.dshmobile.core.wire.dto.AccountProfile
import com.labteto.dshmobile.core.wire.dto.AccountProfileOutcome
import com.labteto.dshmobile.core.wire.dto.AccountUiMetadata
import com.labteto.dshmobile.core.wire.dto.AccountView
import com.labteto.dshmobile.core.wire.dto.AccountWallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The calls the Account page makes, and nothing else. A port rather than the client so the controller
 * is tested against a fake that records exactly what it is handed.
 */
interface AccountPort {
    suspend fun state(): RpcResult<AccountView>
    suspend fun profile(): RpcResult<AccountProfileOutcome?>
    suspend fun balance(): RpcResult<AccountWallet?>
    suspend fun bonuses(): RpcResult<AccountBonusBatch?>
    suspend fun ackBonus(accountId: String, orderId: String): RpcResult<Boolean>
    suspend fun startSignIn(callbackOrigin: String): RpcResult<AccountView>
    suspend fun cancelSignIn(attemptId: String): RpcResult<AccountView>
    suspend fun signOut(): RpcResult<AccountView>
    suspend fun hasRunningTasks(): RpcResult<Boolean>
}

/**
 * The port over a live harness client. The client is looked up at each call, so a reconnect that hands
 * out a new one is picked up, and a moment with none is a refusal the page words, not a crash.
 */
open class DshAccountPort(
    private val api: () -> DshApiClient?,
    private val client: () -> AccountUiMetadata,
) : AccountPort {
    private suspend fun <T> with(call: suspend (DshApiClient) -> RpcResult<T>): RpcResult<T> =
        api()?.let { call(it) } ?: RpcResult.Err(RpcError(code = "internal", message = "not connected"))

    override suspend fun state() = with { it.accountState() }
    override suspend fun profile() = with { it.accountProfile(client()) }
    override suspend fun balance() = with { it.accountBalance(client()) }
    override suspend fun bonuses() = with { it.accountBonuses(client()) }
    override suspend fun ackBonus(accountId: String, orderId: String) = with { it.accountAckBonus(accountId, orderId, client()) }
    override suspend fun startSignIn(callbackOrigin: String) = with { it.accountStartSignIn(client(), callbackOrigin) }
    override suspend fun cancelSignIn(attemptId: String) = with { it.accountCancelSignIn(attemptId) }
    override suspend fun signOut() = with { it.accountSignOut(client()) }
    override suspend fun hasRunningTasks() = with { it.accountHasRunningTasks() }
}

/** Where the first read of the page stands. */
sealed interface AccountPhase {
    data object Loading : AccountPhase

    /** The harness has no account service: an older one, or its account plugin is off. */
    data object Unavailable : AccountPhase

    data class Failed(val failure: AccountFailure) : AccountPhase

    data object Ready : AccountPhase
}

/** A value read from DeepSeek by the harness; each of profile and balance can fail on its own. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data object Failed : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

/** What the page is doing for the person right now, so its buttons say so and cannot be pressed twice. */
enum class AccountWork { Starting, Cancelling, SigningOut }

data class AccountUiState(
    val phase: AccountPhase = AccountPhase.Loading,
    /** The latest answer of `account/getState`; kept across a failed refresh. */
    val snapshot: AccountSnapshot? = null,
    val profile: Loadable<AccountProfile> = Loadable.Loading,
    /** The balance exactly as the usage card reads it: recharge and bonus wallets. */
    val balance: Loadable<AccountWallet> = Loadable.Loading,
    /** Bonuses DeepSeek has not yet recorded as shown, in its order. */
    val bonuses: List<AccountBonus> = emptyList(),
    val working: AccountWork? = null,
    /** The last start, cancel or sign-out that did not land. */
    val actionFailure: AccountFailure? = null,
    /** Which action [actionFailure] belongs to, so the screen can offer to try that one again. */
    val failedWork: AccountWork? = null,
    /** A refresh failed; what is on screen is what was read before. */
    val refreshFailed: Boolean = false,
    /** The harness said its stored sign-in was rejected and removed (`deepseek-account/session-expired`). */
    val sessionEnded: Boolean = false,
    /** The attempt this phone just started and has not yet sent to the browser; the screen opens the link once and says so. */
    val openBrowserFor: String? = null,
    /** The attempt whose link did not open because the phone had no browser to take it; the page says so, and the link can still be copied. */
    val openFailedFor: String? = null,
)

/**
 * The state behind the Account page: read what the harness holds, start or cancel a sign-in, sign out,
 * and never show more than the harness said.
 *
 * Everything lives on the harness. The page asks again whenever it is shown, when the app comes back to
 * the front and while a sign-in runs, so it needs nothing of its own to survive leaving the app or the
 * process dying: the harness still reports a sign-in in progress, and the page reads it again.
 *
 * A read applies only if no read issued after it has already applied, so a slow poll that started
 * before a cancel cannot put the cancelled attempt back.
 *
 * @param onSignInChanged called when the harness goes from signed out to signed in or back, so the chat
 * usage card, which shows the account's balance, reads it again at once instead of on its own timer.
 */
class AccountController(
    private val port: AccountPort,
    private val scope: CoroutineScope,
    private val onSignInChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var issued = 0L
    private var applied = 0L
    private var detailsGeneration = 0L
    private var detailsKey: String? = null
    private var bonusAccount: String? = null
    private var reading: Job? = null

    /** The first read: shows progress until there is something to show. */
    fun load() {
        if (_state.value.snapshot == null) _state.update { it.copy(phase = AccountPhase.Loading) }
        read()
    }

    /** Ask again without taking the page over: when it is shown again, on a poll, on a pull. */
    fun refresh() = read()

    private fun read() {
        // One read at a time: a poll that finds one in flight lets it finish.
        if (reading?.isActive == true) return
        val ticket = ++issued
        reading = scope.launch {
            when (val result = attempt { port.state() }) {
                is RpcResult.Ok -> if (ticket >= applied) {
                    applied = ticket
                    apply(result.value)
                }
                is RpcResult.Err -> fail(result.error.toAccountFailure())
            }
        }
    }

    private fun fail(failure: AccountFailure) {
        _state.update { current ->
            when {
                failure == AccountFailure.Unavailable -> current.copy(phase = AccountPhase.Unavailable)
                // A refresh that fails keeps what is shown; only the first read shows the failure.
                current.snapshot != null -> current.copy(refreshFailed = true)
                else -> current.copy(phase = AccountPhase.Failed(failure))
            }
        }
    }

    /** Take one `account/getState` answer in, and read the account's details when the sign-in is a new one. */
    private fun apply(view: AccountView) {
        val next = view.toSnapshot()
        val before = _state.value.snapshot
        val changed = before != null && before.signedIn != next.signedIn
        if (!next.signedIn) {
            // Signed out: nothing read for the account may land late, and nothing of it is kept.
            detailsGeneration++
            bonusAccount = null
        }
        _state.update { current ->
            val signedOut = !next.signedIn
            current.copy(
                phase = AccountPhase.Ready,
                snapshot = next,
                refreshFailed = false,
                profile = if (signedOut) Loadable.Loading else current.profile,
                balance = if (signedOut) Loadable.Loading else current.balance,
                bonuses = if (signedOut) emptyList() else current.bonuses,
                // Signing in again clears the note that it ended; so does the harness being signed in.
                sessionEnded = current.sessionEnded && signedOut,
                openBrowserFor = current.openBrowserFor?.takeIf { next.progress?.attemptId == it },
                openFailedFor = current.openFailedFor?.takeIf { next.progress?.attemptId == it },
            )
        }
        if (next.credentialKey != detailsKey) {
            detailsKey = next.credentialKey
            if (next.signedIn) loadDetails()
        }
        if (changed) onSignInChanged()
    }

    // ------------------------------------------------------------------ profile, balance, bonus notices

    /** Read the profile, the balance and the bonus notices, each on its own so one failing does not hide the others. */
    fun loadDetails() {
        val generation = ++detailsGeneration
        _state.update { it.copy(profile = Loadable.Loading, balance = Loadable.Loading) }
        scope.launch {
            val result = attempt { port.profile() }
            if (generation != detailsGeneration) return@launch
            val profile = when (result) {
                is RpcResult.Ok -> result.value?.let { outcome ->
                    outcome.value?.takeIf { outcome.status == "ready" }?.let { Loadable.Ready(it) }
                } ?: Loadable.Failed
                is RpcResult.Err -> Loadable.Failed
            }
            // A null answer is the harness saying it is signed out now: the state read will say so too.
            _state.update { it.copy(profile = profile) }
        }
        scope.launch {
            val result = attempt { port.balance() }
            if (generation != detailsGeneration) return@launch
            val balance = when (result) {
                is RpcResult.Ok -> result.value?.takeIf { it.status == "ready" }?.let { Loadable.Ready(it) } ?: Loadable.Failed
                is RpcResult.Err -> Loadable.Failed
            }
            _state.update { it.copy(balance = balance) }
        }
        scope.launch {
            val result = attempt { port.bonuses() }
            if (generation != detailsGeneration) return@launch
            // A failed read of notices says nothing to the person: the next read tries again.
            val batch = (result as? RpcResult.Ok)?.value
            bonusAccount = batch?.accountId
            _state.update { it.copy(bonuses = batch?.bonuses.orEmpty()) }
        }
    }

    /**
     * The person closed a bonus notice: it counts as seen, so the harness is told, once. Only now and
     * not when it was drawn, because the Platform records a notice as shown for every device. A failure
     * is not worth a message; the notice shows again next time.
     */
    fun dismissBonus(orderId: String) {
        val accountId = bonusAccount ?: return
        _state.update { it.copy(bonuses = it.bonuses.filterNot { b -> b.orderId == orderId }) }
        scope.launch { attempt { port.ackBonus(accountId, orderId) } }
    }

    // ------------------------------------------------------------------ sign in, cancel, sign out

    /**
     * Begin a sign-in that this phone will finish in its own browser. The screen opens the link once it
     * is ready ([AccountUiState.openBrowserFor]); until then the attempt is `initializing`.
     */
    fun startSignIn(callbackOrigin: String) = act(AccountWork.Starting) { start(callbackOrigin) }

    /**
     * Give up on the attempt on screen and start a fresh one: the way out of a link this app will not
     * open, which nothing on the page can finish. A plain start would only join the running attempt (the
     * harness answers a start with the attempt already running), so that one is cancelled first. A cancel
     * that fails is not the end of it: the attempt may already be gone, and the start that follows says
     * whether the harness can be reached at all.
     */
    fun retrySignIn(callbackOrigin: String) = act(AccountWork.Starting) {
        _state.value.snapshot?.progress?.attemptId?.let { attemptId -> attempt { port.cancelSignIn(attemptId) } }
        start(callbackOrigin)
    }

    private suspend fun start(callbackOrigin: String) {
        when (val result = attempt { port.startSignIn(callbackOrigin) }) {
            is RpcResult.Ok -> {
                val progress = result.value.toSnapshot().progress
                _state.update { it.copy(openBrowserFor = progress?.attemptId, sessionEnded = false) }
                applied = ++issued
                apply(result.value)
            }
            is RpcResult.Err -> failAction(result.error)
        }
    }

    /** Cancel the running attempt. The attempt named is the one on screen, so a newer one is never cancelled by a stale screen. */
    fun cancelSignIn() {
        val attemptId = _state.value.snapshot?.progress?.attemptId ?: return
        act(AccountWork.Cancelling) {
            when (val result = attempt { port.cancelSignIn(attemptId) }) {
                is RpcResult.Ok -> {
                    applied = ++issued
                    apply(result.value)
                }
                is RpcResult.Err -> failAction(result.error)
            }
        }
    }

    /** Sign out of the harness. API keys are kept. */
    fun signOut() = act(AccountWork.SigningOut) {
        when (val result = attempt { port.signOut() }) {
            is RpcResult.Ok -> {
                applied = ++issued
                apply(result.value)
            }
            is RpcResult.Err -> failAction(result.error)
        }
    }

    /** What signing out would stop, asked when the confirmation opens. */
    suspend fun signOutImpact(): SignOutImpact = signOutImpact(attempt { port.hasRunningTasks() })

    private fun act(work: AccountWork, block: suspend () -> Unit) {
        if (_state.value.working != null) return
        _state.update { it.copy(working = work, actionFailure = null, failedWork = null) }
        scope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(working = null) }
            }
        }
    }

    private fun failAction(error: RpcError) {
        val failure = error.toAccountFailure()
        _state.update {
            if (failure == AccountFailure.Unavailable) it.copy(phase = AccountPhase.Unavailable) else it.copy(actionFailure = failure, failedWork = it.working)
        }
    }

    /**
     * The screen sent the link of [attemptId] to the browser, so it is not sent again. [opened] is false when
     * the phone had no browser to take it, which the page then says.
     */
    fun browserOpened(attemptId: String, opened: Boolean = true) = _state.update {
        it.copy(
            openBrowserFor = it.openBrowserFor?.takeIf { id -> id != attemptId },
            openFailedFor = if (opened) it.openFailedFor?.takeIf { id -> id != attemptId } else attemptId,
        )
    }

    fun dismissActionFailure() = _state.update { it.copy(actionFailure = null, failedWork = null) }

    /** `deepseek-account/session-expired`: the harness removed a sign-in DeepSeek rejected. Read again, and say so. */
    fun onSessionExpired() {
        _state.update { it.copy(sessionEnded = true) }
        read()
    }

    /**
     * Run a call, folding a thrown failure into an error result so a dropped socket is a refusal the
     * screen words, never a crash. Cancellation is not a failure and passes through.
     */
    private suspend fun <T> attempt(call: suspend () -> RpcResult<T>): RpcResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RpcResult.Err(RpcError(code = "internal", message = e.message ?: e::class.simpleName.orEmpty()))
    }
}
