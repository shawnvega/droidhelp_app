package com.bighelp.droidhelp.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end and edge-case unit tests for [HistoryReader] covering simulated live streaming
 * reconciliation, empty pages and empty batches, out-of-order stream bursts, duplicate stream
 * rows, alias promotion rules, and paging/identity stability under concurrent session flows.
 */
class HistoryReaderSimulationTest {

    private companion object {
        const val SESSION_A = "sim-session-a"
        const val SESSION_B = "sim-session-b"
        const val TS_T1 = 1_700_000_010_000L
        const val TS_T2 = 1_700_000_020_000L
        const val TS_T3 = 1_700_000_030_000L
        const val SEQ_ZERO = 0L
        const val SEQ_ONE = 1L
        const val SEQ_TWO = 2L
        const val SEQ_THREE = 3L
        const val SEQ_FOUR = 4L
        const val SEQ_FIVE = 5L
        const val STREAM_SEQ_FOUR = 4L
        const val STREAM_SEQ_FIVE = 5L
        const val STREAM_SEQ_SEVEN = 7L
        const val STREAM_SEQ_EIGHT = 8L
        const val STREAM_SEQ_NINE = 9L
        const val STREAM_SEQ_TEN = 10L
        const val STREAM_SEQ_ELEVEN = 11L
        const val STREAM_SEQ_TWELVE = 12L
        const val STREAM_SEQ_TWENTY = 20L
        const val COUNT_THREE = 3
        const val COUNT_FOUR = 4
        const val COUNT_FIVE = 5
        const val COUNT_SIX = 6
    }

    private fun entry(
        content: String,
        sessionId: String = SESSION_A,
        role: String = "assistant",
        canonicalKey: String? = null,
        canonicalSequence: Long? = null,
        timestamp: Long? = null,
        source: HistoryEntrySource = HistoryEntrySource.CANONICAL_HISTORY,
        isInterim: Boolean = false,
    ): CanonicalHistoryEntry = CanonicalHistoryEntry(
        durableSessionId = sessionId,
        role = role,
        content = content,
        canonicalKey = canonicalKey,
        canonicalSequence = canonicalSequence,
        timestampEpochMillis = timestamp,
        source = source,
        isInterim = isInterim,
    )

