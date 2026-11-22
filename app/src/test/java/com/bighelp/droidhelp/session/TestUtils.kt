package com.bighelp.droidhelp.session

import org.junit.Assert.fail

/**
 * Asserts that [block] throws an exception of type [T] and returns it.
 */
suspend inline fun <reified T : Throwable> assertThrows(crossinline block: suspend () -> Unit): T {
    try {
        block()
        fail("Expected exception ${T::class.java.simpleName} but none was thrown")
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Unreachable")
}

/**
 * Asserts that [block] throws an exception of type [T] and returns it.
 */
suspend inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T =
    assertThrows(block)

/**
 * Asserts that [block] throws an exception of type [T] and returns it.
 * This function must be called from within a coroutine scope.
 * @return The thrown exception.
 */
suspend fun <T : Throwable> assertThrowsSuspendAndGet(block: suspend () -> Unit): T {
    try {
        block()
        fail("Expected exception but none was thrown")
    } catch (e: Throwable) {
        @Suppress("UNCHECKED_CAST")
        return e as T
    }
    throw AssertionError("Unreachable")
}
