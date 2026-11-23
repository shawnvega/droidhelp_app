package com.bighelp.droidhelp.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.home.model.TodayBoardEntry
import com.bighelp.droidhelp.home.model.TodayBoardKind
import com.bighelp.droidhelp.home.model.TodayNeedsYouSummary
import com.bighelp.droidhelp.home.model.TodayUiState

@Composable
fun TodayView(
    state: TodayUiState,
    onBoardClick: (TodayBoardEntry) -> Unit,
    onMakeSpaceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.needsYou.count > 0) {
            item {
                NeedsYouCard(state.needsYou)
            }
        }

        if (state.pendingShares.count > 0) {
            item {
                PendingSharesRow(state.pendingShares.count)
            }
        }

        state.layout.visiblePanels().forEach { panel ->
            item {
                PanelHeader(panel.title)
            }

            val panelEntries = state.boards.filter { it.kind.name == panel.id }
            items(panelEntries) { entry ->
                BoardEntryItem(entry, onBoardClick)
            }
        }

        item {
            MakeSpaceButton(onMakeSpaceClick)
        }
    }
}

@Composable
private fun NeedsYouCard(summary: TodayNeedsYouSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Needs you (${summary.count})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PendingSharesRow(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Shared with bighelp ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PanelHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun BoardEntryItem(
    entry: TodayBoardEntry,
    onBoardClick: (TodayBoardEntry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBoardClick(entry) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (entry.preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MakeSpaceButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Make a space")
    }
}
