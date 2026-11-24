package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bighelp.droidhelp.R

@Composable
fun StorageSettingsSection(
    cacheSize: Long,
    dataUsage: Long,
    onClearCache: () -> Unit,
    onClearData: () -> Unit
) {
    // We'll use an AlertDialog for confirmation. 
    // In Compose, we can manage its visibility with a state.
    var showConfirmDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Cache Size Item
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_cache_size), fontSize = 16.sp) },
            trailingContent = {
                Button(onClick = onClearCache) {
                    Text(stringResource(R.string.storage_clear_cache))
                }
            }
        )
        Text(
            text = formatSize(cacheSize),
            modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        // Data Usage Item
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_data_usage), fontSize = 16.sp) }
        )
        Text(
            text = formatSize(dataUsage),
            modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        // Clear Data Item
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_clear_data), fontSize = 16.sp) },
            modifier = Modifier.clickable { showConfirmDialog = true }
        )

        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(R.string.storage_confirm_clear_data_title)) },
                text = { Text(stringResource(R.string.storage_confirm_clear_data_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showConfirmDialog = false
                        onClearData()
                    }) {
                        Text(stringResource(R.string.storage_confirm_clear_data_positive))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text(stringResource(R.string.storage_confirm_clear_data_negative))
                    }
                }
            )
        }
    }
}

private const val BYTES_PER_UNIT = 1024L

private fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= BYTES_PER_UNIT && unitIndex < units.size - 1) {
        size /= BYTES_PER_UNIT
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${bytes}B"
    } else {
        "%.2f ${units[unitIndex]}".format(size)
    }
}
