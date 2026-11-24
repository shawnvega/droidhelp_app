package com.bighelp.droidhelp.settings

import com.bighelp.droidhelp.platform.SecureStorage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BotSettingsRepository(private val storage: SecureStorage) {
    companion object {
        private const val KEY_BOT_SETTINGS_JSON = "bot.settings_json"
    }

    suspend fun getBotSettings(): BotSettings {
        val json = storage.getString(KEY_BOT_SETTINGS_JSON) ?: return BotSettings()
        return try {
            Json.decodeFromString(json)
        } catch (e: SerializationException) {
            println("Error decoding BotSettings: ${e.message}")
            BotSettings()
        }
    }

    suspend fun saveBotSettings(settings: BotSettings) {
        storage.saveString(KEY_BOT_SETTINGS_JSON, Json.encodeToString(settings))
    }
}
