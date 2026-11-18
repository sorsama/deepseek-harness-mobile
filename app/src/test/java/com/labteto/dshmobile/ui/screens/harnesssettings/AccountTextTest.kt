package com.labteto.dshmobile.ui.screens.harnesssettings

import com.labteto.dshmobile.core.wire.dto.AccountBonus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Which bonus notices are still worth showing. The shapes are the ones the harness forwards from
 * Platform (`packages/credentials/deepseek-account-platform/src/details.ts`: `expires_at` is a string, empty
 * when the bonus never expires), as recorded in the core fixtures: `2027-01-01T00:00:00Z` and an empty one.
 */
class AccountTextTest {
    private val now = Instant.parse("2026-11-18T10:00:00Z")

    private fun bonus(id: String, expiresAt: String) = AccountBonus(orderId = id, message = "m", expiresAt = expiresAt)

    @Test
    fun `a bonus with no expiry and one still in date are shown`() {
        val shown = liveBonuses(listOf(bonus("a", ""), bonus("b", "2027-01-01T00:00:00Z")), now)
        assertEquals(listOf("a", "b"), shown.map { it.orderId })
    }

    @Test
    fun `a bonus whose award has run out is not shown`() {
        val shown = liveBonuses(listOf(bonus("old", "2026-11-18T09:59:59Z"), bonus("edge", "2026-11-18T10:00:00Z"), bonus("new", "2026-11-18T10:00:01Z")), now)
        assertEquals(listOf("new"), shown.map { it.orderId })
    }

    @Test
    fun `an expiry with an offset is read too`() {
        val shown = liveBonuses(listOf(bonus("past", "2026-11-18T17:00:00+08:00"), bonus("later", "2026-11-18T19:00:00+08:00")), now)
        assertEquals(listOf("later"), shown.map { it.orderId })
    }

    @Test
    fun `a timestamp that cannot be read is not evidence of expiry`() {
        val shown = liveBonuses(listOf(bonus("odd", "soon"), bonus("odd2", "2026-13-45")), now)
        assertEquals(listOf("odd", "odd2"), shown.map { it.orderId })
    }

    @Test
    fun `the order of the server is kept`() {
        val shown = liveBonuses(listOf(bonus("2", ""), bonus("1", ""), bonus("3", "")), now)
        assertEquals(listOf("2", "1", "3"), shown.map { it.orderId })
    }
}
