package com.bighelp.droidhelp.session

import java.time.Instant

/**
 * Represents the current state of a submission entry.
 */
enum class State {
    PREPARED, DISPATCHED, ACKNOWLEDGED, UNCERTAIN, HISTORY
}

/**
 * A single entry in the submission journal.
 */
data class Entry(
    val id: String,
    val intent: String,
    val state: State,
    val timestamp: Instant,
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
            return Entry(parts[0], parts[1], State.valueOf(parts[2]), Instant.parse(parts[3]), meta)
        }
    }
}

/**
 * Exception thrown when an invalid state transition is attempted.
 */
class IllegalStateTransitionException(message: String) : Exception(message)

/**
 * A journal that persists submission intent before dispatch.
 */
interface Journal {
    suspend fun appendIntent(intent: String): Entry
    suspend fun appendHistory(history: String): Entry
    suspend fun dispatch(entryId: String): Entry
    suspend fun acknowledge(entryId: String): Entry
    suspend fun markUncertain(entryId: String, reason: String): Entry
    suspend fun get(entryId: String): Entry?
    suspend fun query(state: State?): List<Entry>
    suspend fun queryByVersion(entryId: String, startVersion: Int, endVersion: Int): List<Entry>
    suspend fun prune(retentionPolicy: (Instant) -> Boolean)
}
