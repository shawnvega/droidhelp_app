package com.bighelp.droidhelp.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Date

private const val MS_PER_SECOND = 1000L

@Composable
fun ChatStreamView(
    items: List<TimelineItem>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when items change or last item content changes
    LaunchedEffect(items) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(items.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(items, key = { it.id }) { item ->
            ChatRow(item = item)
        }
    }
}

@Composable
private fun ChatRow(item: TimelineItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (item.role == TimelineRole.HUMAN) Alignment.End else Alignment.Start
    ) {
        Text(
            text = item.sender.snapshot.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        when (val content = item.content) {
            is TimelineContent.Message -> {
                MessageBubble(text = content.text, isHuman = item.role == TimelineRole.HUMAN)
            }
            is TimelineContent.Reasoning -> {
                ReasoningBlock(
                    text = content.text,
                    timestamp = item.metadata.timestamp,
                    isStreaming = item.metadata.isStreaming
                )
            }
            is TimelineContent.ToolCall -> {
                ToolCallBlock(content = content)
            }
        }
    }
}

@Composable
private fun MessageBubble(text: String, isHuman: Boolean) {
    val bubbleColor = if (isHuman) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isHuman) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        color = bubbleColor,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = textColor
        )
    }
}

@Composable
private fun ReasoningBlock(
    text: String,
    timestamp: Date?,
    isStreaming: Boolean
) {
    val durationText = remember(timestamp, isStreaming) {
        if (isStreaming) {
            "Thinking..."
        } else {
            val now = Date()
            val diff = now.time - (timestamp?.time ?: now.time)
            val seconds = diff / MS_PER_SECOND
            "Thought for $seconds seconds"
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolCallBlock(content: TimelineContent.ToolCall) {
    ChatToolCallView(content = content)
}
