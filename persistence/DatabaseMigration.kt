package com.droidhelp.persistence

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Handles database migrations between versions.
 * Provides a clean interface for defining and executing migrations.
 */
class DatabaseMigrationHandler {
    
    /**
     * Represents a single migration from one version to another.
     */
    data class Migration(
        val startVersion: Int,
        val endVersion: Int,
        val migrate: (SupportSQLiteDatabase) -> Unit
    )
    
    private val migrations = mutableListOf<Migration>()
    
    /**
     * Registers a migration from one version to another.
     */
    fun addMigration(migration: Migration) {
        migrations.add(migration)
    }
    
    /**
     * Registers a migration using a lambda.
     */
    fun addMigration(
        startVersion: Int,
        endVersion: Int,
        migrate: (SupportSQLiteDatabase) -> Unit
    ) {
        migrations.add(Migration(startVersion, endVersion, migrate))
    }
    
    /**
     * Gets all migrations that need to be applied to go from current to target version.
     */
    fun getMigrationsFor(currentVersion: Int, targetVersion: Int): List<Migration> {
        return migrations
            .filter { it.startVersion >= currentVersion && it.endVersion <= targetVersion }
            .sortedBy { it.startVersion }
    }
    
    /**
     * Executes all necessary migrations to bring the database from current to target version.
     */
    fun migrate(database: SupportSQLiteDatabase, currentVersion: Int, targetVersion: Int) {
        val migrationsToApply = getMigrationsFor(currentVersion, targetVersion)
        
        for (migration in migrationsToApply) {
            migration.migrate(database)
        }
    }
    
    companion object {
        /**
         * Creates a default migration handler with common migrations.
         */
        fun createDefault(): DatabaseMigrationHandler {
            val handler = DatabaseMigrationHandler()
            
            // Example migration from version 1 to 2
            // handler.addMigration(1, 2) { db ->
            //     db.execSQL("ALTER TABLE journal_entries ADD COLUMN new_column TEXT")
            // }
            
            return handler
        }
    }
}