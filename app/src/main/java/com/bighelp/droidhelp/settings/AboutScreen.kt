package com.bighelp.droidhelp.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bighelp.droidhelp.BuildConfig

/**
 * The About screen of the application.
 *
 * @param onBack Callback to be invoked when the user wants to navigate back.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ossLibraries = listOf(
        OssLibrary(
            "Jetpack Compose",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "Material 3",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "Navigation",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "OkHttp",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "Kotlinx Serialization",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "DataStore",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        ),
        OssLibrary(
            "Security Crypto",
            "Apache 2.0",
            "Apache License, Version 2.0, as may be dealt with at www.apache.org/licenses/LICENSE-2.0"
        )
    )

    var selectedLibrary by remember { mutableStateOf<OssLibrary?>(null) }
    var showTermsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "droidhelp",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyLarge
            )
            if (BuildConfig.VERSION_CODE > 0) {
                Text(
                    text = "Code: ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Open Source Libraries",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ossLibraries.forEach { library ->
                ListItem(
                    headlineContent = { Text(library.name) },
                    supportingContent = { Text(library.license) },
                    modifier = Modifier.clickable { selectedLibrary = library }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Legal",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("Terms of Service") },
                modifier = Modifier.clickable { showTermsDialog = true }
            )
            ListItem(
                headlineContent = { Text("Privacy Policy") },
                modifier = Modifier.clickable { showTermsDialog = true }
            )
        }

        // Library Dialog
        selectedLibrary?.let { library ->
            AlertDialog(
                onDismissRequest = { selectedLibrary = null },
                title = { Text(library.name) },
                text = { Text(library.licenseText) },
                confirmButton = {
                    TextButton(onClick = { selectedLibrary = null }) {
                        Text("OK")
                    }
                }
            )
        }

        // Terms Dialog
        if (showTermsDialog) {
            AlertDialog(
                onDismissRequest = { showTermsDialog = false },
                title = { Text("Legal Information") },
                text = { Text("This is a placeholder for the terms and privacy policy content.") },
                confirmButton = {
                    TextButton(onClick = { showTermsDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
