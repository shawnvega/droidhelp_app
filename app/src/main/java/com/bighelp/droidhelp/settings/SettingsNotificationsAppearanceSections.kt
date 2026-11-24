package com.bighelp.droidhelp.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bighelp.droidhelp.designsystem.ColorMode

@Composable
fun NotificationsSettingsSection(
    pushEnabled: Boolean,
    soundEnabled: Boolean,
    onTogglePush: (Boolean) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text("Push Notifications") },
            trailingContent = {
                Switch(checked = pushEnabled, onCheckedChange = onTogglePush)
            }
        )
        ListItem(
            headlineContent = { Text("Sound") },
            trailingContent = {
                Switch(checked = soundEnabled, onCheckedChange = onToggleSound)
            }
        )
    }
}

@Composable
fun AppearanceSettingsSection(
    onSelectMode: (ColorMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ColorMode.entries.forEach { mode ->
            ListItem(
                headlineContent = { Text(mode.name) },
                modifier = Modifier.clickable { onSelectMode(mode) }
            )
        }
    }
}
