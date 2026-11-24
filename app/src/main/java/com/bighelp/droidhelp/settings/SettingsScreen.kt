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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.bighelp.droidhelp.designsystem.ColorMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSection = viewModel.currentSection
    val onBack = { viewModel.goBack() }

    // Load settings when the screen enters the composition
    LaunchedEffect(Unit) {
        viewModel.loadSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentSection?.title ?: "Settings") },
                navigationIcon = if (currentSection != null) {
                    {
                        Button(onClick = onBack) {
                            Text("Back")
                        }
                    }
                } else {
                    {}
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (currentSection != null) {
            Column(modifier = Modifier.padding(innerPadding)) {
                when (currentSection) {
                    SettingsSection.ACCOUNT -> AccountSettingsSection(
                        endpointUrl = viewModel.endpointUrl,
                        onSignOut = { /* TODO */ },
                        onBack = onBack
                    )
                    SettingsSection.BOTS -> BotsSettingsSection(
                        botNames = viewModel.botSettings.bots.map { it.name },
                        onBack = onBack
                    )
                    SettingsSection.NOTIFICATIONS -> NotificationsSettingsSection(
                        pushEnabled = viewModel.pushEnabled,
                        soundEnabled = viewModel.soundEnabled,
                        onTogglePush = viewModel::togglePush,
                        onToggleSound = viewModel::toggleSound
                    )
                    SettingsSection.APPEARANCE -> AppearanceSettingsSection(
                        selectedMode = viewModel.selectedMode,
                        onSelectMode = viewModel::selectMode
                    )
                    SettingsSection.PRIVACY -> PrivacySettingsSection()
                    SettingsSection.STORAGE -> StorageSettingsSection(
                        cacheSize = viewModel.cacheSize,
                        dataUsage = viewModel.dataUsage,
                        onClearCache = viewModel::clearCache,
                        onClearData = viewModel::clearData
                    )
                    SettingsSection.ABOUT -> AboutScreen(onBack = onBack)
                }
            }
        } else {
            Column(modifier = Modifier.padding(innerPadding)) {
                Button(onClick = onExit) {
                    Text("Done")
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    items(SettingsSection.entries) { section ->
                        ListItem(
                            headlineContent = { Text(section.title) },
                            modifier = Modifier.clickable { viewModel.selectSection(section) }
                        )
                    }
                }
            }
        }
    }
}
