package com.bighelp.droidhelp.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.bighelp.droidhelp.designsystem.ColorMode

class SettingsViewModel : ViewModel() {
    var currentSection by mutableStateOf<SettingsSection?>(null)
        private set

    fun selectSection(section: SettingsSection) {
        currentSection = section
    }

    fun goBack() {
        currentSection = null
    }

    // For now, we'll use some dummy values for the other settings
    val endpointUrl = "https://api.example.com"
    val botNames = listOf("Assistant", "Coder", "Researcher")
    
    var pushEnabled by mutableStateOf(true)
        private set
    fun togglePush(enabled: Boolean) {
        pushEnabled = enabled
    }

    var soundEnabled by mutableStateOf(true)
        private set
    fun toggleSound(enabled: Boolean) {
        soundEnabled = enabled
    }

    var selectedMode by mutableStateOf(ColorMode.Dynamic)
        private set
    fun selectMode(mode: ColorMode) {
        selectedMode = mode
    }

    fun clearCache() {
        // Placeholder implementation
    }
}
