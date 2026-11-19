package com.droidhelp.persistence

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Simple test to verify the persistence layer works correctly.
 */
object PersistenceTest {
    
    /**
     * Runs a simple test to verify the persistence layer.
     */
    fun runTest(context: Context) {
        runBlocking(Dispatchers.IO) {
            // Initialize persistence manager
            val persistence = PersistenceManager.initialize(context)
            
            // Test adding entries
            val historyId = persistence.journalRepository.addHistoryEntry(
                action = "Test Action",
                details = "Test Details",
                metadata = mapOf("test_key" to "test_value")
            )
            
            val intentId = persistence.journalRepository.addIntentEntry(
                intent = "Test Intent",
                parameters = mapOf("param1" to "value1"),
                result = "Test Result"
            )
            
            println("Added history entry with ID: $historyId")
            println("Added intent entry with ID: $intentId")
            
            // Test retrieving entries
            val historyEntries = persistence.journalRepository.getHistoryEntriesInRange(
                System.currentTimeMillis() - 86400000L, // 24 hours ago
                System.currentTimeMillis()
            )
            
            val intentEntries = persistence.journalRepository.getIntentEntriesInRange(
                System.currentTimeMillis() - 86400000L, // 24 hours ago
                System.currentTimeMillis()
            )
            
            println("Retrieved ${historyEntries.size} history entries")
            println("Retrieved ${intentEntries.size} intent entries")
            
            // Test getting all entries
            val allEntries = persistence.journalRepository.getAllEntries()
            allEntries.collect { entries ->
                println("Total entries: ${entries.size}")
                entries.forEach { entry ->
                    println("Entry: ${entry.type}, ID: ${entry.id}, Timestamp: ${entry.timestamp}")
                }
            }
            
            // Close the persistence manager
            persistence.close()
        }
    }
}