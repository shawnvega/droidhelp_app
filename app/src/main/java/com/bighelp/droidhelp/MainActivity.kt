package com.bighelp.droidhelp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.bighelp.droidhelp.app.AppState
import com.bighelp.droidhelp.app.DroidHelpApp
import com.bighelp.droidhelp.designsystem.ThemeWrapper
import com.bighelp.droidhelp.home.model.HomeSelection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appState = remember { AppState(HomeSelection()) }
            ThemeWrapper {
                DroidHelpApp(appState = appState)
            }
        }
    }
}
