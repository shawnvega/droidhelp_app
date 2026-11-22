package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ReconnectPolicyTest {

    @Before
    fun setUp() {
        ReconnectPolicy.reset()
    }

    @After
    fun tearDown() {
        ReconnectPolicy.reset()
    }

    @Test
    fun testDelay() {
        assertEquals(1.seconds, ReconnectPolicy.delay(Int.MIN_VALUE))
        assertEquals(1.seconds, ReconnectPolicy.delay(-1))
        assertEquals(1.seconds, ReconnectPolicy.delay(0))
        assertEquals(2.seconds, ReconnectPolicy.delay(1))
        assertEquals(4.seconds, ReconnectPolicy.delay(2))
        assertEquals(8.seconds, ReconnectPolicy.delay(3))
        assertEquals(15.seconds, ReconnectPolicy.delay(4))
        assertEquals(30.seconds, ReconnectPolicy.delay(5))
        assertEquals(30.seconds, ReconnectPolicy.delay(6))
        assertEquals(30.seconds, ReconnectPolicy.delay(10)) // Cap at last delay
        assertEquals(30.seconds, ReconnectPolicy.delay(Int.MAX_VALUE))
    }

    @Test
    fun testIsQuietAndQuietWindowDuration() {
        assertEquals(3, ReconnectPolicy.QUIET_ATTEMPTS)
        assertEquals(7.seconds, ReconnectPolicy.quietWindowDuration)
        assertTrue(ReconnectPolicy.isQuiet(0))
        assertTrue(ReconnectPolicy.isQuiet(1))
        assertTrue(ReconnectPolicy.isQuiet(2))
        assertFalse(ReconnectPolicy.isQuiet(3))
        assertFalse(ReconnectPolicy.isQuiet(10))
    }

    @Test
    fun testConnectionStateForAttempt() {
        assertEquals(ReconnectConnectionState.RECONNECTING, ReconnectPolicy.connectionStateForAttempt(0))
        assertEquals(ReconnectConnectionState.RECONNECTING, ReconnectPolicy.connectionStateForAttempt(2))
        assertEquals(ReconnectConnectionState.DISCONNECTED, ReconnectPolicy.connectionStateForAttempt(3))
        assertEquals(
            ReconnectConnectionState.RECONNECTING,
            ReconnectPolicy.connectionStateForAttempt(attempt = 3, isConnecting = true),
        )
    }

    @Test
    fun testAutomaticRecoveryDelaySchedule() {
        assertNull(ReconnectPolicy.automaticRecoveryDelay(-1))
        assertEquals(0.seconds, ReconnectPolicy.automaticRecoveryDelay(0))
        assertEquals(1.seconds, ReconnectPolicy.automaticRecoveryDelay(1))
        assertEquals(3.seconds, ReconnectPolicy.automaticRecoveryDelay(2))
        assertNull(ReconnectPolicy.automaticRecoveryDelay(3))
        assertNull(ReconnectPolicy.automaticRecoveryDelay(10))
    }

    @Test
    fun testSessionStorage() {
        val session = Session(id = "test-id", source = "bighelp", profile = "test-profile")
        ReconnectPolicy.setSession(null)
        assertNull(ReconnectPolicy.getSession())

        ReconnectPolicy.setSession(session)
        assertEquals(session, ReconnectPolicy.getSession())

        ReconnectPolicy.setSession(null)
        assertNull(ReconnectPolicy.getSession())
    }

    @Test
    fun testClassifyReconnectSameSignInVsBoundary() {
        val computerA = SignIn("https://host-a.example", "alice", authenticationGeneration = 1L)
        val computerAReauth = SignIn("https://host-a.example", "alice", authenticationGeneration = 2L)
        val computerADifferentPrincipal = SignIn("https://host-a.example", "bob", authenticationGeneration = 1L)
        val computerB = SignIn("https://host-b.example", "alice", authenticationGeneration = 1L)

        assertEquals(
            ReconnectClassification.SAME_SIGN_IN,
            ReconnectPolicy.classifyReconnect(current = computerA, previous = computerA),
        )
        assertEquals(
            ReconnectClassification.BOUNDARY,
            ReconnectPolicy.classifyReconnect(current = computerA, previous = null),
        )
        assertEquals(
            ReconnectClassification.BOUNDARY,
            ReconnectPolicy.classifyReconnect(current = computerAReauth, previous = computerA),
        )
        assertEquals(
            ReconnectClassification.BOUNDARY,
            ReconnectPolicy.classifyReconnect(current = computerADifferentPrincipal, previous = computerA),
        )
        assertEquals(
            ReconnectClassification.BOUNDARY,
            ReconnectPolicy.classifyReconnect(current = computerB, previous = computerA),
        )
    }

    @Test
    fun testRetireSocketGenerationAdvancesGenerationAndFencesOwners() {
        val owners = Owners()
        val signIn = SignIn("https://host.example", "alice", authenticationGeneration = 1L)
        val owner = Owner(signIn, workspaceConnectionGeneration = 1L)
        owners.updateSignIn(signIn)
        owners.updateOwner(owner)
        owners.incrementSocketEpoch()

        val state = ReconnectPolicy.createState()
        val capturedGeneration = state.currentGeneration
        val capturedSocketOwner = owners.getSocketOwner()
        assertNotNull(capturedSocketOwner)
        assertEquals(1L, capturedSocketOwner?.socketEpoch)

        val session = Session(id = "saved-1", source = "bighelp", profile = "research")
        state.setSession(session, runtimeId = "runtime-1")
        assertTrue(state.isReadyForSubmission)

        val retirement = state.retireSocketGeneration(
            owners = owners,
            notify = true,
            hadConnection = true,
            terminallyClosed = false,
        )

        assertEquals(capturedGeneration, retirement.previousGeneration)
        assertEquals(capturedGeneration + 1L, retirement.nextGeneration)
        assertEquals(capturedSocketOwner, retirement.previousSocketOwner)
        assertEquals(2L, retirement.nextSocketOwner?.socketEpoch)
        assertFalse(retirement.hasUncertainMutation)
        assertTrue(retirement.shouldAutomaticallyRecover)
        assertTrue(retirement.preservesHandlers)
        assertTrue(retirement.requiresSessionResume)
        assertTrue(state.requiresResume)
        assertNull(state.runtimeSessionId)
        assertFalse(state.isReadyForSubmission)

        assertThrows(StaleOwnerException::class.java) {
            owners.validateSocketOwner(capturedSocketOwner)
        }
        assertThrows(StaleOwnerException::class.java) {
            state.validateGeneration(capturedGeneration)
        }
        // Current generation validates cleanly
        state.validateGeneration(retirement.nextGeneration)
    }

    @Test
    fun testRetireSocketGenerationTerminalDisconnectDisablesRecoveryAndClearsHandlers() {
        val state = ReconnectPolicy.createState()

        val terminal = state.retireSocketGeneration(
            notify = false,
            hadConnection = true,
            terminallyClosed = true,
            automaticRecoveryActive = false,
        )
        assertFalse(terminal.shouldAutomaticallyRecover)
        assertFalse(terminal.preservesHandlers)

        val duringAutoRecovery = state.retireSocketGeneration(
            notify = false,
            hadConnection = false,
            terminallyClosed = false,
            automaticRecoveryActive = true,
        )
        assertFalse(duringAutoRecovery.shouldAutomaticallyRecover)
        assertTrue(duringAutoRecovery.preservesHandlers)
    }

    @Test
    fun testReadOnlyAllowlistConservativelyTreatsUnknownMethodsAsMutating() {
        assertTrue(ReconnectPolicy.isReadOnlyMethod("gateway.ping"))
        assertTrue(ReconnectPolicy.isReadOnlyMethod("session.events.since"))
        assertTrue(ReconnectPolicy.isReadOnlyMethod("session.history"))
        assertTrue(ReconnectPolicy.isReadOnlyMethod("session.list"))
        assertTrue(ReconnectPolicy.isReadOnlyMethod("client.capabilities"))
        assertTrue(ReconnectPolicy.isReadOnlyMethod("profiles.list"))

        assertFalse(ReconnectPolicy.isReadOnlyMethod("prompt.submit"))
        assertFalse(ReconnectPolicy.isReadOnlyMethod("session.create"))
        assertFalse(ReconnectPolicy.isReadOnlyMethod("session.resume"))
        assertFalse(ReconnectPolicy.isReadOnlyMethod("command.dispatch"))
        assertFalse(ReconnectPolicy.isReadOnlyMethod("slash.exec"))
        assertFalse(ReconnectPolicy.isReadOnlyMethod("unknown.future.method"))
    }

    @Test
    fun testUncertainInFlightMutationBlocksAutomaticRecoveryOnDisconnect() {
        val state = ReconnectPolicy.createState()
        val generation = state.currentGeneration

        val op = state.registerOperation(id = "req-prompt-1", method = "prompt.submit")
        assertFalse(op.readOnly)
        assertFalse(op.sendStarted)
        assertFalse(op.isUncertainMutation)

        assertTrue(state.markSendStarted("req-prompt-1", expectedGeneration = generation))

        val retirement = state.retireSocketGeneration(
            notify = true,
            hadConnection = true,
            terminallyClosed = false,
        )

        assertTrue(retirement.hasUncertainMutation)
        assertFalse(retirement.shouldAutomaticallyRecover)
        assertFalse(retirement.preservesHandlers)
        assertEquals(
            listOf(SettledOperation(id = "req-prompt-1", method = "prompt.submit", outcomeUnknown = true)),
            retirement.settledOperations,
        )
        assertEquals(0, state.pendingOperationCount)
        assertTrue(state.hasUncertainMutations)
        assertEquals(
            SettledOperation(id = "req-prompt-1", method = "prompt.submit", outcomeUnknown = true),
            state.uncertainMutations["req-prompt-1"],
        )

        // Explicit reconciliation clears the uncertain mutation record
        assertTrue(state.resolveUncertainMutation("req-prompt-1"))
        assertFalse(state.resolveUncertainMutation("req-prompt-1"))
        assertFalse(state.hasUncertainMutations)
    }

    @Test
    fun testUnsentMutationAndSentReadOnlyOperationDoNotBlockAutomaticRecovery() {
        val state = ReconnectPolicy.createState()
        val generation = state.currentGeneration

        // Mutating operation still queued locally (sendStarted = false)
        state.registerOperation(id = "queued-mutation", method = "prompt.submit")
        // Read-only operation already sent on the wire (sendStarted = true, readOnly = true)
        state.registerOperation(id = "inflight-ping", method = "gateway.ping")
        assertTrue(state.markSendStarted("inflight-ping", expectedGeneration = generation))

        val retirement = state.retireSocketGeneration(
            notify = true,
            hadConnection = true,
            terminallyClosed = false,
        )

        assertFalse(retirement.hasUncertainMutation)
        assertTrue(retirement.shouldAutomaticallyRecover)
        assertTrue(retirement.preservesHandlers)
        assertEquals(2, retirement.settledOperations.size)
        assertTrue(retirement.settledOperations.all { !it.outcomeUnknown })
        assertFalse(state.hasUncertainMutations)
    }

    @Test
    fun testCompletedMutationBeforeDisconnectIsNotUncertain() {
        val state = ReconnectPolicy.createState()
        val generation = state.currentGeneration

        state.registerOperation(id = "confirmed-1", method = "prompt.submit")
        assertTrue(state.markSendStarted("confirmed-1", expectedGeneration = generation))
        assertNotNull(state.completeOperation("confirmed-1"))

        val retirement = state.retireSocketGeneration(notify = true, hadConnection = true)
        assertFalse(retirement.hasUncertainMutation)
        assertTrue(retirement.shouldAutomaticallyRecover)
        assertTrue(retirement.settledOperations.isEmpty())
        assertFalse(state.hasUncertainMutations)
    }

    @Test
    fun testInterruptedOperationsMarkOutcomeUnknownOnlyForSentMutations() {
        val state = ReconnectPolicy.createState()
        val generation = state.currentGeneration

        state.registerOperation(id = "unsent-mut", method = "prompt.submit")
        state.registerOperation(id = "sent-read", method = "session.status")
        state.registerOperation(id = "sent-mut", method = "custom.unknown.rpc")
        assertTrue(state.markSendStarted("sent-read", generation))
        assertTrue(state.markSendStarted("sent-mut", generation))

        val unsentSettled = state.settleInterruptedOperation("unsent-mut", generation)
        val readSettled = state.settleInterruptedOperation("sent-read", generation)
        val mutSettled = state.settleInterruptedOperation("sent-mut", generation)

        assertEquals(SettledOperation("unsent-mut", "prompt.submit", outcomeUnknown = false), unsentSettled)
        assertEquals(SettledOperation("sent-read", "session.status", outcomeUnknown = false), readSettled)
        assertEquals(SettledOperation("sent-mut", "custom.unknown.rpc", outcomeUnknown = true), mutSettled)
        assertEquals(setOf("sent-mut"), state.uncertainMutations.keys)

        // Operations targeting a retired generation are ignored
        state.retireSocketGeneration()
        assertFalse(state.markSendStarted("sent-mut", generation))
        assertNull(state.settleInterruptedOperation("sent-mut", generation))
    }

    @Test
    fun testHttpMutationOutcomeUnknownRules() {
        // Before bytes leave, outcome is never unknown
        assertFalse(
            ReconnectPolicy.isOutcomeUnknownForHttpMutation(
                bytesMayHaveLeft = false,
                receivedStatusCode = null,
            )
        )
        assertFalse(
            ReconnectPolicy.isOutcomeUnknownForHttpMutation(
                bytesMayHaveLeft = false,
                receivedStatusCode = 500,
            )
        )

        // Definitive rejections prove the host rejected the mutation
        for (code in listOf(400, 401, 403, 404, 405, 409, 413, 415, 422, 429)) {
            assertFalse(
                "Status $code should be a definitive rejection",
                ReconnectPolicy.isOutcomeUnknownForHttpMutation(bytesMayHaveLeft = true, receivedStatusCode = code),
            )
        }

        // Transport loss or 5xx after bytes may have left is always outcome-unknown
        assertTrue(ReconnectPolicy.isOutcomeUnknownForHttpMutation(bytesMayHaveLeft = true, receivedStatusCode = null))
        assertTrue(ReconnectPolicy.isOutcomeUnknownForHttpMutation(bytesMayHaveLeft = true, receivedStatusCode = 500))
        assertTrue(ReconnectPolicy.isOutcomeUnknownForHttpMutation(bytesMayHaveLeft = true, receivedStatusCode = 502))
        assertTrue(ReconnectPolicy.isOutcomeUnknownForHttpMutation(bytesMayHaveLeft = true, receivedStatusCode = 503))
    }

    @Test
    fun testResumeParametersUseDurableIdentityWithoutPromptText() {
        val session = Session(id = "saved-session-1", source = "bighelp", profile = "research")
        val params = ReconnectPolicy.buildResumeParameters(session)
        val json = params.jsonParams

        assertEquals(JsonPrimitive("saved-session-1"), json["session_id"])
        assertEquals(JsonPrimitive("research"), json["profile"])
        assertEquals(JsonPrimitive("bighelp"), json["source"])
        assertEquals(JsonPrimitive(false), json["close_on_disconnect"])
        assertEquals(JsonPrimitive(true), json["defer_history"])
        assertEquals(JsonPrimitive(true), json["omit_messages"])
        assertNull(json["text"])

        // Invalid source, whitespace-padded ID, control characters, and oversized coordinates are rejected
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.buildResumeParameters(session.copy(source = "desktop"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.buildResumeParameters(session.copy(id = " saved-session-1 "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.buildResumeParameters(session.copy(id = "saved\u0000id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.buildResumeParameters(session.copy(id = "a".repeat(513)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.buildResumeParameters(session.copy(profile = "p".repeat(129)))
        }
    }

    @Test
    fun testDecodeResumedSessionSupportsCompactionSuccessor() {
        val valid = buildJsonObject {
            put("session_id", "replacement-runtime")
            put("stored_session_id", "compaction-successor")
            put("session_key", "compaction-successor")
            putJsonObject("info") {
                put("profile_name", "research")
            }
            put("running", false)
        }
        val decoded = ReconnectPolicy.decodeResumedSession(valid, expectedProfile = "research")
        assertEquals(
            ResumedSession(runtimeId = "replacement-runtime", storedId = "compaction-successor"),
            decoded,
        )
    }

    @Test
    fun testDecodeResumedSessionRejectsInvalidPayloads() {
        // Conflicting stored aliases rejected
        val conflictingAliases = buildJsonObject {
            put("session_id", "runtime")
            put("stored_session_id", "saved")
            put("session_key", "different")
            putJsonObject("info") {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.decodeResumedSession(conflictingAliases, expectedProfile = "research")
        }

        // Foreign profile rejected
        val foreignProfile = buildJsonObject {
            put("session_id", "runtime")
            put("stored_session_id", "saved")
            putJsonObject("info") {
                put("profile_name", "other")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.decodeResumedSession(foreignProfile, expectedProfile = "research")
        }

        // Non-boolean running flag rejected
        val badRunning = buildJsonObject {
            put("session_id", "runtime")
            put("stored_session_id", "saved")
            putJsonObject("info") {}
            put("running", "false")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.decodeResumedSession(badRunning, expectedProfile = "research")
        }

        // Missing stored session alias rejected
        val missingStored = buildJsonObject {
            put("session_id", "runtime")
            putJsonObject("info") {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.decodeResumedSession(missingStored, expectedProfile = "research")
        }

        // Missing info object rejected
        val missingInfo = JsonObject(
            mapOf(
                "session_id" to JsonPrimitive("runtime"),
                "stored_session_id" to JsonPrimitive("saved"),
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy.decodeResumedSession(missingInfo, expectedProfile = "research")
        }
    }

    @Test
    fun testAdoptResumedSessionAndStaleGenerationRejection() {
        val state = ReconnectPolicy.createState()
        val initial = Session(id = "saved-original", source = "bighelp", profile = "research")
        state.setSession(initial, runtimeId = "runtime-1")
        assertTrue(state.isReadyForSubmission)

        val retirement = state.retireSocketGeneration()
        val newGeneration = retirement.nextGeneration
        assertTrue(state.requiresResume)
        assertFalse(state.isReadyForSubmission)

        val resumed = ResumedSession(runtimeId = "runtime-2", storedId = "saved-compacted")
        // Stale generation from before retirement must be rejected
        assertThrows(StaleOwnerException::class.java) {
            state.adoptResumedSession(resumed, expectedGeneration = retirement.previousGeneration)
        }

        val adopted = state.adoptResumedSession(resumed, expectedGeneration = newGeneration)
        assertEquals(Session(id = "saved-compacted", source = "bighelp", profile = "research"), adopted)
        assertEquals("runtime-2", state.runtimeSessionId)
        assertFalse(state.requiresResume)
        assertTrue(state.isReadyForSubmission)
    }

    @Test
    fun testReclaimRevokesOnlyExactActiveRuntime() {
        val state = ReconnectPolicy.createState()
        val session = Session(id = "saved", source = "bighelp", profile = "research")
        state.setSession(session, runtimeId = "runtime")
        assertTrue(state.isReadyForSubmission)

        // Foreign runtime with same stored ID must NOT revoke this active owner
        assertFalse(state.reclaimSession(runtimeId = "other", storedId = "saved"))
        assertTrue(state.isReadyForSubmission)
        assertFalse(state.requiresResume)
        assertEquals("runtime", state.runtimeSessionId)

        // Exact active runtime reclaim revokes admission and requires durable resume
        assertTrue(state.reclaimSession(runtimeId = "runtime", storedId = "saved"))
        assertFalse(state.isReadyForSubmission)
        assertTrue(state.requiresResume)
        assertNull(state.runtimeSessionId)
    }

    @Test
    fun testSignInTransitionsPreserveSameSignInAndClearBoundaryState() {
        val owners = Owners()
        val signInA = SignIn("https://host-a.example", "alice", authenticationGeneration = 1L)
        val signInB = SignIn("https://host-b.example", "alice", authenticationGeneration = 1L)
        val ownerA = Owner(signInA, workspaceConnectionGeneration = 1L)
        owners.updateSignIn(signInA)
        owners.updateOwner(ownerA)
        owners.incrementSocketEpoch()

        val state = ReconnectPolicy.createState()
        // First sign-in establishes initial boundary
        assertEquals(ReconnectClassification.BOUNDARY, state.onSignInTransition(signInA, owners))

        val session = Session(id = "saved-a", source = "bighelp", profile = "research")
        state.setSession(session, runtimeId = "runtime-a")
        val genBeforeReconnect = state.currentGeneration
        state.registerOperation("op-1", "prompt.submit")
        assertTrue(state.markSendStarted("op-1", genBeforeReconnect))
        state.settleInterruptedOperation("op-1", genBeforeReconnect)
        assertTrue(state.hasUncertainMutations)

        // Reconnect under the SAME sign-in keeps the retained session and flags it for resume
        assertEquals(ReconnectClassification.SAME_SIGN_IN, state.onSignInTransition(signInA, owners))
        assertEquals(session, state.session)
        assertTrue(state.requiresResume)
        assertTrue(state.hasUncertainMutations)

        // Switching hosts crosses a BOUNDARY: retires generation and clears session + uncertain state
        val epochBeforeBoundary = owners.getSocketOwner()?.socketEpoch ?: 0L
        assertEquals(ReconnectClassification.BOUNDARY, state.onSignInTransition(signInB, owners))
        val snapshot = state.snapshot()
        assertEquals(signInB, snapshot.boundSignIn)
        assertNull(snapshot.session)
        assertNull(snapshot.runtimeSessionId)
        assertFalse(snapshot.requiresResume)
        assertFalse(snapshot.isReadyForSubmission)
        assertTrue(snapshot.uncertainMutations.isEmpty())
        assertEquals(epochBeforeBoundary + 1L, owners.getSocketOwner()?.socketEpoch)
    }

    @Test
    fun testConcurrentStateAccessIsThreadSafe() {
        val state = ReconnectPolicy.createState()
        val session = Session(id = "concurrent-session", source = "bighelp", profile = "research")
        state.setSession(session, runtimeId = "runtime-0")

        val workerCount = 4
        val iterations = 100
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val tasks = (0 until workerCount).map { worker ->
                Callable {
                    repeat(iterations) { idx ->
                        val opId = "w$worker-op$idx"
                        val gen = state.currentGeneration
                        state.registerOperation(opId, if (idx % 2 == 0) "prompt.submit" else "gateway.ping")
                        state.markSendStarted(opId, gen)
                        if (idx % 5 == 0) {
                            state.retireSocketGeneration()
                        } else {
                            state.completeOperation(opId)
                        }
                        state.snapshot()
                    }
                }
            }
            val futures = executor.invokeAll(tasks)
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val finalRetirement = state.retireSocketGeneration()
        assertTrue(finalRetirement.nextGeneration > 0L)
        assertEquals(0, state.pendingOperationCount)
        assertEquals(session, state.session)
    }
}
