package com.bighelp.droidhelp.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

class ChatViewModel(
    private val onSendMessage: suspend (String) -> Unit = {}
) : ViewModel() {

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val chatPerformance = ChatPerformance()

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isBlank() || _isSending.value) return

        _inputText.value = ""
        _isSending.value = true

        val newItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            role = TimelineRole.HUMAN,
            sender = TimelineSender.user("user"),
            content = TimelineContent.Message(text),
            metadata = TimelineMetadata(timestamp = Date())
        )

        chatPerformance.append(newItem)
        _items.value = chatPerformance.getHistory()

        viewModelScope.launch {
            try {
                onSendMessage(text)
            } finally {
                _isSending.value = false
            }
        }
    }
}
