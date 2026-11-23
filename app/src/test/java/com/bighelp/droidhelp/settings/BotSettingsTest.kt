package com.bighelp.droidhelp.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun testSerialization() {
        val bot = Bot(id = "2", name = "SerBot", avatar = "url", basePrompt = "hi", enabled = false)
        val json = Json.encodeToString(bot)
        val decoded = Json.decodeFromString<Bot>(json)
        assertEquals(bot, decoded)

        val settings = BotSettings(
            bots = listOf(bot),
            selectedBotId = "2",
            customPrompts = mapOf("key" to "value")
        )
        val settingsJson = Json.encodeToString(settings)
        val decodedSettings = Json.decodeFromString<BotSettings>(settingsJson)
        assertEquals(settings, decodedSettings)
    }
}
