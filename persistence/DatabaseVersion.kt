package com.droidhelp.persistence

/**
 * Represents the current version of the local database.
 * Used to track schema versions and facilitate future migrations.
 */
object DatabaseVersion {
    const val CURRENT_VERSION = 1
    
    /**
     * Version history for tracking schema changes.
     * Each entry maps a version number to a description of changes.
     */
    val VERSION_HISTORY: Map<Int, String> = mapOf(
        1 to "Initial schema with history and intent journals"
    )
    
    /**
     * Checks if a migration is needed from one version to another.
     */
    fun requiresMigration(fromVersion: Int, toVersion: Int): Boolean {
        return fromVersion < toVersion
    }
    
    /**
     * Validates that a version number is valid.
     */
    fun isValidVersion(version: Int): Boolean {
        return version > 0 && VERSION_HISTORY.containsKey(version)
    }
}