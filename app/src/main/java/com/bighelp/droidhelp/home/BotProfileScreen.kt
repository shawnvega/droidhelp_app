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
    val selectedTab: BotProfileTab = BotProfileTab.DASHBOARD,
    val role: String = "",
    val status: String = "Idle",
    val hostName: String = "localhost",
    val isConnected: Boolean = true,
    val files: List<String> = emptyList(),
    val mediaItems: List<String> = emptyList(),
    val approvals: List<BotApprovalItem> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotProfileScreen(
    state: BotProfileState,
    onSelectTab: (BotProfileTab) -> Unit,
    onBack: () -> Unit,
    onFileClick: (String) -> Unit = {},
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
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
                BotProfileTab.DASHBOARD -> BotDashboardTab(
                    info = BotOverviewInfo(
                        botId = state.botId,
                        botName = state.botName,
                        role = state.role,
                        status = state.status
                    ),
                    modifier = Modifier.padding(16.dp)
                )
                BotProfileTab.INFO -> BotInfoTab(
                    info = BotOverviewInfo(
                        botId = state.botId,
                        botName = state.botName,
                        role = state.role,
                        status = state.status
                    ),
                    modifier = Modifier.padding(16.dp)
                )
                BotProfileTab.COMPUTER -> BotComputerTab(
                    hostName = state.hostName,
                    isConnected = state.isConnected,
                    modifier = Modifier.padding(16.dp)
                )
                BotProfileTab.FILES -> BotFilesTab(
                    files = state.files,
                    onFileClick = onFileClick,
                    modifier = Modifier.padding(16.dp)
                )
                BotProfileTab.MEDIA -> BotMediaTab(
                    mediaItems = state.mediaItems,
                    modifier = Modifier.padding(16.dp)
                )
                BotProfileTab.APPROVALS -> BotApprovalsTab(
                    approvals = state.approvals,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
