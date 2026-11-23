package com.bighelp.droidhelp.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class BotProfileTab(val title: String) {
    DASHBOARD("Dashboard"),
    INFO("Info"),
    COMPUTER("Computer"),
    FILES("Files"),
    MEDIA("Media"),
    APPROVALS("Approvals")
}

data class BotProfileState(
    val botId: String,
    val botName: String,
    val selectedTab: BotProfileTab = BotProfileTab.DASHBOARD
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotProfileScreen(
    state: BotProfileState,
    onSelectTab: (BotProfileTab) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.botName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowLeft,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = modifier.padding(innerPadding)) {
            ScrollableTabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                edgePadding = 16.dp
            ) {
                BotProfileTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.title) }
                    )
                }
            }

            when (state.selectedTab) {
                BotProfileTab.DASHBOARD -> Text("Dashboard Content", modifier = Modifier.padding(16.dp))
                BotProfileTab.INFO -> Text("Info Content", modifier = Modifier.padding(16.dp))
                BotProfileTab.COMPUTER -> Text("Computer Content", modifier = Modifier.padding(16.dp))
                BotProfileTab.FILES -> Text("Files Content", modifier = Modifier.padding(16.dp))
                BotProfileTab.MEDIA -> Text("Media Content", modifier = Modifier.padding(16.dp))
                BotProfileTab.APPROVALS -> Text("Approvals Content", modifier = Modifier.padding(16.dp))
            }
        }
    }
}
