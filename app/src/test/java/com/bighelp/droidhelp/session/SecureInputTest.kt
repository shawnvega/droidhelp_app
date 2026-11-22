package com.bighelp.droidhelp.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureInputTest {

    @Test
    fun `test put and get`() = runTest {
        SecureInput.put("test-id", "secret-value")
        assertEquals("secret-value", SecureInput.get("test-id"))
    }

    @Test
    fun `test clear`() = runTest {
        SecureInput.put("test-id", "secret-value")
        SecureInput.clear()
        assertEquals(null, SecureInput.get("test-id"))
    }

    @Test
    fun `test bind and clear on profile change`() = runTest {
        SecureInput.bind("user-a")
        SecureInput.put("test-id", "secret-value")
        assertEquals("secret-value", SecureInput.get("test-id"))

        SecureInput.bind("user-b")
        assertEquals(null, SecureInput.get("test-id"))
    }
}
