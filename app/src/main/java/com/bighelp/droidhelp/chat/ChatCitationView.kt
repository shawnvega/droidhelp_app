package com.bighelp.droidhelp.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatCitationChips(
    citations: List<ChatCitation>,
    modifier: Modifier = Modifier
) {
    var selectedCitation by remember { mutableStateOf<ChatCitation?>(null) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        citations.forEach { citation ->
            val label = "[${citation.index}] ${citation.title ?: citation.url}"
            AssistChip(
                onClick = { selectedCitation = citation },
                label = { Text(label) }
            )
        }
    }

    if (selectedCitation != null) {
        val citation = selectedCitation!!
        AlertDialog(
            onDismissRequest = { selectedCitation = null },
            confirmButton = {
                TextButton(onClick = { selectedCitation = null }) {
                    Text("Close")
                }
            },
            title = {
                Text(
                    text = citation.title ?: citation.url,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = citation.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = citation.snippet ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }
}
