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
            headlineContent = { Text("Endpoint URL") },
            supportingContent = { Text(endpointUrl) }
        )
        Button(onClick = onSignOut) {
            Text("Sign out")
        }
        Button(onClick = onBack) {
            Text("Back")
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
            Text("No bots available")
        } else {
            botNames.forEach { name ->
                ListItem(headlineContent = { Text(name) })
            }
        }
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}
