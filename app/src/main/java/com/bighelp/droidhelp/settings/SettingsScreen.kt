package com.bighelp.droidhelp.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSectionClick: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            items(SettingsSection.entries) { section ->
                ListItem(
                    headlineContent = { Text(section.title) },
                    modifier = Modifier.clickable { onSectionClick(section) }
                )
            }
        }
    }
}

enum class SettingsSection(val title: String) {
    ACCOUNT("Account"),
    BOTS("Bots"),
    NOTIFICATIONS("Notifications"),
    APPEARANCE("Appearance"),
    PRIVACY("Privacy"),
    STORAGE("Storage"),
    ABOUT("About")
}

@Composable
fun SettingsSectionPlaceholder(
    section: SettingsSection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = section.title)
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}
