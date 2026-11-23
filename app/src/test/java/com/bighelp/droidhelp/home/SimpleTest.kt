package com.bighelp.droidhelp.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleTest {
    @Test
    fun testState() {
        val state = NewChatUiState(query = "test")
        assertEquals("test", state.query)
    }
}
