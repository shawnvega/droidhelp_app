package com.bighelp.droidhelp.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedMethodTest {

    @Test
    fun testUnsupportedMethodCreation() {
        val methodName = "test_method"
        val message = unsupportedMethod(methodName)

        assertTrue(message is Message)
        assertTrue(message is UnsupportedMethod)
        assertEquals(methodName, (message as UnsupportedMethod).method)
    }

    @Test
    fun testUnsupportedMethodEquality() {
        val m1 = unsupportedMethod("foo")
        val m2 = unsupportedMethod("foo")
        val m3 = unsupportedMethod("bar")

        assertEquals(m1, m2)
        assertEquals(m1, UnsupportedMethod("foo"))
        assertTrue(m1 != m3)
    }
}
