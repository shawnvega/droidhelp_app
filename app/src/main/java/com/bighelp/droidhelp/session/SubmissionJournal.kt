package com.bighelp.droidhelp.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Represents the current state of a submission entry.
 */
enum class State {
    PREPARED, DISPATCHED, ACKNOWLEDGED, UNCERTAIN
}

/**
 * A single entry in the submission journal.
 */
data class Entry(
    val id: String,
    val intent: String,
    val state: State,
    val timestamp: String,
    val metadata: Map<String, String>? = null
) {
    fun toCsv(): String {
        val meta = metadata?.entries?.joinToString(";") { "${it.key}:${it.value}" } ?: ""
        return "$id|$intent|$state|$timestamp|$meta"
    }

    companion object {
        fun fromCsv(csv: String): Entry {
            val parts = csv.split("|")
            val meta = if (parts.size > 4 && parts[4].isNotEmpty()) {
                parts[4].split(";").associate {
                    val kv = it.split(":", limit = 2)
                    kv[0] to kv[1]
                }
            } else null
            return Entry(parts[0], parts[1], State.valueOf(parts[2]), parts[3], meta)
        }
    }
}

/**
 * Exception thrown when an invalid state transition is attempted.
 */
class IllegalStateTransitionException(message: String) : Exception(message)

/**
 * A journal that persists submission intent before dispatch.
 * Ensures thread-safety and provides state machine enforcement.
 */
class SubmissionJournal(
    private val storageFile: File? = null
) {
    private val mutex = Mutex()
    private var entries = mutableMapOf<String, Entry>()

    init {
        storageFile?.let {
            if (it.exists()) {
                try {
                    val lines = it.readLines()
                    entries = lines.filter { line -> line.isNotBlank() }
                        .map { Entry.fromCsv(it) }
                        .associateBy { it.id }
                        .toMutableMap()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun save() {
        storageFile?.let {
            val content = entries.values.joinToString("\n") { it.toCsv() }
            it.writeText(content)
        }
    }

    suspend fun prepare(intent: String): Entry = mutex.withLock {
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            intent = intent,
            state = State.PREPARED,
            timestamp = Instant.now().toString(),
            metadata = null
        )
        entries[entry.id] = entry
        save()
        entry
    }

    suspend fun dispatch(entryId: String): Entry = mutex.withLock {
        val entry = entries[entryId] ?: throw IllegalArgumentException("Entry not found: $entryId")
        if (entry.state != State.PREPARED) {
            throw IllegalStateTransitionException("Cannot dispatch from state ${entry.state}")
        }
        val updatedEntry = entry.copy(
            state = State.DISPATCHED,
            timestamp = Instant.now().toString()
        )
        entries[entryId] = updatedEntry
        save()
        updatedEntry
    }

    suspend fun acknowledge(entryId: String): Entry = mutex.withLock {
        val entry = entries[entryId] ?: throw IllegalArgumentException("Entry not found: $entryId")
        if (entry.state != State.DISPATCHED) {
            throw IllegalStateTransitionException("Cannot acknowledge from state ${entry.state}")
        }
        val updatedEntry = entry.copy(
            state = State.ACKNOWLEDGED,
            timestamp = Instant.now().toString()
        )
        entries[entryId] = updatedEntry
        save()
        updatedEntry
    }

    suspend fun markUncertain(entryId: String, reason: String): Entry = mutex.withLock {
        val entry = entries[entryId] ?: throw IllegalArgumentException("Entry not found: $entryId")
        val updatedMetadata = (entry.metadata ?: emptyMap()) + ("reason" to reason)
        val updatedEntry = entry.copy(
            state = State.UNCERTAIN,
            timestamp = Instant.now().toString(),
            metadata = updatedMetadata
        )
        entries[entryId] = updatedEntry
        save()
        updatedEntry
    }

    suspend fun get(entryId: String): Entry? = mutex.withLock {
        entries[entryId]
    }

    suspend fun query(state: State?): List<Entry> = mutex.withLock {
        if (state == null) {
            entries.values.toList()
        } else {
            entries.values.filter { it.state == state }
        }
    }
}
