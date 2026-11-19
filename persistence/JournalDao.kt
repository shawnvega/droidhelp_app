package com.droidhelp.persistence

import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object interface for journal entries.
 * Provides a clean interface for reading and writing journal entries.
 */
interface JournalDao {
    
    /**
     * Inserts a new journal entry.
     * @return The ID of the inserted entry.
     */
    suspend fun insertEntry(entry: JournalEntry): String
    
    /**
     * Inserts a history entry.
     * @return The ID of the inserted entry.
     */
    suspend fun insertHistoryEntry(entry: HistoryEntry): String
    
    /**
     * Inserts an intent entry.
     * @return The ID of the inserted entry.
     */
    suspend fun insertIntentEntry(entry: IntentEntry): String
    
    /**
     * Retrieves all journal entries as a Flow.
     */
    fun getAllEntries(): Flow<List<JournalEntry>>
    
    /**
     * Retrieves all history entries as a Flow.
     */
    fun getAllHistoryEntries(): Flow<List<HistoryEntry>>
    
    /**
     * Retrieves all intent entries as a Flow.
     */
    fun getAllIntentEntries(): Flow<List<IntentEntry>>
    
    /**
     * Retrieves a specific journal entry by ID.
     * @return The entry, or null if not found.
     */
    suspend fun getEntryById(id: String): JournalEntry?
    
    /**
     * Retrieves history entries within a time range.
     * @param startTime Start of the time range (inclusive).
     * @param endTime End of the time range (inclusive).
     * @return List of history entries in the time range.
     */
    suspend fun getHistoryEntriesInRange(startTime: Long, endTime: Long): List<HistoryEntry>
    
    /**
     * Retrieves intent entries within a time range.
     * @param startTime Start of the time range (inclusive).
     * @param endTime End of the time range (inclusive).
     * @return List of intent entries in the time range.
     */
    suspend fun getIntentEntriesInRange(startTime: Long, endTime: Long): List<IntentEntry>
    
    /**
     * Deletes a journal entry by ID.
     * @return True if the entry was deleted, false otherwise.
     */
    suspend fun deleteEntry(id: String): Boolean
    
    /**
     * Deletes all journal entries.
     */
    suspend fun deleteAllEntries()
    
    /**
     * Gets the count of journal entries.
     */
    suspend fun getEntryCount(): Int
    
    /**
     * Gets the count of history entries.
     */
    suspend fun getHistoryEntryCount(): Int
    
    /**
     * Gets the count of intent entries.
     */
    suspend fun getIntentEntryCount(): Int
}