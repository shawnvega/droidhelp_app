package com.bighelp.droidhelp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

data class BotsItem(
    val id: String,
    val title: String,
    val role: String?,
    val preview: String?,
    val isPinned: Boolean,
    val isUnread: Boolean,
    val updatedAt: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsView(
    items: List<BotsItem>,
    onItemClick: (BotsItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(items, searchQuery) {
        val query = searchQuery.trim()
        items.filter { item ->
            query.isEmpty() || 
                item.title.contains(query, ignoreCase = true) || 
                item.preview?.contains(query, ignoreCase = true) == true
        }
    }

    // Sort: pinned first, then by updatedAt (desc), then by title
    val sortedItems = remember(filteredItems) {
        filtered        .sortedWith(
            compareByDescending<BotsItem> { it.isPinned }
                .thenByDescending { it.updatedAt }
                .thenBy { it.title }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
        // Search Bar
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("search_field"),
            placeholder = { Text("Search bots") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            colors = TextFieldDefaults.colors(),
            singleLine = true
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("bots_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sortedItems) { item ->
                BotsItemRow(item = item, onItemClick = onItemClick)
            }
        }
    }
}

@Composable
fun BotsItemRow(
    item: BotsItem,
    onItemClick: (BotsItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick(item) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Placeholder face
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.role != null) {
                Text(
                    text = item.role,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.preview != null) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.isPinned) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (item.isUnread) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}
