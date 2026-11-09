package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.Serializable

@Serializable data class SubscriptionRoster(val providers: Map<String, SubscriptionProviderStatus> = emptyMap())
@Serializable data class SubscriptionProviderStatus(val accounts: List<SubscriptionAccount> = emptyList())
/** Opaque host selector. Never render, log or persist this object. */
@Serializable data class SubscriptionAccount(val key: String, val isDefault: Boolean = false)
@Serializable data class ProviderQuota(val supported: Boolean, val windows: List<QuotaWindow> = emptyList(), val observation: QuotaObservation? = null)
@Serializable data class QuotaWindow(val kind: String, val usedPercent: Double, val resetsAt: Long? = null, val scope: String? = null)
@Serializable data class QuotaObservation(val sampledAt: Long? = null, val stale: Boolean)
@Serializable data class AccountUiMetadata(val version: String, val locale: String, val timezoneOffsetSeconds: Int)
@Serializable data class AccountWallet(val status: String, val value: List<CurrencyWallet> = emptyList(), val bonusWallets: List<CurrencyWallet> = emptyList())
@Serializable data class CurrencyWallet(val currency: String, val balance: String)
