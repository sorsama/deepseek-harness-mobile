package com.labteto.dshmobile.ui.components

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** `oneDecimal` (common code) writes what JVM `"%.1f".format(Locale.US, …)` wrote before the move. */
class OneDecimalTest {

    private fun jvm(value: Double) = "%.1f".format(Locale.US, value)

    @Test
    fun `halves and edges round as the JVM formatter does`() {
        listOf(
            0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, 0.95, 1.05, 2.675, 9.95, 99.95, 18.4, 18.45, 1e-5, 4.9999,
            123456789.95, 1e21, 1.7976931348623157E308, -0.05, -1.25, -0.01, Double.NaN, Double.POSITIVE_INFINITY,
        ).forEach { assertEquals("$it", jvm(it), oneDecimal(it)) }
    }

    @Test
    fun `rates and durations of every size match`() {
        val random = Random(141)
        repeat(20_000) {
            val value = when (it % 4) {
                0 -> random.nextDouble(0.0, 100.0)
                1 -> random.nextInt(0, 100_000) / 1000.0
                2 -> random.nextInt(0, 10_000) / 100.0
                else -> random.nextDouble(0.0, 1e7)
            }
            assertEquals("$value", jvm(value), oneDecimal(value))
        }
    }

    private fun jvmWhole(value: Double) = String.format(Locale.US, "%.0f", value)

    @Test
    fun `a whole number rounds as the JVM formatter does`() {
        listOf(
            0.0, -0.0, 0.5, 1.5, 2.5, 0.49999999999999994, 0.4, 9.5, 99.5, 18.4, 18.5, 1e-5, 4.9999, 123456789.5, 1e21,
            1.7976931348623157E308, -0.5, -1.5, -0.4, Double.NaN, Double.POSITIVE_INFINITY,
        ).forEach { assertEquals("$it", jvmWhole(it), wholeNumber(it)) }
    }

    @Test
    fun `token rates of every size match`() {
        val random = Random(157)
        repeat(20_000) {
            val value = when (it % 3) {
                0 -> random.nextDouble(0.0, 500.0)
                1 -> random.nextInt(0, 100_000) / 10.0
                else -> random.nextDouble(0.0, 1e7)
            }
            assertEquals("$value", jvmWhole(value), wholeNumber(value))
        }
    }
}
