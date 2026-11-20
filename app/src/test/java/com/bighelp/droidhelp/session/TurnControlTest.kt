package com.bighelp.droidhelp.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows as assertThrowsSuspend
import java.util.concurrent.atomic.AtomicInteger

class TurnControlTest {

    private class RecordingDispatcher(
        var failNextStart: Boolean = false,
        var failNextSteer: Boolean = false,
        var failNextInterrupt: Boolean = false,
        var steerDelayMillis: Long = 0L,
    ) : TurnControlDispatcher {
        val events = mutableListOf<String>()
        val startedTurns = mutableListOf<ActiveTurn>()
        val steeredDirectives = mutableListOf<Pair<String, SteeringDirective>>()
        val interruptedTurns = mutableListOf<Pair<String, String?>>()

        override suspend fun onStartTurn(turn: ActiveTurn) {
            if (failNextStart) {
                failNextStart = false
                error("start dispatch failed")
            }
            events.add("start:${turn.turnId}")
            startedTurns.add(turn)
        }

        override suspend fun onSteerTurn(targetTurnId: String, directive: SteeringDirective) {
            if (steerDelayMillis > 0L) {
                delay(steerDelayMillis)
            }
            if (failNextSteer) {
                failNextSteer = false
                error("steer dispatch failed")
            }
            events.add("steer:$targetTurnId:${directive.sequence}")
            steeredDirectives.add(targetTurnId to directive)
        }

        override suspend fun onInterruptTurn(turnId: String, reason: String?) {
            if (failNextInterrupt) {
                failNextInterrupt = false
                error("interrupt dispatch failed")
            }
            events.add("interrupt:$turnId")
            interruptedTurns.add(turnId to reason)
        }
    }

    private fun sequentialIdGenerator(prefix: String = "id"): () -> String {
        val counter = AtomicInteger(0)
        return { "$prefix-${counter.incrementAndGet()}" }
    }

