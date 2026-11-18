package com.bighelp.droidhelp.home.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class HomeSelection {
    var tab by mutableStateOf(HomeTab.CONVERSATIONS)
    var view by mutableStateOf(HomeConversationsView.CHATS)
    var isNeedsYouRequested by mutableStateOf(false)
    var isSettingsPresented by mutableStateOf(false)
    var settingsPath by mutableStateOf<List<AppRoute>>(emptyList())

    fun land(landing: Landing, defaultView: HomeConversationsView) {
        when (landing) {
            Landing.BOTS -> {
                tab = HomeTab.CONVERSATIONS
                view = HomeConversationsView.BOTS
            }
            Landing.CHATS -> {
                tab = HomeTab.CONVERSATIONS
                view = HomeConversationsView.CHATS
            }
            Landing.TODAY -> {
                tab = HomeTab.TODAY
                view = defaultView
            }
            Landing.WORK -> {
                tab = HomeTab.WORK
                view = defaultView
            }
        }
    }

    enum class Landing {
        BOTS, CHATS, TODAY, WORK
    }
}
