package com.labteto.dshmobile.rates

import com.labteto.dshmobile.core.rates.RateSource
import com.labteto.dshmobile.core.rates.RateTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRateStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun nothingSavedReadsAsNothing() = runBlocking {
        assertNull(FileRateStore(File(folder.root, "rates.json")).load())
    }

    @Test
    fun aTableSurvivesAWriteAndARead() = runBlocking {
        val store = FileRateStore(File(folder.root, "rates.json"))
        val table = RateTable(RateSource.EXCHANGE_RATE_API, fetchedAt = 1_000, publishedAt = 500, rates = mapOf("USD" to 1.0, "EUR" to 0.86))
        store.save(Json.encodeToString(RateTable.serializer(), table))
        assertEquals(table, Json.decodeFromString(RateTable.serializer(), store.load()!!))
    }

    @Test
    fun aSecondWriteReplacesTheFirstAndLeavesNoTempFile() = runBlocking {
        val file = File(folder.root, "sub/rates.json")
        val store = FileRateStore(file)
        store.save("one")
        store.save("two")
        assertEquals("two", store.load())
        assertTrue(file.isFile)
        assertFalse(File(file.parentFile, "rates.json.tmp").exists())
    }
}
