package com.bighelp.droidhelp.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class HistoryReaderTest {

    private companion object {
        const val SESSION_ID = "durable-session-1"
        const val OTHER_SESSION_ID = "durable-session-2"
        const val SHA256_HEX_LENGTH = 64
        const val SEQ_ONE = 1L
        const val SEQ_FIVE = 5L
        const val TS_CLIENT = 1_700_000_001_234L
        const val TS_SERVER = 1_700_000_001_987L
        const val PAGE_OFFSET_THREE = 3
        const val PAGE_OFFSET_SEVENTEEN = 17
        const val TIMELINE_SIZE = 7
        const val CONCURRENT_THREADS = 4
        const val CONCURRENT_ITEMS = 25
    }

    @Test
    fun `identity scheme selects immutable key, positioned content hash, or content hash deterministically`() {
        val keyed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "hello",
            canonicalKey = "msg-42",
            canonicalSequence = SEQ_FIVE,
        )
        assertEquals(HistoryEntryIdentityScheme.IMMUTABLE_KEY, keyed.identity.scheme)
        assertTrue(keyed.stableId.startsWith("v1:key:"))
        assertEquals("v1:key:".length + SHA256_HEX_LENGTH, keyed.stableId.length)
        assertEquals(keyed.identity, HistoryReader.identityOf(keyed))
        assertEquals(keyed.contentHash, HistoryReader.contentHashOf(keyed))

        val positioned = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "yes",
            canonicalSequence = SEQ_ONE,
        )
        val repeatedLater = positioned.copy(canonicalSequence = SEQ_FIVE)
        assertEquals(HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH, positioned.identity.scheme)
        assertTrue(positioned.stableId.startsWith("v1:seq:"))
        assertEquals("v1:seq:".length + SHA256_HEX_LENGTH, positioned.stableId.length)
        assertEquals(positioned.contentHash, repeatedLater.contentHash)
        assertNotEquals(positioned.identity, repeatedLater.identity)

        val unkeyed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "done",
        )
        assertEquals(HistoryEntryIdentityScheme.CONTENT_HASH, unkeyed.identity.scheme)
        assertEquals("v1:hash:${unkeyed.contentHash}", unkeyed.stableId)
        assertEquals(SHA256_HEX_LENGTH, unkeyed.contentHash.length)
        assertEquals(unkeyed.identity, HistoryReader.identityOf(unkeyed.copy()))
    }

    @Test
    fun `identity and content hash ignore paging, runtime, timestamp, and attribute map order`() {
        val streamed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Final answer",
            kind = "message",
            canonicalAttributes = linkedMapOf("b" to "2", "a" to "1"),
            timestampEpochMillis = TS_CLIENT,
            source = HistoryEntrySource.STREAMED,
            runtimeSessionId = "runtime-ephemeral-1",
            pageCursor = null,
            pageOffset = null,
            isInterim = true,
        )
        val paged = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Final answer",
            kind = "message",
            canonicalAttributes = linkedMapOf("a" to "1", "b" to "2"),
            timestampEpochMillis = TS_SERVER,
            source = HistoryEntrySource.CANONICAL_HISTORY,
            runtimeSessionId = "runtime-ephemeral-2",
            pageCursor = "cursor-page-2",
            pageOffset = PAGE_OFFSET_SEVENTEEN,
            isInterim = false,
        )

        assertEquals(streamed.contentHash, paged.contentHash)
        assertEquals(streamed.identity, paged.identity)
        assertEquals(streamed.stableId, paged.stableId)

        val keyedStreamed = streamed.copy(canonicalKey = "srv-1", content = "Partial")
        val keyedPaged = paged.copy(canonicalKey = "srv-1", content = "Final answer")
        assertEquals(keyedStreamed.identity, keyedPaged.identity)
        assertNotEquals(keyedStreamed.contentHash, keyedPaged.contentHash)
    }

    @Test
    fun `identity framing prevents delimiter collisions and preserves verbatim IDs without normalization`() {
        val splitOne = CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "ab", content = "c")
        val splitTwo = CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "a", content = "bc")
        assertNotEquals(splitOne.contentHash, splitTwo.contentHash)
        assertNotEquals(splitOne.identity, splitTwo.identity)

        val pipeOne = CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "a|b", content = "c")
        val pipeTwo = CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "a", content = "b|c")
        assertNotEquals(pipeOne.identity, pipeTwo.identity)

        val upperSession = CanonicalHistoryEntry(durableSessionId = "Session-A", role = "user", content = "hi")
        val lowerSession = CanonicalHistoryEntry(durableSessionId = "session-a", role = "user", content = "hi")
        assertNotEquals(upperSession.identity, lowerSession.identity)

        val nfdKey = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "hi",
            canonicalKey = "Cafe\u0301",
        )
        val nfcKey = nfdKey.copy(canonicalKey = "Caf\u00E9")
        assertNotEquals(nfdKey.identity, nfcKey.identity)

        val withToolCall = splitOne.copy(toolCallId = "call-1")
        val otherToolCall = splitOne.copy(toolCallId = "call-2")
        assertNotEquals(splitOne.identity, withToolCall.identity)
        assertNotEquals(withToolCall.identity, otherToolCall.identity)
    }

    @Test
    fun `identities and ordering survive forward paging, reverse prepending, and overlapping pages`() {
        val timeline = (0..4).map { idx ->
            CanonicalHistoryEntry(
                durableSessionId = SESSION_ID,
                role = if (idx % 2 == 0) "user" else "assistant",
                content = "turn-$idx",
                canonicalSequence = idx.toLong(),
            )
        }

        val forwardReader = HistoryReader()
        val fwdPage1 = timeline.subList(0, PAGE_OFFSET_THREE)
            .mapIndexed { i, e -> e.copy(pageCursor = "fwd-1", pageOffset = i) }
        val fwdPage2 = listOf(timeline[2], timeline[PAGE_OFFSET_THREE], timeline[4], timeline[4])
            .mapIndexed { i, e -> e.copy(pageCursor = "fwd-2", pageOffset = i) }
        forwardReader.ingestPage(HistoryPage(fwdPage1, cursor = "fwd-1", nextCursor = "fwd-2"), prepend = false)
        forwardReader.ingestPage(HistoryPage(fwdPage2, cursor = "fwd-2", nextCursor = null), prepend = false)

        val reverseReader = HistoryReader()
        val revNewest = timeline.subList(2, timeline.size)
            .mapIndexed { i, e -> e.copy(pageCursor = "rev-newest", pageOffset = i) }
        val revOlder = timeline.subList(0, PAGE_OFFSET_THREE)
            .mapIndexed { i, e -> e.copy(pageCursor = "rev-older", pageOffset = i) }
        reverseReader.ingestPage(HistoryPage(revNewest, cursor = "rev-newest", nextCursor = "rev-older"))
        reverseReader.ingestPage(HistoryPage(revOlder, cursor = "rev-older", nextCursor = null), prepend = true)

        val expectedIds = timeline.map { it.identity }
        assertEquals(expectedIds, forwardReader.entries().map { it.identity })
        assertEquals(expectedIds, reverseReader.entries().map { it.identity })
        assertEquals(timeline.size, forwardReader.size)
        assertEquals(timeline.size, reverseReader.size)

        for (entry in timeline) {
            assertEquals(entry.content, forwardReader.findByIdentity(entry.identity)?.content)
            assertEquals(entry.content, reverseReader.findByStableId(entry.stableId)?.content)
        }
    }

    @Test
    fun `streaming reconciliation preserves immutable key identity across deltas and canonical upgrades`() {
        val reader = HistoryReader()

        val delta1 = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Hel",
            canonicalKey = "msg-stream-1",
            runtimeSessionId = "rt-1",
            timestampEpochMillis = TS_CLIENT,
            isInterim = true,
        )
        val firstStored = reader.reconcileStreamed(delta1)
        assertEquals(HistoryEntrySource.STREAMED, firstStored.source)
        assertEquals(1, reader.size)

        val delta2 = delta1.copy(content = "Hello, world", isInterim = true)
        val secondStored = reader.reconcileStreamed(delta2)
        assertEquals(firstStored.identity, secondStored.identity)
        assertEquals(1, reader.size)
        assertEquals("Hello, world", reader.findByIdentity(firstStored.identity)?.content)

        val canonical = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Hello, world!",
            canonicalKey = "msg-stream-1",
            timestampEpochMillis = TS_SERVER,
            pageCursor = "page-1",
            pageOffset = 0,
        )
        val reconciled = reader.ingestPage(HistoryPage(listOf(canonical)))
        assertEquals(1, reconciled.size)
        assertEquals(firstStored.identity, reconciled.single().identity)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, reconciled.single().source)
        assertFalse(reconciled.single().isInterim)
        assertEquals("Hello, world!", reconciled.single().content)

        // A stale streamed replay arriving after canonical history must not downgrade or duplicate it
        val staleReplay = reader.reconcileStreamed(delta1)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, staleReplay.source)
        assertEquals("Hello, world!", staleReplay.content)
        assertEquals(1, reader.size)
    }

    @Test
    fun `streaming reconciliation deduplicates content-hashed entries without keys or sequences`() {
        val reader = HistoryReader()
        val streamed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Exact reply",
            runtimeSessionId = "rt-live",
            timestampEpochMillis = TS_CLIENT,
            isInterim = false,
        )
        reader.reconcileStreamed(streamed)

        val canonical = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Exact reply",
            timestampEpochMillis = TS_SERVER,
            pageCursor = "c-0",
            pageOffset = PAGE_OFFSET_THREE,
        )
        reader.ingestPage(HistoryPage(listOf(canonical)))

        assertEquals(streamed.identity, canonical.identity)
        assertEquals(1, reader.size)
        val stored = reader.findByStableId(streamed.stableId)
        assertNotNull(stored)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, stored?.source)
        assertEquals(TS_SERVER, stored?.timestampEpochMillis)
    }

    @Test
    fun `provisional streamed entries promote in place to canonical identities with alias lookup`() {
        val reader = HistoryReader()

        val provisionalUnkeyed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Promoted answer",
            runtimeSessionId = "rt-1",
        )
        val provisionalSeq = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "Follow-up",
            canonicalSequence = SEQ_FIVE,
            runtimeSessionId = "rt-1",
        )
        reader.reconcileStreamed(provisionalUnkeyed)
        reader.reconcileStreamed(provisionalSeq)
        assertEquals(2, reader.size)

        val canonicalKeyed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Promoted answer",
            canonicalKey = "srv-100",
            canonicalSequence = SEQ_ONE,
        )
        val canonicalSeqKeyed = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "Follow-up",
            canonicalKey = "srv-105",
            canonicalSequence = SEQ_FIVE,
        )
        val otherSessionSameContent = CanonicalHistoryEntry(
            durableSessionId = OTHER_SESSION_ID,
            role = "assistant",
            content = "Promoted answer",
            canonicalKey = "srv-other",
        )

        reader.ingestPage(
            HistoryPage(listOf(canonicalKeyed, canonicalSeqKeyed, otherSessionSameContent)),
        )

        assertEquals(PAGE_OFFSET_THREE, reader.size)
        assertEquals(
            listOf(canonicalKeyed.identity, canonicalSeqKeyed.identity, otherSessionSameContent.identity),
            reader.entries().map { it.identity },
        )

        // Old provisional identities resolve transparently to their promoted canonical entries
        assertEquals(canonicalKeyed.identity, reader.resolveIdentity(provisionalUnkeyed.identity))
        assertEquals(canonicalKeyed.identity, reader.identityFor(provisionalUnkeyed))
        assertEquals(canonicalKeyed.canonicalKey, reader.findByIdentity(provisionalUnkeyed.identity)?.canonicalKey)
        assertEquals(canonicalKeyed.canonicalKey, reader.findByStableId(provisionalUnkeyed.stableId)?.canonicalKey)

        assertEquals(canonicalSeqKeyed.identity, reader.resolveIdentity(provisionalSeq.identity))
        assertEquals(canonicalSeqKeyed.canonicalKey, reader.findByIdentity(provisionalSeq.identity)?.canonicalKey)

        // Replaying the provisional streamed entry does not duplicate or overwrite canonical state
        val replayed = reader.reconcileStreamed(provisionalUnkeyed)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, replayed.source)
        assertEquals("srv-100", replayed.canonicalKey)
        assertEquals(PAGE_OFFSET_THREE, reader.size)
    }

    @Test
    fun `serialization drops transient fields and preserves deterministic identity across round-trip`() {
        val original = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "Persisted body",
            kind = "tool_result",
            canonicalKey = "evt-77",
            canonicalSequence = SEQ_FIVE,
            toolCallId = "tool-9",
            canonicalAttributes = mapOf("tool" to "search"),
            timestampEpochMillis = TS_SERVER,
            source = HistoryEntrySource.STREAMED,
            runtimeSessionId = "volatile-rt-secret",
            pageCursor = "cursor-volatile",
            pageOffset = PAGE_OFFSET_THREE,
            isInterim = true,
        )

        val encoded = Json.encodeToString(original)
        val jsonObj = Json.parseToJsonElement(encoded).jsonObject
        assertFalse("runtimeSessionId" in jsonObj)
        assertFalse("pageCursor" in jsonObj)
        assertFalse("pageOffset" in jsonObj)
        assertFalse("source" in jsonObj)
        assertFalse("isInterim" in jsonObj)
        assertFalse(encoded.contains("volatile-rt-secret"))

        val decoded = Json.decodeFromString<CanonicalHistoryEntry>(encoded)
        assertEquals(original.identity, decoded.identity)
        assertEquals(original.stableId, decoded.stableId)
        assertEquals(original.contentHash, decoded.contentHash)
        assertEquals(HistoryEntrySource.CANONICAL_HISTORY, decoded.source)
        assertNull(decoded.runtimeSessionId)
        assertNull(decoded.pageCursor)
        assertNull(decoded.pageOffset)
        assertFalse(decoded.isInterim)

        val encodedId = Json.encodeToString(original.identity)
        assertEquals(original.identity, Json.decodeFromString<HistoryEntryIdentity>(encodedId))
    }

    @Test
    fun `validates canonical entry invariants, allows empty content, and supports clear`() {
        val emptyContent = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "system",
            content = "",
            kind = "receipt",
        )
        assertTrue(emptyContent.stableId.startsWith("v1:hash:"))

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = "   ", role = "user", content = "hi")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = " ", content = "hi")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "user", content = "hi", kind = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "user", content = "hi", canonicalKey = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(
                durableSessionId = SESSION_ID,
                role = "user",
                content = "hi",
                canonicalSequence = -1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "user", content = "hi", pageOffset = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(durableSessionId = SESSION_ID, role = "user", content = "hi", toolCallId = "\t")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalHistoryEntry(
                durableSessionId = SESSION_ID,
                role = "user",
                content = "hi",
                runtimeSessionId = "",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryEntryIdentity(value = " ", scheme = HistoryEntryIdentityScheme.CONTENT_HASH)
        }

        val reader = HistoryReader()
        reader.ingestPage(HistoryPage(listOf(emptyContent)))
        assertEquals(1, reader.size)
        reader.clear()
        assertEquals(0, reader.size)
        assertNull(reader.findByIdentity(emptyContent.identity))
        assertNull(reader.findByStableId(emptyContent.stableId))
    }

    @Test
    fun `concurrent paging and streaming reconciliation remains thread-safe without duplicates`() {
        val reader = HistoryReader()
        val entries = (0 until CONCURRENT_ITEMS).map { idx ->
            CanonicalHistoryEntry(
                durableSessionId = SESSION_ID,
                role = "assistant",
                content = "item-$idx",
                canonicalKey = "concurrent-$idx",
                canonicalSequence = idx.toLong(),
            )
        }
        val executor = Executors.newFixedThreadPool(CONCURRENT_THREADS)
        try {
            val tasks: List<Callable<Unit>> = listOf(
                Callable {
                    entries.forEach { reader.reconcileStreamed(it.copy(isInterim = true)) }
                },
                Callable {
                    reader.ingestPage(HistoryPage(entries))
                    Unit
                },
                Callable {
                    entries.forEach { reader.findByIdentity(it.identity) }
                },
                Callable {
                    reader.entries()
                    Unit
                },
            )
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(CONCURRENT_ITEMS, reader.size)
        assertEquals(entries.map { it.identity }.toSet(), reader.entries().map { it.identity }.toSet())
        assertTrue(reader.entries().all { it.source == HistoryEntrySource.CANONICAL_HISTORY })
    }

    @Test
    fun `immutable key identity is invariant to non-key fields and distinct across verbatim keys and sessions`() {
        val base = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "user",
            content = "first draft",
            kind = "message",
            canonicalKey = "srv-msg-1",
            canonicalSequence = 0L,
            toolCallId = null,
            canonicalAttributes = emptyMap(),
            timestampEpochMillis = TS_CLIENT,
            source = HistoryEntrySource.STREAMED,
            runtimeSessionId = "rt-1",
            pageCursor = "c-1",
            pageOffset = 0,
            isInterim = true,
        )
        val mutated = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "completely rewritten final reply",
            kind = "tool_result",
            canonicalKey = "srv-msg-1",
            canonicalSequence = Long.MAX_VALUE,
            toolCallId = "call-99",
            canonicalAttributes = mapOf("k" to "v", "extra" to "true"),
            timestampEpochMillis = TS_SERVER,
            source = HistoryEntrySource.CANONICAL_HISTORY,
            runtimeSessionId = "rt-2",
            pageCursor = "c-9",
            pageOffset = PAGE_OFFSET_SEVENTEEN,
            isInterim = false,
        )

        assertEquals(HistoryEntryIdentityScheme.IMMUTABLE_KEY, base.identity.scheme)
        assertEquals(base.identity, mutated.identity)
        assertEquals(base.stableId, mutated.stableId)
        assertNotEquals(base.contentHash, mutated.contentHash)

        // Verbatim session ID and canonicalKey distinctions (case and whitespace) never collapse
        assertNotEquals(base.identity, base.copy(durableSessionId = OTHER_SESSION_ID).identity)
        assertNotEquals(base.identity, base.copy(durableSessionId = " $SESSION_ID ").identity)
        assertNotEquals(base.identity, base.copy(canonicalKey = "SRV-MSG-1").identity)
        assertNotEquals(base.identity, base.copy(canonicalKey = " srv-msg-1 ").identity)
    }

    @Test
    fun `content hash and positioned identity distinguish null fields, attributes, Unicode, and sequence bounds`() {
        val nullToolCall = CanonicalHistoryEntry(
            durableSessionId = SESSION_ID,
            role = "assistant",
            content = "payload",
            toolCallId = null,
        )
        val literalNullToolCall = nullToolCall.copy(toolCallId = "null")
        assertNotEquals(nullToolCall.contentHash, literalNullToolCall.contentHash)
        assertNotEquals(nullToolCall.identity, literalNullToolCall.identity)

        val attrSplitA = nullToolCall.copy(canonicalAttributes = mapOf("ab" to "c"))
        val attrSplitB = nullToolCall.copy(canonicalAttributes = mapOf("a" to "bc"))
        val attrMulti = nullToolCall.copy(canonicalAttributes = mapOf("a" to "1", "b" to "2"))
        val attrJoined = nullToolCall.copy(canonicalAttributes = mapOf("a" to "1b2"))
        val attrEmptyEntry = nullToolCall.copy(canonicalAttributes = mapOf("" to ""))
        assertNotEquals(attrSplitA.identity, attrSplitB.identity)
        assertNotEquals(attrMulti.identity, attrJoined.identity)
        assertNotEquals(nullToolCall.identity, attrEmptyEntry.identity)

        // Role and kind changes alter content hash and identity
        assertNotEquals(nullToolCall.identity, nullToolCall.copy(role = "tool").identity)
        assertNotEquals(nullToolCall.identity, nullToolCall.copy(kind = "tool_result").identity)

        // Multibyte UTF-8 and NFD vs NFC remain deterministic and verbatim
        val nfdContent = nullToolCall.copy(content = "Cafe\u0301 \uD83D\uDE80 \u4F60\u597D")
        val nfcContent = nullToolCall.copy(content = "Caf\u00E9 \uD83D\uDE80 \u4F60\u597D")
        assertEquals(nfdContent.identity, nfdContent.copy().identity)
        assertNotEquals(nfdContent.identity, nfcContent.identity)

        // Extreme canonicalSequence bounds share contentHash but get distinct positioned identities
        val seqZero = nullToolCall.copy(canonicalSequence = 0L)
        val seqOne = nullToolCall.copy(canonicalSequence = SEQ_ONE)
        val seqMax = nullToolCall.copy(canonicalSequence = Long.MAX_VALUE)
        assertEquals(seqZero.contentHash, seqMax.contentHash)
        assertEquals(setOf(seqZero.identity, seqOne.identity, seqMax.identity).size, PAGE_OFFSET_THREE)
    }

    @Test
    fun `identity determinism holds across repeated calls, cross-scheme inputs, and independent paged readers`() {
        val timeline = (0 until TIMELINE_SIZE).map { idx ->
            when (idx % PAGE_OFFSET_THREE) {
                0 -> CanonicalHistoryEntry(
                    durableSessionId = SESSION_ID,
                    role = "user",
                    content = "turn-$idx",
                    canonicalKey = "key-$idx",
                    canonicalSequence = idx.toLong(),
                )
                1 -> CanonicalHistoryEntry(
                    durableSessionId = SESSION_ID,
                    role = "assistant",
                    content = "turn-$idx",
                    canonicalSequence = idx.toLong(),
                )
                else -> CanonicalHistoryEntry(
                    durableSessionId = SESSION_ID,
                    role = "assistant",
                    content = "unkeyed-turn-$idx",
                )
            }
        }

        // Repeated calls and independent copies yield identical identities
        val baselineIds = timeline.map { HistoryReader.identityOf(it) }
        repeat(PAGE_OFFSET_THREE) {
            val recomputed = timeline.map { entry ->
                HistoryReader.identityOf(entry.copy(pageOffset = entry.hashCode().and(1)))
            }
            assertEquals(baselineIds, recomputed)
        }

        // Cross-scheme isolation even if canonicalKey equals another entry's contentHash or stableId
        val unkeyed = timeline[2]
        val keyedMimicHash = unkeyed.copy(canonicalKey = unkeyed.contentHash)
        val keyedMimicStableId = unkeyed.copy(canonicalKey = unkeyed.stableId)
        assertNotEquals(unkeyed.identity, keyedMimicHash.identity)
        assertNotEquals(unkeyed.identity, keyedMimicStableId.identity)
        assertNotEquals(keyedMimicHash.identity, keyedMimicStableId.identity)

        // Independent readers ingesting via different page chunkings produce identical stable IDs
        val readerOne = HistoryReader()
        timeline.chunked(2).forEach { chunk -> readerOne.ingestPage(HistoryPage(chunk)) }

        val readerTwo = HistoryReader()
        timeline.chunked(PAGE_OFFSET_THREE).reversed().forEachIndexed { idx, chunk ->
            readerTwo.ingestPage(HistoryPage(chunk), prepend = idx > 0)
        }

        assertEquals(baselineIds.map { it.value }, readerOne.entries().map { it.stableId })
        assertEquals(baselineIds.map { it.value }, readerTwo.entries().map { it.stableId })
    }
}
