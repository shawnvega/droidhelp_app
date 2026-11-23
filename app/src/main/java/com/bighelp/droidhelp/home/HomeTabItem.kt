package com.bighelp.droidhelp.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.bighelp.droidhelp.home.model.HomeTab

/**
 * Represents a single tab in the home screen.
 */
internal data class HomeTabItem(
    val tab: HomeTab,
    val label: String,
    val icon: ImageVector
)

/**
 * Provides the mapping of [HomeTab] to its display properties.
 */
internal fun getHomeTabItems(conversationsLabel: String): List<HomeTabItem> {
    return listOf(
        HomeTabItem(HomeTab.CONVERSATIONS, conversationsLabel, Icons.Filled.Chat),
        HomeTabItem(HomeTab.TODAY, "Today", Icons.Filled.WbSunny),
        HomeTabItem(HomeTab.WORK, "Work", Icons.Filled.Build)
    )
}
