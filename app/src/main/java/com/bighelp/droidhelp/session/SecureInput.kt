package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages secure prompt values that should be cleared when the session owner changes.
 */
object SecureInput {
    private var currentProfile: String? = null
    private val _secureInputs = MutableStateFlow<Map<String, String>>(emptyMap())
    val secureInputs: StateFlow<Map<String, String>> = _secureInputs.asStateFlow()

    /**
     * Binds the store to a specific profile. If the profile changes, 
     * all existing secure inputs are cleared.
     */
    fun bind(profile: String) {
        if (currentProfile != null && currentProfile != profile) {
            clear()
        }
        currentProfile = profile
    }

    /**
     * Stores a secure value for a given prompt ID.
     */
    fun put(id: String, value: String) {
        _secureInputs.value += (id to value)
    }

    /**
     * Retrieves a secure value for a given prompt ID.
     * Returns null if the value is not found or if the store has been cleared.
     */
    fun get(id: String): String? {
        return _secureInputs.value[id]
    }

    /**
     * Clears all stored secure values.
     */
    fun clear() {
        _secureInputs.value = emptyMap()
    }
}
