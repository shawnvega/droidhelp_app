package com.bighelp.droidhelp.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bighelp.droidhelp.home.model.HomeTab

/**
 * A bottom tab bar with three tabs: Conversations, Today, and Work.
 * Includes an optional badge on the Today tab and a new chat button.
 */
@Composable
fun HomeTabBar(
    selectedTab: HomeTab,
    conversationsLabel: String,
    todayHasNew: Boolean,
    onSelectTab: (HomeTab) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = getHomeTabItems(conversationsLabel)

    androidx.compose.foundation.layout.Row(modifier = modifier) {
        NavigationBar(modifier = Modifier.weight(1f)) {
            items.forEach { item ->
                NavigationBarItem(
                    selected = selectedTab == item.tab,
                    onClick = { onSelectTab(item.tab) },
                    icon = {
                        if (item.tab == HomeTab.TODAY && todayHasNew) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(item.icon, contentDescription = item.label)
                                Badge(modifier = Modifier.align(Alignment.TopEnd))
                            }
                        } else {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    },
                    label = { Text(item.label) }
                )
            }
        }
        IconButton(onClick = onNewChat) {
            Icon(Icons.Filled.Add, contentDescription = "New Chat")
        }
    }
}
