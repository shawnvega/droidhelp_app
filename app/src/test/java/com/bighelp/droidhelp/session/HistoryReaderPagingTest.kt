package com.bighelp.droidhelp.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class HistoryReaderPagingTest {

    private companion object {
        const val SESSION_A = "Session-A"
        const val SESSION_A_LOWER = "session-a"
        const val SESSION_B = "Session-B"
        const val STATIC_HISTORY_SIZE = 11
        const val PAGE_SIZE_THREE = 3
        const val PAGE_SIZE_FOUR = 4
        const val PAGE_SIZE_FIVE = 5
        const val PAGE_SIZE_TEN = 10
        const val PAGE_SIZE_LARGE = 100
        const val CONCURRENT_THREADS = 4
        const val CONCURRENT_READ_ITERATIONS = 20
        val OPAQUE_CURSOR_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }

    private fun buildStaticEntries(
        count: Int,
        sessionId: String = SESSION_A,
        keyPrefix: String = "msg",
        startIndex: Int = 0,
    ): List<CanonicalHistoryEntry> {
        return (startIndex until startIndex + count).map { idx ->
            CanonicalHistoryEntry(
                durableSessionId = sessionId,
                role = if (idx % 2 == 0) "user" else "assistant",
                content = "entry-$idx",
                canonicalKey = "$keyPrefix-$idx",
                canonicalSequence = idx.toLong(),
            )
        }
    }

    private fun drainForwardPages(
        reader: HistoryReader,
        pageSize: Int,
        durableSessionId: String? = null,
        includeStreamed: Boolean = false,
    ): List<HistoryPage> {
        val pages = mutableListOf<HistoryPage>()
        var cursor: String? = null
        do {
            val page = reader.readPage(
                pageSize = pageSize,
                cursor = cursor,
                durableSessionId = durableSessionId,
                direction = HistoryPageDirection.FORWARD,
                includeStreamed = includeStreamed,
            )
            assertEquals(cursor, page.cursor)
            pages.add(page)
            cursor = page.nextCursor
        } while (page.hasMore)
        return pages
    }

    @Test
    fun `forward paging returns fixed-size subsets without duplicates across page sizes`() {
        val staticHistory = buildStaticEntries(STATIC_HISTORY_SIZE)
        val reader = HistoryReader(staticHistory)
        val pageSizes = listOf(
            1,
            2,
            PAGE_SIZE_THREE,
            PAGE_SIZE_FOUR,
            PAGE_SIZE_FIVE,
            STATIC_HISTORY_SIZE,
            PAGE_SIZE_LARGE,
        )

        for (pageSize in pageSizes) {
            val pages = drainForwardPages(reader, pageSize)
            assertTrue(pages.isNotEmpty())

            pages.dropLast(1).forEach { page ->
                assertEquals(pageSize, page.entries.size)
                assertTrue(page.hasMore)
                assertNotNull(page.nextCursor)
            }

            val lastPage = pages.last()
            assertTrue(lastPage.entries.size in 1..pageSize)
            assertFalse(lastPage.hasMore)
            assertNull(lastPage.nextCursor)

            val combined = pages.flatMap { it.entries }
            assertEquals(staticHistory, combined)
            assertEquals(staticHistory.size, combined.map { it.identity }.toSet().size)
        }

        // Exact-multiple boundary produces no empty trailing page
        val evenHistory = buildStaticEntries(PAGE_SIZE_FOUR * 2)
        val evenReader = HistoryReader(evenHistory)
        val evenPages = drainForwardPages(evenReader, PAGE_SIZE_FOUR)
        assertEquals(2, evenPages.size)
        assertEquals(PAGE_SIZE_FOUR, evenPages[0].entries.size)
        assertTrue(evenPages[0].hasMore)
        assertEquals(PAGE_SIZE_FOUR, evenPages[1].entries.size)
        assertFalse(evenPages[1].hasMore)
        assertNull(evenPages[1].nextCursor)
        assertEquals(evenHistory, evenPages.flatMap { it.entries })
    }

    @Test
    fun `empty history, single entry, and Int MAX_VALUE pageSize are handled safely`() {
        val emptyReader = HistoryReader()
        val emptyPage = emptyReader.readPage(pageSize = PAGE_SIZE_FIVE)
        assertTrue(emptyPage.entries.isEmpty())
        assertNull(emptyPage.cursor)
        assertNull(emptyPage.nextCursor)
        assertFalse(emptyPage.hasMore)

        val single = buildStaticEntries(1)
        val singleReader = HistoryReader(single)
        val singlePage = singleReader.readPage(pageSize = 1)
        assertEquals(single, singlePage.entries)
        assertFalse(singlePage.hasMore)
        assertNull(singlePage.nextCursor)

        val history = buildStaticEntries(PAGE_SIZE_FIVE)
        val reader = HistoryReader(history)
        val first = reader.readPage(pageSize = 2)
        assertTrue(first.hasMore)
        val rest = reader.readPage(pageSize = Int.MAX_VALUE, cursor = first.nextCursor)
        assertEquals(history.drop(2), rest.entries)
        assertFalse(rest.hasMore)
        assertNull(rest.nextCursor)
    }

    @Test
    fun `cursors are opaque, deterministic, stable across repeated reads, schemes, and page size changes`() {
        val mixedSchemeEntries = listOf(
            CanonicalHistoryEntry(
                durableSessionId = SESSION_A,
                role = "user",
                content = "secret-keyed-body",
                canonicalKey = "immutable-secret-key",
            ),
            CanonicalHistoryEntry(
                durableSessionId = SESSION_A,
                role = "assistant",
                content = "positioned-body",
                canonicalSequence = 1L,
            ),
            CanonicalHistoryEntry(
                durableSessionId = SESSION_A,
                role = "user",
                content = "content-hash-body",
            ),
            CanonicalHistoryEntry(
                durableSessionId = SESSION_A,
                role = "assistant",
                content = "final-body",
                canonicalKey = "immutable-final-key",
            ),
        )
        val reader = HistoryReader(mixedSchemeEntries)

        // Repeated reads on static history produce identical opaque cursors and pages
        val run1 = drainForwardPages(reader, pageSize = 1)
        val run2 = drainForwardPages(reader, pageSize = 1)
        assertEquals(run1, run2)

        val cursors = run1.mapNotNull { it.nextCursor }
        assertEquals(mixedSchemeEntries.size - 1, cursors.size)
        assertEquals(cursors.size, cursors.toSet().size)
        for (cursor in cursors) {
            assertTrue(OPAQUE_CURSOR_REGEX.matches(cursor), "Cursor should be URL-safe opaque base64: $cursor")
            assertFalse(cursor.contains(SESSION_A))
            assertFalse(cursor.contains("immutable-secret-key"))
            assertFalse(cursor.contains("v1:key:"))
            assertFalse(cursor.contains("v1:seq:"))
            assertFalse(cursor.contains("v1:hash:"))
            assertFalse(cursor.contains("offset", ignoreCase = true))
        }

        // Changing pageSize mid-traversal using an existing cursor preserves continuity without duplicates
        val first = reader.readPage(pageSize = 1)
        val second = reader.readPage(pageSize = 2, cursor = first.nextCursor)
        val third = reader.readPage(pageSize = PAGE_SIZE_FIVE, cursor = second.nextCursor)
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)
        assertNull(third.nextCursor)
        assertEquals(mixedSchemeEntries, first.entries + second.entries + third.entries)
    }

    @Test
    fun `backward paging traverses newest to oldest and reconstructs canonical order on prepend`() {
        val staticHistory = buildStaticEntries(STATIC_HISTORY_SIZE)
        val reader = HistoryReader(staticHistory)

        val pages = mutableListOf<HistoryPage>()
        var cursor: String? = null
        do {
            val page = reader.readPage(
                pageSize = PAGE_SIZE_THREE,
                cursor = cursor,
                direction = HistoryPageDirection.BACKWARD,
            )
            assertEquals(cursor, page.cursor)
            pages.add(page)
            cursor = page.nextCursor
        } while (page.hasMore)

        assertEquals(PAGE_SIZE_FOUR, pages.size)
        pages.dropLast(1).forEach { page ->
            assertEquals(PAGE_SIZE_THREE, page.entries.size)
            assertTrue(page.hasMore)
            assertNotNull(page.nextCursor)
        }
        assertEquals(2, pages.last().entries.size)
        assertFalse(pages.last().hasMore)
        assertNull(pages.last().nextCursor)

        // Each page is in chronological order, and prepending reconstructs the full history cleanly
        assertEquals(staticHistory.takeLast(PAGE_SIZE_THREE), pages.first().entries)
        assertEquals(staticHistory.take(2), pages.last().entries)

        val reconstructed = HistoryReader()
        pages.forEachIndexed { index, page ->
            reconstructed.ingestPage(page, prepend = index > 0)
        }
        assertEquals(staticHistory, reconstructed.entries())
        assertEquals(staticHistory.size, reconstructed.size)
    }

    @Test
    fun `keyset cursors remain stable across prepends, appends, and provisional identity promotion`() {
        // Forward cursor stays stable when older pages are prepended before the anchor
        val initial = buildStaticEntries(PAGE_SIZE_FOUR, startIndex = PAGE_SIZE_THREE)
        val forwardReader = HistoryReader(initial)
        val fwdPage1 = forwardReader.readPage(pageSize = 2)
        assertEquals(initial.take(2), fwdPage1.entries)
        assertTrue(fwdPage1.hasMore)

        val olderEntries = buildStaticEntries(PAGE_SIZE_THREE, startIndex = 0)
        forwardReader.ingestPage(HistoryPage(olderEntries), prepend = true)

        val fwdPage2 = forwardReader.readPage(pageSize = 2, cursor = fwdPage1.nextCursor)
        assertEquals(initial.drop(2), fwdPage2.entries)
        assertFalse(fwdPage2.hasMore)
        assertNull(fwdPage2.nextCursor)

        // Backward cursor stays stable when newer entries are appended after the anchor
        val backwardReader = HistoryReader(olderEntries + initial)
        val bwdPage1 = backwardReader.readPage(pageSize = 2, direction = HistoryPageDirection.BACKWARD)
        assertEquals(initial.takeLast(2), bwdPage1.entries)
        assertTrue(bwdPage1.hasMore)

        val newerEntries = buildStaticEntries(2, startIndex = PAGE_SIZE_THREE + PAGE_SIZE_FOUR)
        backwardReader.ingestPage(HistoryPage(newerEntries), prepend = false)

        val bwdPage2 = backwardReader.readPage(
            pageSize = 2,
            cursor = bwdPage1.nextCursor,
            direction = HistoryPageDirection.BACKWARD,
        )
        assertEquals(initial.take(2), bwdPage2.entries)
        assertTrue(bwdPage2.hasMore)

        // Provisional streamed anchor still resolves after canonical promotion via identity alias
        val promoReader = HistoryReader()
        val streamedFirst = CanonicalHistoryEntry(
            durableSessionId = SESSION_A,
            role = "assistant",
            content = "promoted-anchor",
        )
        val streamedSecond = CanonicalHistoryEntry(
            durableSessionId = SESSION_A,
            role = "user",
            content = "after-anchor",
        )
        promoReader.reconcileStreamed(streamedFirst)
        promoReader.reconcileStreamed(streamedSecond)

        val streamedPage1 = promoReader.readPage(pageSize = 1, includeStreamed = true)
        assertTrue(streamedPage1.hasMore)
        assertNotNull(streamedPage1.nextCursor)

        val canonicalFirst = streamedFirst.copy(canonicalKey = "srv-promoted-1", canonicalSequence = 1L)
        promoReader.ingestPage(HistoryPage(listOf(canonicalFirst)))

        val streamedPage2 = promoReader.readPage(
            pageSize = 1,
            cursor = streamedPage1.nextCursor,
            includeStreamed = true,
        )
        assertEquals(1, streamedPage2.entries.size)
        assertEquals("after-anchor", streamedPage2.entries.single().content)
        assertFalse(streamedPage2.hasMore)
    }

    @Test
    fun `session scoping and canonical-only filtering isolate paged reads`() {
        val reader = HistoryReader()
        val sessionAEntries = buildStaticEntries(PAGE_SIZE_THREE, sessionId = SESSION_A, keyPrefix = "a")
        val sessionALowerEntries = buildStaticEntries(2, sessionId = SESSION_A_LOWER, keyPrefix = "al")
        val sessionBEntries = buildStaticEntries(2, sessionId = SESSION_B, keyPrefix = "b")

        reader.ingestPage(
            HistoryPage(
                listOf(
                    sessionAEntries[0],
                    sessionBEntries[0],
                    sessionALowerEntries[0],
                    sessionAEntries[1],
                    sessionBEntries[1],
                    sessionALowerEntries[1],
                    sessionAEntries[2],
                ),
            ),
        )
        val liveStreamed = CanonicalHistoryEntry(
            durableSessionId = SESSION_A,
            role = "assistant",
            content = "interim-streamed-delta",
            canonicalKey = "a-live",
            isInterim = true,
        )
        reader.reconcileStreamed(liveStreamed)

        // Canonical reads exclude live streamed entries and preserve verbatim session ID distinction
        val sessionACanonical = drainForwardPages(reader, pageSize = 2, durableSessionId = SESSION_A)
            .flatMap { it.entries }
        val sessionALowerCanonical = drainForwardPages(reader, pageSize = 2, durableSessionId = SESSION_A_LOWER)
            .flatMap { it.entries }
        val sessionBCanonical = drainForwardPages(reader, pageSize = 1, durableSessionId = SESSION_B)
            .flatMap { it.entries }

        assertEquals(sessionAEntries, sessionACanonical)
        assertEquals(sessionALowerEntries, sessionALowerCanonical)
        assertEquals(sessionBEntries, sessionBCanonical)

        // Opting into includeStreamed surfaces the live streamed entry as well
        val sessionAWithStreamed = drainForwardPages(
            reader,
            pageSize = 2,
            durableSessionId = SESSION_A,
            includeStreamed = true,
        ).flatMap { it.entries }
        assertEquals(PAGE_SIZE_FOUR, sessionAWithStreamed.size)
        assertEquals(liveStreamed.identity, sessionAWithStreamed.last().identity)
    }

    @Test
    fun `rejects invalid page sizes, blank session IDs, tampered cursors, and scope mismatches`() {
        val entriesA = buildStaticEntries(PAGE_SIZE_FOUR, sessionId = SESSION_A, keyPrefix = "a")
        val entriesLower = buildStaticEntries(PAGE_SIZE_FOUR, sessionId = SESSION_A_LOWER, keyPrefix = "al")
        val reader = HistoryReader(entriesA + entriesLower)

        assertThrows(IllegalArgumentException::class.java) { reader.readPage(pageSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { reader.readPage(pageSize = -1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readPage(pageSize = Int.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { reader.readPage(durableSessionId = "   ") }

        val validCursor = requireNotNull(reader.readPage(pageSize = 2, durableSessionId = SESSION_A).nextCursor)
        val unscopedCursor = requireNotNull(reader.readPage(pageSize = 2).nextCursor)
        assertNotEquals(validCursor, unscopedCursor)

        // Malformed and tampered cursors
        val flippedLast = if (validCursor.last() == 'A') 'B' else 'A'
        val tamperedCursor = validCursor.dropLast(1) + flippedLast
        val invalidCursors = listOf(
            "",
            "   ",
            "2",
            "offset:2",
            "$validCursor==",
            validCursor.dropLast(1),
            tamperedCursor,
            "not!valid@base64#",
        )
        for (bad in invalidCursors) {
            assertThrows(InvalidHistoryCursorException::class.java) {
                reader.readPage(pageSize = 2, cursor = bad, durableSessionId = SESSION_A)
            }
        }

        // Direction, session-scope, and includeStreamed mismatches
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(
                pageSize = 2,
                cursor = validCursor,
                durableSessionId = SESSION_A,
                direction = HistoryPageDirection.BACKWARD,
            )
        }
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(pageSize = 2, cursor = validCursor, durableSessionId = SESSION_A_LOWER)
        }
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(pageSize = 2, cursor = validCursor, durableSessionId = null)
        }
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(pageSize = 2, cursor = unscopedCursor, durableSessionId = SESSION_A)
        }
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(
                pageSize = 2,
                cursor = validCursor,
                durableSessionId = SESSION_A,
                includeStreamed = true,
            )
        }

        // Stale cursor after clear fails cleanly
        reader.clear()
        assertThrows(InvalidHistoryCursorException::class.java) {
            reader.readPage(pageSize = 2, cursor = validCursor, durableSessionId = SESSION_A)
        }
    }

    @Test
    fun `concurrent paged reads and writes remain thread-safe without duplicates`() {
        val initial = buildStaticEntries(PAGE_SIZE_TEN, startIndex = 0)
        val additional = buildStaticEntries(PAGE_SIZE_TEN, startIndex = PAGE_SIZE_TEN)
        val reader = HistoryReader(initial)
        val executor = Executors.newFixedThreadPool(CONCURRENT_THREADS)
        try {
            val tasks: List<Callable<Unit>> = listOf(
                Callable {
                    repeat(CONCURRENT_READ_ITERATIONS) {
                        val combined = drainForwardPages(reader, pageSize = PAGE_SIZE_THREE).flatMap { it.entries }
                        assertEquals(combined.size, combined.map { it.identity }.toSet().size)
                    }
                },
                Callable {
                    repeat(CONCURRENT_READ_ITERATIONS) {
                        var cursor: String? = null
                        val seen = mutableSetOf<HistoryEntryIdentity>()
                        do {
                            val page = reader.readPage(
                                pageSize = PAGE_SIZE_THREE,
                                cursor = cursor,
                                direction = HistoryPageDirection.BACKWARD,
                            )
                            page.entries.forEach { entry ->
                                assertTrue(seen.add(entry.identity))
                            }
                            cursor = page.nextCursor
                        } while (page.hasMore)
                    }
                },
                Callable {
                    additional.chunked(2).forEach { chunk ->
                        reader.ingestPage(HistoryPage(chunk))
                    }
                },
                Callable {
                    additional.forEach { entry ->
                        reader.reconcileStreamed(entry.copy(isInterim = true))
                    }
                },
            )
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val finalAll = drainForwardPages(reader, pageSize = PAGE_SIZE_FOUR).flatMap { it.entries }
        assertEquals(initial.size + additional.size, finalAll.size)
        assertEquals(finalAll.size, finalAll.map { it.identity }.toSet().size)
    }
}
