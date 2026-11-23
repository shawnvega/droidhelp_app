package com.bighelp.droidhelp.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val Padding16 = 16.dp
private val Padding8 = 8.dp
private val SpacerWidth8 = 8.dp

data class BotApprovalItem(
    val id: String,
    val title: String,
    val detail: String = ""
)

@Composable
fun BotMediaTab(
    mediaItems: List<String>,
    modifier: Modifier = Modifier
) {
    if (mediaItems.isEmpty()) {
        Text(
            text = "No media available",
            modifier = modifier.padding(Padding16),
            style = MaterialTheme.typography.bodyLarge
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(mediaItems) { item ->
                Text(
                    text = item,
                    modifier = Modifier.padding(Padding16),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun BotApprovalsTab(
    approvals: List<BotApprovalItem>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (approvals.isEmpty()) {
        Text(
            text = "No pending approvals",
            modifier = modifier.padding(Padding16),
            style = MaterialTheme.typography.bodyLarge
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(approvals) { approval ->
                Column(
                    modifier = Modifier.padding(Padding16)
                ) {
                    Text(
                        text = approval.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (approval.detail.isNotEmpty()) {
                        Text(
                            text = approval.detail,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = Padding8),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { onDeny(approval.id) }) {
                            Text("Deny")
                        }
                        Spacer(modifier = Modifier.width(SpacerWidth8))
                        Button(onClick = { onApprove(approval.id) }) {
                            Text("Approve")
                        }
                    }
                }
            }
        }
    }
}
