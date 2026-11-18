package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.platform.VersionedDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * An implementation of [Journal] that uses a [VersionedDatabase].
 */
class SubmissionJournalImpl(
    private val db: VersionedDatabase
) : Journal {
    private val mutex = Mutex()

    override suspend fun appendIntent(intent: String): Entry = mutex.withLock {
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

    override suspend fun dispatch(entryId: String): Entry = mutex.withLock {
        val entry = getInternal(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
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

    override suspend fun acknowledge(entryId: String): Entry = mutex.withLock {
        val entry = getInternal(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
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

    override suspend fun markUncertain(entryId: String, reason: String): Entry = mutex.withLock {
        val entry = getInternal(entryId) ?: throw IllegalArgumentException("Entry not found: $entryId")
        val updatedMetadata = (entry.metadata ?: emptyMap()) + ("reason" to reason)
        val updatedEntry = entry.copy(
            state = State.UNCERTAIN,
            timestamp = Instant.now(),
            metadata = updatedMetadata
        )
        db.insert(entryId, updatedEntry.toCsv())
        updatedEntry
    }

    override suspend fun get(entryId: String): Entry? = mutex.withLock {
        getInternal(entryId)
    }

    override suspend fun query(state: State?): List<Entry> = mutex.withLock {
        db.listKeys().mapNotNull { id ->
            val entry = getInternal(id)
            if (state == null || entry?.state == state) entry else null
        }
    }

    override suspend fun queryByVersion(entryId: String, startVersion: Int, endVersion: Int): List<Entry> = mutex.withLock {
        db.queryByVersion(entryId, startVersion, endVersion).map { Entry.fromCsv(it) }
    }

    override suspend fun prune(retentionPolicy: (Instant) -> Boolean) = mutex.withLock {
        val keysToRemove = db.listKeys().filter { id ->
            val entry = getInternal(id)
            entry != null && retentionPolicy(entry.timestamp)
        }
        keysToRemove.forEach { db.delete(it) }
    }

    private fun getInternal(id: String): Entry? {
        return db.retrieve(id)?.let { Entry.fromCsv(it) }
    }
}