    @Test
    fun `empty paged history and empty streaming batches reconcile cleanly`() {
        val bothEmpty = HistoryReader.reconcile(emptyList(), emptyList())
        assertTrue(bothEmpty.entries.isEmpty())
        assertTrue(bothEmpty.diff.isEmpty)
        assertEquals(0, bothEmpty.ignoredDuplicates)
        assertEquals(0, bothEmpty.ignoredOutOfOrder)

        val paged = listOf(
            entry("turn-0", role = "user", canonicalKey = "k-0", canonicalSequence = SEQ_ZERO),
            entry("turn-1", canonicalKey = "k-1", canonicalSequence = SEQ_ONE),
        )
        val noStream = HistoryReader.reconcile(paged, emptyList())
        assertEquals(paged.map { it.identity }, noStream.entries.map { it.identity })
        assertTrue(noStream.diff.isEmpty)
        assertEquals(0, noStream.ignoredDuplicates)

        val missingKey = entry("ghost", canonicalKey = "ghost-1").identity
        val streamOnly = HistoryReader.reconcile(
            pagedHistory = emptyList(),
            streamingRows = listOf(
                StreamingHistoryRow.delete(missingKey, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(paged[0], streamSequence = SEQ_TWO),
            ),
        )
        assertEquals(listOf(paged[0].identity), streamOnly.entries.map { it.identity })
        assertEquals(1, streamOnly.diff.inserts.size)
        assertTrue(streamOnly.diff.deletes.isEmpty())

        val reader = HistoryReader(paged)
        val emptyBatch = reader.reconcileStream(emptyList())
        assertEquals(paged.map { it.identity }, emptyBatch.entries.map { it.identity })
        assertTrue(emptyBatch.diff.isEmpty)
    }

    @Test
    fun `simulated live streaming interleaved with backward paging preserves identity, order, and cursors`() {
        val olderPage = listOf(
            entry("q0", role = "user", canonicalKey = "m-0", canonicalSequence = SEQ_ZERO, timestamp = TS_T1),
            entry("a0", canonicalKey = "m-1", canonicalSequence = SEQ_ONE, timestamp = TS_T1),
            entry("q1", role = "user", canonicalKey = "m-2", canonicalSequence = SEQ_TWO, timestamp = TS_T1),
        )
        val newestPage = listOf(
            entry("a1", canonicalKey = "m-3", canonicalSequence = SEQ_THREE, timestamp = TS_T2),
            entry("q2", role = "user", canonicalKey = "m-4", canonicalSequence = SEQ_FOUR, timestamp = TS_T2),
        )

        val reader = HistoryReader()
        reader.reconcilePage(HistoryPage(newestPage))
        val anchorCursor = requireNotNull(reader.readPage(pageSize = 1, includeStreamed = true).nextCursor)

        // Live assistant response streams token-by-token while older history is prepended mid-stream
        val delta1 = entry(
            content = "Work",
            canonicalKey = "m-5",
            canonicalSequence = SEQ_FIVE,
            timestamp = TS_T3,
            isInterim = true,
        )
        val delta2 = delta1.copy(content = "Working on it")
        val finalStream = delta1.copy(content = "Working on it — done!", isInterim = false)
        val stableStreamId = delta1.identity

        val r1 = reader.reconcileStream(listOf(StreamingHistoryRow.upsert(delta1, streamSequence = SEQ_ONE)))
        assertEquals(listOf(stableStreamId), r1.diff.inserts.map { it.identity })

        // User scrolls up to load older page while streaming continues
        val prependResult = reader.reconcilePage(HistoryPage(olderPage), prepend = true)
        assertEquals(COUNT_THREE, prependResult.diff.inserts.size)
        assertTrue(prependResult.diff.deletes.isEmpty())

        val r2 = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(delta2, streamSequence = SEQ_TWO),
                StreamingHistoryRow.upsert(finalStream, streamSequence = SEQ_THREE),
            ),
        )
        assertEquals(listOf(stableStreamId), r2.diff.updates.map { it.identity })
        assertFalse(requireNotNull(reader.findByIdentity(stableStreamId)).isInterim)

