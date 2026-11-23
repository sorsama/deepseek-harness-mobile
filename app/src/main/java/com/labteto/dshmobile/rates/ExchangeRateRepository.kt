package com.labteto.dshmobile.rates

import android.content.Context
import com.labteto.dshmobile.core.rates.ExchangeRates
import com.labteto.dshmobile.core.rates.RateStore
import com.labteto.dshmobile.core.rates.RateTable
import com.labteto.dshmobile.core.wire.OkHttpPlainHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The exchange-rate table behind the approximate balance, for the Android screens.
 *
 * The rules (24 hour refresh, 7 day limit, the two feeds, the cache) live in [ExchangeRates] in
 * `:core`, shared with iOS; this class is only the Android carrier (the app's OkHttp client) and
 * storage (a file). Nothing here runs by itself: a screen calls [refresh] only while the "Also show
 * balance in" setting is on, so with it off the app never asks anyone for a rate.
 */
@Singleton
class ExchangeRateRepository @Inject constructor(
    client: OkHttpClient,
    @ApplicationContext context: Context,
) {
    private val rates = ExchangeRates(
        http = OkHttpPlainHttp(client),
        store = FileRateStore(File(context.noBackupFilesDir, "exchange-rates.json")),
        clock = System::currentTimeMillis,
    )

    private val _table = MutableStateFlow<RateTable?>(null)

    /** The table to convert with, or null while there is none that may be shown. */
    val table: StateFlow<RateTable?> = _table.asStateFlow()

    /**
     * Publishes what is on disk at once, then fetches if the table is due (at most once a day) and
     * publishes whatever may be shown after that. The first step is what keeps an offline phone, or
     * a request that takes its full timeout, from holding the estimate back until the request ends.
     */
    suspend fun refresh() {
        if (_table.value == null) _table.value = rates.table(allowNetwork = false)
        _table.value = rates.table()
    }
}

/**
 * The cache as one file in the app's no-backup folder: a restored backup must not bring back
 * rates from another day, and the table holds nothing that is worth backing up. Written to a
 * sibling and renamed, so a kill in the middle leaves the old file and not half of the new one.
 */
internal class FileRateStore(private val file: File) : RateStore {
    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (file.isFile) file.readText() else null
    }

    override suspend fun save(text: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                throw java.io.IOException("could not replace ${file.name}")
            }
        }
    }
}
