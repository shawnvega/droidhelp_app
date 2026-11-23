package com.bighelp.droidhelp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.bighelp.droidhelp.app.AppState
import com.bighelp.droidhelp.app.AppComposition
import com.bighelp.droidhelp.designsystem.ThemeWrapper
import com.bighelp.droidhelp.home.model.HomeSelection
import com.bighelp.droidhelp.settings.AppearanceSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appState = remember { AppState(HomeSelection()) }
            val appearanceSettings = remember { AppearanceSettings(this) }

            LaunchedEffect(appearanceSettings) {
                appearanceSettings.themeModeFlow.collect { mode ->
                    appState.colorMode = mode
                }
            }

            ThemeWrapper(colorMode = appState.colorMode) {
                AppComposition(appState = appState)
            }
        }
    }
}
