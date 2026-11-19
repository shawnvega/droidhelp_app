package com.droidhelp.persistence

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Repository providing a clean interface for journal persistence.
 * Handles all database operations for history and intent journals.
 */
class JournalRepository(private val dao: JournalDao) {
    
    //region History Operations
    
    /**
     * Adds a history entry to the journal.
     */
    suspend fun addHistoryEntry(
        action: String,
        details: String,
        metadata: Map<String, String> = emptyMap()
    ): String {
        val entry = HistoryEntry(
            id = generateId(),
            timestamp = System.currentTimeMillis(),
            action = action,
            details = details,
            metadata = metadata
        )
        return dao.insertHistoryEntry(entry)
    }
    
    /**
     * Gets all history entries as a Flow.
     */
    fun getHistoryEntries(): Flow<List<HistoryEntry>> = dao.getAllHistoryEntries()
    
    /**
     * Gets history entries within a time range.
     */
    suspend fun getHistoryEntriesInRange(
        startTime: Long,
        endTime: Long
    ): List<HistoryEntry> = dao.getHistoryEntriesInRange(startTime, endTime)
    
    //endregion
    
    //region Intent Operations
    
    /**
     * Adds an intent entry to the journal.
     */
    suspend fun addIntentEntry(
        intent: String,
        parameters: Map<String, String> = emptyMap(),
        result: String? = null
    ): String {
        val entry = IntentEntry(
            id = generateId(),
            timestamp = System.currentTimeMillis(),
            intent = intent,
            parameters = parameters,
            result = result
        )
        return dao.insertIntentEntry(entry)
    }
    
    /**
     * Gets all intent entries as a Flow.
     */
    fun getIntentEntries(): Flow<List<IntentEntry>> = dao.getAllIntentEntries()
    
    /**
     * Gets intent entries within a time range.
     */
    suspend fun getIntentEntriesInRange(
        startTime: Long,
        endTime: Long
    ): List<IntentEntry> = dao.getIntentEntriesInRange(startTime, endTime)
    
    //endregion
    
    //region General Operations
    
    /**
     * Gets all journal entries as a Flow.
     */
    fun getAllEntries(): Flow<List<JournalEntry>> = dao.getAllEntries()
    
    /**
     * Gets a specific journal entry by ID.
     */
    suspend fun getEntryById(id: String): JournalEntry? = dao.getEntryById(id)
    
    /**
     * Deletes a journal entry by ID.
     */
    suspend fun deleteEntry(id: String): Boolean = dao.deleteEntry(id)
    
    /**
     * Deletes all journal entries.
     */
    suspend fun deleteAllEntries() = dao.deleteAllEntries()
    
    /**
     * Gets the total count of journal entries.
     */
    suspend fun getEntryCount(): Int = dao.getEntryCount()
    
    /**
     * Gets the count of history entries.
     */
    suspend fun getHistoryEntryCount(): Int = dao.getHistoryEntryCount()
    
    /**
     * Gets the count of intent entries.
     */
    suspend fun getIntentEntryCount(): Int = dao.getIntentEntryCount()
    
    //endregion
    
    private fun generateId(): String {
        return java.util.UUID.randomUUID().toString()
    }
    
    companion object {
        /**
         * Creates a JournalRepository with the default database instance.
         */
        suspend fun create(context: Context): JournalRepository {
            val db = AppDatabase.getDatabase(context)
            return JournalRepository(db.journalDao())
        }
    }
}