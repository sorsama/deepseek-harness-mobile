package com.labteto.dshmobile.testing

import kotlin.math.abs
import kotlin.test.fail

/*
 * The JUnit assertions the app's tests were written with, over `kotlin.test`, so a test that moved
 * from `:app` into the shared module runs in common code unchanged. JUnit puts the message first and
 * `kotlin.test` last; these keep JUnit's order.
 */

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)

fun assertFalse(message: String, condition: Boolean) = kotlin.test.assertFalse(condition, message)

fun <T> assertEquals(expected: T, actual: T) = kotlin.test.assertEquals(expected, actual)

fun <T> assertEquals(message: String, expected: T, actual: T) = kotlin.test.assertEquals(expected, actual, message)

fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals("", expected, actual, delta)

fun assertEquals(message: String, expected: Float, actual: Float, delta: Float) {
    if (expected == actual) return
    if (abs(expected - actual) > delta) fail("$message expected:<$expected> but was:<$actual>")
}

fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals("", expected, actual, delta)

fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) {
    if (expected == actual) return
    if (abs(expected - actual) > delta) fail("$message expected:<$expected> but was:<$actual>")
}

fun assertNotNull(actual: Any?) = kotlin.test.assertNotNull(actual)

fun assertNotNull(message: String, actual: Any?) = kotlin.test.assertNotNull(actual, message)

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String, actual: Any?) = kotlin.test.assertNull(actual, message)

fun <T> assertNotEquals(unexpected: T, actual: T) = kotlin.test.assertNotEquals(unexpected, actual)
