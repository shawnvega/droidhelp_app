package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OwnersTest {

    private lateinit var owners: Owners

    @Before
    fun setUp() {
        owners = Owners()
    }

    @Test
    fun testValidTransitions() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        assertEquals(signIn, owners.getSignIn())

        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        assertEquals(owner, owners.getOwner())

        val socketOwner = SocketOwner(owner, 1L)
        owners.updateSocketOwner(socketOwner)
        assertEquals(socketOwner, owners.getSocketOwner())
    }

    @Test
    fun testIncrementSocketEpoch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)

        // Initial socket owner should be null or we set it
        val initialSocketOwner = SocketOwner(owner, 0L)
        owners.updateSocketOwner(initialSocketOwner)

        val returned = owners.incrementSocketEpoch()
        val updatedSocketOwner = owners.getSocketOwner()
        assertEquals(returned, updatedSocketOwner)
        assertEquals(1L, updatedSocketOwner?.socketEpoch)
        assertEquals(owner, updatedSocketOwner?.owner)
    }

    @Test
    fun testValidateOwnerSuccess() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)

        owners.validateOwner(owner) // Should not throw
    }

    @Test
    fun testValidateOwnerFailure() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner1 = Owner(signIn, 1L)
        owners.updateOwner(owner1)

        val owner2 = Owner(signIn, 2L)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateOwner(owner2)
        }
    }

    @Test
    fun testValidateSocketOwnerSuccess() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner)

        owners.validateSocketOwner(socketOwner) // Should not throw
    }

    @Test
    fun testValidateSocketOwnerFailure() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner1 = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner1)

        val socketOwner2 = SocketOwner(owner, 6L)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateSocketOwner(socketOwner2)
        }
    }

    @Test
    fun testSocketMismatch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner = Owner(signIn, 1L)
        owners.updateOwner(owner)
        val socketOwner = SocketOwner(owner, 5L)
        owners.updateSocketOwner(socketOwner)

        // Capture a different socket owner
        val staleSocketOwner = SocketOwner(owner, 4L)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateSocketOwner(staleSocketOwner)
        }
    }

    @Test
    fun testOwnerMismatch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)
        val owner1 = Owner(signIn, 1L)
        owners.updateOwner(owner1)

        val owner2 = Owner(signIn, 2L)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateOwner(owner2)
        }
    }

    @Test
    fun testValidateRejectsNullAndAbsentOwners() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        val socketOwner = SocketOwner(owner, 1L)

        // Even when current state is null, null captures must fail validation
        assertThrows(StaleOwnerException::class.java) { owners.validateSignIn(null) }
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(null) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(null) }

        // Non-null captures also fail when no active owner exists
        assertThrows(StaleOwnerException::class.java) { owners.validateSignIn(signIn) }
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(owner) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(socketOwner) }

        // Null captures still fail once active owners are established
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.updateSocketOwner(socketOwner)
        assertThrows(StaleOwnerException::class.java) { owners.validateSignIn(null) }
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(null) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(null) }
    }

    @Test
    fun testSignInTransitionInvalidatesOwnerAndSocketOwner() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val owner1 = Owner(signIn1, 1L)
        val socketOwner1 = SocketOwner(owner1, 3L)
        owners.updateSignIn(signIn1)
        owners.updateOwner(owner1)
        owners.updateSocketOwner(socketOwner1)

        // Advancing authenticationGeneration clears lower levels
        val signIn2 = SignIn("https://example.com", "user1", 2L)
        owners.updateSignIn(signIn2)

        assertEquals(signIn2, owners.getSignIn())
        assertNull(owners.getOwner())
        assertNull(owners.getSocketOwner())
        owners.validateSignIn(signIn2)
        assertThrows(StaleOwnerException::class.java) { owners.validateSignIn(signIn1) }
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(owner1) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(socketOwner1) }

        // Switching host/principal also clears lower levels
        val owner2 = Owner(signIn2, 1L)
        val socketOwner2 = SocketOwner(owner2, 1L)
        owners.updateOwner(owner2)
        owners.updateSocketOwner(socketOwner2)

        val otherHost = SignIn("https://other.example.com", "user1", 1L)
        owners.updateSignIn(otherHost)
        assertEquals(otherHost, owners.getSignIn())
        assertNull(owners.getOwner())
        assertNull(owners.getSocketOwner())
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(owner2) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(socketOwner2) }
    }

    @Test
    fun testSignInIdempotentForIdenticalActiveSignIn() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        val socketOwner = SocketOwner(owner, 2L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.updateSocketOwner(socketOwner)

        // Re-asserting the exact active SignIn preserves active Owner and SocketOwner
        owners.updateSignIn(signIn)
        assertEquals(signIn, owners.getSignIn())
        assertEquals(owner, owners.getOwner())
        assertEquals(socketOwner, owners.getSocketOwner())
    }

    @Test
    fun testOwnerTransitionInvalidatesSocketOwner() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner1 = Owner(signIn, 1L)
        val socketOwner1 = SocketOwner(owner1, 5L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner1)
        owners.updateSocketOwner(socketOwner1)

        val owner2 = Owner(signIn, 2L)
        owners.updateOwner(owner2)

        assertEquals(owner2, owners.getOwner())
        assertNull(owners.getSocketOwner())
        assertThrows(StaleOwnerException::class.java) { owners.validateOwner(owner1) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(socketOwner1) }

        // New owner gets a fresh socket epoch namespace starting at 1L via incrementSocketEpoch
        val socketOwner2 = owners.incrementSocketEpoch(owner2)
        assertEquals(SocketOwner(owner2, 1L), socketOwner2)
        owners.validateSocketOwner(socketOwner2)
    }

    @Test
    fun testUpdateOwnerRequiresMatchingActiveSignIn() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val signIn2 = SignIn("https://example.com", "user1", 2L)
        val owner1 = Owner(signIn1, 1L)

        // Updating Owner without an active SignIn is rejected
        assertThrows(StaleOwnerException::class.java) {
            owners.updateOwner(owner1)
        }
        assertNull(owners.getOwner())

        // Updating Owner with a stale SignIn is rejected without mutating state
        owners.updateSignIn(signIn2)
        val owner2 = Owner(signIn2, 1L)
        owners.updateOwner(owner2)

        assertThrows(StaleOwnerException::class.java) {
            owners.updateOwner(owner1)
        }
        assertEquals(owner2, owners.getOwner())
    }

    @Test
    fun testUpdateSocketOwnerRequiresMatchingActiveOwner() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner1 = Owner(signIn, 1L)
        val owner2 = Owner(signIn, 2L)
        val staleSocket = SocketOwner(owner1, 1L)

        // Without active Owner
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(staleSocket)
        }
        assertNull(owners.getSocketOwner())

        // With active SignIn but no active Owner
        owners.updateSignIn(signIn)
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(staleSocket)
        }

        // With a newer active Owner
        owners.updateOwner(owner2)
        val activeSocket = SocketOwner(owner2, 1L)
        owners.updateSocketOwner(activeSocket)

        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner1, 99L))
        }
        assertEquals(activeSocket, owners.getSocketOwner())
    }

    @Test
    fun testSignInRejectsRegressiveAndRetiredGenerations() {
        val hostAGen2 = SignIn("https://host-a.example", "alice", 2L)
        val hostAGen1 = SignIn("https://host-a.example", "alice", 1L)
        val hostBGen1 = SignIn("https://host-b.example", "alice", 1L)
        val hostAGen3 = SignIn("https://host-a.example", "alice", 3L)

        owners.updateSignIn(hostAGen2)

        // Regressive generation on same active scope rejected
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(hostAGen1)
        }
        assertEquals(hostAGen2, owners.getSignIn())

        // Switching to another host succeeds
        owners.updateSignIn(hostBGen1)
        assertEquals(hostBGen1, owners.getSignIn())

        // Stale callbacks from hostA (gen 1 or previously retired gen 2) cannot resurrect
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(hostAGen1)
        }
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(hostAGen2)
        }
        assertEquals(hostBGen1, owners.getSignIn())

        // Fresh higher generation for hostA succeeds
        owners.updateSignIn(hostAGen3)
        assertEquals(hostAGen3, owners.getSignIn())
    }

    @Test
    fun testOwnerRejectsRegressiveAndDuplicateGenerations() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        owners.updateSignIn(signIn)

        val owner2 = Owner(signIn, 2L)
        val socket2 = SocketOwner(owner2, 4L)
        owners.updateOwner(owner2)
        owners.updateSocketOwner(socket2)

        // Regressive workspaceConnectionGeneration rejected
        assertThrows(StaleOwnerException::class.java) {
            owners.updateOwner(Owner(signIn, 1L))
        }
        // Duplicate workspaceConnectionGeneration rejected
        assertThrows(StaleOwnerException::class.java) {
            owners.updateOwner(Owner(signIn, 2L))
        }
        // Active Owner and SocketOwner remain intact
        assertEquals(owner2, owners.getOwner())
        assertEquals(socket2, owners.getSocketOwner())
    }

    @Test
    fun testSocketOwnerRejectsRegressiveAndDuplicateEpochs() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)

        val epoch5 = SocketOwner(owner, 5L)
        owners.updateSocketOwner(epoch5)

        // Regressive epoch rejected
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner, 4L))
        }
        // Duplicate epoch rejected (prevents two sockets from claiming the same epoch)
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner, 5L))
        }
        assertEquals(epoch5, owners.getSocketOwner())

        // Strictly higher epoch succeeds
        val epoch6 = SocketOwner(owner, 6L)
        owners.updateSocketOwner(epoch6)
        assertEquals(epoch6, owners.getSocketOwner())
    }

    @Test
    fun testIncrementSocketEpochWithoutOwnerReturnsNull() {
        assertNull(owners.incrementSocketEpoch())
        assertNull(owners.getSocketOwner())
    }

    @Test
    fun testIncrementSocketEpochWithExpectedOwnerFencesStaleOwner() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner1 = Owner(signIn, 1L)
        val owner2 = Owner(signIn, 2L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner1)

        val s1 = owners.incrementSocketEpoch(owner1)
        assertEquals(SocketOwner(owner1, 1L), s1)

        // Transition to owner2; stale owner1 cannot advance socket epoch
        owners.updateOwner(owner2)
        assertThrows(StaleOwnerException::class.java) {
            owners.incrementSocketEpoch(owner1)
        }
        assertNull(owners.getSocketOwner())

        val s2 = owners.incrementSocketEpoch(owner2)
        assertEquals(SocketOwner(owner2, 1L), s2)
    }

    @Test
    fun testIncrementSocketEpochRejectsOverflow() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.updateSocketOwner(SocketOwner(owner, Long.MAX_VALUE))

        assertThrows(IllegalStateException::class.java) {
            owners.incrementSocketEpoch()
        }
        assertEquals(Long.MAX_VALUE, owners.getSocketOwner()?.socketEpoch)
    }

    @Test
    fun testRetireSocketOwnerPreventsEpochReuseAndStaleClobber() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)

        val epoch5 = SocketOwner(owner, 5L)
        owners.updateSocketOwner(epoch5)

        // Mismatched expectedSocketOwner cannot retire the active socket
        assertFalse(owners.retireSocketOwner(expectedSocketOwner = SocketOwner(owner, 4L)))
        assertEquals(epoch5, owners.getSocketOwner())

        // Exact match retires the active socket
        assertTrue(owners.retireSocketOwner(expectedSocketOwner = epoch5))
        assertNull(owners.getSocketOwner())
        assertFalse(owners.retireSocketOwner(expectedSocketOwner = epoch5))

        // Validation rejects the retired epoch and null
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(epoch5) }
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(null) }

        // Retired epoch cannot be resurrected via updateSocketOwner
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(epoch5)
        }
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSocketOwner(SocketOwner(owner, 1L))
        }

        // Next incrementSocketEpoch advances past retired epoch 5 to 6 (no ABA reuse!)
        val epoch6 = owners.incrementSocketEpoch(owner)
        assertEquals(SocketOwner(owner, 6L), epoch6)
        assertThrows(StaleOwnerException::class.java) { owners.validateSocketOwner(epoch5) }
        owners.validateSocketOwner(epoch6)
    }

    @Test
    fun testRetireSignInClearsHierarchyAndPreventsResurrection() {
        val signIn1 = SignIn("https://example.com", "user1", 1L)
        val otherSignIn = SignIn("https://other.example.com", "user1", 1L)
        val owner1 = Owner(signIn1, 1L)
        val socket1 = SocketOwner(owner1, 2L)
        owners.updateSignIn(signIn1)
        owners.updateOwner(owner1)
        owners.updateSocketOwner(socket1)

        // Stale/wrong expectedSignIn does not retire active sign-in
        assertFalse(owners.retireSignIn(expectedSignIn = otherSignIn))
        assertEquals(signIn1, owners.getSignIn())
        assertEquals(owner1, owners.getOwner())
        assertEquals(socket1, owners.getSocketOwner())

        // Exact match retires all 3 levels
        assertTrue(owners.retireSignIn(expectedSignIn = signIn1))
        assertNull(owners.getSignIn())
        assertNull(owners.getOwner())
        assertNull(owners.getSocketOwner())
        assertFalse(owners.retireSignIn())

        // Stale sign-in callback cannot resurrect the retired generation
        assertThrows(StaleOwnerException::class.java) {
            owners.updateSignIn(signIn1)
        }

        // Fresh higher generation succeeds
        val signIn2 = SignIn("https://example.com", "user1", 2L)
        owners.updateSignIn(signIn2)
        assertEquals(signIn2, owners.getSignIn())
    }

    @Test
    fun testWithSocketOwnerFencesPreFlightAndPostFlight() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        val epoch1 = owners.incrementSocketEpoch(owner)

        // Happy path
        val result = owners.withSocketOwner(epoch1) { active ->
            "ok:${active.socketEpoch}"
        }
        assertEquals("ok:1", result)

        // Pre-flight rejection never executes block
        var invoked = false
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(SocketOwner(owner, 99L)) {
                invoked = true
                "should-not-run"
            }
        }
        assertFalse(invoked)

        // Post-flight rejection when socket epoch rotates mid-operation
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(epoch1) {
                owners.incrementSocketEpoch(owner)
                "stale-result"
            }
        }
    }

    @Test
    fun testWithSocketOwnerPreservesCurrentErrorsAndSupersedesStaleErrors() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        val epoch1 = owners.incrementSocketEpoch(owner)

        // Error propagates unchanged when SocketOwner is still active
        assertThrows(IOException::class.java) {
            owners.withSocketOwner(epoch1) {
                throw IOException("transient failure")
            }
        }

        // Non-cancellation error is superseded by StaleOwnerException if ownership changed
        assertThrows(StaleOwnerException::class.java) {
            owners.withSocketOwner(epoch1) {
                owners.retireSocketOwner(epoch1)
                throw IOException("stale socket failure")
            }
        }
    }

    @Test
    fun testWithSocketOwnerPreservesCancellationExceptionEvenWhenRetired() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        val epoch1 = owners.incrementSocketEpoch(owner)

        // CancellationException always propagates unchanged even if SocketOwner retired
        assertThrows(CancellationException::class.java) {
            owners.withSocketOwner(epoch1) {
                owners.retireSocketOwner(epoch1)
                throw CancellationException("caller cancelled")
            }
        }
    }

    @Test
    fun testDataModelValidationByteExactPrincipalsAndSerialization() {
        assertThrows(IllegalArgumentException::class.java) { SignIn("", "user", 1L) }
        assertThrows(IllegalArgumentException::class.java) { SignIn("https://example.com", "", 1L) }
        assertThrows(IllegalArgumentException::class.java) { SignIn("https://example.com", "user", -1L) }

        val signIn = SignIn("https://example.com", "user", 0L)
        assertThrows(IllegalArgumentException::class.java) { Owner(signIn, -1L) }
        val owner = Owner(signIn, 0L)
        assertThrows(IllegalArgumentException::class.java) { SocketOwner(owner, -1L) }

        // Opaque principals are compared verbatim without Unicode normalization
        val composed = SignIn("https://example.com", "caf\u00e9", 1L)
        val decomposed = SignIn("https://example.com", "cafe\u0301", 1L)
        assertNotEquals(composed, decomposed)

        // Serialization round-trip succeeds and rejects negative epochs on decode
        val socketOwner = SocketOwner(owner, 7L)
        val encoded = Json.encodeToString(socketOwner)
        assertEquals(socketOwner, Json.decodeFromString<SocketOwner>(encoded))

        val negativeEpochJson = encoded.replace("\"socketEpoch\":7", "\"socketEpoch\":-1")
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<SocketOwner>(negativeEpochJson)
        }
    }

    @Test
    fun testConcurrentUpdateSocketOwnerAllowsOnlySingleWinnerPerEpoch() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)

        val workerCount = 8
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(workerCount)
        val successes = AtomicInteger(0)
        val staleFailures = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val target = SocketOwner(owner, 1L)
            val tasks = (0 until workerCount).map {
                Callable {
                    readyLatch.countDown()
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    try {
                        owners.updateSocketOwner(target)
                        successes.incrementAndGet()
                    } catch (_: StaleOwnerException) {
                        staleFailures.incrementAndGet()
                    }
                }
            }
            val futures = tasks.map { executor.submit(it) }
            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startLatch.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, successes.get())
        assertEquals(workerCount - 1, staleFailures.get())
        assertEquals(SocketOwner(owner, 1L), owners.getSocketOwner())
    }

    @Test
    fun testConcurrentIncrementSocketEpochProducesUniqueMonotonicEpochs() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)

        val workerCount = 8
        val iterationsPerWorker = 50
        val issuedEpochs = Collections.synchronizedSet(mutableSetOf<Long>())
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val tasks = (0 until workerCount).map {
                Callable {
                    repeat(iterationsPerWorker) {
                        val issued = owners.incrementSocketEpoch(owner)
                        assertEquals(owner, issued.owner)
                        assertTrue(issuedEpochs.add(issued.socketEpoch))
                    }
                }
            }
            val futures = executor.invokeAll(tasks)
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val total = (workerCount * iterationsPerWorker).toLong()
        assertEquals(total.toInt(), issuedEpochs.size)
        assertEquals((1L..total).toSet(), issuedEpochs)
        assertEquals(total, owners.getSocketOwner()?.socketEpoch)
    }

    @Test
    fun testConcurrentSocketOperationsFencedAcrossEpochTransitions() {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.incrementSocketEpoch(owner)

        val executor = Executors.newFixedThreadPool(4)
        val rotations = 100
        val staleCatches = AtomicInteger(0)
        val validCompletions = AtomicInteger(0)
        try {
            val rotator = executor.submit(Callable {
                repeat(rotations) { step ->
                    val active = owners.getSocketOwner()
                    if (step % 3 == 0) {
                        owners.retireSocketOwner(active)
                    }
                    owners.incrementSocketEpoch(owner)
                }
            })
            val workers = (0 until 3).map {
                executor.submit(Callable {
                    repeat(rotations * 2) {
                        val captured = owners.getSocketOwner()
                        try {
                            val epoch = owners.withSocketOwner(captured) { it.socketEpoch }
                            assertTrue(epoch >= 1L)
                            validCompletions.incrementAndGet()
                        } catch (_: StaleOwnerException) {
                            staleCatches.incrementAndGet()
                        }
                    }
                })
            }
            rotator.get(5, TimeUnit.SECONDS)
            workers.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(validCompletions.get() + staleCatches.get() == rotations * 6)
        assertEquals((rotations + 1).toLong(), owners.getSocketOwner()?.socketEpoch)
    }

    @Test
    fun testWithSocketOwnerFencesAcrossCoroutineSuspension() = runTest {
        val signIn = SignIn("https://example.com", "user1", 1L)
        val owner = Owner(signIn, 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        val epoch1 = owners.incrementSocketEpoch(owner)

        val enteredBlock = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        supervisorScope {
            val fencedOp = async {
                owners.withSocketOwner(epoch1) { active ->
                    enteredBlock.complete(Unit)
                    allowCompletion.await()
                    "completed:${active.socketEpoch}"
                }
            }

            enteredBlock.await()
            assertEquals(2L, owners.incrementSocketEpoch(owner).socketEpoch)
            allowCompletion.complete(Unit)

            var caughtStale = false
            try {
                fencedOp.await()
            } catch (_: StaleOwnerException) {
                caughtStale = true
            }
            assertTrue("Expected StaleOwnerException when SocketOwner rotated while suspended", caughtStale)
        }
    }
}
