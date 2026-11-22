package com.bighelp.droidhelp.transport

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Extended generation/epoch fencing, state-preservation, and concurrency stress tests for
 * [Owners] and [SocketOwner].
 */
class SocketOwnerFencingTest {

    private lateinit var owners: Owners

    @Before
    fun setUp() {
        owners = Owners()
    }

    @Test
    fun testZeroBasedGenerationsAndFreshNamespaceResets() {
        val signIn0 = SignIn("https://example.com", "user1", 0L)
        val owner0 = Owner(signIn0, 0L)
        val socket0 = SocketOwner(owner0, 0L)

        owners.updateSignIn(signIn0)
        owners.updateOwner(owner0)
        owners.updateSocketOwner(socket0)
        assertEquals(socket0, owners.getSocketOwner())
        owners.validateSocketOwner(socket0)

        // Retiring epoch 0 prevents reusing epoch 0 and advances incrementSocketEpoch to 1
        assertTrue(owners.retireSocketOwner(socket0))
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(socket0)
        }
        val socket1 = owners.incrementSocketEpoch()
        assertEquals(SocketOwner(owner0, 1L), socket1)
        owners.updateSocketOwner(SocketOwner(owner0, 5L))

        // Advancing Owner resets socket epoch namespace so epoch 0 is valid for the new Owner
        val owner1 = Owner(signIn0, 1L)
        owners.updateOwner(owner1)
        val newOwnerSocket0 = SocketOwner(owner1, 0L)
        owners.updateSocketOwner(newOwnerSocket0)
        assertEquals(newOwnerSocket0, owners.getSocketOwner())

