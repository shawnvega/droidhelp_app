package com.bighelp.droidhelp.session

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SubmissionJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageFile: File
    private lateinit var journal: SubmissionJournal

    @Before
    fun setup() {
        storageFile = File(tempFolder.root, "journal.json")
        journal = SubmissionJournal(storageFile)
    }

    @Test
    fun `test valid state transitions prepared to dispatched to acknowledged`() {
        runBlocking {
            val intent = "test-intent"
            val entry = journal.recordIntent(intent)
            assertEquals(State.PREPARED, entry.state)
            assertEquals(intent, entry.intent)

            val dispatchedEntry = journal.markDispatched(entry.id)
            assertEquals(State.DISPATCHED, dispatchedEntry.state)

            val acknowledgedEntry = journal.markAcknowledged(dispatchedEntry.id)
            assertEquals(State.ACKNOWLEDGED, acknowledgedEntry.state)
        }
    }

    @Test
    fun `test valid state transition dispatched to uncertain`() {
        runBlocking {
            val intent = "test-intent"
            val entry = journal.recordIntent(intent)
            journal.markDispatched(entry.id)

            val uncertainEntry = journal.markUncertain(entry.id, "network error")
            assertEquals(State.UNCERTAIN, uncertainEntry.state)
            assertEquals("network error", uncertainEntry.metadata?.get("reason"))
        }
    }

    @Test
    fun `test persistence before dispatch`() {
        runBlocking {
            val intent = "persistent-intent"
            val entry = journal.recordIntent(intent)
            
            // Verify it's in the journal while still PREPARED
            val retrieved = journal.get(entry.id)
            assertNotNull(retrieved)
            assertEquals(State.PREPARED, retrieved?.state)
            assertEquals(intent, retrieved?.intent)

            // Now dispatch and verify again
            journal.markDispatched(entry.id)
            val dispatched = journal.get(entry.id)
            assertEquals(State.DISPATCHED, dispatched?.state)
        }
    }

    @Test
    fun `test recovery of pending intents across restart`() {
        runBlocking {
            // 1. Prepared
            val intent1 = "intent-prepared"
            val entry1 = journal.recordIntent(intent1)

            // 2. Dispatched
            val intent2 = "intent-dispatched"
            val entry2 = journal.recordIntent(intent2)
            journal.markDispatched(entry2.id)

            // 3. Uncertain
            val intent3 = "intent-uncertain"
            val entry3 = journal.recordIntent(intent3)
            journal.markUncertain(entry3.id, "timeout")

            // Create new journal instance (simulating restart)
            val newJournal = SubmissionJournal(storageFile)

            // Check all are recovered and pending
            val pending = newJournal.listPending()
            assertEquals(3, pending.size)
            
            val r1 = newJournal.get(entry1.id)
            assertEquals(State.PREPARED, r1?.state)
            
            val r2 = newJournal.get(entry2.id)
            assertEquals(State.DISPATCHED, r2?.state)
            
            val r3 = newJournal.get(entry3.id)
            assertEquals(State.UNCERTAIN, r3?.state)
            assertEquals("timeout", r3?.metadata?.get("reason"))
        }
    }

    @Test
    fun `test invalid state transitions`() {
        runBlocking {
            val entry = journal.recordIntent("test-intent")

            // Cannot acknowledge directly from PREPARED
            assertThrows(IllegalStateTransitionException::class.java) {
                runBlocking { journal.markAcknowledged(entry.id) }
            }

            // Cannot dispatch twice
            journal.markDispatched(entry.id)
            assertThrows(IllegalStateTransitionException::class.java) {
                runBlocking { journal.markDispatched(entry.id) }
            }

            // Cannot acknowledge from UNCERTAIN (if we transition to uncertain first)
            val entry2 = journal.recordIntent("intent-2")
            journal.markUncertain(entry2.id, "reason")
            assertThrows(IllegalStateTransitionException::class.java) {
                runBlocking { journal.markAcknowledged(entry2.id) }
            }
        }
    }

    @Test
    fun `test query by state`() {
        runBlocking {
            journal.recordIntent("intent-1") // PREPARED
            
            val entry2 = journal.recordIntent("intent-2")
            journal.markDispatched(entry2.id) // DISPATCHED

            val entry3 = journal.recordIntent("intent-3")
            journal.markUncertain(entry3.id, "reason") // UNCERTAIN

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
    }

    @Test
    fun `test list pending`() {
        runBlocking {
            journal.recordIntent("intent-1") // PREPARED
            
            val entry2 = journal.recordIntent("intent-2")
            journal.markDispatched(entry2.id) // DISPATCHED

            val entry3 = journal.recordIntent("intent-3")
            journal.markUncertain(entry3.id, "reason") // UNCERTAIN

            // All should be in pending
            var pending = journal.listPending()
            assertEquals(3, pending.size)

            // Acknowledge one
            journal.markAcknowledged(entry2.id)
            pending = journal.listPending()
            assertEquals(2, pending.size)
            assertTrue(pending.none { it.id == entry2.id })
        }
    }

    @Test
    fun `test thread safety`() {
        runBlocking {
            val numConcurrent = 50
            val jobs = (1..numConcurrent).map { i ->
                async(Dispatchers.Default) {
                    val entry = journal.recordIntent("intent-$i")
                    journal.markDispatched(entry.id)
                }
            }
            jobs.awaitAll()

            val dispatchedCount = journal.query(State.DISPATCHED).size
            assertEquals(numConcurrent, dispatchedCount)
        }
    }
}
