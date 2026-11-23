package com.bighelp.droidhelp.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatScreenTest {

    @Test
    fun testFilterRecipients() {
        val recipients = listOf(
            NewChatRecipient("1", "Alice", false),
            NewChatRecipient("2", "Bob", true),
            NewChatRecipient("3", "Charlie", false)
        )

        // Test empty query
        assertEquals(recipients, filterRecipients(recipients, ""))

        // Test query with match
        val filtered = filterRecipients(recipients, "Bo")
        assertEquals(1, filtered.size)
        assertEquals("Bob", filtered[0].name)

        // Test query with no match
        val noMatch = filterRecipients(recipients, "Z")
        assertTrue(noMatch.isEmpty())

        // Test case insensitivity
        val caseInsensitive = filterRecipients(recipients, "alice")
        assertEquals(1, caseInsensitive.size)
        assertEquals("Alice", caseInsensitive[0].name)
    }

    @Test
    fun testNewChatUiState() {
        val recipient1 = NewChatRecipient("1", "Alice", false)
        val recipient2 = NewChatRecipient("2", "Bob", true)
        val recipients = listOf(recipient1, recipient2)

        val state = NewChatUiState(
            query = "Al",
            selectedIds = setOf("1"),
            recipients = recipients,
            showCodingOptions = true
        )

        assertEquals("Al", state.query)
        assertTrue(state.selectedIds.contains("1"))
        assertEquals(2, state.recipients.size)
        assertTrue(state.showCodingOptions)
    }
}
