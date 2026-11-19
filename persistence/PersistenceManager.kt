package com.droidhelp.persistence

import android.content.Context
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main entry point for the versioned local persistence layer.
 * Provides a clean interface for initializing and accessing the persistence system.
 */
class PersistenceManager private constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val repository: JournalRepository
) {
    
    /**
     * Gets the journal repository for database operations.
     */
    val journalRepository: JournalRepository = repository
    
    /**
     * Gets the underlying Room database instance.
     */
    val roomDatabase: RoomDatabase = database
    
    /**
     * Gets the current database version.
     */
    val currentVersion: Int = DatabaseVersion.CURRENT_VERSION
    
    /**
     * Checks if a migration is needed.
     */
    fun requiresMigration(fromVersion: Int): Boolean {
        return DatabaseVersion.requiresMigration(fromVersion, currentVersion)
    }
    
    /**
     * Closes the database and releases resources.
     */
    fun close() {
        database.close()
    }
    
    companion object {
        private var INSTANCE: PersistenceManager? = null
        
        /**
         * Initializes the persistence manager.
         * Must be called before accessing the instance.
         */
        suspend fun initialize(context: Context): PersistenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createInstance(context).also { INSTANCE = it }
            }
        }
        
        /**
         * Gets the initialized persistence manager instance.
         * Throws if not initialized.
         */
        fun getInstance(): PersistenceManager {
            return INSTANCE ?: throw IllegalStateException(
                "PersistenceManager not initialized. Call initialize() first."
            )
        }
        
        /**
         * Resets the persistence manager instance.
         * Useful for testing.
         */
        suspend fun reset() {
            INSTANCE?.close()
            INSTANCE = null
            AppDatabase.resetDatabase()
        }
        
        private suspend fun createInstance(context: Context): PersistenceManager {
            val database = AppDatabase.getDatabase(context)
            val repository = JournalRepository(database.journalDao())
            return PersistenceManager(context, database, repository)
        }
    }
}