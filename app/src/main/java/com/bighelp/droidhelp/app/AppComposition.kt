package com.bighelp.droidhelp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.bighelp.droidhelp.chat.ChatScreen
import com.bighelp.droidhelp.chat.ChatViewModel
import com.bighelp.droidhelp.platform.OkHttpClientImpl
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val REASONING_DELAY_MS = 1000L
private const val MESSAGE_DELAY_MS = 500L

@Composable
fun AppComposition(appState: AppState) {
    val httpClient = remember { OkHttpClientImpl(OkHttpClient()) }
    val chatViewModel = remember { ChatViewModel(httpClient, onSendMessage = { _, _ -> }) }

    LaunchedEffect(chatViewModel) {
        // Simulate the sequence of events directly on the VM
        chatViewModel.startReasoning("Thinking...")
        delay(REASONING_DELAY_MS)
        chatViewModel.updateReasoning("This is a thought process.")
        chatViewModel.finishReasoning()
        
        delay(MESSAGE_DELAY_MS)
        chatViewModel.appendAssistantMessage("Hello! I have listed the files for you.")
    }

    ChatScreen(viewModel = chatViewModel)
}
