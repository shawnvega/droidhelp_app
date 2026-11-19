# DroidHelp Persistence Layer

This module implements a versioned local database system for the DroidHelp Android application.

## Overview

The persistence layer provides durable storage for history and intent journals with the following features:

- **Versioned Database**: Tracks database versions to facilitate future migrations
- **History Journal**: Stores application actions and events
- **Intent Journal**: Stores user intents and their results
- **Clean Interface**: Provides a simple API for reading and writing journal entries
- **Room Integration**: Uses Android Room for database management
- **Migration Support**: Includes infrastructure for database migrations

## Key Components

### 1. DatabaseVersion.kt
Tracks the current database version and provides version history for migrations.

### 2. JournalEntry.kt
Data classes for journal entries (HistoryEntry and IntentEntry).

### 3. JournalDao.kt
Data Access Object interface for database operations.

### 4. JournalDaoImpl.kt
Room DAO implementation for journal operations.

### 5. AppDatabase.kt
Room database configuration with version tracking.

### 6. JournalConverters.kt
Type converters for Room to handle complex data types.

### 7. JournalRepository.kt
Repository layer providing a clean interface for persistence operations.

### 8. PersistenceManager.kt
Main entry point for the persistence layer.

### 9. DatabaseMigration.kt
Handles database migrations between versions.

## Usage

### Initialization
```kotlin
val persistence = PersistenceManager.initialize(context)
```

### Adding History Entries
```kotlin
val historyId = persistence.journalRepository.addHistoryEntry(
    action = "User Login",
    details = "User successfully logged in",
    metadata = mapOf("user_id" to "123", "device" to "android")
)
```

### Adding Intent Entries
```kotlin
val intentId = persistence.journalRepository.addIntentEntry(
    intent = "Create Task",
    parameters = mapOf("title" to "New Task", "priority" to "high"),
    result = "Task created successfully"
)
```

### Retrieving Entries
```kotlin
// Get all history entries
persistence.journalRepository.getHistoryEntries().collect { entries ->
    // Process entries
}

// Get entries in a time range
val entries = persistence.journalRepository.getHistoryEntriesInRange(
    startTime = 0L,
    endTime = System.currentTimeMillis()
)
```

## Database Versioning

The database uses version tracking to facilitate migrations:

- **Current Version**: 1
- **Version History**: Tracks schema changes
- **Migration Support**: Infrastructure for future migrations

## Testing

Run the test to verify the persistence layer:
```kotlin
PersistenceTest.runTest(context)
```

## Dependencies

The persistence layer depends on:
- Android Room
- Kotlin Serialization
- Coroutines
- Gson (for JSON serialization)

## Migration Path

Future migrations can be added to `DatabaseMigration.kt`:

```kotlin
handler.addMigration(1, 2) { db ->
    db.execSQL("ALTER TABLE journal_entries ADD COLUMN new_column TEXT")
}
```

## Notes

- The persistence layer is designed to be durable and reliable
- All operations are performed asynchronously using coroutines
- The database is initialized lazily on first use
- The persistence manager is a singleton to ensure a single database instance