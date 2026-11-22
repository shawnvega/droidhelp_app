package com.bighelp.droidhelp.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.designsystem.Theme
import kotlinx.serialization.json.Json
import java.util.*

@Composable
fun ChatToolCallView(
    content: TimelineContent.ToolCall,
    modifier: Modifier = Modifier
) {
    var argsExpanded by rememberSaveable { mutableStateOf(false) }
    var resultExpanded by rememberSaveable { mutableStateOf(false) }

    val prettyArgs = remember(content.arguments) {
        try {
            val jsonElement = Json.parseToJsonElement(content.arguments)
            Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
        } catch (e: Exception) {
            content.arguments
        }
    }

    val prettyResult = remember(content.result) {
        content.result?.let {
            try {
                val jsonElement = Json.parseToJsonElement(it)
                Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
            } catch (e: Exception) {
                it
            }
        }
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
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Arguments",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (argsExpanded) "Collapse" else "Expand",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                if (argsExpanded) {
                    Text(
                        text = prettyArgs,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        text = if (prettyArgs.length > 100) "${prettyArgs.take(100)}..." else prettyArgs,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Button(
                    onClick = { argsExpanded = !argsExpanded },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                ) {
                    Text(
                        text = if (argsExpanded) "Hide Arguments" else "Show Full Arguments",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Result Section
            content.result?.let { result ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (resultExpanded) "Collapse" else "Expand",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (resultExpanded) {
                        Text(
                            text = prettyResult ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            text = if ((prettyResult?.length ?: 0) > 100) "${prettyResult?.take(100)}..." else (prettyResult ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Button(
                        onClick = { resultExpanded = !resultExpanded },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    ) {
                        Text(
                            text = if (resultExpanded) "Hide Result" else "Show Full Result",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
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
