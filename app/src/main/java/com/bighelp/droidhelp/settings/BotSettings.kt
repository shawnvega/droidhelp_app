package com.bighelp.droidhelp.settings

import kotlinx.serialization.Serializable

@Serializable
data class Bot(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val basePrompt: String = "",
    val enabled: Boolean = true
)

@Serializable
data class BotSettings(
    val bots: List<Bot> = emptyList(),
    val selectedBotId: String? = null,
    val customPrompts: Map<String, String> = emptyMap()
)

object BotSettingsOperations {
    fun addBot(settings: BotSettings, bot: Bot): BotSettings {
        if (settings.bots.any { it.id == bot.id }) return settings
        return settings.copy(bots = settings.bots + bot)
    }

    fun removeBot(settings: BotSettings, botId: String): BotSettings {
        val newBots = settings.bots.filter { it.id != botId }
        if (newBots.size == settings.bots.size) return settings

        val newSelectedBotId = if (settings.selectedBotId == botId) {
            newBots.firstOrNull()?.id
        } else {
            settings.selectedBotId
        }

        return settings.copy(bots = newBots, selectedBotId = newSelectedBotId)
    }

    fun renameBot(settings: BotSettings, botId: String, newName: String): BotSettings {
        if (newName.isBlank()) return settings
        val index = settings.bots.indexOfFirst { it.id == botId }
        return if (index == -1) {
            settings
        } else {
            val updatedBots = settings.bots.toMutableList().apply {
                this[index] = this[index].copy(name = newName)
            }
            settings.copy(bots = updatedBots)
        }
    }

    fun setBotEnabled(settings: BotSettings, botId: String, enabled: Boolean): BotSettings {
        val index = settings.bots.indexOfFirst { it.id == botId }
        if (index == -1) return settings

        val updatedBots = settings.bots.toMutableList().apply {
            this[index] = this[index].copy(enabled = enabled)
        }
        return settings.copy(bots = updatedBots)
    }

    fun selectBot(settings: BotSettings, botId: String?): BotSettings {
        return if (botId == null || settings.bots.any { it.id == botId }) {
            settings.copy(selectedBotId = botId)
        } else {
            settings
        }
    }
}

object CustomPromptOperations {
    fun upsertPrompt(settings: BotSettings, key: String, prompt: String): BotSettings {
        if (key.isBlank() || prompt.isBlank()) return settings
        return settings.copy(customPrompts = settings.customPrompts + (key to prompt))
    }

    fun deletePrompt(settings: BotSettings, key: String): BotSettings {
        if (!settings.customPrompts.containsKey(key)) return settings
        return settings.copy(customPrompts = settings.customPrompts - key)
    }

    fun getPromptForBot(settings: BotSettings, botId: String): String? {
        return settings.customPrompts[botId] ?: settings.bots.find { it.id == botId }?.basePrompt
    }
}
