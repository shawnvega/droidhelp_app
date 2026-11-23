package com.bighelp.droidhelp.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testToggleRecipientSelection_SingleBot() {
        val bot = NewChatRecipient("2", "Bob", true)
        val user = NewChatRecipient("1", "Alice", false)
        val recipients = listOf(bot, user)
        val initialState = NewChatUiState(recipients = recipients)

        // Select bot
        val stateAfterBot = toggleRecipientSelection(initialState, "2")
        assertTrue(stateAfterBot.selectedIds.contains("2"))
        assertTrue(stateAfterBot.showCodingOptions)
    }

    @Test
    fun testToggleRecipientSelection_MultiBot() {
        val bot1 = NewChatRecipient("2", "Bob", true)
        val bot2 = NewChatRecipient("3", "Charlie", true)
        val recipients = listOf(bot1, bot2)
        val initialState = NewChatUiState(recipients = recipients)

        // Select bot1
        val stateAfterBot1 = toggleRecipientSelection(initialState, "2")
        assertTrue(stateAfterBot1.selectedIds.contains("2"))
        assertTrue(stateAfterBot1.showCodingOptions)

        // Select bot2
        val stateAfterBoth = toggleRecipientSelection(stateAfterBot1, "3")
        assertTrue(stateAfterBoth.selectedIds.contains("3"))
        assertFalse(stateAfterBoth.showCodingOptions)
    }

    @Test
    fun testToggleRecipientSelection_Deselection() {
        val bot = NewChatRecipient("2", "Bob", true)
        val recipients = listOf(bot)
        val initialState = NewChatUiState(recipients = recipients, selectedIds = setOf("2"), showCodingOptions = true)

        // Deselect bot
        val stateAfterDeselect = toggleRecipientSelection(initialState, "2")
        assertFalse(stateAfterDeselect.selectedIds.contains("2"))
        assertFalse(stateAfterDeselect.showCodingOptions)
    }

    @Test
    fun testCanStartNewChat() {
        val recipients = emptyList<NewChatRecipient>()
        val stateEmpty = NewChatUiState(recipients = recipients)
        assertFalse(canStartNewChat(stateEmpty))

        val stateWithOne = stateEmpty.copy(selectedIds = setOf("1"))
        assertTrue(canStartNewChat(stateWithOne))
    }
}
