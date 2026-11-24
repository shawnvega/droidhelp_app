package com.bighelp.droidhelp.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bighelp.droidhelp.designsystem.ColorMode
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val botRepository: BotSettingsRepository,
    private val accountSettings: AccountSettings,
    private val storageRepository: StorageSettingsRepository
) : ViewModel() {
    var currentSection by mutableStateOf<SettingsSection?>(null)
        private set

    var cacheSize by mutableStateOf(0L)
        private set

    var dataUsage by mutableStateOf(0L)
        private set

    fun selectSection(section: SettingsSection) {
        currentSection = section
        if (section == SettingsSection.STORAGE) {
            refreshStorageInfo()
        }
    }

    fun goBack() {
        currentSection = null
    }

    var endpointUrl by mutableStateOf("")
        private set

    var botSettings by mutableStateOf(BotSettings())
        private set

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

    fun refreshStorageInfo() {
        cacheSize = storageRepository.getCacheSize()
        dataUsage = storageRepository.getDataUsage()
    }

    fun clearCache() {
        storageRepository.clearCache()
        refreshStorageInfo()
    }

    fun clearData() {
        storageRepository.clearData()
        refreshStorageInfo()
    }

    fun loadSettings() {
        viewModelScope.launch {
            endpointUrl = accountSettings.getEndpointUrl() ?: ""
            botSettings = botRepository.getBotSettings()
            refreshStorageInfo()
        }
    }

    fun updateBotSettings(updater: (BotSettings) -> BotSettings) {
        viewModelScope.launch {
            val newSettings = updater(botSettings)
            botRepository.saveBotSettings(newSettings)
            botSettings = newSettings
        }
    }
}
