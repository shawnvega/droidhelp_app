package com.bighelp.droidhelp.chat

data class ChatCardAction(
    val id: String,
    val label: String,
    val isPrimary: Boolean = false
)

data class ChatCard(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val actions: List<ChatCardAction> = emptyList()
)
