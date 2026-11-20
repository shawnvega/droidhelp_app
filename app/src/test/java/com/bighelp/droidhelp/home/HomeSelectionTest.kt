package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.HomeConversationsView
import com.bighelp.droidhelp.home.model.HomeSelection
import com.bighelp.droidhelp.home.model.HomeTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeSelectionTest {

    @Test
    fun `defaults to conversations tab and chats view`() {
        val selection = HomeSelection()

        assertEquals(HomeTab.CONVERSATIONS, selection.tab)
        assertEquals(HomeConversationsView.CHATS, selection.view)
        assertFalse(selection.isNeedsYouRequested)
        assertFalse(selection.isSettingsPresented)
        assertTrue(selection.settingsPath.isEmpty())
    }

    @Test
    fun `land updates tab and view for each landing target`() {
        val selection = HomeSelection()

        selection.land(HomeSelection.Landing.BOTS, HomeConversationsView.CHATS)
        assertEquals(HomeTab.CONVERSATIONS, selection.tab)
        assertEquals(HomeConversationsView.BOTS, selection.view)

        selection.land(HomeSelection.Landing.CHATS, HomeConversationsView.BOTS)
        assertEquals(HomeTab.CONVERSATIONS, selection.tab)
        assertEquals(HomeConversationsView.CHATS, selection.view)

        selection.land(HomeSelection.Landing.TODAY, HomeConversationsView.BOTS)
        assertEquals(HomeTab.TODAY, selection.tab)
        assertEquals(HomeConversationsView.BOTS, selection.view)

        selection.land(HomeSelection.Landing.WORK, HomeConversationsView.CHATS)
        assertEquals(HomeTab.WORK, selection.tab)
        assertEquals(HomeConversationsView.CHATS, selection.view)
    }
}
