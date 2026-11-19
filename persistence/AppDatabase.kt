package com.droidhelp.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room database for the versioned local persistence layer.
 * Supports history and intent journals with version tracking.
 */
@Database(
    entities = [
        JournalEntity::class,
        HistoryEntity::class,
        IntentEntity::class
    ],
    version = DatabaseVersion.CURRENT_VERSION,
    exportSchema = true
)
@TypeConverters(JournalConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    
    companion object {
        private const val DATABASE_NAME = "droidhelp_journal.db"
        private var INSTANCE: AppDatabase? = null
        private val mutex = Mutex()
        
        /**
         * Gets the singleton instance of the database.
         * Uses double-checked locking for thread safety.
         */
        suspend fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: mutex.withLock {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .build()
        }
        
        /**
         * Resets the database instance. Useful for testing and migrations.
         */
        suspend fun resetDatabase() {
            mutex.withLock {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}