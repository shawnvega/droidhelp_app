package com.bighelp.droidhelp.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BotProfileScreenTest {

    @Test
    fun testBotProfileTabEntries() {
        val expected = listOf(
            BotProfileTab.DASHBOARD to "Dashboard",
            BotProfileTab.INFO to "Info",
            BotProfileTab.COMPUTER to "Computer",
            BotProfileTab.FILES to "Files",
            BotProfileTab.MEDIA to "Media",
            BotProfileTab.APPROVALS to "Approvals"
        )

        assertEquals(expected.size, BotProfileTab.entries.size)
        expected.forEachIndexed { index, (tab, title) ->
            assertEquals(tab, BotProfileTab.entries[index])
            assertEquals(title, tab.title)
        }
    }

    @Test
    fun testBotProfileStateDefaults() {
        val state = BotProfileState(botId = "test-bot", botName = "Test Bot")
        assertEquals("test-bot", state.botId)
        assertEquals("Test Bot", state.botName)
        assertEquals(BotProfileTab.DASHBOARD, state.selectedTab)
    }
}
