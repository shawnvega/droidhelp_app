package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Capabilities
import com.bighelp.droidhelp.transport.CapabilitiesRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TurnControlCapabilitiesTest {

    private class RecordingDispatcher(
        var steerDelayMillis: Long = 0L,
    ) : TurnControlDispatcher {
        val events = mutableListOf<String>()
        val interruptedTurns = mutableListOf<Pair<String, String?>>()

        override suspend fun onStartTurn(turn: ActiveTurn) {
            events.add("start:${turn.turnId}")
        }

        override suspend fun onSteerTurn(targetTurnId: String, directive: SteeringDirective) {
            if (steerDelayMillis > 0L) {
                delay(steerDelayMillis)
            }
            events.add("steer:$targetTurnId:${directive.sequence}")
        }

        override suspend fun onInterruptTurn(turnId: String, reason: String?) {
            events.add("interrupt:$turnId")
            interruptedTurns.add(turnId to reason)
        }
    }

    private fun sequentialIdGenerator(prefix: String = "id"): () -> String {
        val counter = AtomicInteger(0)
        return { "$prefix-${counter.incrementAndGet()}" }
    }

    @Test
    fun `fail-closed defaults reject queue and interrupt without side effects while allowing steering`() = runTest {
        val dispatcher = RecordingDispatcher()
        val idCalls = AtomicInteger(0)
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = { "id-${idCalls.incrementAndGet()}" },
        )

        assertFalse(control.supportsQueue)
        assertFalse(control.supportsInterrupt)
        assertTrue(control.isModeSupported(TurnControlMode.STEER))
        assertFalse(control.isModeSupported(TurnControlMode.QUEUE))
        assertFalse(control.isModeSupported(TurnControlMode.INTERRUPT))
        assertEquals(TurnControlCapabilities.NONE, control.state.value.capabilities)

        // Idle: unsupported QUEUE and INTERRUPT reject safely with zero side effects.
        val idleQueue = control.submit("Should not queue", mode = TurnControlMode.QUEUE)
        assertTrue(idleQueue is TurnControlResult.Rejected)
        val idleQueueRejected = idleQueue as TurnControlResult.Rejected
        assertEquals(TurnControlMode.QUEUE, idleQueueRejected.requestedMode)
        assertEquals(TurnControlCapabilities.KEY_TURN_QUEUE, idleQueueRejected.requiredCapability)
        assertNull(idleQueueRejected.activeTurnId)

        val idleInterrupt = control.submit("Should not interrupt", mode = TurnControlMode.INTERRUPT)
        assertTrue(idleInterrupt is TurnControlResult.Rejected)
        val idleInterruptRejected = idleInterrupt as TurnControlResult.Rejected
        assertEquals(TurnControlMode.INTERRUPT, idleInterruptRejected.requestedMode)
        assertEquals(TurnControlCapabilities.KEY_TURN_INTERRUPT, idleInterruptRejected.requiredCapability)
        assertNull(idleInterruptRejected.activeTurnId)

        val idleEx = assertThrowsSuspend<UnsupportedTurnOperationException> {
            control.interruptActiveTurn()
        }
        assertEquals(TurnControlMode.INTERRUPT, idleEx.requestedMode)
        assertEquals(TurnControlCapabilities.KEY_TURN_INTERRUPT, idleEx.requiredCapability)

        assertFalse(control.hasActiveTurn)
        assertTrue(dispatcher.events.isEmpty())
        assertEquals(0, idCalls.get())

        // Baseline start + steer still work when queue and interrupt are unsupported.
        val started = control.submit("Active work") as TurnControlResult.Started
        assertEquals("id-1", started.turn.turnId)
        val stateBeforeRejections = control.state.value

        // Active: unsupported QUEUE and INTERRUPT reject without mutating state or dispatching.
        val activeQueue = control.submit("Rejected queue", mode = TurnControlMode.QUEUE)
        assertTrue(activeQueue is TurnControlResult.Rejected)
        assertEquals("id-1", (activeQueue as TurnControlResult.Rejected).activeTurnId)

        val activeInterrupt = control.submit("Rejected interrupt", mode = TurnControlMode.INTERRUPT)
        assertTrue(activeInterrupt is TurnControlResult.Rejected)
        assertEquals("id-1", (activeInterrupt as TurnControlResult.Rejected).activeTurnId)

        assertThrowsSuspend<UnsupportedTurnOperationException> {
            control.interruptActiveTurn(expectedTurnId = "id-1", reason = "stop", clearQueue = true)
        }

        assertEquals(stateBeforeRejections, control.state.value)
        assertEquals(listOf("start:id-1"), dispatcher.events)
        assertEquals(1, idCalls.get())

        // Default steering continues cleanly on the unaffected active turn.
        val steered = control.submit("Steer after rejections") as TurnControlResult.Steered
        assertEquals("id-1", steered.targetTurnId)
        assertEquals(1L, steered.directive.sequence)
        assertEquals(listOf("start:id-1", "steer:id-1:1"), dispatcher.events)
    }

    @Test
    fun `gateway Capabilities gates queue and interrupt independently`() = runTest {
        // Null/empty gateway capabilities fail closed.
        assertEquals(TurnControlCapabilities.NONE, TurnControlCapabilities.from(null as Capabilities?))
        assertEquals(
            TurnControlCapabilities.NONE,
            TurnControlCapabilities.from(Capabilities(per_session_exclusive_submit = true)),
        )

        // Queue supported, interrupt unsupported.
        val queueDispatcher = RecordingDispatcher()
        val queueOnly = TurnControl(
            capabilities = Capabilities(
                per_session_exclusive_submit = true,
                turnQueue = true,
                turnInterrupt = false,
            ),
            dispatcher = queueDispatcher,
            idGenerator = sequentialIdGenerator("qo"),
        )
        assertTrue(queueOnly.supportsQueue)
        assertFalse(queueOnly.supportsInterrupt)

        queueOnly.submit("Primary task", turnId = "turn-1")
        val queued = queueOnly.submit("Queued follow-up", mode = TurnControlMode.QUEUE)
        assertTrue(queued is TurnControlResult.Queued)
        assertEquals(1, queueOnly.queuedInputs.size)

        // Interrupt attempts are rejected and must NOT interrupt turn-1 or clear queued inputs.
        val rejectedInterrupt = queueOnly.submit("Pivot", mode = TurnControlMode.INTERRUPT)
        assertTrue(rejectedInterrupt is TurnControlResult.Rejected)
        assertThrowsSuspend<UnsupportedTurnOperationException> {
            queueOnly.interruptActiveTurn(expectedTurnId = "turn-1", clearQueue = true)
        }
        assertEquals("turn-1", queueOnly.activeTurnId)
        assertEquals(1, queueOnly.queuedInputs.size)
        assertTrue(queueDispatcher.interruptedTurns.isEmpty())

        // Interrupt supported, queue unsupported.
        val interruptDispatcher = RecordingDispatcher()
        val interruptOnly = TurnControl(
            capabilities = Capabilities(
                per_session_exclusive_submit = true,
                turnQueue = false,
                turnInterrupt = true,
            ),
            dispatcher = interruptDispatcher,
            idGenerator = sequentialIdGenerator("io"),
        )
        assertFalse(interruptOnly.supportsQueue)
        assertTrue(interruptOnly.supportsInterrupt)

        interruptOnly.submit("Initial task", turnId = "turn-A")
        val rejectedQueue = interruptOnly.submit("Cannot queue", mode = TurnControlMode.QUEUE)
        assertTrue(rejectedQueue is TurnControlResult.Rejected)
        assertTrue(interruptOnly.queuedInputs.isEmpty())
        assertEquals("turn-A", interruptOnly.activeTurnId)

        val replaced = interruptOnly.submit(
            input = "Interrupt works",
            mode = TurnControlMode.INTERRUPT,
            turnId = "turn-B",
        ) as TurnControlResult.Started
        assertEquals("turn-B", replaced.turn.turnId)
        assertEquals("turn-A", replaced.interruptedTurnId)
        assertEquals(listOf("turn-A" to null), interruptDispatcher.interruptedTurns)
    }

    @Test
    fun `CapabilitiesRegistry gates queue and interrupt with aliases and owner retirement`() = runTest {
        val registry = CapabilitiesRegistry()
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            capabilitiesRegistry = registry,
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("reg"),
        )

        // Empty registry fails closed.
        assertFalse(control.supportsQueue)
        assertFalse(control.supportsInterrupt)
        control.submit("Turn 1", turnId = "turn-1")
        assertTrue(control.submit("Blocked queue", mode = TurnControlMode.QUEUE) is TurnControlResult.Rejected)
        val blockedInt = control.submit("Blocked interrupt", mode = TurnControlMode.INTERRUPT)
        assertTrue(blockedInt is TurnControlResult.Rejected)

        // Enable queue under owner "conn-1".
        registry.register(TurnControlCapabilities.KEY_TURN_QUEUE, "true", owner = "conn-1")
        assertTrue(control.supportsQueue)
        assertFalse(control.supportsInterrupt)
        assertTrue(control.submit("Allowed queue", mode = TurnControlMode.QUEUE) is TurnControlResult.Queued)

        // Enable interrupt with numeric "1" under owner "conn-1".
        registry.register(TurnControlCapabilities.KEY_TURN_INTERRUPT, "1", owner = "conn-1")
        assertTrue(control.supportsInterrupt)
        val replaced = control.submit(
            input = "Allowed interrupt",
            mode = TurnControlMode.INTERRUPT,
            turnId = "turn-2",
        )
        assertTrue(replaced is TurnControlResult.Started)
        assertEquals("turn-2", control.activeTurnId)

        // Retiring owner immediately revokes both capabilities with zero side effects on blocked calls.
        assertEquals(2, registry.retireOwner("conn-1"))
        assertFalse(control.supportsQueue)
        assertFalse(control.supportsInterrupt)
        val snapshotAfterRetire = control.state.value
        val postRetireQueue = control.submit("Post-retire queue", mode = TurnControlMode.QUEUE)
        assertTrue(postRetireQueue is TurnControlResult.Rejected)
        val postRetireInt = control.submit("Post-retire interrupt", mode = TurnControlMode.INTERRUPT)
        assertTrue(postRetireInt is TurnControlResult.Rejected)
        assertThrowsSuspend<UnsupportedTurnOperationException> {
            control.interruptActiveTurn(expectedTurnId = "turn-2", clearQueue = true)
        }
        assertEquals(snapshotAfterRetire, control.state.value)
        assertEquals(1, control.queuedInputs.size)

        // Presence-only flag (null value) enables; explicit "false" / invalid values fail closed.
        registry.register(TurnControlCapabilities.KEY_TURN_QUEUE, null)
        assertTrue(control.supportsQueue)
        registry.register(TurnControlCapabilities.KEY_TURN_QUEUE, "false")
        assertFalse(control.supportsQueue)
        registry.register(TurnControlCapabilities.KEY_TURN_QUEUE, "unsupported")
        assertFalse(control.supportsQueue)

        // Dot-delimited alias works when canonical key is absent; canonical "false" overrides alias "true".
        registry.clear()
        registry.register(TurnControlCapabilities.ALT_KEY_TURN_QUEUE, "true")
        registry.register(TurnControlCapabilities.ALT_KEY_TURN_INTERRUPT, "TRUE")
        assertTrue(control.supportsQueue)
        assertTrue(control.supportsInterrupt)

        registry.register(TurnControlCapabilities.KEY_TURN_QUEUE, "false")
        assertFalse(control.supportsQueue)
        assertTrue(control.supportsInterrupt)
    }

    @Test
    fun `updateCapabilities revokes queue support and blocks auto-promotion on completeTurn`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("upd"),
            capabilities = TurnControlCapabilities.FULL,
        )

        control.submit("Task 1", turnId = "turn-1")
        control.submit("Task 2", mode = TurnControlMode.QUEUE, turnId = "turn-2")
        control.submit("Task 3", mode = TurnControlMode.QUEUE, turnId = "turn-3")
        assertEquals(2, control.queuedInputs.size)

        // Downgrade capabilities mid-turn.
        control.updateCapabilities(Capabilities(turnQueue = false, turnInterrupt = false))
        assertFalse(control.supportsQueue)
        assertFalse(control.supportsInterrupt)
        assertEquals(TurnControlCapabilities.NONE, control.state.value.capabilities)

        // Further queue/interrupt attempts rejected without losing existing queue or active turn.
        assertTrue(control.submit("Task 4", mode = TurnControlMode.QUEUE) is TurnControlResult.Rejected)
        assertThrowsSuspend<UnsupportedTurnOperationException> {
            control.interruptActiveTurn(expectedTurnId = "turn-1", clearQueue = true)
        }
        assertEquals("turn-1", control.activeTurnId)
        assertEquals(2, control.queuedInputs.size)

        // Completing turn-1 while queue support is revoked does NOT auto-promote queued turns.
        val completion = control.completeTurn("turn-1")
        assertEquals(TurnLifecycleState.COMPLETED, completion.finishedTurn.state)
        assertNull(completion.startedNextTurn)
        assertNull(control.activeTurn)
        assertEquals(2, control.queuedInputs.size)
        assertEquals(listOf("start:turn-1"), dispatcher.events)

        // Caller can safely clean up remaining queue items via clearQueue().
        val cleared = control.clearQueue()
        assertEquals(listOf("Task 2", "Task 3"), cleared.map { it.input })
        assertTrue(control.queuedInputs.isEmpty())

        // Re-enabling capabilities unlocks queue and interrupt again.
        control.updateCapabilities(TurnControlCapabilities.FULL)
        assertTrue(control.supportsQueue)
        assertTrue(control.supportsInterrupt)
        control.submit("Fresh task", turnId = "turn-fresh")
        assertTrue(control.submit("Queued again", mode = TurnControlMode.QUEUE) is TurnControlResult.Queued)
        val interrupted = control.interruptActiveTurn(expectedTurnId = "turn-fresh", clearQueue = true)
        assertEquals("turn-fresh", interrupted?.turnId)
        assertTrue(control.queuedInputs.isEmpty())
    }

    @Test
    fun `concurrent unsupported queue and interrupt attempts do not disrupt active-turn steering`() = runTest {
        val dispatcher = RecordingDispatcher(steerDelayMillis = 5L)
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("gate"),
            capabilities = TurnControlCapabilities.NONE,
        )

        control.submit("Base task", turnId = "turn-live")

        val steerJobs = (1..5).map { idx ->
            async { control.submit("Steer $idx") }
        }
        val queueJobs = (1..5).map { idx ->
            async { control.submit("Blocked queue $idx", mode = TurnControlMode.QUEUE) }
        }
        val interruptJobs = (1..5).map { idx ->
            async { control.submit("Blocked interrupt $idx", mode = TurnControlMode.INTERRUPT) }
        }
        val directInterruptJobs = (1..5).map {
            async {
                runCatching { control.interruptActiveTurn(expectedTurnId = "turn-live", clearQueue = true) }
            }
        }

        val steerResults = steerJobs.awaitAll()
        val queueResults = queueJobs.awaitAll()
        val interruptResults = interruptJobs.awaitAll()
        val directInterruptResults = directInterruptJobs.awaitAll()

        assertTrue(steerResults.all { it is TurnControlResult.Steered })
        assertTrue(queueResults.all { it is TurnControlResult.Rejected })
        assertTrue(interruptResults.all { it is TurnControlResult.Rejected })
        assertTrue(directInterruptResults.all { it.exceptionOrNull() is UnsupportedTurnOperationException })
        assertEquals("turn-live", control.activeTurnId)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), control.activeTurn?.steeringDirectives?.map { it.sequence })
        assertTrue(control.queuedInputs.isEmpty())
        assertTrue(dispatcher.interruptedTurns.isEmpty())
    }
}
