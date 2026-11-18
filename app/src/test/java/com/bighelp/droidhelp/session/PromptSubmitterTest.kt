package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.io.TempDir

class PromptSubmitterTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var storageFile: File
    private lateinit var journal: SubmissionJournal
    private lateinit var submitter: PromptSubmitter

    @BeforeEach
    fun setup() {
        storageFile = tempDir.resolve("journal.json").toFile()
        journal = SubmissionJournal(storageFile)
        submitter = PromptSubmitter(journal, FakeStreamingClient(), SecureInput)
    }

    @Test
    fun `test journal-then-submit ordering`() = runTest {
        val prompt = "test prompt"
        val events = mutableListOf<PromptEvent>()

        submitter.submit(prompt).collect { event ->
            events.add(event)
        }

        // Verify that the entry was created and dispatched
        val entries = journal.query(null)
        assertEquals(1, entries.size)
        assertEquals(prompt, entries[0].intent)
        assertEquals(State.ACKNOWLEDGED, entries[0].state)
    }

    @Test
    fun `test admission-on-receipt`() = runTest {
        val prompt = "test prompt"
        val events = mutableListOf<PromptEvent>()

        submitter.submit(prompt).collect { event ->
            events.add(event)
        }

        // Verify that the entry was acknowledged after receiving the receipt
        val entries = journal.query(null)
        assertEquals(1, entries.size)
        assertEquals(State.ACKNOWLEDGED, entries[0].state)

        // Verify that the receipt event was the first event
        assertEquals("receipt", events[0].type)
    }

    @Test
    fun `test ordered event delivery`() = runTest {
        val prompt = "test prompt"
        val events = mutableListOf<PromptEvent>()

        submitter.submit(prompt).collect { event ->
            events.add(event)
        }

        // Verify that events are in the order they were emitted
        assertEquals(3, events.size)
        assertEquals("receipt", events[0].type)
        assertEquals("event1", events[1].type)
        assertEquals("event2", events[2].type)
    }

    @Test
    fun `test submitAndAwait returns correct result`() = runTest {
        val prompt = "test prompt"
        val result = submitter.submitAndAwait(prompt)

        assertEquals(prompt, result.prompt)
        assertTrue(result.admitted)
        assertEquals(3, result.events.size)
        assertEquals("receipt", result.events[0].type)
    }

    @Test
    fun `test no receipt marks entry uncertain`() = runTest {
        val prompt = "test prompt"
        val submitterNoReceipt = PromptSubmitter(journal, FakeStreamingClientNoReceipt(), SecureInput)
        val events = mutableListOf<PromptEvent>()

        submitterNoReceipt.submit(prompt).collect { event ->
            events.add(event)
        }

        // Verify that the entry was marked uncertain
        val entries = journal.query(null)
        assertEquals(1, entries.size)
        assertEquals(State.UNCERTAIN, entries[0].state)
        assertEquals("No receipt was received", entries[0].metadata?.get("reason"))
    }

    private class FakeStreamingClient : StreamingClient {
        override suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent> = flow {
            emit(PromptEvent(
                id = "1",
                type = "receipt",
                content = "Receipt for $prompt",
                timestamp = Instant.now(),
                metadata = mapOf("entryId" to entryId)
            ))
            emit(PromptEvent(
                id = "2",
                type = "event1",
                content = "Event 1",
                timestamp = Instant.now()
            ))
            emit(PromptEvent(
                id = "3",
                type = "event2",
                content = "Event 2",
                timestamp = Instant.now()
            ))
        }

        override suspend fun respondToClarify(response: String) {}

        override suspend fun respondToApproval(decision: ApprovalDecision) {}
    }

    private class FakeStreamingClientNoReceipt : StreamingClient {
        override suspend fun submit(prompt: String, entryId: String): Flow<PromptEvent> = flow {
            emit(PromptEvent(
                id = "1",
                type = "event1",
                content = "Event 1",
                timestamp = Instant.now()
            ))
            emit(PromptEvent(
                id = "2",
                type = "event2",
                content = "Event 2",
                timestamp = Instant.now()
            ))
        }

        override suspend fun respondToClarify(response: String) {}

        override suspend fun respondToApproval(decision: ApprovalDecision) {}
    }
}
