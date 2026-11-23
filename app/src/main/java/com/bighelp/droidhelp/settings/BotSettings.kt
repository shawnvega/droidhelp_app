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
