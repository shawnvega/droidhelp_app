package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val KB_FACTOR = 1024L

private val Context.storageDataStore by androidx.datastore.preferences.preferencesDataStore(name = "storage_settings")

class StorageSettings(private val context: Context) {
    companion object {
        private val TOTAL_USAGE_KEY = androidx.datastore.preferences.core.longPreferencesKey("total_usage")
    }

    val totalUsageFlow: Flow<Long> = context.storageDataStore.data.map { preferences ->
        preferences[TOTAL_USAGE_KEY] ?: 1024L
    }

    suspend fun clearCache() {
        context.storageDataStore.edit { preferences ->
            preferences[TOTAL_USAGE_KEY] = 0L
        }
    }
}

@Composable
fun PrivacySettingsSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val privacySettings = PrivacySettings(context)
    val analyticsEnabled by privacySettings.analyticsEnabledFlow.collectAsState(initial = false)
    val biometricLockEnabled by privacySettings.biometricLockEnabledFlow.collectAsState(initial = false)
    val dataSharingEnabled by privacySettings.dataSharingEnabledFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text("Analytics") },
            supportingContent = { Text("Share usage data to improve the app") },
            trailingContent = {
                Switch(
                    checked = analyticsEnabled,
                    onCheckedChange = { scope.launch { privacySettings.setAnalyticsEnabled(it) } }
                )
            }
        )
        ListItem(
            headlineContent = { Text("Biometric Lock") },
            supportingContent = { Text("Require biometrics to access the app") },
            trailingContent = {
                Switch(
                    checked = biometricLockEnabled,
                    onCheckedChange = { scope.launch { privacySettings.setBiometricLockEnabled(it) } }
                )
            }
        )
        ListItem(
            headlineContent = { Text("Data Sharing") },
            supportingContent = { Text("Share data with third-party services") },
            trailingContent = {
                Switch(
                    checked = dataSharingEnabled,
                    onCheckedChange = { scope.launch { privacySettings.setDataSharingEnabled(it) } }
                )
            }
        )
    }
}

@Composable
fun StorageSettingsSection(
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageSettings = StorageSettings(context)
    val totalUsage by storageSettings.totalUsageFlow.collectAsState(initial = 0L)
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text("Storage Usage") },
            supportingContent = { Text("${totalUsage / KB_FACTOR} KB used") }
        )
        ListItem(
            headlineContent = { Text("Clear Cache") },
            trailingContent = {
                Button(onClick = { scope.launch { onClearCache() } }) {
                    Text("Clear")
                }
            }
        )
    }
}

@Composable
fun AboutSettingsSection(
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text("App Version") },
            supportingContent = { Text("1.0.0") }
        )
        ListItem(
            headlineContent = { Text("Build Info") },
            supportingContent = { Text("2026-11-24-01-00") }
        )
        ListItem(
            headlineContent = { Text("Terms of Service") },
            supportingContent = { Text("https://example.com/terms") }
        )
        ListItem(
            headlineContent = { Text("Open Source Licenses") },
            supportingContent = { Text("https://example.com/licenses") }
        )
    }
}
