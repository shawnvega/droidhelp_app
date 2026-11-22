package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.platform.VersionedDatabase
import com.bighelp.droidhelp.platform.FileBasedVersionedDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * A journal that persists submission intent before dispatch.
 */
class SubmissionJournal(private val db: VersionedDatabase) : Journal {

    private val mutex = Mutex()

    /**
     * For backward compatibility with existing tests.
     */
    constructor(storageFile: File) : this(FileBasedVersionedDatabase(storageFile))

    // --- Small API requested by task ---

    /**
     * Records a new submission intent.
     */
    suspend fun recordIntent(intent: String): Entry = prepare(intent)

    /**
     * Marks an intent as dispatched.
     */
    suspend fun markDispatched(entryId: String): Entry = dispatch(entryId)

    /**
     * Marks an intent as acknowledged.
     */
    suspend fun markAcknowledged(entryId: String): Entry = acknowledge(entryId)

    /**
     * Lists all pending intents (not yet acknowledged).
     */
    suspend fun listPending(): List<Entry> = query(null).filter { it.state != State.ACKNOWLEDGED }

    // --- Existing API (used by tests and potentially others) ---

    /**
     * Prepares a new intent.
     */
    suspend fun prepare(intent: String): Entry = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val entry = Entry(
            id = id,
            intent = intent,
            state = State.PREPARED,
            timestamp = Instant.now(),
            metadata = null
        )
        db.insert(id, entry.toCsv())
        entry
    }

    /**
     * Dispatches an intent.
     */
    override suspend fun dispatch(entryId: String): Entry = mutex.withLock {
        val entry = db.retrieveEntry(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
        if (entry.state != State.PREPARED) {
            throw IllegalStateTransitionException("Cannot dispatch from state ${entry.state}")
        }
        val updatedEntry = entry.copy(
            state = State.DISPATCHED,
            timestamp = Instant.now()
        )
        db.insert(entryId, updatedEntry.toCsv())
        updatedEntry
    }

    /**
     * Acknowledges an intent.
     */
    override suspend fun acknowledge(entryId: String): Entry = mutex.withLock {
        val entry = db.retrieveEntry(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
        if (entry.state != State.DISPATCHED) {
            throw IllegalStateTransitionException("Cannot acknowledge from state ${entry.state}")
        }
        val updatedEntry = entry.copy(
            state = State.ACKNOWLEDGED,
            timestamp = Instant.now()
        )
        db.insert(entryId, updatedEntry.toCsv())
        updatedEntry
    }

    /**
     * Marks an intent as uncertain.
     */
    override suspend fun markUncertain(entryId: String, reason: String): Entry = mutex.withLock {
        val entry = db.retrieveEntry(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
        val updatedMetadata = (entry.metadata ?: emptyMap()) + ("reason" to reason)
        val updatedEntry = entry.copy(
            state = State.UNCERTAIN,
            timestamp = Instant.now(),
            metadata = updatedMetadata
        )
        db.insert(entryId, updatedEntry.toCsv())
        updatedEntry
    }

    /**
     * Retrieves an entry by ID.
     */
    override suspend fun get(entryId: String): Entry? = mutex.withLock { db.retrieveEntry(entryId) }

    /**
     * Queries entries by state.
     */
    override suspend fun query(state: State?): List<Entry> = mutex.withLock {
        db.listKeys().mapNotNull { id ->
            val entry = db.retrieveEntry(id)
            if (state == null || (entry != null && entry.state == state)) entry else null
        }
    }

    // --- Journal Interface Implementation ---

    override suspend fun appendIntent(intent: String): Entry = prepare(intent)

    override suspend fun appendHistory(history: String): Entry = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val entry = Entry(
            id = id,
            intent = history,
            state = State.HISTORY,
            timestamp = Instant.now(),
            metadata = null
        )
        db.insert(id, entry.toCsv())
        entry
    }

    override suspend fun queryByVersion(
        entryId: String,
        startVersion: Int,
        endVersion: Int,
    ): List<Entry> = mutex.withLock {
        db.queryByVersion(entryId, startVersion, endVersion).map { Entry.fromCsv(it) }
    }

    override suspend fun prune(retentionPolicy: (Instant) -> Boolean) = mutex.withLock {
        val keysToRemove = db.listKeys().filter { id ->
            val entry = db.retrieveEntry(id)
            entry != null && retentionPolicy(entry.timestamp)
        }
        keysToRemove.forEach { db.delete(it) }
    }
}

private fun VersionedDatabase.retrieveEntry(id: String): Entry? =
    retrieve(id)?.let { Entry.fromCsv(it) }
