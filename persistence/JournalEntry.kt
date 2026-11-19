package com.droidhelp.persistence

import kotlinx.serialization.Serializable

/**
 * Represents a journal entry in the local database.
 * Can be either a history entry or an intent entry.
 */
@Serializable
data class JournalEntry(
    val id: String,
    val type: JournalType,
    val timestamp: Long,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Types of journal entries supported by the persistence layer.
 */
@Serializable
enum class JournalType {
    HISTORY,
    INTENT
}

/**
 * Represents a history journal entry.
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val action: String,
    val details: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Represents an intent journal entry.
 */
@Serializable
data class IntentEntry(
    val id: String,
    val timestamp: Long,
    val intent: String,
    val parameters: Map<String, String> = emptyMap(),
    val result: String? = null
)