        // Canonical tail page arrives and upgrades the streamed message in place without duplication
        val canonicalFinal = finalStream.copy(source = HistoryEntrySource.CANONICAL_HISTORY, pageCursor = "tail-1")
        val upgrade = reader.reconcilePage(HistoryPage(listOf(newestPage.last(), canonicalFinal)))
        assertEquals(1, upgrade.ignoredDuplicates)
        assertEquals(listOf(stableStreamId), upgrade.diff.updates.map { it.identity })
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, reader.findByIdentity(stableStreamId)?.source)

        assertEquals(
            listOf("m-0", "m-1", "m-2", "m-3", "m-4", "m-5"),
            reader.entries().map { it.canonicalKey },
        )
        assertEquals(COUNT_SIX, reader.size)

        // Cursor acquired before prepending and streaming still continues cleanly from m-3
        val continued = reader.readPage(pageSize = COUNT_FOUR, cursor = anchorCursor, includeStreamed = true)
        assertEquals(listOf("m-4", "m-5"), continued.entries.map { it.canonicalKey })
        assertFalse(continued.hasMore)
    }

    @Test
    fun `duplicate stream upserts, deletes, and page rows are idempotent and advance stream watermark`() {
        val reader = HistoryReader()
        val m0 = entry("streamed-body", canonicalKey = "dup-0", canonicalSequence = SEQ_ZERO, isInterim = false)
        val m1 = entry("canon-body", canonicalKey = "dup-1", canonicalSequence = SEQ_ONE)
        reader.reconcilePage(HistoryPage(listOf(m1)))

        // Duplicate upsert with strictly higher streamSequence advances watermark and blocks mid-gap stale writes
        val dupSequenceResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(m0, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(m0, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(m0, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(m0.copy(content = "late-gap"), streamSequence = STREAM_SEQ_SEVEN),
                StreamingHistoryRow.upsert(m0),
            ),
        )
        assertEquals(1, dupSequenceResult.diff.inserts.size)
        assertEquals(COUNT_THREE, dupSequenceResult.ignoredDuplicates)
        assertEquals(1, dupSequenceResult.ignoredOutOfOrder)
        assertEquals("streamed-body", reader.findByIdentity(m0.identity)?.content)

        // Duplicate deletes (null seq, equal seq, higher seq) and stale lower-seq delete
        val dupDeletes = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.delete(m0.identity, streamSequence = STREAM_SEQ_ELEVEN),
                StreamingHistoryRow.delete(m0.identity, streamSequence = STREAM_SEQ_ELEVEN),
                StreamingHistoryRow.delete(m0.identity, streamSequence = STREAM_SEQ_TWELVE),
                StreamingHistoryRow.delete(m0.identity),
                StreamingHistoryRow.delete(m0.identity, streamSequence = STREAM_SEQ_NINE),
            ),
        )
        assertEquals(listOf(m0.identity), dupDeletes.diff.deletes.map { it.identity })
        assertEquals(COUNT_THREE, dupDeletes.ignoredDuplicates)
        assertEquals(1, dupDeletes.ignoredOutOfOrder)

        // Canonical pages with duplicate rows (forward and prepend) and transient-only changes
        val older = entry("older", canonicalKey = "dup-old", canonicalSequence = SEQ_ZERO)
        val prependDup = reader.reconcilePage(HistoryPage(listOf(older, older)), prepend = true)
        assertEquals(1, prependDup.ignoredDuplicates)
        assertEquals(listOf("dup-old", "dup-1"), prependDup.entries.map { it.canonicalKey })

        val transientOnly = reader.reconcilePage(
            HistoryPage(listOf(m1.copy(pageCursor = "p-99", pageOffset = COUNT_FIVE, runtimeSessionId = "rt-new"))),
        )
        assertTrue(transientOnly.diff.isEmpty)
        assertEquals(1, transientOnly.ignoredDuplicates)
    }

    @Test
    fun `finalizing interim row with same content or assigning late canonicalSequence is accepted`() {
        val reader = HistoryReader(listOf(entry("second", canonicalKey = "k-2", canonicalSequence = SEQ_TWO)))
        val interim = entry("same text", canonicalKey = "k-1", canonicalSequence = null, isInterim = true)

        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(interim, streamSequence = SEQ_ONE)))
        assertEquals(listOf("k-2", "k-1"), reader.entries().map { it.canonicalKey })

        // Same content with isInterim = false is NOT a duplicate; it finalizes the entry
        val finalized = interim.copy(isInterim = false)
        val finalizeResult = reader.reconcileStream(
            listOf(StreamingHistoryRow.upsert(finalized, streamSequence = SEQ_TWO)),
        )
        assertEquals(0, finalizeResult.ignoredDuplicates)
        assertEquals(listOf(interim.identity), finalizeResult.diff.updates.map { it.identity })
        assertFalse(requireNotNull(reader.findByIdentity(interim.identity)).isInterim)

        // Same content with newly discovered canonicalSequence repositions into chronological order
        val sequenced = finalized.copy(canonicalSequence = SEQ_ONE)
        val seqResult = reader.reconcileStream(
            listOf(StreamingHistoryRow.upsert(sequenced, streamSequence = SEQ_THREE)),
        )
        assertEquals(0, seqResult.ignoredDuplicates)
        assertEquals(listOf("k-1", "k-2"), seqResult.entries.map { it.canonicalKey })
        assertEquals(1, seqResult.diff.updates.single().previousIndex)
        assertEquals(0, seqResult.diff.updates.single().currentIndex)
    }

    @Test
    fun `out-of-order stream bursts handle delete-before-upsert, clock skew, interim regression, and repositioning`() {
        val reader = HistoryReader()

        // Delete arriving BEFORE delayed upsert blocks the stale upsert, while newer upsert succeeds
        val racingKey = entry("raced", canonicalKey = "race-1", canonicalSequence = SEQ_ZERO)
        val raceResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.delete(racingKey.identity, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(racingKey.copy(content = "delayed"), streamSequence = STREAM_SEQ_FIVE),
                StreamingHistoryRow.upsert(racingKey.copy(content = "reborn"), streamSequence = STREAM_SEQ_ELEVEN),
            ),
        )
        assertEquals(1, raceResult.ignoredOutOfOrder)
        assertEquals(listOf(racingKey.identity), raceResult.diff.inserts.map { it.identity })
        assertEquals("reborn", reader.findByIdentity(racingKey.identity)?.content)

        // Explicit higher streamSequence accepts clock-skewed older timestamp, whereas null sequence rejects it
        val clocked = entry("v1", canonicalKey = "clk-1", canonicalSequence = SEQ_ONE, timestamp = TS_T3)
        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(clocked, streamSequence = STREAM_SEQ_FOUR)))
        val skewedWithSeq = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(
                    clocked.copy(content = "v2-skewed", timestampEpochMillis = TS_T1),
                    streamSequence = STREAM_SEQ_EIGHT,
                ),
            ),
        )
        assertEquals(0, skewedWithSeq.ignoredOutOfOrder)
        assertEquals("v2-skewed", reader.findByIdentity(clocked.identity)?.content)

        // Stale interim frame is rejected even with a strictly higher streamSequence
        val lateInterim = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(
                    clocked.copy(content = "late-interim", isInterim = true),
                    streamSequence = STREAM_SEQ_TWENTY,
                ),
            ),
        )
        assertTrue(lateInterim.diff.isEmpty)
        assertEquals(1, lateInterim.ignoredOutOfOrder)

        // Updating canonicalSequence to a higher sequence repositions the entry forward
        val m2 = entry("seq-2", canonicalKey = "k-2", canonicalSequence = SEQ_TWO)
        val m3 = entry("seq-3", canonicalKey = "k-3", canonicalSequence = SEQ_THREE)
        reader.reconcilePage(HistoryPage(listOf(m2, m3)))
        val movedForward = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(
                    clocked.copy(content = "moved-to-end", canonicalSequence = SEQ_FIVE),
                    streamSequence = STREAM_SEQ_TWENTY,
                ),
            ),
        )
        assertEquals(
            listOf("race-1", "k-2", "k-3", "clk-1"),
            movedForward.entries.map { it.canonicalKey },
        )
    }

    @Test
    fun `net diff collapses within a single batch and supports multi-hop and cyclic alias chains`() {
        val existing = entry(
            content = "initial",
            canonicalKey = "k-0",
            canonicalSequence = SEQ_ZERO,
            source = HistoryEntrySource.STREAMED,
        )
        val reader = HistoryReader()
        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(existing, streamSequence = SEQ_ONE)))

        val transient = entry("draft", canonicalKey = "k-temp", canonicalSequence = SEQ_ONE, isInterim = true)
        val batch = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(transient, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(transient.copy(content = "draft-2"), streamSequence = SEQ_TWO),
                StreamingHistoryRow.delete(transient.identity, streamSequence = SEQ_THREE),
                StreamingHistoryRow.upsert(existing.copy(content = "changed"), streamSequence = SEQ_TWO),
                StreamingHistoryRow.upsert(existing.copy(content = "initial"), streamSequence = SEQ_THREE),
            ),
        )
        assertTrue(batch.diff.isEmpty)
        assertEquals(listOf(existing.identity), batch.entries.map { it.identity })

        // Multi-hop alias chain A -> B -> C resolves in HistoryReader.diff
        val hopA = entry("chain-body", source = HistoryEntrySource.STREAMED)
        val hopB = hopA.copy(canonicalSequence = SEQ_TWO)
        val hopC = hopB.copy(canonicalKey = "srv-chain", source = HistoryEntrySource.CANONICAL_HISTORY)
        val chainedDiff = HistoryReader.diff(
            previous = listOf(hopA),
            current = listOf(hopC),
            identityAliases = mapOf(hopA.identity to hopB.identity, hopB.identity to hopC.identity),
        )
        assertTrue(chainedDiff.inserts.isEmpty())
        assertTrue(chainedDiff.deletes.isEmpty())
        val update = chainedDiff.updates.single()
        assertEquals(hopC.identity, update.identity)
        assertEquals(hopA.identity, update.previousIdentity)

        // Cyclic aliases terminate safely without hanging
        val cycleDiff = HistoryReader.diff(
            previous = listOf(hopA),
            current = listOf(hopB),
            identityAliases = mapOf(hopA.identity to hopB.identity, hopB.identity to hopA.identity),
        )
        assertFalse(cycleDiff.isEmpty)
    }

    @Test
    fun `provisional promotion enforces scheme hierarchy, sequence matching, and latest repeated match`() {
        val reader = HistoryReader()

        // CONTENT_HASH streamed promotes cleanly to POSITIONED_CONTENT_HASH canonical
        val unkeyedStream = entry("promoted-to-seq", source = HistoryEntrySource.STREAMED)
        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(unkeyedStream)))
        val positionedCanonical = unkeyedStream.copy(canonicalSequence = SEQ_ONE)
        val promoted = reader.reconcilePage(HistoryPage(listOf(positionedCanonical)))
        assertEquals(1, reader.size)
        assertEquals(unkeyedStream.identity, promoted.diff.updates.single().previousIdentity)
        assertEquals(positionedCanonical.identity, reader.resolveIdentity(unkeyedStream.identity))
        assertNotNull(reader.findByStableId(unkeyedStream.stableId))

        // POSITIONED_CONTENT_HASH at seq=2 must NOT merge with IMMUTABLE_KEY canonical at seq=5
        val repeatedAtSeq2 = entry("yes", canonicalSequence = SEQ_TWO, source = HistoryEntrySource.STREAMED)
        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(repeatedAtSeq2)))
        val canonicalYesSeq5 = entry("yes", canonicalKey = "srv-yes-5", canonicalSequence = SEQ_FIVE)
        reader.reconcilePage(HistoryPage(listOf(canonicalYesSeq5)))
        assertEquals(COUNT_THREE, reader.size)
        assertNotEquals(canonicalYesSeq5.identity, reader.resolveIdentity(repeatedAtSeq2.identity))

        // Reverse matching: positioned stream with mismatched seq does NOT alias; matching seq DOES alias
        val streamYesSeq4 = entry("yes", canonicalSequence = SEQ_FOUR, source = HistoryEntrySource.STREAMED)
        val streamYesSeq5 = entry("yes", canonicalSequence = SEQ_FIVE, source = HistoryEntrySource.STREAMED)
        val reverseResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(streamYesSeq4),
                StreamingHistoryRow.upsert(streamYesSeq5),
            ),
        )
        assertEquals(listOf(streamYesSeq4.identity), reverseResult.diff.inserts.map { it.identity })
        assertEquals(1, reverseResult.ignoredDuplicates)
        assertEquals(canonicalYesSeq5.identity, reader.resolveIdentity(streamYesSeq5.identity))

        // Repeated identical canonical messages at different turns: unkeyed stream aliases to the latest match
        val dupReader = HistoryReader(
            listOf(
                entry("ok", canonicalKey = "ok-1", canonicalSequence = SEQ_ONE),
                entry("ok", canonicalKey = "ok-5", canonicalSequence = SEQ_FIVE),
            ),
        )
        val unkeyedOk = entry("ok", source = HistoryEntrySource.STREAMED)
        val okResult = dupReader.reconcileStream(listOf(StreamingHistoryRow.upsert(unkeyedOk)))
        assertEquals(1, okResult.ignoredDuplicates)
        assertEquals(2, dupReader.size)
        assertEquals(
            entry("ok", canonicalKey = "ok-5", canonicalSequence = SEQ_FIVE).identity,
            dupReader.resolveIdentity(unkeyedOk.identity),
        )
    }

    @Test
    fun `reconcileStreamed protects canonical entries while reconcileStream permits authoritative edits`() {
        val canonical = entry("canonical truth", canonicalKey = "k-1", canonicalSequence = SEQ_ONE, timestamp = TS_T1)
        val edited = canonical.copy(content = "streamed edit", timestampEpochMillis = TS_T2, isInterim = false)

        val reader = HistoryReader(listOf(canonical))
        val returned = reader.reconcileStreamed(edited)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, returned.source)
        assertEquals("canonical truth", returned.content)

        val streamUpdated = reader.reconcileStream(
            listOf(StreamingHistoryRow.upsert(edited, streamSequence = SEQ_ONE)),
        )
        assertEquals(listOf(canonical.identity), streamUpdated.diff.updates.map { it.identity })
        assertEquals("streamed edit", reader.findByIdentity(canonical.identity)?.content)
        assertEquals(HistoryEntrySource.STREAMED, reader.findByIdentity(canonical.identity)?.source)
    }

    @Test
    fun `simulated reconnect replay storm across sessions yields zero duplicates and exact order`() {
        val sessionACanon = (0 until COUNT_FOUR).map { i ->
            entry("a-$i", SESSION_A, if (i % 2 == 0) "user" else "assistant", "a-$i", i.toLong(), TS_T1 + i)
        }
        val sessionBCanon = (0 until 2).map { i ->
            entry("b-$i", SESSION_B, canonicalKey = "b-$i", canonicalSequence = i.toLong(), timestamp = TS_T1 + i)
        }

        val reader = HistoryReader(sessionACanon + sessionBCanon)
        val a4 = entry("a-4-final", SESSION_A, canonicalKey = "a-4", canonicalSequence = SEQ_FOUR, timestamp = TS_T3)
        val a5 = entry("a-5-final", SESSION_A, canonicalKey = "a-5", canonicalSequence = SEQ_FIVE, timestamp = TS_T3)
        val b2 = entry("b-2-final", SESSION_B, canonicalKey = "b-2", canonicalSequence = SEQ_TWO, timestamp = TS_T3)

        // Replay storm: duplicates, stale interims, out-of-order new turns, and stale deletes
        val storm = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(sessionACanon[1].copy(content = "stale-interim", isInterim = true)),
                StreamingHistoryRow.upsert(sessionACanon[2].copy(runtimeSessionId = "rt-reconnect")),
                StreamingHistoryRow.upsert(a5, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(a5.copy(content = "a-5-old"), streamSequence = STREAM_SEQ_SEVEN),
                StreamingHistoryRow.upsert(a5, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(a4.copy(content = "a-4-part", isInterim = true), streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(a4, streamSequence = SEQ_TWO),
                StreamingHistoryRow.upsert(a4.copy(content = "a-4-late", isInterim = true), streamSequence = SEQ_THREE),
                StreamingHistoryRow.upsert(b2, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(b2, streamSequence = SEQ_ONE),
                StreamingHistoryRow.delete(a5.identity, streamSequence = STREAM_SEQ_EIGHT),
            ),
        )

        val finalA = storm.entries.filter { it.durableSessionId == SESSION_A }
        val finalB = storm.entries.filter { it.durableSessionId == SESSION_B }
        assertEquals(listOf("a-0", "a-1", "a-2", "a-3", "a-4", "a-5"), finalA.map { it.canonicalKey })
        assertEquals(listOf("b-0", "b-1", "b-2"), finalB.map { it.canonicalKey })
        assertEquals(storm.entries.size, storm.entries.map { it.identity }.toSet().size)
        assertEquals(COUNT_THREE, storm.diff.inserts.size)
        assertEquals(COUNT_THREE, storm.ignoredDuplicates)
        assertEquals(COUNT_FOUR, storm.ignoredOutOfOrder)
        assertNull(reader.findByIdentity(entry("never", canonicalKey = "missing").identity))
    }
}
