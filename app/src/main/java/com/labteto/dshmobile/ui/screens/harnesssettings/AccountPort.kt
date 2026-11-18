package com.labteto.dshmobile.ui.screens.harnesssettings

import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.core.account.AccountPort
import com.labteto.dshmobile.core.account.DshAccountPort
import com.labteto.dshmobile.core.account.withAccountCapability
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AccountUiMetadata
import com.labteto.dshmobile.core.wire.dto.AccountView
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import java.util.Locale
import java.util.TimeZone

/**
 * The account calls over the connected harness, with the answer of `account/getState` reported to the
 * store's capability record so the Settings entry can drop the page on a harness that has no account
 * service. The client is looked up per call, so a reconnect is picked up without a new port.
 *
 * A harness with its account plugin switched off still exports the calls and answers
 * `gateway/service-unavailable`; that is spelled as a missing capability before it is reported, because
 * the record only retires a feature on that.
 */
internal class StoreAccountPort(
    private val store: SessionStore,
    private val host: String,
    private val base: DshAccountPort = DshAccountPort({ store.apiForHost(host) }, ::accountMetadata),
) : AccountPort by base {
    override suspend fun state(): RpcResult<AccountView> {
        val api = store.apiForHost(host) ?: return base.state()
        return store.featureCall(HarnessFeature.ACCOUNT, api) { base.state().withAccountCapability() }
    }
}

/**
 * Who is asking, as the Platform wants it: this build's version, the language in force and the UTC
 * offset in seconds. DeepSeek writes bonus notices in that language, and the harness sends these on as
 * request headers; the chat usage card sends the same.
 */
internal fun accountMetadata() = AccountUiMetadata(
    BuildConfig.VERSION_NAME,
    Locale.getDefault().toLanguageTag(),
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
)
