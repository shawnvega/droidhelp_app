package com.bighelp.droidhelp.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bighelp.droidhelp.home.model.HomeSelection
import com.bighelp.droidhelp.home.model.HomeTab

@Composable
fun HomeShell(
    selection: HomeSelection,
    todayHasNew: Boolean = false,
    onNewChat: () -> Unit = {},
    conversationsContent: @Composable () -> Unit = { Text("Conversations") },
    todayContent: @Composable () -> Unit = { Text("Today") },
    workContent: @Composable () -> Unit = { Text("Work") },
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            HomeTabBar(
                selectedTab = selection.tab,
                conversationsLabel = "Conversations",
                todayHasNew = todayHasNew,
                onSelectTab = { selection.tab = it },
                onNewChat = onNewChat
            )
        }
    ) { _ ->
        when (selection.tab) {
            HomeTab.CONVERSATIONS -> conversationsContent()
            HomeTab.TODAY -> todayContent()
            HomeTab.WORK -> workContent()
        }
    }
}
