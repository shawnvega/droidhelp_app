package com.droidhelp.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO implementation for journal entries.
 */
@Dao
interface JournalDaoImpl : JournalDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertEntry(entry: JournalEntry): String
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertHistoryEntry(entry: HistoryEntry): String
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    override suspend fun insertIntentEntry(entry: IntentEntry): String
    
    @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC")
    override fun getAllEntries(): Flow<List<JournalEntry>>
    
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC")
    override fun getAllHistoryEntries(): Flow<List<HistoryEntry>>
    
    @Query("SELECT * FROM intent_entries ORDER BY timestamp DESC")
    override fun getAllIntentEntries(): Flow<List<IntentEntry>>
    
    @Query("SELECT * FROM journal_entries WHERE id = :id")
    override suspend fun getEntryById(id: String): JournalEntry?
    
    @Query("SELECT * FROM history_entries WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    override suspend fun getHistoryEntriesInRange(startTime: Long, endTime: Long): List<HistoryEntry>
    
    @Query("SELECT * FROM intent_entries WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    override suspend fun getIntentEntriesInRange(startTime: Long, endTime: Long): List<IntentEntry>
    
    @Query("DELETE FROM journal_entries WHERE id = :id")
    override suspend fun deleteEntry(id: String): Boolean
    
    @Query("DELETE FROM journal_entries")
    override suspend fun deleteAllEntries()
    
    @Query("SELECT COUNT(*) FROM journal_entries")
    override suspend fun getEntryCount(): Int
    
    @Query("SELECT COUNT(*) FROM history_entries")
    override suspend fun getHistoryEntryCount(): Int
    
    @Query("SELECT COUNT(*) FROM intent_entries")
    override suspend fun getIntentEntryCount(): Int
}