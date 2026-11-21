package com.bighelp.droidhelp.session

import org.junit.Assert.fail
import kotlinx.coroutines.test.runTest

/**
 * Asserts that [block] throws an exception of type [T].
 * This function starts its own [runTest] to ensure a coroutine scope is available.
 */
inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit) {
    runTest {
        try {
            block()
            fail("Expected exception ${T::class.java.simpleName} but none was thrown")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}

/**
 * Asserts that [block] throws an exception of type [T] and returns it.
 * This function must be called from within a coroutine scope.
 * @return The thrown exception.
 */
suspend fun <T : Throwable> assertThrowsSuspendAndGet(block: suspend () -> Unit): T {
    try {
        block()
        throw AssertionError("Expected exception but none was thrown")
    } catch (e: Throwable) {
        @Suppress("UNCHECKED_CAST")
        return e as T
    }
}
