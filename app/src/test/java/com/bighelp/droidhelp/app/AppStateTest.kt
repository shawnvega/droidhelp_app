package com.bighelp.droidhelp.app

import com.bighelp.droidhelp.home.model.AppRoute
import com.bighelp.droidhelp.home.model.HomeSelection
import com.bighelp.droidhelp.home.model.HomeTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppStateTest {

    @Test
    fun `initial state mirrors HomeSelection defaults`() {
        val selection = HomeSelection()
        val appState = AppState(selection)

        assertEquals(HomeTab.CONVERSATIONS, appState.selectedTab)
        assertTrue(appState.path.isEmpty())
        assertNull(appState.activeConversationID)
        assertFalse(appState.isSettingsPresented)
        assertTrue(appState.settingsPath.isEmpty())
        assertFalse(appState.isNeedsYouRequested)
    }

    @Test
    fun `open appends routes and select resets path`() {
        val appState = AppState(HomeSelection())

        appState.open(AppRoute.Projects)
        appState.open(AppRoute.Chat("conv-1"))
        assertEquals(listOf(AppRoute.Projects, AppRoute.Chat("conv-1")), appState.path)

        appState.select(HomeTab.WORK)
        assertEquals(HomeTab.WORK, appState.selectedTab)
        assertTrue(appState.path.isEmpty())
    }

    @Test
    fun `activateConversation updates activeConversationID`() {
        val appState = AppState(HomeSelection())

        appState.activateConversation("conv-42")
        assertEquals("conv-42", appState.activeConversationID)
    }
}
