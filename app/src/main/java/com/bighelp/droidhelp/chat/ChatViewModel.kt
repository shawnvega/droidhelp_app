package com.bighelp.droidhelp.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bighelp.droidhelp.platform.HttpClient
import com.bighelp.droidhelp.platform.UploadPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Date
import java.util.UUID

class ChatViewModel(
    private val httpClient: HttpClient,
    private val onSendMessage: suspend (String, ChatViewModel.() -> Unit) -> Unit = { _, _ -> }
) : ViewModel() {

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _draftAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val draftAttachments: StateFlow<List<ChatAttachment>> = _draftAttachments.asStateFlow()

    private val chatPerformance = ChatPerformance()

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun appendItem(item: TimelineItem) {
        chatPerformance.append(item)
        _items.value = chatPerformance.getHistory()
    }

    fun updateLastItem(updater: (TimelineItem) -> TimelineItem) {
        val currentHistory = chatPerformance.getHistory().toMutableList()
        if (currentHistory.isNotEmpty()) {
            val lastIndex = currentHistory.size - 1
            val updatedItem = updater(currentHistory[lastIndex])
            chatPerformance.update(updatedItem.id, updatedItem)
            _items.value = chatPerformance.getHistory()
        }
    }

    fun addAttachment(attachment: ChatAttachment) {
        _draftAttachments.value = _draftAttachments.value + attachment
        startUpload(attachment)
    }

    fun removeAttachment(attachment: ChatAttachment) {
        _draftAttachments.value = _draftAttachments.value.filter { it.id != attachment.id }
    }

    fun clearAttachments() {
        _draftAttachments.value = emptyList()
    }

    private fun startUpload(attachment: ChatAttachment) {
        fun setStatus(status: AttachmentStatus) {
            _draftAttachments.value = _draftAttachments.value.map {
                if (it.id == attachment.id) it.copy(status = status) else it
            }
        }
        viewModelScope.launch {
            try {
                setStatus(AttachmentStatus.Uploading(0f))

                val payload = UploadPayload(
                    url = "/api/upload",
                    name = "file",
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    data = attachment.data,
                )
                val response = httpClient.upload(payload) { progress ->
                    setStatus(AttachmentStatus.Uploading(progress))
                }

                if (response.isSuccessful) {
                    setStatus(AttachmentStatus.Sent)
                } else {
                    setStatus(AttachmentStatus.Failed("Upload failed: ${response.code}"))
                }
            } catch (e: IOException) {
                setStatus(AttachmentStatus.Failed(e.message ?: "Unknown error"))
            } catch (e: IllegalStateException) {
                setStatus(AttachmentStatus.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    fun retryAttachment(attachment: ChatAttachment) {
        startUpload(attachment)
    }

    // Assistant actions
    fun appendAssistantMessage(text: String) {
        val assistantItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            role = TimelineRole.ASSISTANT,
            sender = TimelineSender.agent("assistant", "Assistant"),
            content = TimelineContent.Message(text),
            metadata = TimelineMetadata(timestamp = Date())
        )
        appendItem(assistantItem)
    }

    fun startReasoning(text: String) {
        val reasoningItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            role = TimelineRole.ASSISTANT,
            sender = TimelineSender.agent("assistant", "Assistant"),
            content = TimelineContent.Reasoning(text),
            metadata = TimelineMetadata(timestamp = Date(), isStreaming = true)
        )
        appendItem(reasoningItem)
    }

    fun updateReasoning(text: String) {
        updateLastItem { item ->
            if (item.content is TimelineContent.Reasoning) {
                item.copy(
                    content = TimelineContent.Reasoning(text),
                    metadata = item.metadata.copy(isStreaming = true)
                )
            } else {
                item
            }
        }
    }

    fun finishReasoning() {
        updateLastItem { item ->
            if (item.content is TimelineContent.Reasoning) {
                item.copy(
                    metadata = item.metadata.copy(isStreaming = false)
                )
            } else {
                item
            }
        }
    }

    fun updateMessage(text: String) {
        updateLastItem { item ->
            if (item.content is TimelineContent.Message) {
                item.copy(content = TimelineContent.Message(text))
            } else {
                item
            }
        }
    }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isBlank() && _draftAttachments.value.isEmpty()) return
        if (_isSending.value) return

        _inputText.value = ""
        _isSending.value = true

        val humanItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            role = TimelineRole.HUMAN,
            sender = TimelineSender.user("user"),
            content = TimelineContent.Message(text),
            metadata = TimelineMetadata(timestamp = Date()),
            attachments = _draftAttachments.value
        )

        appendItem(humanItem)
        _draftAttachments.value = emptyList()

        viewModelScope.launch {
            try {
                onSendMessage(text) {
                    // This block is for assistant updates
                }
            } finally {
                _isSending.value = false
            }
        }
    }
}
