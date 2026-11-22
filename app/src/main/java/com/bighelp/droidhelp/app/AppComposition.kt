package com.bighelp.droidhelp.app

import androidx.compose.runtime.*
import com.bighelp.droidhelp.chat.*
import com.bighelp.droidhelp.home.model.HomeSelection
import kotlinx.coroutines.delay
import com.bighelp.droidhelp.platform.HttpClient
import com.bighelp.droidhelp.platform.OkHttpClientImpl
import okhttp3.OkHttpClient

@Composable
fun AppComposition(appState: AppState) {
    val httpClient = remember { OkHttpClientImpl(OkHttpClient()) }
    val chatViewModel = remember { ChatViewModel(httpClient, onSendMessage = { _, _ -> }) }

    LaunchedEffect(chatViewModel) {
        // Simulate the sequence of events directly on the VM
        chatViewModel.startReasoning("Thinking...")
        delay(1000)
        chatViewModel.updateReasoning("This is a thought process.")
        chatViewModel.finishReasoning()
        
        delay(500)
        chatViewModel.appendAssistantMessage("Hello! I have listed the files for you.")
    }

    ChatScreen(viewModel = chatViewModel)
}
