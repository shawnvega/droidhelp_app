package com.bighelp.droidhelp.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AccountSettingsSection(
    endpointUrl: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = endpointUrl) }
        )
        Button(onClick = onSignOut) {
            Text(text = "Sign out")
        }
        Button(onClick = onBack) {
            Text(text = "Back")
        }
    }
}

@Composable
fun BotsSettingsSection(
    botNames: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (botNames.isEmpty()) {
            Text(text = "No bots available")
        } else {
            botNames.forEach { name ->
                ListItem(headlineContent = { Text(text = name) })
            }
        }
        Button(onClick = onBack) {
            Text(text = "Back")
        }
    }
}
