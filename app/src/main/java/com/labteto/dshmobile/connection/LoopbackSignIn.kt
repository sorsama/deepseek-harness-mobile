package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.HarnessLaunchLine
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.ui.screens.connect.HarnessSignIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs the app in to a harness it just started on this device.
 *
 * Shared by both ways the app starts a local harness: through Termux, and the built-in runtime.
 * Each one ends with the harness's readiness line in hand; this exchanges the token on it for a
 * session cookie and stores the cookie against the loopback host record, so the connect screen
 * finds a signed-in harness. Neither caller connects; this only signs in.
 */
@Singleton
class LoopbackSignIn @Inject constructor(
    private val hostsStore: HostsStore,
    private val sessions: HarnessSessionStore,
) {
    /** [output] is whatever holds the readiness line: the line itself, or a script's whole stdout. */
    suspend fun signIn(output: String, port: Int): StartSignIn =
        signIn(output, sessions) { hostsStore.ensureLoopbackHost(port) }

    companion object {
        /**
         * Exchange the token on the readiness line for a cookie, stored against the loopback record.
         *
         * The whole line goes to the exchange, which already accepts it; parsing here only decides
         * whether there is a token to exchange at all. The loopback record is created only when
         * there is one, so a token-less start leaves the host list alone.
         */
        suspend fun signIn(
            output: String,
            exchange: HarnessSignIn,
            loopbackHost: suspend () -> HostConfig,
        ): StartSignIn {
            val line = HarnessLaunchLine.parse(output) ?: return StartSignIn.NO_TOKEN
            if (line.token == null) return StartSignIn.NO_TOKEN
            val host = loopbackHost()
            return when (exchange.pair(host.id, host.baseUrl, line.raw)) {
                is SessionExchange.Granted -> StartSignIn.SIGNED_IN
                is SessionExchange.Refused -> StartSignIn.REFUSED
                is SessionExchange.Unreachable -> StartSignIn.UNREACHABLE
            }
        }
    }
}
