package com.bighelp.droidhelp.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatComposer(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    draftAttachments: List<ChatAttachment>,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    onPickPhoto: () -> Unit,
    onCapturePhoto: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (draftAttachments.isNotEmpty()) {
            ChatAttachmentRail(
                attachments = draftAttachments,
                onRemove = onRemoveAttachment
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPickPhoto) {
                Icon(Icons.Default.Add, contentDescription = "Pick Photo")
            }
            IconButton(onClick = onCapturePhoto) {
                Icon(Icons.Default.Add, contentDescription = "Capture Photo")
            }
            IconButton(onClick = onPickFile) {
                Icon(Icons.Default.Add, contentDescription = "Pick File")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = (inputText.isNotBlank() || draftAttachments.isNotEmpty()) && !isSending
            ) {
                Text("Send")
            }
        }
    }
}
