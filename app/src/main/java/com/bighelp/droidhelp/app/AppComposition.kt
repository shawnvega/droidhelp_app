package com.bighelp.droidhelp.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.bighelp.droidhelp.chat.ChatScreen
import com.bighelp.droidhelp.chat.ChatViewModel
import com.bighelp.droidhelp.designsystem.ColorMode
import com.bighelp.droidhelp.home.model.*
import com.bighelp.droidhelp.settings.AccountSettings
import com.bighelp.droidhelp.settings.BotSettingsRepository
import com.bighelp.droidhelp.settings.StorageSettingsRepository
import com.bighelp.droidhelp.settings.SettingsScreen
import com.bighelp.droidhelp.settings.SettingsSection
import com.bighelp.droidhelp.settings.SettingsViewModel
import com.bighelp.droidhelp.platform.AndroidSecureStorage
import com.bighelp.droidhelp.platform.OkHttpClientImpl
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.*

private const val REASONING_DELAY_LONG = 1000L
private const val REASONING_DELAY_SHORT = 500L

@Composable
fun AppComposition(appState: AppState) {
    val context = LocalContext.current
    val secureStorage = remember { AndroidSecureStorage(context) }
    val botRepository = remember { BotSettingsRepository(secureStorage) }
    val accountSettings = remember { AccountSettings(secureStorage) }
    val storageRepository = remember { StorageSettingsRepository(context) }
    val settingsViewModel = remember { SettingsViewModel(botRepository, accountSettings, storageRepository) }

    val httpClient = remember { OkHttpClientImpl(OkHttpClient()) }
    val chatViewModel = remember { ChatViewModel(httpClient, onSendMessage = { _, _ -> }) }

    if (appState.home.isSettingsPresented) {
        SettingsScreen(
            viewModel = settingsViewModel,
            onExit = { appState.home.isSettingsPresented = false }
        )
    } else {
        LaunchedEffect(chatViewModel) {
            // Simulate the sequence of events directly on the VM
            chatViewModel.startReasoning("Thinking...")
            delay(REASONING_DELAY_LONG)
            chatViewModel.updateReasoning("This is a thought process.")
            chatViewModel.finishReasoning()
            
            delay(REASONING_DELAY_SHORT)
            chatViewModel.appendAssistantMessage("Hello! I have listed the files for you.")
        }
        ChatScreen(viewModel = chatViewModel)
    }
}