        // Advancing SignIn resets both workspace generation and socket epoch namespaces
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn1)
        val freshOwner0 = Owner(signIn1, 0L)
        val freshSocket0 = SocketOwner(freshOwner0, 0L)
        owners.updateOwner(freshOwner0)
        owners.updateSocketOwner(freshSocket0)
        assertEquals(freshOwner0, owners.getOwner())
        assertEquals(freshSocket0, owners.getSocketOwner())
    }

    @Test
    fun testNonContiguousSocketEpochsAndRejectedUpdatesPreserveState() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val signIn2 = SignIn("https://example.com", "user1", 2L)
        val owner1 = Owner(signIn2, 1L)
        val staleOwner = Owner(signIn1, 1L)

        owners.updateSignIn(signIn1)
        owners.updateSignIn(signIn2)
        owners.updateOwner(owner1)

        // Non-contiguous jumps advance the high-water mark and reject skipped intermediate epochs
        owners.updateSocketOwner(SocketOwner(owner1, 0L))
        owners.updateSocketOwner(SocketOwner(owner1, 10L))
        owners.updateSocketOwner(SocketOwner(owner1, 100L))
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner1, 50L))
        }
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner1, 99L))
        }
        val epoch101 = owners.incrementSocketEpoch(owner1)
        assertEquals(SocketOwner(owner1, 101L), epoch101)

        // Rejected updateSocketOwner from stale Owner must not pollute lastSocketEpoch
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(staleOwner, 999L))
        }
        assertEquals(epoch101, owners.getSocketOwner())
        val epoch102 = SocketOwner(owner1, 102L)
        owners.updateSocketOwner(epoch102)
        assertEquals(epoch102, owners.getSocketOwner())

        // Rejected updateSignIn must preserve active SignIn, Owner, SocketOwner, and epoch watermark
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(signIn1)
        }
        assertEquals(signIn2, owners.getSignIn())
        assertEquals(owner1, owners.getOwner())
        assertEquals(epoch102, owners.getSocketOwner())
        owners.validateSocketOwner(epoch102)

        // Rejected updateOwner with stale SignIn and high generation must not pollute workspace watermark
        assertThrows(StaleOwnerException::class.java) {
            owners.updateOwner(Owner(signIn1, 999L))
        }
        assertEquals(owner1, owners.getOwner())
        assertEquals(epoch102, owners.getSocketOwner())
        val owner2 = Owner(signIn2, 2L)
        owners.updateOwner(owner2)
        assertEquals(owner2, owners.getOwner())
    }

    @Test
    fun testSocketOwnerRejectsMismatchedParentSignInAndOverflowAfterRetirement() {
        val signInGen1 = SignIn("https://example.com", "user1", 1L)
        val signInGen2 = SignIn("https://example.com", "user1", 2L)
        val otherHostSignIn = SignIn("https://other.example.com", "user1", 2L)
        val otherUserSignIn = SignIn("https://example.com", "user2", 2L)

        // incrementSocketEpoch without Owner returns null even when SignIn is active
        owners.updateSignIn(signInGen2)
        assertNull(owners.incrementSocketEpoch())
        assertThrows(StaleOwnerException::class.java) {
            owners.incrementSocketEpoch(Owner(signInGen2, 1L))
        }

        val activeOwner = Owner(signInGen2, 1L)
        owners.updateOwner(activeOwner)
        val activeSocket = owners.incrementSocketEpoch(activeOwner)
        assertEquals(SocketOwner(activeOwner, 1L), activeSocket)

        // Same workspaceConnectionGeneration and higher socketEpoch, but mismatched parent SignIn
        val mismatchedOwners = listOf(
            Owner(signInGen1, 1L),
            Owner(otherHostSignIn, 1L),
            Owner(otherUserSignIn, 1L)
        )
        for (badOwner in mismatchedOwners) {
            val badSocket = SocketOwner(badOwner, 50L)
            assertThrows(StaleOwnerException::class.java) { owners.updateSocketOwner(badSocket) }
            assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(badSocket) }
            assertThrows(StaleOwnerException::class.java) { owners.validateOwner(badOwner) }
            assertThrows(StaleOwnerException::class.java) { owners.incrementSocketEpoch(badOwner) }
        }
        assertEquals(activeSocket, owners.getSocketOwner())

        // Overflow is rejected by both increment overloads even after retiring the MAX_VALUE socket
        val maxSocket = SocketOwner(activeOwner, Long.MAX_VALUE)
        owners.updateSocketOwner(maxSocket)
        assertThrows(IllegalStateException::class.java) {
            owners.incrementSocketEpoch(activeOwner)
        }
        assertTrue(owners.retireSocketOwner(maxSocket))
        assertNull(owners.getSocketOwner())
        assertThrows(IllegalStateException::class.java) {
            owners.incrementSocketEpoch()
        }
        assertThrows(IllegalStateException::class.java) {
            owners.incrementSocketEpoch(activeOwner)
        }
        assertNull(owners.getSocketOwner())
    }

    @Test
    fun testMultiPrincipalScopesVerbatimComparisonAndFullSerialization() {
        val aliceGen5 = SignIn("https://example.com", "alice", 5L)
        val bobGen0 = SignIn("https://example.com", "bob", 0L)
        val upperAliceGen0 = SignIn("https://example.com", "Alice", 0L)
        val paddedAliceGen0 = SignIn("https://example.com", "alice ", 0L)
        val upperHostGen0 = SignIn("https://Example.com", "alice", 0L)

        owners.updateSignIn(aliceGen5)
        val aliceOwner = Owner(aliceGen5, 1L)
        val aliceSocket = SocketOwner(aliceOwner, 2L)
        owners.updateOwner(aliceOwner)
        owners.updateSocketOwner(aliceSocket)

        // Switching principal on the same endpoint invalidates Owner/SocketOwner and uses independent scope
        owners.updateSignIn(bobGen0)
        assertEquals(bobGen0, owners.getSignIn())
        assertNull(owners.getOwner())
        assertNull(owners.getSocketOwner())
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(aliceOwner) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(aliceSocket) }

        // Verbatim identifiers: case and whitespace differences are distinct scopes
        owners.updateSignIn(upperAliceGen0)
        assertEquals(upperAliceGen0, owners.getSignIn())
        owners.updateSignIn(paddedAliceGen0)
        assertEquals(paddedAliceGen0, owners.getSignIn())
        owners.updateSignIn(upperHostGen0)
        assertEquals(upperHostGen0, owners.getSignIn())

        // Original alice scope preserved its high-water mark of 5L
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(SignIn("https://example.com", "alice", 5L))
        }
        val aliceGen6 = SignIn("https://example.com", "alice", 6L)
        owners.updateSignIn(aliceGen6)
        assertEquals(aliceGen6, owners.getSignIn())

        // Serialization round-trips and init validation on decode for SignIn and Owner
        val encodedSignIn = Json.encodeToString(aliceGen6)
        assertEquals(aliceGen6, Json.decodeFromString<SignIn>(encodedSignIn))
        val badAuthGenJson = encodedSignIn.replace(
            "\"authenticationGeneration\":6",
            "\"authenticationGeneration\":-1"
        )
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<SignIn>(badAuthGenJson)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<SignIn>(encodedSignIn.replace("\"https://example.com\"", "\"\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<SignIn>(encodedSignIn.replace("\"alice\"", "\"\""))
        }

        val owner = Owner(aliceGen6, 3L)
        val encodedOwner = Json.encodeToString(owner)
        assertEquals(owner, Json.decodeFromString<Owner>(encodedOwner))
        val badWorkspaceGenJson = encodedOwner.replace(
            "\"workspaceConnectionGeneration\":3",
            "\"workspaceConnectionGeneration\":-1"
        )
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<Owner>(badWorkspaceGenJson)
        }
    }

    @Test
    fun testUnconditionalAndCrossParentRetirement() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val signIn2 = SignIn("https://example.com", "user1", 2L)
        val owner1 = Owner(signIn1, 1L)
        val owner2 = Owner(signIn1, 2L)

        // Retiring when nothing is active returns false
        assertFalse(owners.retireSocketOwner())
        assertFalse(owners.retireSignIn())

        owners.updateSignIn(signIn1)
        owners.updateOwner(owner2)
        val activeSocket = SocketOwner(owner2, 4L)
        owners.updateSocketOwner(activeSocket)

        // Same socketEpoch but mismatched parent Owner or SignIn must not retire active socket
        assertFalse(owners.retireSocketOwner(SocketOwner(owner1, 4L)))
        assertFalse(owners.retireSocketOwner(SocketOwner(Owner(signIn2, 2L), 4L)))
        assertEquals(activeSocket, owners.getSocketOwner())

        // Unconditional socket retirement preserves Owner/SignIn and epoch high-water mark
        assertTrue(owners.retireSocketOwner())
        assertNull(owners.getSocketOwner())
        assertFalse(owners.retireSocketOwner())
        assertEquals(owner2, owners.getOwner())
        assertEquals(signIn1, owners.getSignIn())

        val nextSocket = owners.incrementSocketEpoch()
        assertEquals(SocketOwner(owner2, 5L), nextSocket)

        // Mismatched expectedSignIn by generation, endpoint, or principal does not retire SignIn
        assertFalse(owners.retireSignIn(SignIn("https://example.com", "user1", 2L)))
        assertFalse(owners.retireSignIn(SignIn("https://other.example.com", "user1", 1L)))
        assertFalse(owners.retireSignIn(SignIn("https://example.com", "user2", 1L)))
        assertEquals(nextSocket, owners.getSocketOwner())

        // Unconditional SignIn retirement clears all 3 levels and prevents stale resurrection
        assertTrue(owners.retireSignIn())
        assertFalse(owners.retireSignIn())
        assertNull(owners.getSignIn())
        assertNull(owners.getOwner())
        assertNull(owners.getSocketOwner())
        assertNull(owners.incrementSocketEpoch())
        assertThrows(StaleOwnerException::class.java) { owners.updateOwner(owner2) }
        assertThrows(StaleOwnerException::class.java) { owners.updateSocketOwner(SocketOwner(owner2, 6L)) }
        assertThrows(StaleOwnerException::class.java) { owners.updateSignIn(signIn1) }
    }

    @Test
    fun testWithSocketOwnerNullCaptureNullableReturnAndHierarchyInvalidation() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val owner1 = Owner(signIn1, 1L)
        owners.updateSignIn(signIn1)
        owners.updateOwner(owner1)
        val epoch1 = owners.incrementSocketEpoch(owner1)

        // Null capture fails pre-flight and never executes block
        var executed = false
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(null) {
                executed = true
                "never"
            }
        }
        assertFalse(executed)

        // Supports nullable return types cleanly
        val nullableResult: String? = owners.withSocketOwner(epoch1) { null }
        assertNull(nullableResult)

        // Invalidated mid-block by retireSocketOwner()
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(epoch1) {
                owners.retireSocketOwner(epoch1)
                "stale-after-retire"
            }
        }

        // Invalidated mid-block by explicit updateSocketOwner()
        val epoch2 = owners.incrementSocketEpoch(owner1)
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(epoch2) {
                owners.updateSocketOwner(SocketOwner(owner1, 10L))
                "stale-after-update-socket"
            }
        }

        // Invalidated mid-block by updateOwner()
        val epoch10 = owners.getSocketOwner()!!
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(epoch10) {
                owners.updateOwner(Owner(signIn1, 2L))
                "stale-after-update-owner"
            }
        }

        // Invalidated mid-block by updateSignIn()
        val owner2 = owners.getOwner()!!
        val owner2Socket = owners.incrementSocketEpoch(owner2)
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(owner2Socket) {
                owners.updateSignIn(SignIn("https://example.com", "user1", 2L))
                "stale-after-update-signin"
            }
        }

        // Invalidated mid-block by retireSignIn()
        val signIn2 = owners.getSignIn()!!
        val ownerUnderSignIn2 = Owner(signIn2, 1L)
        owners.updateOwner(ownerUnderSignIn2)
        val socketUnderSignIn2 = owners.incrementSocketEpoch(ownerUnderSignIn2)
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(socketUnderSignIn2) {
                owners.retireSignIn()
                "stale-after-retire-signin"
            }
        }
    }

    @Test
    fun testConcurrentMixedEpochUpdateSocketOwnerPreservesMonotonicityAndRejectsStaleOwner() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val staleOwner = Owner(signIn, 1L)
        val activeOwner = Owner(signIn, 2L)
        owners.updateSignIn(signIn)
        owners.updateOwner(staleOwner)
        owners.updateOwner(activeOwner)

        val activeWorkers = 6
        val staleWorkers = 2
        val epochsPerWorker = 40
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(activeWorkers + staleWorkers)
        val succeededEpochs = Collections.synchronizedList(mutableListOf<Long>())
        val staleSuccesses = AtomicInteger(0)
        val staleRejections = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(activeWorkers + staleWorkers)
        try {
            val activeFutures = (0 until activeWorkers).map { idx ->
                val epochs = shuffledEpochsForWorker(idx, activeWorkers, epochsPerWorker)
                executor.submit(Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    runActiveEpochAttempts(activeOwner, epochs, succeededEpochs)
                })
            }
            val staleFutures = (0 until staleWorkers).map { idx ->
                executor.submit(Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    runStaleEpochAttempts(staleOwner, idx, epochsPerWorker, staleSuccesses, staleRejections)
                })
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()
            activeFutures.forEach { it.get(5, TimeUnit.SECONDS) }
            staleFutures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(0, staleSuccesses.get())
        assertEquals(staleWorkers * epochsPerWorker, staleRejections.get())
        assertTrue(succeededEpochs.isNotEmpty())
        assertEquals("Each winning epoch must be unique", succeededEpochs.size, succeededEpochs.toSet().size)
        val finalSocket = owners.getSocketOwner()
        assertEquals(activeOwner, finalSocket?.owner)
        assertEquals(succeededEpochs.maxOrNull(), finalSocket?.socketEpoch)
    }

    @Test
    fun testConcurrentRetireAndIncrementSocketEpochPreventsDoubleRetireAndEpochReuse() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)

        val incrementWorkers = 4
        val retireWorkers = 4
        val iterationsPerIncrementWorker = 60
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(incrementWorkers + retireWorkers)
        val doneIncrementing = AtomicBoolean(false)
        val issuedEpochs = Collections.synchronizedSet(mutableSetOf<Long>())
        val retireCountsByEpoch = ConcurrentHashMap<Long, AtomicInteger>()
        val executor = Executors.newFixedThreadPool(incrementWorkers + retireWorkers)
        try {
            val incFutures = (0 until incrementWorkers).map {
                executor.submit(Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    repeat(iterationsPerIncrementWorker) {
                        val issued = owners.incrementSocketEpoch(owner)
                        assertEquals(owner, issued.owner)
                        assertTrue("Epoch ${issued.socketEpoch} reused", issuedEpochs.add(issued.socketEpoch))
                    }
                })
            }
            val retireFutures = (0 until retireWorkers).map {
                executor.submit(Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    while (!doneIncrementing.get()) {
                        val captured = owners.getSocketOwner()
                        if (captured != null && owners.retireSocketOwner(captured)) {
                            val count = retireCountsByEpoch
                                .computeIfAbsent(captured.socketEpoch) { AtomicInteger(0) }
                                .incrementAndGet()
                            assertEquals("Epoch ${captured.socketEpoch} was retired more than once", 1, count)
                        }
                    }
                })
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()
            incFutures.forEach { it.get(5, TimeUnit.SECONDS) }
            doneIncrementing.set(true)
            retireFutures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val totalIssued = (incrementWorkers * iterationsPerIncrementWorker).toLong()
        assertEquals((1L..totalIssued).toSet(), issuedEpochs)
        assertTrue(retireCountsByEpoch.values.all { it.get() == 1 })
        val finalSocket = owners.getSocketOwner()
        if (finalSocket != null) {
            assertEquals(SocketOwner(owner, totalIssued), finalSocket)
        }
        assertEquals(SocketOwner(owner, totalIssued + 1L), owners.incrementSocketEpoch(owner))
    }

    @Test
    fun testConcurrentUpdateOwnerAllowsOnlySingleWinnerPerGeneration() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)

        val workerCount = 8
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(workerCount)
        val successes = AtomicInteger(0)
        val staleFailures = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val target = Owner(signIn, 1L)
            val futures = (0 until workerCount).map {
                executor.submit(Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    try {
                        owners.updateOwner(target)
                        successes.incrementAndGet()
                    } catch (_: StaleOwnerException) {
                        staleFailures.incrementAndGet()
                    }
                })
            }
            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, successes.get())
        assertEquals(workerCount - 1, staleFailures.get())
        assertEquals(Owner(signIn, 1L), owners.getOwner())
    }

    @Test
    fun testConcurrentHierarchyTransitionsRejectStaleContendersAndPreserveIntegrity() {
        val signInGen1 = SignIn("https://example.com", "user1", 1L)
        val ownerGen1 = Owner(signInGen1, 1L)
        val socketGen1 = SocketOwner(ownerGen1, 1L)

        // Establish and supersede generation 1 before launching stale contenders
        owners.updateSignIn(signInGen1)
        owners.updateOwner(ownerGen1)
        owners.updateSocketOwner(socketGen1)
        owners.updateSignIn(SignIn("https://example.com", "user1", 2L))

        val totalGenerations = 30L
        val done = AtomicBoolean(false)
        val staleMutationSuccesses = AtomicInteger(0)
        val highestObservedGen = AtomicLong(2L)
        val regressionDetected = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val coordinator = executor.submit(Callable {
                advanceHierarchyGenerations(2L..totalGenerations)
                done.set(true)
            })
            val staleContenders = (0 until 2).map { idx ->
                executor.submit(Callable {
                    hammerStaleGeneration(
                        staleSocket = socketGen1,
                        offset = idx.toLong(),
                        done = done,
                        staleSuccesses = staleMutationSuccesses
                    )
                })
            }
            val observer = executor.submit(Callable {
                observeMonotonicHierarchy(done, highestObservedGen, regressionDetected)
            })

            coordinator.get(10, TimeUnit.SECONDS)
            staleContenders.forEach { it.get(10, TimeUnit.SECONDS) }
            observer.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(0, staleMutationSuccesses.get())
        assertFalse("Observed SignIn authenticationGeneration regression", regressionDetected.get())
        val finalSignIn = SignIn("https://example.com", "user1", totalGenerations)
        val finalOwner = Owner(finalSignIn, 1L)
        assertEquals(finalSignIn, owners.getSignIn())
        assertEquals(finalOwner, owners.getOwner())
        assertEquals(SocketOwner(finalOwner, 2L), owners.getSocketOwner())
    }

    private fun shuffledEpochsForWorker(workerIdx: Int, workerCount: Int, count: Int): List<Long> =
        (1..count)
            .map { step -> (step * workerCount + workerIdx).toLong() }
            .shuffled(Random(workerIdx.toLong() + 17L))

    private fun runActiveEpochAttempts(
        activeOwner: Owner,
        epochs: List<Long>,
        succeededEpochs: MutableList<Long>
    ) {
        for (epoch in epochs) {
            try {
                owners.updateSocketOwner(SocketOwner(activeOwner, epoch))
                succeededEpochs.add(epoch)
            } catch (_: StaleOwnerException) {
                // Expected when a higher or equal epoch already won
            }
        }
    }

    private fun runStaleEpochAttempts(
        staleOwner: Owner,
        workerIdx: Int,
        attempts: Int,
        staleSuccesses: AtomicInteger,
        staleRejections: AtomicInteger
    ) {
        for (step in 1..attempts) {
            val hugeEpoch = 10_000L + workerIdx * 1_000L + step
            try {
                owners.updateSocketOwner(SocketOwner(staleOwner, hugeEpoch))
                staleSuccesses.incrementAndGet()
            } catch (_: StaleOwnerException) {
                staleRejections.incrementAndGet()
            }
        }
    }

    private fun advanceHierarchyGenerations(generations: LongRange) {
        for (gen in generations) {
            val signIn = SignIn("https://example.com", "user1", gen)
            owners.updateSignIn(signIn)
            val owner = Owner(signIn, 1L)
            owners.updateOwner(owner)
            assertEquals(1L, owners.incrementSocketEpoch(owner).socketEpoch)
            assertEquals(2L, owners.incrementSocketEpoch(owner).socketEpoch)
        }
    }

    private fun hammerStaleGeneration(
        staleSocket: SocketOwner,
        offset: Long,
        done: AtomicBoolean,
        staleSuccesses: AtomicInteger
    ) {
        val ownerGen1 = staleSocket.owner
        val signInGen1 = ownerGen1.signIn
        var attempt = 2L
        while (!done.get()) {
            recordUnexpectedSuccess(staleSuccesses) { owners.updateSignIn(signInGen1) }
            recordUnexpectedSuccess(staleSuccesses) { owners.updateOwner(Owner(signInGen1, attempt)) }
            recordUnexpectedSuccess(staleSuccesses) {
                owners.updateSocketOwner(SocketOwner(ownerGen1, attempt + offset))
            }
            recordUnexpectedSuccess(staleSuccesses) { owners.incrementSocketEpoch(ownerGen1) }
            recordUnexpectedSuccess(staleSuccesses) { owners.withSocketOwner(staleSocket) { it.socketEpoch } }
            attempt++
        }
    }

    private fun observeMonotonicHierarchy(
        done: AtomicBoolean,
        highestObservedGen: AtomicLong,
        regressionDetected: AtomicBoolean
    ) {
        while (!done.get()) {
            val currentSignIn = owners.getSignIn()
            if (currentSignIn != null) {
                val gen = currentSignIn.authenticationGeneration
                val prevMax = highestObservedGen.getAndAccumulate(gen, ::maxOf)
                if (gen < prevMax) {
                    regressionDetected.set(true)
                }
            }
            val currentSocket = owners.getSocketOwner()
            if (currentSocket != null) {
                assertTrue(currentSocket.owner.signIn.authenticationGeneration >= 2L)
                assertTrue(currentSocket.socketEpoch in 1L..2L)
            }
        }
    }

    private inline fun recordUnexpectedSuccess(counter: AtomicInteger, block: () -> Unit) {
        try {
            block()
            counter.incrementAndGet()
        } catch (_: StaleOwnerException) {
            // Expected stale rejection
        }
    }
}