    @Test
    fun `default submit starts a turn when idle and steers active turn without explicit flags`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            durableSessionId = "durable-session-1",
            runtimeSessionId = "runtime-session-1",
            idGenerator = sequentialIdGenerator("gen"),
        )

        assertEquals(TurnControlMode.STEER, TurnControl.DEFAULT_ACTIVE_TURN_MODE)
        assertFalse(control.hasActiveTurn)
        assertNull(control.activeTurn)

        // First submission while idle starts a turn.
        val firstResult = control.submit("Implement feature A")
        assertTrue(firstResult is TurnControlResult.Started)
        val started = firstResult as TurnControlResult.Started
        assertNull(started.interruptedTurnId)
        assertEquals("gen-1", started.turn.turnId)
        assertEquals("Implement feature A", started.turn.initialInput)
        assertEquals(1L, started.turn.sequence)
        assertEquals("durable-session-1", started.turn.durableSessionId)
        assertEquals("runtime-session-1", started.turn.runtimeSessionId)
        assertTrue(control.hasActiveTurn)
        assertEquals("gen-1", control.activeTurnId)

        // Second submission with NO queueing or interrupt flags defaults to steering the active turn.
        val secondResult = control.submit("Also add edge-case validation")
        assertTrue(secondResult is TurnControlResult.Steered)
        val steered = secondResult as TurnControlResult.Steered
        assertEquals("gen-1", steered.targetTurnId)
        assertEquals("gen-1", steered.directive.targetTurnId)
        assertEquals("gen-2", steered.directive.steeringId)
        assertEquals("Also add edge-case validation", steered.directive.input)
        assertEquals(1L, steered.directive.sequence)
        assertEquals("gen-1", steered.activeTurn.turnId)
        assertEquals(1, steered.activeTurn.steeringCount)

        // Active turn remained active, nothing queued, nothing interrupted.
        assertEquals("gen-1", control.activeTurnId)
        assertTrue(control.queuedInputs.isEmpty())
        assertTrue(dispatcher.interruptedTurns.isEmpty())
        assertEquals(listOf("start:gen-1", "steer:gen-1:1"), dispatcher.events)
    }

    @Test
    fun `multiple consecutive default submissions steer the same active turn in order`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("t"),
        )

        val started = control.submit("Draft architecture") as TurnControlResult.Started
        val activeId = started.turn.turnId

        val steer1 = control.submit("Use coroutines") as TurnControlResult.Steered
        val steer2 = control.submit("Keep it thread-safe") as TurnControlResult.Steered
        val steer3 = control.submit("Add unit tests") as TurnControlResult.Steered

        assertEquals(activeId, steer1.targetTurnId)
        assertEquals(activeId, steer2.targetTurnId)
        assertEquals(activeId, steer3.targetTurnId)
        assertEquals(listOf(1L, 2L, 3L), control.activeTurn?.steeringDirectives?.map { it.sequence })
        assertEquals(
            listOf("Use coroutines", "Keep it thread-safe", "Add unit tests"),
            control.activeTurn?.steeringDirectives?.map { it.input },
        )
        assertEquals(1, dispatcher.startedTurns.size)
        assertEquals(3, dispatcher.steeredDirectives.size)
        assertTrue(dispatcher.interruptedTurns.isEmpty())
    }

    @Test
    fun `default submit steers an externally attached active turn directly`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("steer"),
        )

        val attached = control.attachActiveTurn(
            turnId = "server-turn-42",
            initialInput = "Investigate logs",
        )
        assertEquals("server-turn-42", attached.turnId)
        assertTrue(dispatcher.startedTurns.isEmpty())

        // Re-attaching the exact same active turn ID is idempotent.
        val reattached = control.attachActiveTurn(
            turnId = "server-turn-42",
            initialInput = "Investigate logs",
        )
        assertEquals(attached, reattached)

        // Attaching a different turn while one is active is rejected.
        assertThrowsSuspend<IllegalStateException> {
            control.attachActiveTurn(turnId = "server-turn-99", initialInput = "Other work")
        }

        // Default submit steers the attached active turn without requiring flags.
        val steered = control.submit("Focus on transport errors") as TurnControlResult.Steered
        assertEquals("server-turn-42", steered.targetTurnId)
        assertEquals("Focus on transport errors", steered.directive.input)
        assertEquals(listOf("steer:server-turn-42:1"), dispatcher.events)
    }

    @Test
    fun `explicit steer enforces active turn existence and optional targetTurnId match`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("s"),
        )

        // Idle: explicit steer without targetTurnId throws NoActiveTurnException.
        assertThrowsSuspend<NoActiveTurnException> {
            control.steer("Steer nothing")
        }

        // Idle: explicit steer with targetTurnId throws StaleTurnException.
        val idleStale = assertThrowsSuspend<StaleTurnException> {
            control.steer("Steer stale", targetTurnId = "turn-old")
        }
        assertEquals("turn-old", idleStale.requestedTurnId)
        assertNull(idleStale.activeTurnId)

        // Start active turn.
        val started = control.submit("Work", turnId = "turn-live") as TurnControlResult.Started
        assertEquals("turn-live", started.turn.turnId)

        // Explicit steer without targetTurnId targets active turn.
        val steeredImplicit = control.steer("Adjust A")
        assertEquals("turn-live", steeredImplicit.targetTurnId)

        // Explicit steer with matching targetTurnId succeeds.
        val steeredExplicit = control.steer("Adjust B", targetTurnId = "turn-live")
        assertEquals("turn-live", steeredExplicit.targetTurnId)
        assertEquals(2L, steeredExplicit.directive.sequence)

        // Stale targetTurnId rejected on both steer() and submit().
        val mismatchSteer = assertThrowsSuspend<StaleTurnException> {
            control.steer("Adjust stale", targetTurnId = "turn-previous")
        }
        assertEquals("turn-previous", mismatchSteer.requestedTurnId)
        assertEquals("turn-live", mismatchSteer.activeTurnId)

        val mismatchSubmit = assertThrowsSuspend<StaleTurnException> {
            control.submit("Adjust stale via submit", turnId = "turn-previous")
        }
        assertEquals("turn-previous", mismatchSubmit.requestedTurnId)
        assertEquals("turn-live", mismatchSubmit.activeTurnId)
    }

    @Test
    fun `explicit queue mode enqueues follow-ups while default submit continues steering active turn`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("q"),
        )

        val turn1 = (control.submit("Task 1", turnId = "turn-1") as TurnControlResult.Started).turn

        // Explicitly opt into queueing two follow-up prompts.
        val queued1 = control.submit(
            input = "Task 2",
            mode = TurnControlMode.QUEUE,
            turnId = "turn-2",
        ) as TurnControlResult.Queued
        val queued2 = control.submit(
            input = "Task 3",
            mode = TurnControlMode.QUEUE,
            turnId = "turn-3",
        ) as TurnControlResult.Queued

        assertEquals("turn-1", queued1.activeTurnId)
        assertEquals(1, queued1.queuedInput.position)
        assertEquals(1, queued1.queueDepth)
        assertEquals(2, queued2.queuedInput.position)
        assertEquals(2, queued2.queueDepth)

        // Default submit (no mode/flags) still steers turn-1 without touching the queue.
        val steeredTurn1 = control.submit("Refine Task 1") as TurnControlResult.Steered
        assertEquals(turn1.turnId, steeredTurn1.targetTurnId)
        assertEquals(2, control.queuedInputs.size)

        // Completing turn-1 promotes Task 2 (turn-2) and re-indexes Task 3 to position 1.
        val completion1 = control.completeTurn("turn-1")
        assertEquals(TurnLifecycleState.COMPLETED, completion1.finishedTurn.state)
        assertEquals("turn-2", completion1.startedNextTurn?.turnId)
        assertEquals("turn-2", control.activeTurnId)
        assertEquals(1, control.queuedInputs.size)
        assertEquals(1, control.queuedInputs[0].position)
        assertEquals("Task 3", control.queuedInputs[0].input)

        // Now default submit targets the newly active turn-2, NOT turn-1.
        val steeredTurn2 = control.submit("Refine Task 2") as TurnControlResult.Steered
        assertEquals("turn-2", steeredTurn2.targetTurnId)
        assertEquals(1L, steeredTurn2.directive.sequence)

        // Clear remaining queue without affecting turn-2.
        val cleared = control.clearQueue()
        assertEquals(1, cleared.size)
        assertEquals("Task 3", cleared[0].input)
        assertTrue(control.queuedInputs.isEmpty())
        assertEquals("turn-2", control.activeTurnId)
    }

    @Test
    fun `explicit queue mode when idle starts immediately`() = runTest {
        val control = TurnControl(idGenerator = sequentialIdGenerator("idle"))
        val result = control.submit("First when idle", mode = TurnControlMode.QUEUE)
        assertTrue(result is TurnControlResult.Started)
        assertTrue(control.queuedInputs.isEmpty())
        assertTrue(control.hasActiveTurn)
    }

    @Test
    fun `explicit interrupt mode replaces active turn and subsequent default submit steers new turn`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("int"),
        )

        val turn1 = (control.submit("Long task", turnId = "turn-1") as TurnControlResult.Started).turn
        assertEquals("turn-1", turn1.turnId)

        val replacement = control.submit(
            input = "Urgent pivot",
            mode = TurnControlMode.INTERRUPT,
            turnId = "turn-2",
        ) as TurnControlResult.Started
        assertEquals("turn-2", replacement.turn.turnId)
        assertEquals("turn-1", replacement.interruptedTurnId)
        assertEquals(TurnLifecycleState.INTERRUPTED, control.state.value.lastFinishedTurn?.state)
        assertEquals("turn-1", control.state.value.lastFinishedTurn?.turnId)

        // Default submit steers the new active turn-2.
        val steered = control.submit("Keep it short") as TurnControlResult.Steered
        assertEquals("turn-2", steered.targetTurnId)
        assertEquals(
            listOf("start:turn-1", "interrupt:turn-1", "start:turn-2", "steer:turn-2:1"),
            dispatcher.events,
        )
    }

    @Test
    fun `standalone interruptActiveTurn validates turnId and handles optional queue clearing`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("x"),
        )

        // Idle interrupt without expectedTurnId is a no-op returning null.
        assertNull(control.interruptActiveTurn())

        // Idle interrupt with expectedTurnId rejects as stale.
        assertThrowsSuspend<StaleTurnException> {
            control.interruptActiveTurn(expectedTurnId = "turn-missing")
        }

        control.submit("Active work", turnId = "turn-1")
        control.submit("Queued follow-up", mode = TurnControlMode.QUEUE)

        // Mismatched expectedTurnId is rejected without interrupting.
        assertThrowsSuspend<StaleTurnException> {
            control.interruptActiveTurn(expectedTurnId = "turn-other", reason = "user stop")
        }
        assertEquals("turn-1", control.activeTurnId)
        assertTrue(dispatcher.interruptedTurns.isEmpty())

        // Matching expectedTurnId interrupts and optionally clears queue.
        val interrupted = control.interruptActiveTurn(
            expectedTurnId = "turn-1",
            reason = "user cancelled",
            clearQueue = true,
        )
        assertEquals("turn-1", interrupted?.turnId)
        assertEquals(TurnLifecycleState.INTERRUPTED, interrupted?.state)
        assertNull(control.activeTurn)
        assertTrue(control.queuedInputs.isEmpty())
        assertEquals(listOf("turn-1" to "user cancelled"), dispatcher.interruptedTurns)
    }

    @Test
    fun `completeTurn transitions to COMPLETED or FAILED and rejects stale or absent turns`() = runTest {
        val control = TurnControl(idGenerator = sequentialIdGenerator("c"))

        assertThrowsSuspend<NoActiveTurnException> {
            control.completeTurn()
        }
        assertThrowsSuspend<StaleTurnException> {
            control.completeTurn(turnId = "turn-none")
        }

        control.submit("Task A", turnId = "turn-A")
        assertThrowsSuspend<StaleTurnException> {
            control.completeTurn(turnId = "turn-B")
        }

        val failedResult = control.completeTurn(turnId = "turn-A", failed = true)
        assertEquals(TurnLifecycleState.FAILED, failedResult.finishedTurn.state)
        assertNull(failedResult.startedNextTurn)
        assertNull(control.activeTurn)
        assertEquals(TurnLifecycleState.FAILED, control.state.value.lastFinishedTurn?.state)
    }

    @Test
    fun `dispatcher failures preserve consistent state without phantom mutations or lost queued items`() = runTest {
        val dispatcher = RecordingDispatcher()
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("f"),
        )

        // 1. Failure on start leaves controller idle and does not burn turn sequence.
        dispatcher.failNextStart = true
        assertThrowsSuspend<IllegalStateException> {
            control.submit("Will fail", turnId = "turn-fail")
        }
        assertFalse(control.hasActiveTurn)

        val started = control.submit("Will succeed", turnId = "turn-1") as TurnControlResult.Started
        assertEquals(1L, started.turn.sequence)

        // 2. Failure on steer leaves active turn intact without recording failed directive.
        dispatcher.failNextSteer = true
        assertThrowsSuspend<IllegalStateException> {
            control.submit("Failed steering")
        }
        assertEquals("turn-1", control.activeTurnId)
        assertEquals(0, control.activeTurn?.steeringCount)

        // Next steering succeeds starting cleanly at sequence 1.
        val steered = control.submit("Recovered steering") as TurnControlResult.Steered
        assertEquals(1L, steered.directive.sequence)

        // 3. Failure on interrupt leaves active turn active.
        dispatcher.failNextInterrupt = true
        assertThrowsSuspend<IllegalStateException> {
            control.submit("Cannot interrupt", mode = TurnControlMode.INTERRUPT)
        }
        assertEquals("turn-1", control.activeTurnId)

        // 4. Failure on queued turn promotion during completeTurn keeps completed state and preserves queue head.
        control.submit("Queued safe item", mode = TurnControlMode.QUEUE, turnId = "turn-queued")
        dispatcher.failNextStart = true
        assertThrowsSuspend<IllegalStateException> {
            control.completeTurn("turn-1")
        }
        assertNull(control.activeTurn)
        assertEquals(TurnLifecycleState.COMPLETED, control.state.value.lastFinishedTurn?.state)
        assertEquals(1, control.queuedInputs.size)
        assertEquals("Queued safe item", control.queuedInputs[0].input)

        // 5. Interrupt succeeds then replacement start fails: old turn recorded as INTERRUPTED and state is idle.
        control.submit("Fresh active", turnId = "turn-fresh")
        dispatcher.failNextStart = true
        assertThrowsSuspend<IllegalStateException> {
            control.submit("Replacement fails", mode = TurnControlMode.INTERRUPT)
        }
        assertNull(control.activeTurn)
        assertEquals(TurnLifecycleState.INTERRUPTED, control.state.value.lastFinishedTurn?.state)
        assertEquals("turn-fresh", control.state.value.lastFinishedTurn?.turnId)
    }

    @Test
    fun `identifiers are preserved verbatim, blank inputs rejected, and runtimeSessionId is transient`() = runTest {
        val nfdTurnId = "Turn-Cafe\u0301-1"
        val nfcDurableId = "Durable-Caf\u00E9"
        val volatileRuntimeId = "Runtime-Ephemeral-9"

        val control = TurnControl(
            durableSessionId = nfcDurableId,
            runtimeSessionId = volatileRuntimeId,
            idGenerator = sequentialIdGenerator("Verbatim-ID"),
        )

        val started = control.submit(
            input = "  keep leading/trailing whitespace  ",
            turnId = nfdTurnId,
        ) as TurnControlResult.Started
        assertEquals(nfdTurnId, started.turn.turnId)
        assertEquals("  keep leading/trailing whitespace  ", started.turn.initialInput)

        // Serialization preserves durableSessionId and omits @Transient runtimeSessionId.
        val encoded = Json.encodeToString(started.turn)
        val jsonObject = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(nfdTurnId), jsonObject["turnId"])
        assertEquals(JsonPrimitive(nfcDurableId), jsonObject["durableSessionId"])
        assertFalse("runtimeSessionId" in jsonObject)
        assertFalse(encoded.contains(volatileRuntimeId))

        val decoded = Json.decodeFromString<ActiveTurn>(encoded)
        assertEquals(nfdTurnId, decoded.turnId)
        assertEquals(nfcDurableId, decoded.durableSessionId)
        assertNull(decoded.runtimeSessionId)

        // Blank inputs and blank IDs are rejected.
        assertThrowsSuspend<IllegalArgumentException> {
            control.submit("   ")
        }
        assertThrowsSuspend<IllegalArgumentException> {
            control.submit("Valid input", turnId = "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnControl(durableSessionId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnControl(runtimeSessionId = "")
        }
    }

    @Test
    fun `concurrent default submissions while active serialize cleanly onto active turn`() = runTest {
        val dispatcher = RecordingDispatcher(steerDelayMillis = 10L)
        val control = TurnControl(
            dispatcher = dispatcher,
            idGenerator = sequentialIdGenerator("conc"),
        )

        val started = control.submit("Base prompt", turnId = "turn-concurrent") as TurnControlResult.Started
        val activeId = started.turn.turnId
        val totalSteers = 8

        val results = (1..totalSteers).map { idx ->
            async {
                control.submit("Steer update $idx")
            }
        }.awaitAll()

        assertTrue(results.all { it is TurnControlResult.Steered })
        val steeredResults = results.map { it as TurnControlResult.Steered }
        assertTrue(steeredResults.all { it.targetTurnId == activeId })
        assertEquals(
            (1L..totalSteers.toLong()).toList(),
            control.activeTurn?.steeringDirectives?.map { it.sequence },
        )
        assertEquals(totalSteers, dispatcher.steeredDirectives.size)
        assertTrue(dispatcher.interruptedTurns.isEmpty())
        assertTrue(control.queuedInputs.isEmpty())
    }
}
