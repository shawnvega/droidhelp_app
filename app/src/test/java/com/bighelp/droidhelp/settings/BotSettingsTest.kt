package com.bighelp.droidhelp.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BotSettingsTest {

    @Test
    fun testBotDefaults() {
        val bot = Bot(id = "1", name = "Test Bot")
        assertEquals("1", bot.id)
        assertEquals("Test Bot", bot.name)
        assertEquals(null, bot.avatar)
        assertEquals("", bot.basePrompt)
        assertEquals(true, bot.enabled)
    }

    @Test
    fun testBotSettingsDefaults() {
        val settings = BotSettings()
        assertEquals(emptyList<Bot>(), settings.bots)
        assertEquals(null, settings.selectedBotId)
        assertEquals(emptyMap<String, String>(), settings.customPrompts)
    }

    @Test
    fun testAddBot() {
        val bot1 = Bot(id = "1", name = "Bot 1")
        val bot2 = Bot(id = "2", name = "Bot 2")
        val settings = BotSettings(bots = listOf(bot1))

        // Add new bot
        val settingsWithBot2 = BotSettingsOperations.addBot(settings, bot2)
        assertEquals(2, settingsWithBot2.bots.size)
        assertEquals(bot2, settingsWithBot2.bots[1])

        // Add duplicate bot
        val settingsWithDuplicate = BotSettingsOperations.addBot(settingsWithBot2, bot2)
        assertEquals(2, settingsWithDuplicate.bots.size)
    }

    @Test
    fun testRemoveBot() {
        val bot1 = Bot(id = "1", name = "Bot 1")
        val bot2 = Bot(id = "2", name = "Bot 2")
        val bot3 = Bot(id = "3", name = "Bot 3")
        val settings = BotSettings(bots = listOf(bot1, bot2, bot3), selectedBotId = "2")

        // Remove non-selected bot
        val settingsAfterRemoving1 = BotSettingsOperations.removeBot(settings, "1")
        assertEquals(2, settingsAfterRemoving1.bots.size)
        assertEquals("2", settingsAfterRemoving1.selectedBotId)

        // Remove selected bot (should fall back to first remaining)
        val settingsAfterRemoving2 = BotSettingsOperations.removeBot(settings, "2")
        assertEquals(2, settingsAfterRemoving2.bots.size)
        assertEquals("1", settingsAfterRemoving2.selectedBotId)

        // Remove last remaining bot (should fall back to null)
        val singleBotSettings = BotSettings(bots = listOf(bot1), selectedBotId = "1")
        val settingsAfterRemovingLast = BotSettingsOperations.removeBot(singleBotSettings, "1")
        assertEquals(0, settingsAfterRemovingLast.bots.size)
        assertEquals(null, settingsAfterRemovingLast.selectedBotId)

        // Remove non-existent bot
        val settingsAfterRemovingNonExistent = BotSettingsOperations.removeBot(settings, "99")
        assertEquals(3, settingsAfterRemovingNonExistent.bots.size)
    }

    @Test
    fun testRenameBot() {
        val bot1 = Bot(id = "1", name = "Bot 1")
        val settings = BotSettings(bots = listOf(bot1))

        // Rename existing bot
        val renamedSettings = BotSettingsOperations.renameBot(settings, "1", "New Name")
        assertEquals("New Name", renamedSettings.bots[0].name)

        // Rename with blank name
        val blankNameSettings = BotSettingsOperations.renameBot(settings, "1", "  ")
        assertEquals("Bot 1", blankNameSettings.bots[0].name)

        // Rename non-existent bot
        val nonExistentSettings = BotSettingsOperations.renameBot(settings, "99", "Ghost")
        assertEquals(1, nonExistentSettings.bots.size)
    }

    @Test
    fun testSetBotEnabled() {
        val bot1 = Bot(id = "1", name = "Bot 1", enabled = true)
        val settings = BotSettings(bots = listOf(bot1))

        // Disable bot
        val disabledSettings = BotSettingsOperations.setBotEnabled(settings, "1", false)
        assertEquals(false, disabledSettings.bots[0].enabled)

        // Enable bot
        val enabledSettings = BotSettingsOperations.setBotEnabled(disabledSettings, "1", true)
        assertEquals(true, enabledSettings.bots[0].enabled)

        // Non-existent bot
        val nonExistentSettings = BotSettingsOperations.setBotEnabled(settings, "99", false)
        assertEquals(1, nonExistentSettings.bots.size)
        assertEquals(true, nonExistentSettings.bots[0].enabled)
    }

    @Test
    fun testSelectBot() {
        val bot1 = Bot(id = "1", name = "Bot 1")
        val bot2 = Bot(id = "2", name = "Bot 2")
        val settings = BotSettings(bots = listOf(bot1, bot2))

        // Select existing bot
        val selectedSettings = BotSettingsOperations.selectBot(settings, "2")
        assertEquals("2", selectedSettings.selectedBotId)

        // Select null
        val nullSelectedSettings = BotSettingsOperations.selectBot(settings, null)
        assertEquals(null, nullSelectedSettings.selectedBotId)

        // Select non-existent bot
        val nonExistentSettings = BotSettingsOperations.selectBot(settings, "99")
        assertEquals(null, nonExistentSettings.selectedBotId)
    }

    @Test
    fun testUpsertPrompt() {
        val bot1 = Bot(id = "1", name = "Bot 1", basePrompt = "Base 1")
        val settings = BotSettings(bots = listOf(bot1))

        // Valid upsert
        val settingsWithPrompt = CustomPromptOperations.upsertPrompt(settings, "1", "Custom 1")
        assertEquals("Custom 1", settingsWithPrompt.customPrompts["1"])

        // Blank key rejection
        val settingsWithBlankKey = CustomPromptOperations.upsertPrompt(settings, "", "Custom 1")
        assertEquals(emptyMap<String, String>(), settingsWithBlankKey.customPrompts)

        // Blank prompt rejection
        val settingsWithBlankPrompt = CustomPromptOperations.upsertPrompt(settings, "1", "  ")
        assertEquals(emptyMap<String, String>(), settingsWithBlankPrompt.customPrompts)
    }

    @Test
    fun testDeletePrompt() {
        val bot1 = Bot(id = "1", name = "Bot 1")
        val settings = BotSettings(
            bots = listOf(bot1),
            customPrompts = mapOf("1" to "Custom 1")
        )

        // Delete existing prompt
        val settingsAfterDelete = CustomPromptOperations.deletePrompt(settings, "1")
        assertEquals(emptyMap<String, String>(), settingsAfterDelete.customPrompts)

        // Delete non-existent prompt
        val settingsAfterDeletingNonExistent = CustomPromptOperations.deletePrompt(settings, "99")
        assertEquals(1, settingsAfterDeletingNonExistent.customPrompts.size)
    }

    @Test
    fun testGetPromptForBot() {
        val bot1 = Bot(id = "1", name = "Bot 1", basePrompt = "Base 1")
        val bot2 = Bot(id = "2", name = "Bot 2", basePrompt = "Base 2")
        val settings = BotSettings(
            bots = listOf(bot1, bot2),
            customPrompts = mapOf("1" to "Custom 1")
        )

        // Custom prompt exists
        assertEquals("Custom 1", CustomPromptOperations.getPromptForBot(settings, "1"))

        // Fallback to base prompt
        assertEquals("Base 2", CustomPromptOperations.getPromptForBot(settings, "2"))

        // No bot found
        assertEquals(null, CustomPromptOperations.getPromptForBot(settings, "3"))
    }
}
