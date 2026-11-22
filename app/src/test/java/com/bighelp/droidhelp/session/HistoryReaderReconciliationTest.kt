package com.bighelp.droidhelp.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class HistoryReaderReconciliationTest {

    private companion object {
        const val SESSION_A = "durable-session-a"
        const val SESSION_B = "durable-session-b"
        const val TS_OLD = 1_700_000_001_000L
        const val TS_MID = 1_700_000_002_000L
        const val TS_NEW = 1_700_000_003_000L
        const val CLOCK_SKEW_MS = 50L
        const val SEQ_ZERO = 0L
        const val SEQ_ONE = 1L
        const val SEQ_TWO = 2L
        const val SEQ_THREE = 3L
        const val SEQ_FOUR = 4L
        const val SEQ_FIVE = 5L
        const val SEQ_SIX = 6L
        const val STREAM_SEQ_FIVE = 5L
        const val STREAM_SEQ_SEVEN = 7L
        const val STREAM_SEQ_EIGHT = 8L
        const val STREAM_SEQ_TEN = 10L
        const val STREAM_SEQ_TWELVE = 12L
        const val COUNT_THREE = 3
        const val COUNT_FOUR = 4
        const val CONCURRENT_THREADS = 4
        const val CONCURRENT_ITEMS = 24
    }

    private fun entry(
        content: String,
        sessionId: String = SESSION_A,
        role: String = "assistant",
        canonicalKey: String? = null,
        canonicalSequence: Long? = null,
        attributes: Map<String, String> = emptyMap(),
        timestamp: Long? = null,
        source: HistoryEntrySource = HistoryEntrySource.CANONICAL_HISTORY,
        isInterim: Boolean = false,
    ): CanonicalHistoryEntry = CanonicalHistoryEntry(
        durableSessionId = sessionId,
        role = role,
        content = content,
        canonicalKey = canonicalKey,
        canonicalSequence = canonicalSequence,
        canonicalAttributes = attributes,
        timestampEpochMillis = timestamp,
        source = source,
        isInterim = isInterim,
    )

    @Test
    fun `reconcile merges streaming rows into paged history with insert, update, and delete diffs`() {
        val m0 = entry("Hello", role = "user", canonicalKey = "msg-0", canonicalSequence = SEQ_ZERO, timestamp = TS_OLD)
        val m1 = entry("Draft answer", canonicalKey = "msg-1", canonicalSequence = SEQ_ONE, timestamp = TS_OLD)
        val m2 = entry("Temporary note", canonicalKey = "msg-2", canonicalSequence = SEQ_TWO, timestamp = TS_OLD)

        val duplicateM0 = m0.copy(
            source = HistoryEntrySource.STREAMED,
            runtimeSessionId = "rt-live-1",
            timestampEpochMillis = TS_OLD - CLOCK_SKEW_MS,
        )
        val updatedM1 = m1.copy(
            content = "Edited answer",
            canonicalSequence = null,
            canonicalAttributes = mapOf("edited" to "true"),
            timestampEpochMillis = TS_NEW,
            source = HistoryEntrySource.STREAMED,
            isInterim = false,
        )
        val m3Delta = entry(
            "New ", canonicalKey = "msg-3", canonicalSequence = SEQ_THREE, timestamp = TS_MID, isInterim = true,
        )
        val m3Final = m3Delta.copy(content = "New reply!", timestampEpochMillis = TS_NEW, isInterim = false)

        val result = HistoryReader.reconcile(
            pagedHistory = listOf(m0, m1, m2),
            streamingRows = listOf(
                StreamingHistoryRow.upsert(duplicateM0, streamSequence = SEQ_ONE),
                StreamingHistoryRow.upsert(updatedM1, streamSequence = SEQ_TWO),
                StreamingHistoryRow.delete(m2.identity, streamSequence = SEQ_THREE),
                StreamingHistoryRow.upsert(m3Delta, streamSequence = SEQ_FOUR),
                StreamingHistoryRow.upsert(m3Final, streamSequence = SEQ_FIVE),
            ),
        )

        assertEquals(listOf(m0.identity, m1.identity, m3Final.identity), result.entries.map { it.identity })
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, result.entries[0].source)
        assertEquals("Edited answer", result.entries[1].content)
        assertEquals(SEQ_ONE, result.entries[1].canonicalSequence)
        assertEquals(mapOf("edited" to "true"), result.entries[1].canonicalAttributes)
        assertEquals("New reply!", result.entries[2].content)
        assertFalse(result.entries[2].isInterim)
        assertEquals(1, result.ignoredDuplicates)
        assertEquals(0, result.ignoredOutOfOrder)

        val del = result.diff.deletes.single()
        assertEquals(m2.identity, del.identity)
        assertEquals(2, del.previousIndex)
        assertNull(del.currentIndex)
        val upd = result.diff.updates.single()
        assertEquals(m1.identity, upd.identity)
        assertEquals("Draft answer", upd.previous?.content)
        assertEquals("Edited answer", upd.current?.content)
        assertEquals(1, upd.previousIndex)
        assertEquals(1, upd.currentIndex)
        val ins = result.diff.inserts.single()
        assertEquals(m3Final.identity, ins.identity)
        assertNull(ins.previousIndex)
        assertEquals(2, ins.currentIndex)
    }

    @Test
    fun `matches streaming rows across all identity schemes including reverse provisional matching`() {
        val canonicalKeyed = entry(
            content = "Keyed canonical reply",
            canonicalKey = "srv-10",
            canonicalSequence = SEQ_ONE,
            timestamp = TS_MID,
        )
        val canonicalSeq = entry(
            content = "Positioned canonical reply",
            canonicalSequence = SEQ_TWO,
            timestamp = TS_MID,
        )
        val canonicalHash = entry(content = "Content-hashed reply", timestamp = TS_MID)
        val reader = HistoryReader(listOf(canonicalKeyed, canonicalSeq, canonicalHash))

        val streamPositionedForKeyed = entry(
            content = "Keyed canonical reply",
            canonicalSequence = SEQ_ONE,
            source = HistoryEntrySource.STREAMED,
        )
        val streamUnkeyedForSeq = entry(
            content = "Positioned canonical reply",
            source = HistoryEntrySource.STREAMED,
        )
        val streamHashDuplicate = entry(
            content = "Content-hashed reply",
            source = HistoryEntrySource.STREAMED,
            timestamp = TS_OLD,
        )

        val duplicateResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(streamPositionedForKeyed),
                StreamingHistoryRow.upsert(streamUnkeyedForSeq),
                StreamingHistoryRow.upsert(streamHashDuplicate),
            ),
        )

        assertEquals(COUNT_THREE, reader.size)
        assertTrue(duplicateResult.diff.isEmpty)
        assertEquals(COUNT_THREE, duplicateResult.ignoredDuplicates)
        assertEquals(canonicalKeyed.identity, reader.resolveIdentity(streamPositionedForKeyed.identity))
        assertEquals(canonicalSeq.identity, reader.resolveIdentity(streamUnkeyedForSeq.identity))
        assertTrue(reader.entries().all { it.source == HistoryEntrySource.CANONICAL_HISTORY })

        val deleteViaProvisional = reader.reconcileStream(
            listOf(StreamingHistoryRow.delete(streamPositionedForKeyed)),
        )
        assertEquals(2, reader.size)
        assertEquals(canonicalKeyed.identity, deleteViaProvisional.diff.deletes.single().identity)
        assertNull(reader.findByIdentity(canonicalKeyed.identity))
        assertNull(reader.findByIdentity(streamPositionedForKeyed.identity))
    }

    @Test
    fun `out-of-order streaming rows by canonicalSequence merge in chronological order per session`() {
        val m0 = entry("turn-0", canonicalKey = "a-0", canonicalSequence = SEQ_ZERO)
        val m1 = entry("turn-1", canonicalKey = "a-1", canonicalSequence = SEQ_ONE)
        val sessionB0 = entry("b-0", sessionId = SESSION_B, canonicalKey = "b-0", canonicalSequence = SEQ_ZERO)
        val sessionB2 = entry("b-2", sessionId = SESSION_B, canonicalKey = "b-2", canonicalSequence = SEQ_TWO)
        val reader = HistoryReader(listOf(m0, sessionB0, m1, sessionB2))

        val result = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(entry("turn-4", canonicalKey = "a-4", canonicalSequence = SEQ_FOUR)),
                StreamingHistoryRow.upsert(entry("turn-2", canonicalKey = "a-2", canonicalSequence = SEQ_TWO)),
                StreamingHistoryRow.upsert(entry("turn-5", canonicalKey = "a-5", canonicalSequence = SEQ_FIVE)),
                StreamingHistoryRow.upsert(entry("turn-3", canonicalKey = "a-3", canonicalSequence = SEQ_THREE)),
                StreamingHistoryRow.upsert(
                    entry("b-1", sessionId = SESSION_B, canonicalKey = "b-1", canonicalSequence = SEQ_ONE),
                ),
            ),
        )

        val sessionAEntries = result.entries.filter { it.durableSessionId == SESSION_A }
        val sessionBEntries = result.entries.filter { it.durableSessionId == SESSION_B }
        assertEquals(
            listOf(SEQ_ZERO, SEQ_ONE, SEQ_TWO, SEQ_THREE, SEQ_FOUR, SEQ_FIVE),
            sessionAEntries.map { it.canonicalSequence },
        )
        assertEquals(listOf(SEQ_ZERO, SEQ_ONE, SEQ_TWO), sessionBEntries.map { it.canonicalSequence })
        assertEquals(SEQ_FIVE.toInt(), result.diff.inserts.size)

        val lateReader = HistoryReader(listOf(entry("seq-2", canonicalKey = "k-2", canonicalSequence = SEQ_TWO)))
        val unsequenced = entry("seq-1-draft", canonicalKey = "k-1", isInterim = true)
        lateReader.reconcileStream(listOf(StreamingHistoryRow.upsert(unsequenced, streamSequence = SEQ_ONE)))
        assertEquals(listOf("k-2", "k-1"), lateReader.entries().map { it.canonicalKey })

        val sequenced = unsequenced.copy(content = "seq-1-final", canonicalSequence = SEQ_ONE, isInterim = false)
        val repositioned = lateReader.reconcileStream(
            listOf(StreamingHistoryRow.upsert(sequenced, streamSequence = SEQ_TWO)),
        )
        assertEquals(listOf("k-1", "k-2"), repositioned.entries.map { it.canonicalKey })
        assertEquals(1, repositioned.diff.updates.single().previousIndex)
        assertEquals(0, repositioned.diff.updates.single().currentIndex)
    }

    @Test
    fun `stale out-of-order stream frames, interim regressions, and older timestamps are safely ignored`() {
        val canonical = entry("Final canonical", canonicalKey = "c-1", canonicalSequence = SEQ_ZERO, timestamp = TS_NEW)
        val reader = HistoryReader(listOf(canonical))

        val staleCanonicalAttempt = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(
                    canonical.copy(content = "Partial", isInterim = true, timestampEpochMillis = TS_NEW),
                ),
                StreamingHistoryRow.upsert(
                    canonical.copy(content = "Older final", isInterim = false, timestampEpochMillis = TS_OLD),
                ),
            ),
        )
        assertTrue(staleCanonicalAttempt.diff.isEmpty)
        assertEquals(2, staleCanonicalAttempt.ignoredOutOfOrder)
        assertEquals("Final canonical", reader.findByIdentity(canonical.identity)?.content)

        val liveV2 = entry(
            content = "Hello, world",
            canonicalKey = "live-1",
            canonicalSequence = SEQ_ONE,
            timestamp = TS_MID,
            isInterim = true,
        )
        val liveV1Stale = liveV2.copy(content = "Hel", timestampEpochMillis = TS_OLD)
        val liveConflictSameSeq = liveV2.copy(content = "Conflicting frame")
        val liveFinal = liveV2.copy(content = "Hello, world!", timestampEpochMillis = TS_NEW, isInterim = false)
        val liveInterimAfterFinal = liveV2.copy(content = "Late interim", isInterim = true)

        val streamResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(liveV2, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(liveV1Stale, streamSequence = STREAM_SEQ_SEVEN),
                StreamingHistoryRow.upsert(liveV2, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(liveConflictSameSeq, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(liveFinal, streamSequence = STREAM_SEQ_TWELVE),
                StreamingHistoryRow.upsert(liveInterimAfterFinal),
            ),
        )

        assertEquals(2, streamResult.entries.size)
        assertEquals("Hello, world!", reader.findByIdentity(liveV2.identity)?.content)
        assertFalse(requireNotNull(reader.findByIdentity(liveV2.identity)).isInterim)
        assertEquals(1, streamResult.diff.inserts.size)
        assertEquals(1, streamResult.ignoredDuplicates)
        assertEquals(COUNT_THREE, streamResult.ignoredOutOfOrder)

        val timestampRegression = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(
                    liveFinal.copy(content = "Stale timestamp edit", timestampEpochMillis = TS_OLD),
                ),
            ),
        )
        assertTrue(timestampRegression.diff.isEmpty)
        assertEquals(1, timestampRegression.ignoredOutOfOrder)
    }

    @Test
    fun `delete tombstones block stale resurrection and handle duplicate or unknown deletes gracefully`() {
        val m0 = entry("Keep me", canonicalKey = "k-0", canonicalSequence = SEQ_ZERO)
        val m1 = entry("Delete me", canonicalKey = "k-1", canonicalSequence = SEQ_ONE)
        val unknown = entry("Never existed", canonicalKey = "k-unknown", canonicalSequence = SEQ_FIVE)
        val reader = HistoryReader(listOf(m0, m1))

        val deleteResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.delete(m1.identity, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.upsert(m1.copy(content = "Stale resurrection"), streamSequence = STREAM_SEQ_EIGHT),
                StreamingHistoryRow.delete(m1.identity, streamSequence = STREAM_SEQ_TEN),
                StreamingHistoryRow.delete(unknown.identity),
            ),
        )
        assertEquals(listOf(m0.identity), deleteResult.entries.map { it.identity })
        assertEquals(m1.identity, deleteResult.diff.deletes.single().identity)
        assertEquals(1, deleteResult.ignoredOutOfOrder)
        assertEquals(1, deleteResult.ignoredDuplicates)

        val reinsertResult = reader.reconcileStream(
            listOf(
                StreamingHistoryRow.upsert(m1.copy(content = "Re-created"), streamSequence = STREAM_SEQ_TWELVE),
                StreamingHistoryRow.delete(m1.identity, streamSequence = STREAM_SEQ_EIGHT),
            ),
        )
        assertEquals(2, reinsertResult.entries.size)
        assertEquals("Re-created", reader.findByIdentity(m1.identity)?.content)
        assertEquals(1, reinsertResult.diff.inserts.size)
        assertEquals(1, reinsertResult.ignoredOutOfOrder)

        val promoReader = HistoryReader()
        val provisional = entry("Provisional body", source = HistoryEntrySource.STREAMED)
        promoReader.reconcileStreamed(provisional)
        val canonical = provisional.copy(canonicalKey = "srv-promo", canonicalSequence = SEQ_ZERO)
        promoReader.reconcilePage(HistoryPage(listOf(canonical)))

        val aliasDelete = promoReader.reconcileStream(
            listOf(
                StreamingHistoryRow.delete(provisional.identity, streamSequence = STREAM_SEQ_FIVE),
                StreamingHistoryRow.upsert(provisional, streamSequence = SEQ_TWO),
            ),
        )
        assertEquals(0, promoReader.size)
        assertEquals(canonical.identity, aliasDelete.diff.deletes.single().identity)
        assertEquals(1, aliasDelete.ignoredOutOfOrder)
    }

    @Test
    fun `reconcilePage detects canonical inserts, updates, and streamed-to-canonical promotions`() {
        val a0 = entry("a-0", sessionId = SESSION_A, canonicalKey = "a-0", canonicalSequence = SEQ_ZERO)
        val a1 = entry("a-1", sessionId = SESSION_A, canonicalKey = "a-1", canonicalSequence = SEQ_ONE)
        val a2 = entry("a-2-v1", sessionId = SESSION_A, canonicalKey = "a-2", canonicalSequence = SEQ_TWO)
        val reader = HistoryReader(listOf(a0, a1, a2))

        val s3 = entry("a-3-interim", canonicalKey = "a-3", canonicalSequence = SEQ_THREE, isInterim = true)
        val s4Provisional = entry("a-4-body", source = HistoryEntrySource.STREAMED)
        reader.reconcileStream(
            listOf(StreamingHistoryRow.upsert(s3), StreamingHistoryRow.upsert(s4Provisional)),
        )

        val a2Updated = a2.copy(content = "a-2-v2")
        val c3Final = s3.copy(content = "a-3-final", isInterim = false)
        val c4Canonical = s4Provisional.copy(canonicalKey = "a-4", canonicalSequence = SEQ_FOUR)
        val c6New = entry("a-6-new", canonicalKey = "a-6", canonicalSequence = SEQ_SIX)

        val result = reader.reconcilePage(HistoryPage(listOf(a1, a2Updated, c3Final, c4Canonical, c6New)))

        assertEquals(1, result.ignoredDuplicates)
        assertTrue(result.diff.deletes.isEmpty())
        assertEquals(listOf(c6New.identity), result.diff.inserts.map { it.identity })
        assertEquals(COUNT_THREE, result.diff.updates.size)
        val promotedChange = result.diff.updates.first { it.identity == c4Canonical.identity }
        assertEquals(s4Provisional.identity, promotedChange.previousIdentity)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, promotedChange.current?.source)
        assertNotNull(reader.findByIdentity(a0.identity))
    }

    @Test
    fun `reconcilePage snapshot pruning detects scoped deletions and restores stream-tombstoned rows`() {
        val a0 = entry("a-0", sessionId = SESSION_A, canonicalKey = "a-0", canonicalSequence = SEQ_ZERO)
        val a1 = entry("a-1", sessionId = SESSION_A, canonicalKey = "a-1", canonicalSequence = SEQ_ONE)
        val a2 = entry("a-2", sessionId = SESSION_A, canonicalKey = "a-2", canonicalSequence = SEQ_TWO)
        val b0 = entry("b-0", sessionId = SESSION_B, canonicalKey = "b-0", canonicalSequence = SEQ_ZERO)
        val sLive = entry("a-live", sessionId = SESSION_A, canonicalKey = "a-live", isInterim = true)

        val reader = HistoryReader(listOf(a0, a1, a2, b0))
        reader.reconcileStream(listOf(StreamingHistoryRow.upsert(sLive)))

        val snapshotResult = reader.reconcilePage(
            page = HistoryPage(listOf(a1, a2)),
            pruneMissingInScope = true,
            durableSessionId = SESSION_A,
            retainUnmatchedStreamed = true,
        )
        assertEquals(setOf(a0.identity), snapshotResult.diff.deletes.map { it.identity }.toSet())
        assertNotNull(reader.findByIdentity(b0.identity))
        assertNotNull(reader.findByIdentity(sLive.identity))

        val rollbackResult = reader.reconcilePage(
            page = HistoryPage(listOf(a1)),
            pruneMissingInScope = true,
            durableSessionId = SESSION_A,
            retainUnmatchedStreamed = false,
        )
        assertEquals(
            setOf(a2.identity, sLive.identity),
            rollbackResult.diff.deletes.map { it.identity }.toSet(),
        )
        assertNotNull(reader.findByIdentity(b0.identity))

        reader.reconcileStream(listOf(StreamingHistoryRow.delete(a1.identity, streamSequence = STREAM_SEQ_TEN)))
        assertNull(reader.findByIdentity(a1.identity))
        val restored = reader.reconcilePage(HistoryPage(listOf(a1)))
        assertNotNull(reader.findByIdentity(a1.identity))
        assertEquals(listOf(a1.identity), restored.diff.inserts.map { it.identity })
    }

    @Test
    fun `reconcileEntries and standalone diff handle deduplication, aliases, and transient fields`() {
        val e0 = entry("zero", canonicalKey = "k-0", canonicalSequence = SEQ_ZERO)
        val e1 = entry("one", canonicalKey = "k-1", canonicalSequence = SEQ_ONE)
        val e2 = entry("two", canonicalKey = "k-2", canonicalSequence = SEQ_TWO)

        val merged = HistoryReader.reconcileEntries(
            pagedHistory = listOf(e0, e1, e1.copy(pageCursor = "p2", pageOffset = 0)),
            streamingEntries = listOf(e1, e2, e2),
        )
        assertEquals(listOf(e0.identity, e1.identity, e2.identity), merged.entries.map { it.identity })
        assertEquals(COUNT_THREE, merged.ignoredDuplicates)
        assertEquals(listOf(e2.identity), merged.diff.inserts.map { it.identity })

        val provisional = entry("promoted", source = HistoryEntrySource.STREAMED)
        val promoted = provisional.copy(canonicalKey = "srv-p", source = HistoryEntrySource.CANONICAL_HISTORY)
        val standaloneDiff = HistoryReader.diff(
            previous = listOf(e0, e1, provisional),
            current = listOf(
                e0.copy(pageCursor = "c-9", pageOffset = COUNT_FOUR),
                promoted,
                e2,
            ),
            identityAliases = mapOf(provisional.identity to promoted.identity),
        )
        assertEquals(listOf(e1.identity), standaloneDiff.deletes.map { it.identity })
        assertEquals(listOf(promoted.identity), standaloneDiff.updates.map { it.identity })
        assertEquals(provisional.identity, standaloneDiff.updates.single().previousIdentity)
        assertEquals(listOf(e2.identity), standaloneDiff.inserts.map { it.identity })
    }

    @Test
    fun `cursor consistency after deletion, clear reset, and StreamingHistoryRow validation`() {
        val e0 = entry("zero", canonicalKey = "k-0", canonicalSequence = SEQ_ZERO)
        val e1 = entry("one", canonicalKey = "k-1", canonicalSequence = SEQ_ONE)
        val e2 = entry("two", canonicalKey = "k-2", canonicalSequence = SEQ_TWO)
        val reader = HistoryReader(listOf(e0, e1, e2))

        val page1 = reader.readPage(pageSize = 1)
        val page2 = reader.readPage(pageSize = 1, cursor = page1.nextCursor)
        assertEquals(listOf(e1), page2.entries)

        reader.reconcileStream(listOf(StreamingHistoryRow.delete(e1.identity, streamSequence = STREAM_SEQ_TEN)))
        val afterDelete = reader.readPage(pageSize = 2, cursor = page1.nextCursor)
        assertEquals(listOf(e2), afterDelete.entries)
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(pageSize = 1, cursor = page2.nextCursor)
        }

        reader.clear()
        val afterClear = reader.reconcileStream(listOf(StreamingHistoryRow.upsert(e1, streamSequence = SEQ_ONE)))
        assertEquals(listOf(e1.identity), afterClear.entries.map { it.identity })
        assertEquals(0, afterClear.ignoredOutOfOrder)

        assertThrows(IllegalArgumentException::class.java) {
            StreamingHistoryRow(operation = StreamingRowOperation.UPSERT, entry = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingHistoryRow(operation = StreamingRowOperation.UPSERT, entry = e0, targetIdentity = e1.identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingHistoryRow(operation = StreamingRowOperation.DELETE, entry = null, targetIdentity = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingHistoryRow.upsert(e0, streamSequence = -1L)
        }
    }

    @Test
    fun `concurrent paged and streaming reconciliation remains thread-safe without duplicates`() {
        val reader = HistoryReader()
        val entries = (0 until CONCURRENT_ITEMS).map { idx ->
            entry(
                content = "item-$idx",
                canonicalKey = "conc-$idx",
                canonicalSequence = idx.toLong(),
            )
        }
        val executor = Executors.newFixedThreadPool(CONCURRENT_THREADS)
        try {
            val tasks: List<Callable<Unit>> = listOf(
                Callable {
                    val reversedRows = entries.reversed().mapIndexed { idx, item ->
                        StreamingHistoryRow.upsert(
                            item.copy(content = "interim-${item.canonicalKey}", isInterim = true),
                            streamSequence = idx.toLong(),
                        )
                    }
                    reader.reconcileStream(reversedRows)
                    Unit
                },
                Callable {
                    entries.chunked(COUNT_FOUR).forEach { chunk ->
                        reader.reconcilePage(HistoryPage(chunk))
                    }
                },
                Callable {
                    entries.chunked(COUNT_THREE).forEach { chunk ->
                        reader.reconcileStream(chunk.map { StreamingHistoryRow.upsert(it) })
                    }
                },
                Callable {
                    repeat(CONCURRENT_ITEMS) {
                        val snapshot = reader.entries()
                        assertEquals(snapshot.size, snapshot.map { it.identity }.toSet().size)
                    }
                },
            )
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val finalEntries = reader.entries()
        assertEquals(CONCURRENT_ITEMS, finalEntries.size)
        assertEquals(entries.map { it.identity }, finalEntries.map { it.identity })
        assertTrue(finalEntries.all { it.source == HistoryEntrySource.CANONICAL_HISTORY })
    }
}
