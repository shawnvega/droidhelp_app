package com.bighelp.droidhelp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.home.model.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsInboxView(
    rows: List<ChatsInboxRow>,
    onChatClick: (ChatsInboxRow) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChatsInboxFilter.ALL) }

    val filteredRows = remember(rows, searchQuery, filter) {
        val query = searchQuery.trim()
        val result = rows.filter { row ->
            val matchesFilter = when (filter) {
                ChatsInboxFilter.ALL -> true
                ChatsInboxFilter.COMPUTER -> false // In a real app, we'd pass the host ID
                ChatsInboxFilter.UNREAD -> row.isUnread
            }
            val matchesQuery = query.isEmpty() || 
                row.title.contains(query, ignoreCase = true) || 
                row.preview.contains(query, ignoreCase = true)
            
            matchesFilter && matchesQuery
        }
        result
    }

    // Grouping into sections
    val sections = remember(filteredRows) {
        val now = Calendar.getInstance().time
        val calendar = Calendar.getInstance()
        val startOfToday = calendar.apply { 
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0) 
        }.time
        val startOfYesterday = calendar.apply { 
            add(Calendar.DAY_OF_MONTH, -1) 
        }.time

        val pinned = filteredRows.filter { it.isPinned }
        val today = filteredRows.filter { !it.isPinned && it.updatedAt >= startOfToday }
        val yesterday = filteredRows.filter { !it.isPinned && it.updatedAt < startOfToday && it.updatedAt >= startOfYesterday }
        val earlier = filteredRows.filter { !it.isPinned && it.updatedAt < startOfYesterday }

        buildList {
            if (pinned.isNotEmpty()) add(ChatsInboxSection(ChatsInboxSection.Kind.PINNED, pinned))
            if (today.isNotEmpty()) add(ChatsInboxSection(ChatsInboxSection.Kind.TODAY, today))
            if (yesterday.isNotEmpty()) add(ChatsInboxSection(ChatsInboxSection.Kind.YESTERDAY, yesterday))
            if (earlier.isNotEmpty()) add(ChatsInboxSection(ChatsInboxSection.Kind.EARLIER, earlier))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // Search Bar
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            colors = TextFieldDefaults.colors(),
            singleLine = true
        )

        // Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == ChatsInboxFilter.ALL,
                onClick = { filter = ChatsInboxFilter.ALL },
                label = { Text("All") }
            )
            FilterChip(
                selected = filter == ChatsInboxFilter.UNREAD,
                onClick = { filter = ChatsInboxFilter.UNREAD },
                label = { Text("Unread") }
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            sections.forEach { section ->
                item {
                    Text(
                        text = section.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray)
                    )
                }
                items(section.rows) { row ->
                    ChatRow(row, onChatClick)
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

@Composable
fun ChatRow(row: ChatsInboxRow, onClick: (ChatsInboxRow) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(row) }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar Placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.LightGray)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (row.isUnread) FontWeight.Bold else FontWeight.Medium
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.isPinned) {
                    Icon(Icons.Default.Star, contentDescription = "Pinned", modifier = Modifier.size(16.dp), tint = Color.Gray)
                }
                Text(
                    text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(row.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 8.dp)
                )
                if (row.isUnread) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Blue))
                }
            }
            Text(
                text = row.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
