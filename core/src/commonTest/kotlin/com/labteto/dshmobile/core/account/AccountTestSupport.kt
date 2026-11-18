package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.RpcResultJsonSerializer
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.AccountBonusBatch
import com.labteto.dshmobile.core.wire.dto.AccountProfileOutcome
import com.labteto.dshmobile.core.wire.dto.AccountView
import com.labteto.dshmobile.core.wire.dto.AccountWallet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/** The `result` slot of a recorded answer, as the client reads it. */
internal fun accountResult(json: String): RpcResult<kotlinx.serialization.json.JsonElement> =
    WireJson.decodeFromString(RpcResultJsonSerializer, json)

/** A recorded answer decoded as [serializer]'s type; the test fails if the answer was a refusal. */
internal fun <T> accountValue(json: String, serializer: KSerializer<T>): T {
    val result = accountResult(json) as RpcResult.Ok
    return WireJson.decodeFromJsonElement(serializer, result.value)
}

/** A recorded `account/getState` answer. */
internal fun stateOf(json: String): AccountView = accountValue(json, AccountView.serializer())

internal fun profileOf(json: String): AccountProfileOutcome? = accountValue(json, AccountProfileOutcome.serializer().nullable)

internal fun balanceOf(json: String): AccountWallet? = accountValue(json, AccountWallet.serializer().nullable)

internal fun bonusesOf(json: String): AccountBonusBatch? = accountValue(json, AccountBonusBatch.serializer().nullable)

internal fun ackOf(json: String): Boolean = accountValue(json, Boolean.serializer())

/** A recorded refusal as the error the client folds it into. */
internal fun accountRefusal(json: String): RpcError = (accountResult(json) as RpcResult.Err).error
