package com.droidhelp.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Room entity representing a journal entry.
 */
@Entity(tableName = "journal_entries")
data class JournalEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "metadata") val metadata: String
) {
    /**
     * Converts a JournalEntity to a JournalDomain model.
     */
    fun toDomain(): JournalEntry {
        val type = JournalType.valueOf(this.type)
        return when (type) {
            JournalType.HISTORY -> JournalEntry(
                id = this.id,
                type = type,
                timestamp = this.timestamp,
                content = this.content,
                metadata = parseMetadata(this.metadata)
            )
            JournalType.INTENT -> JournalEntry(
                id = this.id,
                type = type,
                timestamp = this.timestamp,
                content = this.content,
                metadata = parseMetadata(this.metadata)
            )
        }.also {
            // Ensure type is set correctly
            it.copy(type = type)
        }
    }
    
    private fun parseMetadata(metadata: String): Map<String, String> {
        // Simple JSON-like metadata parsing
        return if (metadata.isEmpty()) emptyMap()
        else metadata
    }
}

/**
 * Room entity for history entries.
 */
@Entity(tableName = "history_entries")
data class HistoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "details") val details: String,
    @ColumnInfo(name = "metadata") val metadata: String
) {
    fun toDomain(): HistoryEntry {
        return HistoryEntry(
            id = this.id,
            timestamp = this.timestamp,
            action = this.action,
            details = this.details,
            metadata = parseMetadata(this.metadata)
        )
    }
    
    private fun parseMetadata(metadata: String): Map<String, String> {
        return if (metadata.isEmpty()) emptyMap()
        else metadata
    }
}

/**
 * Room entity for intent entries.
 */
@Entity(tableName = "intent_entries")
data class IntentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "intent") val intent: String,
    @ColumnInfo(name = "parameters") val parameters: String,
    @ColumnInfo(name = "result") val result: String?
) {
    fun toDomain(): IntentEntry {
        return IntentEntry(
            id = this.id,
            timestamp = this.timestamp,
            intent = this.intent,
            parameters = parseMetadata(this.parameters),
            result = this.result
        )
    }
    
    private fun parseMetadata(parameters: String): Map<String, String> {
        return if (parameters.isEmpty()) emptyMap()
        else parameters
    }
}