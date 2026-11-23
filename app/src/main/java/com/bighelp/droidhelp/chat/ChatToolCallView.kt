package com.bighelp.droidhelp.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val PREVIEW_MAX_LENGTH = 100
private val PRETTY_JSON = Json { prettyPrint = true }

private fun formatPrettyJson(raw: String): String = try {
    val jsonElement = PRETTY_JSON.parseToJsonElement(raw)
    PRETTY_JSON.encodeToString(JsonElement.serializer(), jsonElement)
} catch (_: SerializationException) {
    raw
} catch (_: IllegalArgumentException) {
    raw
}

@Composable
fun ChatToolCallView(
    content: TimelineContent.ToolCall,
    modifier: Modifier = Modifier
) {
    var argsExpanded by rememberSaveable { mutableStateOf(false) }
    var resultExpanded by rememberSaveable { mutableStateOf(false) }

    val prettyArgs = remember(content.arguments) {
        formatPrettyJson(content.arguments)
    }

    val prettyResult = remember(content.result) {
        content.result?.let(::formatPrettyJson)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header: Name and Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = content.name,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusIndicator(status = content.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Arguments Section
            ToolCallPayloadSection(
                title = "Arguments",
                payload = prettyArgs,
                expanded = argsExpanded,
                onToggle = { argsExpanded = !argsExpanded },
                expandButtonText = "Show Full Arguments",
                collapseButtonText = "Hide Arguments",
            )

            // Result Section
            prettyResult?.let { formattedResult ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ToolCallPayloadSection(
                    title = "Result",
                    payload = formattedResult,
                    expanded = resultExpanded,
                    onToggle = { resultExpanded = !resultExpanded },
                    expandButtonText = "Show Full Result",
                    collapseButtonText = "Hide Result",
                )
            }
        }
    }
}

@Composable
private fun ToolCallPayloadSection(
    title: String,
    payload: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandButtonText: String,
    collapseButtonText: String,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "Collapse" else "Expand",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (expanded) {
            Text(
                text = payload,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            val preview = if (payload.length > PREVIEW_MAX_LENGTH) {
                "${payload.take(PREVIEW_MAX_LENGTH)}..."
            } else {
                payload
            }
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Button(
            onClick = onToggle,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.padding(top = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(
                text = if (expanded) collapseButtonText else expandButtonText,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun StatusIndicator(status: TimelineContent.ToolCallStatus) {
    val color = when (status) {
        TimelineContent.ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.secondary
        TimelineContent.ToolCallStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        TimelineContent.ToolCallStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = status.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(modifier = modifier)
}
