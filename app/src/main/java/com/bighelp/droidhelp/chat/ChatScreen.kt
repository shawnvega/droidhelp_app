package com.bighelp.droidhelp.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bighelp.droidhelp.chat.rememberChatAttachmentActions

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val items by viewModel.items.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val draftAttachments by viewModel.draftAttachments.collectAsState()

    val attachmentActions = rememberChatAttachmentActions { attachment ->
        viewModel.addAttachment(attachment)
    }

    ChatScreenContent(
        items = items,
        inputText = inputText,
        onInputChange = viewModel::onInputChange,
        onSend = { viewModel.send() },
        isSending = isSending,
        draftAttachments = draftAttachments,
        onRemoveAttachment = viewModel::removeAttachment,
        onPickPhoto = { attachmentActions.pickPhoto() },
        onCapturePhoto = { attachmentActions.capturePhoto() },
        onPickFile = { attachmentActions.pickFile() }
    )
}

@Composable
private fun ChatScreenContent(
    items: List<TimelineItem>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    draftAttachments: List<ChatAttachment>,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    onPickPhoto: () -> Unit,
    onCapturePhoto: () -> Unit,
    onPickFile: () -> Unit
) {
    Scaffold(
        bottomBar = {
            ChatComposer(
                inputText = inputText,
                onInputChange = onInputChange,
                onSend = onSend,
                isSending = isSending,
                draftAttachments = draftAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onPickPhoto = onPickPhoto,
                onCapturePhoto = onCapturePhoto,
                onPickFile = onPickFile
            )
        }
    ) { paddingValues ->
        ChatStreamView(
            items = items,
            modifier = Modifier.padding(paddingValues)
        )
    }
}
