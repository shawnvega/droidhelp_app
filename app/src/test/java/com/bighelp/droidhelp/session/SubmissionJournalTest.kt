package com.bighelp.droidhelp.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

class SubmissionJournalTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storageFile: File
    private lateinit var journal: SubmissionJournal

    @BeforeEach
    fun setup() {
        storageFile = tempDir.resolve("journal.json").toFile()
        journal = SubmissionJournal(storageFile)
    }

    @Test
    fun `test successful state transitions`() = runBlocking {
        val intent = "test-intent"
        val entry = journal.appendIntent(intent)
        assertEquals(State.PREPARED, entry.state)
        assertEquals(intent, entry.intent)

        val dispatchedEntry = journal.dispatch(entry.id)
        assertEquals(State.DISPATCHED, dispatchedEntry.state)

        val acknowledgedEntry = journal.acknowledge(dispatchedEntry.id)
        assertEquals(State.ACKNOWLEDGED, acknowledgedEntry.state)
    }

    @Test
    fun `test append history`() = runBlocking {
        val history = "some history event"
        val entry = journal.appendHistory(history)
        assertEquals(State.HISTORY, entry.state)
        assertEquals(history, entry.intent)

        val retrieved = journal.get(entry.id)
        assertNotNull(retrieved)
        assertEquals(history, retrieved?.intent)
    }

    @Test
    fun `test append intent`() = runBlocking {
        val intent = "test-intent"
        val entry = journal.appendIntent(intent)
        assertEquals(State.PREPARED, entry.state)
        assertEquals(intent, entry.intent)

        val retrieved = journal.get(entry.id)
        assertNotNull(retrieved)
        assertEquals(intent, retrieved?.intent)
    }

    @Test
    fun `test invalid state transitions`() = runBlocking {
        val entry = journal.appendIntent("test-intent")
        // Cannot acknowledge directly from PREPARED
        assertThrows(IllegalStateTransitionException::class.java) {
            runBlocking { journal.acknowledge(entry.id) }
        }

        // Cannot dispatch twice
        journal.dispatch(entry.id)
        assertThrows(IllegalStateTransitionException::class.java) {
            runBlocking { journal.dispatch(entry.id) }
        }
    }

    @Test
    fun `test mark uncertain from any state`() = runBlocking {
        // From PREPARED
        val prepared = journal.appendIntent("intent-1")
        val uncertain1 = journal.markUncertain(prepared.id, "reason-1")
        assertEquals(State.UNCERTAIN, uncertain1.state)
        assertEquals("reason-1", uncertain1.metadata?.get("reason"))

        // From DISPATCHED
        val dispatched = journal.appendIntent("intent-2")
        journal.dispatch(dispatched.id)
        val uncertain2 = journal.markUncertain(dispatched.id, "reason-2")
        assertEquals(State.UNCERTAIN, uncertain2.state)
        assertEquals("reason-2", uncertain2.metadata?.get("reason"))

        // From ACKNOWLEDGED
        val acknowledged = journal.appendIntent("intent-3")
        journal.dispatch(acknowledged.id)
        journal.acknowledge(acknowledged.id)
        val uncertain3 = journal.markUncertain(acknowledged.id, "reason-3")
        assertEquals(State.UNCERTAIN, uncertain3.state)
        assertEquals("reason-3", uncertain3.metadata?.get("reason"))
    }

    @Test
    fun `test persistence`() = runBlocking {
        val intent = "persistent-intent"
        val entry = journal.appendIntent(intent)
        journal.dispatch(entry.id)

        // Create a new journal instance pointing to the same file
        val newJournal = SubmissionJournal(storageFile)
        val retrievedEntry = newJournal.get(entry.id)
        assertNotNull(retrievedEntry)
        assertEquals(entry.id, retrievedEntry?.id)
        assertEquals(State.DISPATCHED, retrievedEntry?.state)
        assertEquals(intent, retrievedEntry?.intent)
    }

    @Test
    fun `test query by state`() = runBlocking {
        journal.appendIntent("intent-1")
        val entry2 = journal.appendIntent("intent-2")
        journal.dispatch(entry2.id)
        val entry3 = journal.appendIntent("intent-3")
        journal.markUncertain(entry3.id, "uncertain-reason")

        val preparedEntries = journal.query(State.PREPARED)
        assertEquals(1, preparedEntries.size)
        assertEquals("intent-1", preparedEntries[0].intent)

        val dispatchedEntries = journal.query(State.DISPATCHED)
        assertEquals(1, dispatchedEntries.size)
        assertEquals("intent-2", dispatchedEntries[0].intent)

        val uncertainEntries = journal.query(State.UNCERTAIN)
        assertEquals(1, uncertainEntries.size)
        assertEquals("intent-3", uncertainEntries[0].intent)

        val allEntries = journal.query(null)
        assertEquals(3, allEntries.size)
    }

    @Test
    fun `test thread safety`() = runBlocking {
        val numConcurrent = 50
        val jobs = (1..numConcurrent).map { i ->
            async(Dispatchers.Default) {
                val entry = journal.appendIntent("intent-$i")
                journal.dispatch(entry.id)
            }
        }
        jobs.awaitAll()

        val dispatchedCount = journal.query(State.DISPATCHED).size
        assertEquals(numConcurrent, dispatchedCount)
    }
}
