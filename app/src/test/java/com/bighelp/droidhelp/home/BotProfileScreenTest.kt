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
        assertEquals("", state.role)
        assertEquals("Idle", state.status)
        assertEquals("localhost", state.hostName)
        assertEquals(true, state.isConnected)
        assertEquals(emptyList<String>(), state.files)
        assertEquals(emptyList<String>(), state.mediaItems)
        assertEquals(emptyList<BotApprovalItem>(), state.approvals)
    }

    @Test
    fun testBotProfileStateCustom() {
        val state = BotProfileState(
            botId = "custom-bot",
            botName = "Custom Bot",
            selectedTab = BotProfileTab.FILES,
            role = "Admin",
            status = "Running",
            hostName = "remote-host",
            isConnected = false,
            files = listOf("file1.txt", "file2.txt"),
            mediaItems = listOf("image1.png"),
            approvals = listOf(BotApprovalItem("1", "Approval 1"))
        )
        assertEquals("custom-bot", state.botId)
        assertEquals("Custom Bot", state.botName)
        assertEquals(BotProfileTab.FILES, state.selectedTab)
        assertEquals("Admin", state.role)
        assertEquals("Running", state.status)
        assertEquals("remote-host", state.hostName)
        assertEquals(false, state.isConnected)
        assertEquals(listOf("file1.txt", "file2.txt"), state.files)
        assertEquals(listOf("image1.png"), state.mediaItems)
        assertEquals(listOf(BotApprovalItem("1", "Approval 1")), state.approvals)
    }
}
