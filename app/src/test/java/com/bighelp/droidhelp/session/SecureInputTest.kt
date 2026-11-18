package com.bighelp.droidhelp.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureInputTest {

    @Test
    fun `test put and get`() = runTest {
        val secureInput = SecureInput()
        secureInput.put("test-id", "secret-value")
        assertEquals("secret-value", secureInput.get("test-id"))
    }

    @Test
    fun `test clear`() = runTest {
        val secureInput = SecureInput()
        secureInput.put("test-id", "secret-value")
        secureInput.clear()
        assertEquals(null, secureInput.get("test-id"))
    }

    @Test
    fun `test bind and clear on profile change`() = runTest {
        val secureInput = SecureInput()
        secureInput.bind("user-a")
        secureInput.put("test-id", "secret-value")
        assertEquals("secret-value", secureInput.get("test-id"))

        secureInput.bind("user-b")
        assertEquals(null, secureInput.get("test-id"))
    }
}
