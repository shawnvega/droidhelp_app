package com.bighelp.droidhelp.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class NewChatRecipient(val id: String, val name: String, val isBot: Boolean)

data class NewChatUiState(
    val query: String = "",
    val selectedIds: Set<String> = emptySet(),
    val recipients: List<NewChatRecipient> = emptyList(),
    val showCodingOptions: Boolean = false
)

fun toggleRecipientSelection(state: NewChatUiState, recipientId: String): NewChatUiState {
    val newSelectedIds = if (state.selectedIds.contains(recipientId)) {
        state.selectedIds - recipientId
    } else {
        state.selectedIds + recipientId
    }

    val botCount = state.recipients.count { it.id in newSelectedIds && it.isBot }
    return state.copy(
        selectedIds = newSelectedIds,
        showCodingOptions = botCount == 1
    )
}

fun canStartNewChat(state: NewChatUiState): Boolean = state.selectedIds.isNotEmpty()

fun filterRecipients(recipients: List<NewChatRecipient>, query: String): List<NewChatRecipient> {
    if (query.isEmpty()) return recipients
    return recipients.filter { it.name.contains(query, ignoreCase = true) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    state: NewChatUiState,
    onQueryChange: (String) -> Unit,
    onToggleRecipient: (String) -> Unit,
    onStartChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // To: Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text("To:", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.width(8.dp))

            val selectedRecipients = state.recipients.filter { it.id in state.selectedIds }
            selectedRecipients.forEach { recipient ->
                InputChip(
                    selected = true,
                    onClick = { onToggleRecipient(recipient.id) },
                    label = { Text(recipient.name) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            TextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Add a bot") },
                modifier = Modifier.widthIn(min = 120.dp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent
                )
            )
        }

        // Coding options row
        if (state.showCodingOptions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Coding Options", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        }

        // Recipient list
        val filteredRecipients = filterRecipients(state.recipients, state.query)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredRecipients) { recipient ->
                ListItem(
                    headlineContent = { Text(recipient.name) },
                    leadingContent = {
                        if (recipient.isBot) {
                            Text("[Bot]", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.clickable { onToggleRecipient(recipient.id) }
                )
            }
        }

        // Start Chat Button
        Button(
            onClick = onStartChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Start Chat")
        }
    }
}
