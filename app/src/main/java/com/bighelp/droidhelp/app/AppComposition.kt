package com.bighelp.droidhelp.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.home.*
import com.bighelp.droidhelp.home.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppComposition(appState: AppState) {
    val home = appState.home
    val currentTab = home.tab
    val currentView = home.view

    Scaffold(
        topBar = {
            HomeHeaderBar(
                isBotMode = currentView == HomeConversationsView.BOTS,
                onToggleMode = {
                    home.view = if (currentView == HomeConversationsView.BOTS) 
                        HomeConversationsView.CHATS else HomeConversationsView.BOTS
                },
                title = if (currentView == HomeConversationsView.BOTS) "Bots" else "Chats",
                searchQuery = "", 
                onSearchQueryChange = {},
                computerName = "My Computer",
                onComputerSelect = {},
                onRootControlClick = {
                    home.tab = HomeTab.CONVERSATIONS
                    home.view = HomeConversationsView.CHATS
                },
                onAddClick = {}
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                HomeTab.CONVERSATIONS -> {
                    when (currentView) {
                        HomeConversationsView.BOTS -> {
                            // Placeholder for Bots
                        }
                        HomeConversationsView.CHATS -> {
                            ChatsInboxView(rows = emptyList(), onChatClick = {})
                        }
                    }
                }
                HomeTab.TODAY -> {
                    // Placeholder for Today
                }
                HomeTab.WORK -> {
                    WorkView()
                }
            }
        }
    }
}
