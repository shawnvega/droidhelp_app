package com.bighelp.droidhelp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHeaderBar(
    isBotMode: Boolean,
    onToggleMode: () -> Unit,
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    computerName: String,
    onComputerSelect: (String) -> Unit,
    onRootControlClick: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Avatar
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.LightGray)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 2. Bots/Chats toggle switch
        Switch(
            checked = isBotMode,
            onCheckedChange = { onToggleMode() }
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 3. Title text
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.widthIn(max = 80.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 4. Search input field
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search...", style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 5. Computer menu
        IconButton(onClick = { onComputerSelect(computerName) }) {
            Icon(Icons.Default.Settings, contentDescription = "Computer")
        }

        // 6. Root control
        IconButton(onClick = onRootControlClick) {
            Icon(Icons.Default.Home, contentDescription = "Root")
        }

        // 7. + (add) button
        IconButton(onClick = onAddClick) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewHomeHeaderBar() {
    MaterialTheme {
        HomeHeaderBar(
            isBotMode = false,
            onToggleMode = {},
            title = "Chats",
            searchQuery = "",
            onSearchQueryChange = {},
            computerName = "My Computer",
            onComputerSelect = {},
            onRootControlClick = {},
            onAddClick = {}
        )
    }
}
