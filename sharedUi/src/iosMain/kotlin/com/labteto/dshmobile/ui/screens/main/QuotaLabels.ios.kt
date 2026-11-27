package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.core.usage.UsageLabels
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.quota_compact_day
import com.labteto.dshmobile.shared.resources.quota_compact_hour
import com.labteto.dshmobile.shared.resources.quota_compact_minute
import com.labteto.dshmobile.shared.resources.quota_compact_session
import com.labteto.dshmobile.shared.resources.quota_compact_week
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun quotaLabels(): UsageLabels = UsageLabels(
    stringResource(Res.string.quota_compact_session), stringResource(Res.string.quota_compact_week),
    stringResource(Res.string.quota_compact_day), stringResource(Res.string.quota_compact_hour), stringResource(Res.string.quota_compact_minute),
    decimalSeparator = ".", digits = "0123456789", groupingSeparator = ",",
)
