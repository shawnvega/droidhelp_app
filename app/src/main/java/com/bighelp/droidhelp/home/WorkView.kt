package com.bighelp.droidhelp.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.home.model.*
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun WorkView(
    viewModel: WorkViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Filter logic
    val filteredItems = remember(items, filter) {
        items.filter { filter.includes(it.bucket) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorkFilter.entries.forEach { chipFilter ->
                FilterChip(
                    selected = filter == chipFilter,
                    onClick = { viewModel.setFilter(chipFilter) },
                    label = { Text(chipFilter.title) }
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredItems) { item ->
                    WorkItemCard(item = item)
                }
            }
        }
    }

    // Trigger initial load
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
}

@Composable
fun WorkItemCard(item: WorkItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                item.pill?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Extension for WorkFilter to match iOS logic
fun WorkFilter.includes(bucket: WorkBucket): Boolean {
    return when (this) {
        WorkFilter.ALL -> true
        WorkFilter.NEEDS_YOU -> bucket == WorkBucket.NEEDS_YOU
        WorkFilter.WORKING -> bucket == WorkBucket.WORKING
        WorkFilter.DONE -> bucket == WorkBucket.DONE
    }
}
