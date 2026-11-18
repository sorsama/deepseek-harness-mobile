package com.labteto.dshmobile.core.account

/**
 * Real answers of the account calls, recorded from a harness booted from source (0.2.1-rc.2, `dsh web`,
 * a throwaway DSH_HOME) whose account plugin was pointed at a local stand-in for DeepSeek's Platform. The
 * stand-in answered with the shapes the harness's own tests use (`deepseek-account-platform/tests/account.spec.ts`),
 * so every name, amount and address below is invented; no real account was signed in and no real token exists
 * here (the stand-in's grant is `dsh_mock_0000`, and the harness never returns it). The `result` slot of each
 * envelope is what is stored, as the wire carries it.
 *
 * Two answers could not be recorded that way and say so beside them.
 */
internal object AccountFixtures {

    /** account/getState on a harness with no stored sign-in and no attempt (captured). */
    val STATE_SIGNED_OUT = """{"ok":true,"value":{"status":"signed-out","attempt":null,"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/startSignIn with callbackOrigin http://192.168.1.20:3590, which the harness refuses (loginOrigin accepts only loopback) (captured). */
    val START_REFUSED_LAN_ORIGIN = """{"ok":false,"error":{"code":"gateway/internal","message":"account: protocol","details":{}}}"""

    /** account/startSignIn with callbackOrigin https://example.com (refused the same way) (captured). */
    val START_REFUSED_HTTPS_ORIGIN = """{"ok":false,"error":{"code":"gateway/internal","message":"account: protocol","details":{}}}"""

    /** account/startSignIn, answered at once: the attempt is initializing and carries no link yet (captured). */
    val STATE_STARTING = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"a0fab414-e838-47cc-aa87-a4086c3cfd85","phase":"initializing"},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/getState while the harness waits for the browser (link and expiry present) (captured). */
    val STATE_WAITING = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"a0fab414-e838-47cc-aa87-a4086c3cfd85","phase":"waiting-browser","authorizeUrl":"http://127.0.0.1:4590/dsh/authorize?authorize_id=MOCKID","expiresAt":1794979700569},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/startSignIn while an attempt runs: the same attempt comes back (captured). */
    val STATE_JOINED = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"a0fab414-e838-47cc-aa87-a4086c3cfd85","phase":"waiting-browser","authorizeUrl":"http://127.0.0.1:4590/dsh/authorize?authorize_id=MOCKID","expiresAt":1794979700569},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/cancelSignIn with an id that is not the attempt: the attempt is left alone (captured). */
    val STATE_STALE_CANCEL = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"a0fab414-e838-47cc-aa87-a4086c3cfd85","phase":"waiting-browser","authorizeUrl":"http://127.0.0.1:4590/dsh/authorize?authorize_id=MOCKID","expiresAt":1794979700569},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/cancelSignIn with the right id: cancelled, link gone, expiry kept (captured). */
    val STATE_CANCELLED = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"a0fab414-e838-47cc-aa87-a4086c3cfd85","phase":"cancelled","expiresAt":1794979700569},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/getState after the browser callback: credential stored, the attempt stays as succeeded (captured). */
    val STATE_SIGNED_IN = """{"ok":true,"value":{"status":"credential-stored","attempt":{"id":"db5cce04-6f5b-4f9c-b5bf-7c6960b814f4","phase":"succeeded","expiresAt":1794979701273},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/getProfile when signed in (captured). */
    val PROFILE_READY = """{"ok":true,"value":{"status":"ready","value":{"id":"mock-user-1","avatarUrl":null,"name":"Mock Account","contact":"138****5678"}}}"""

    /** account/getBalance when signed in: recharge wallets in two currencies and a bonus wallet (captured). */
    val BALANCE_READY = """{"ok":true,"value":{"status":"ready","value":[{"currency":"CNY","balance":"123.45"},{"currency":"USD","balance":"6.78"}],"bonusWallets":[{"currency":"CNY","balance":"10.00"}]}}"""

    /** account/getUnnotifiedBonuses with nothing to show (captured). */
    val BONUSES_NONE = """{"ok":true,"value":{"accountId":"mock-user-1","bonuses":[]}}"""

    /** account/getUnnotifiedBonuses with two: one with every field, one with a null title and no expiry (captured). */
    val BONUSES_TWO = """{"ok":true,"value":{"accountId":"mock-user-1","bonuses":[{"campaign":"welcome","amount":"10.00","currency":"CNY","grantedAt":"2026-11-01T00:00:00Z","orderId":"order-1","expiresAt":"2027-01-01T00:00:00Z","message":"You received 10 CNY. It expires on 2027-01-01.","title":"Welcome bonus"},{"orderId":"order-2","expiresAt":"","message":"A second bonus."}]}}"""

    /** account/ackBonusNotified for a bonus of the signed-in account (captured). */
    val ACK_OK = """{"ok":true,"value":true}"""

    /** account/ackBonusNotified naming another account: not recorded (captured). */
    val ACK_WRONG_ACCOUNT = """{"ok":true,"value":false}"""

    /** account/getBalance when Platform answered with something that is not a wallet summary (captured). */
    val BALANCE_FAILED = """{"ok":true,"value":{"status":"failed"}}"""

    /** account/getBalance with a zero wallet in exponent form, as Platform sent it in production (captured). */
    val BALANCE_ZERO_EXPONENT = """{"ok":true,"value":{"status":"ready","value":[{"currency":"CNY","balance":"0E-16"}],"bonusWallets":[]}}"""

    /** account/getState while signed in and a new attempt waits: the credential and the attempt are both reported (captured). */
    val STATE_REAUTH_WAITING = """{"ok":true,"value":{"status":"credential-stored","attempt":{"id":"80da5d79-9f91-4d20-9aa8-9baacfe62b0d","phase":"waiting-browser","authorizeUrl":"http://127.0.0.1:4590/dsh/authorize?authorize_id=MOCKID","expiresAt":1794979702880},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/signOut (captured). */
    val STATE_AFTER_SIGN_OUT = """{"ok":true,"value":{"status":"signed-out","attempt":null,"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/getProfile (and getBalance, getUnnotifiedBonuses) while signed out: null (captured). */
    val SIGNED_OUT_NULL = """{"ok":true,"value":null}"""

    /** account/hasRunningAccountTasks with nothing running (captured). */
    val RUNNING_TASKS_NONE = """{"ok":true,"value":false}"""

    /** account/getState after Platform answered the code exchange with HTTP 502 (captured). */
    val STATE_FAILED_EXCHANGE = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"481207ca-68e0-400c-afcb-bb20dbcbaf45","phase":"failed","expiresAt":1794979702975,"errorCode":"network"},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** account/getState once the attempt ran past its deadline (captured with attemptTimeoutMs set to 3000). */
    val STATE_EXPIRED = """{"ok":true,"value":{"status":"signed-out","attempt":{"id":"313e97a4-561c-4ba0-8820-a4c44ce0019d","phase":"expired","expiresAt":1794979154195,"errorCode":"expired"},"links":{"usageUrl":"http://127.0.0.1:4590/usage","topUpUrl":"http://127.0.0.1:4590/top_up"}}}"""

    /** Any account call on a harness whose account plugin is switched off (captured with the deepseek-account entry disabled). */
    val SERVICE_UNAVAILABLE = """{"ok":false,"error":{"code":"gateway/service-unavailable","message":"typert gateway: account/getState: active Service \"accountController\" is unavailable","details":{"endpoint":"account/getState"}}}"""
}
