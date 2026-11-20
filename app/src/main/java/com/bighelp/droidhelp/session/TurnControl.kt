package com.bighelp.droidhelp.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/**
 * Policy controlling how [TurnControl.submit] behaves when a turn is already active.
 *
 * - [STEER] (**default**): Steers the currently active turn in place, targeting its `turnId`
 *   without requiring explicit queueing or interrupt flags.
 * - [QUEUE]: Explicitly queues the input as a follow-up turn to start after the active turn ends.
 * - [INTERRUPT]: Explicitly interrupts the currently active turn and starts a fresh turn.
 */
@Serializable
enum class TurnControlMode {
    STEER,
    QUEUE,
    INTERRUPT,
}

/**
 * Lifecycle state of a tracked turn.
 */
@Serializable
enum class TurnLifecycleState {
    ACTIVE,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

/**
 * Represents a single steering directive applied to an active turn.
 */
@Serializable
data class SteeringDirective(
    val steeringId: String,
    val targetTurnId: String,
    val input: String,
    val sequence: Long,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        ensureIdNotNormalized(steeringId)
        ensureIdNotNormalized(targetTurnId)
        require(input.isNotBlank()) { "Steering input must not be empty or blank" }
        checkIntBounds(sequence, min = 1L, max = Long.MAX_VALUE)
    }
}

/**
 * Represents an input explicitly queued behind an active turn via [TurnControlMode.QUEUE].
 */
@Serializable
data class QueuedTurnInput(
    val queueId: String,
    val input: String,
    val position: Int,
    val requestedTurnId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        ensureIdNotNormalized(queueId)
        require(input.isNotBlank()) { "Queued turn input must not be empty or blank" }
        checkIntBounds(position.toLong(), min = 1L, max = Int.MAX_VALUE.toLong())
        requestedTurnId?.let { ensureIdNotNormalized(it) }
    }
}

/**
 * Immutable snapshot of a turn tracked by [TurnControl].
 *
 * Keeps [durableSessionId] and volatile [runtimeSessionId] separated consistent with
 * [Session], marking [runtimeSessionId] as [Transient] so runtime-only IDs are never serialized.
 */
@Serializable
data class ActiveTurn(
    val turnId: String,
    val initialInput: String,
    val sequence: Long,
    val durableSessionId: String? = null,
    @Transient
    val runtimeSessionId: String? = null,
    val state: TurnLifecycleState = TurnLifecycleState.ACTIVE,
    val steeringDirectives: List<SteeringDirective> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        ensureIdNotNormalized(turnId)
        require(initialInput.isNotBlank()) { "Turn initialInput must not be empty or blank" }
        checkIntBounds(sequence, min = 1L, max = Long.MAX_VALUE)
        durableSessionId?.let { ensureIdNotNormalized(it) }
        runtimeSessionId?.let { ensureIdNotNormalized(it) }
    }

    /** `true` while this turn is still active and eligible for steering. */
    val isActive: Boolean
        get() = state == TurnLifecycleState.ACTIVE

    /** Number of steering directives delivered to this turn. */
    val steeringCount: Int
        get() = steeringDirectives.size
}

/**
 * Observable immutable snapshot of [TurnControl] state.
 */
data class TurnControlState(
    val activeTurn: ActiveTurn? = null,
    val queuedInputs: List<QueuedTurnInput> = emptyList(),
    val lastFinishedTurn: ActiveTurn? = null,
) {
    /** `true` when a turn is currently active. */
    val hasActiveTurn: Boolean
        get() = activeTurn != null

    /** The identifier of the currently active turn, or `null` when idle. */
    val activeTurnId: String?
        get() = activeTurn?.turnId
}

/**
 * Outcome returned by [TurnControl.submit] and [TurnControl.steer].
 */
sealed class TurnControlResult {
    /**
     * A new turn became active (either from an idle state or after explicitly interrupting an
     * existing turn via [TurnControlMode.INTERRUPT]).
     */
    data class Started(
        val turn: ActiveTurn,
        val interruptedTurnId: String? = null,
    ) : TurnControlResult()

    /**
     * The current active turn was steered in place targeting [targetTurnId].
     *
     * This is the default outcome when [TurnControl.submit] is called while a turn is active.
     */
    data class Steered(
        val targetTurnId: String,
        val directive: SteeringDirective,
        val activeTurn: ActiveTurn,
    ) : TurnControlResult()

    /**
     * The input was explicitly queued behind [activeTurnId] because [TurnControlMode.QUEUE]
     * was specified.
     */
    data class Queued(
        val activeTurnId: String,
        val queuedInput: QueuedTurnInput,
        val queueDepth: Int,
    ) : TurnControlResult()
}

/**
 * Outcome returned when an active turn finishes via [TurnControl.completeTurn].
 */
data class TurnCompletionResult(
    val finishedTurn: ActiveTurn,
    val startedNextTurn: ActiveTurn? = null,
)

/**
 * Thrown when an explicit active-turn operation (such as [TurnControl.steer]) is invoked while
 * no turn is currently active.
 */
class NoActiveTurnException(message: String = "No active turn is currently running") :
    SessionException(message)

/**
 * Thrown when an operation targets a turn ID that does not match the currently active turn.
 */
class StaleTurnException(
    val requestedTurnId: String,
    val activeTurnId: String?,
) : SessionException(
    "Turn mismatch: requested '$requestedTurnId', active '${activeTurnId ?: "<none>"}'"
)

/**
 * Transport/session hook invoked by [TurnControl] when turns are started, steered, or interrupted.
 */
interface TurnControlDispatcher {
    suspend fun onStartTurn(turn: ActiveTurn) = Unit
    suspend fun onSteerTurn(targetTurnId: String, directive: SteeringDirective) = Unit
    suspend fun onInterruptTurn(turnId: String, reason: String?) = Unit
}

private object NoOpTurnControlDispatcher : TurnControlDispatcher

/**
 * Coordinates turn lifecycle and user input routing for a session.
 *
 * **Default active-turn steering**: When a turn is active, calling [submit] without any queueing
 * or interrupt flags automatically steers the current active turn in place, targeting its exact
 * [ActiveTurn.turnId]. Explicit queueing ([TurnControlMode.QUEUE]) and explicit interruption
 * ([TurnControlMode.INTERRUPT] or [interruptActiveTurn]) remain opt-in behaviors.
 *
 * Thread-safe: all state transitions and dispatcher invocations are serialized through a [Mutex].
 * State mutations are committed only after the corresponding dispatcher callback succeeds.
 */
class TurnControl(
    private val dispatcher: TurnControlDispatcher = NoOpTurnControlDispatcher,
    private val durableSessionId: String? = null,
    private val runtimeSessionId: String? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        durableSessionId?.let { ensureIdNotNormalized(it) }
        runtimeSessionId?.let { ensureIdNotNormalized(it) }
    }

    private val mutex = Mutex()
    private var currentTurn: ActiveTurn? = null
    private var lastFinishedTurn: ActiveTurn? = null
    private val queuedInputsList = mutableListOf<QueuedTurnInput>()
    private var turnSequence: Long = 0L

    private val _state = MutableStateFlow(TurnControlState())

    /** Reactive stream of immutable [TurnControlState] snapshots. */
    val state: StateFlow<TurnControlState> = _state.asStateFlow()

    /** Snapshot of the currently active turn, or `null` when idle. */
    val activeTurn: ActiveTurn?
        get() = _state.value.activeTurn

    /** Identifier of the currently active turn, or `null` when idle. */
    val activeTurnId: String?
        get() = _state.value.activeTurnId

    /** `true` if a turn is currently active. */
    val hasActiveTurn: Boolean
        get() = _state.value.hasActiveTurn

    /** Snapshot of explicitly queued follow-up inputs in FIFO order. */
    val queuedInputs: List<QueuedTurnInput>
        get() = _state.value.queuedInputs

    /**
     * Submits user [input] to the session.
     *
     * - When no turn is active, starts a new active turn and returns [TurnControlResult.Started].
     * - When a turn is active, [mode] defaults to [TurnControlMode.STEER], steering the current
     *   active turn in place targeting its [ActiveTurn.turnId] without requiring explicit queueing
     *   or interrupt flags.
     * - Pass [TurnControlMode.QUEUE] to explicitly enqueue behind the active turn, or
     *   [TurnControlMode.INTERRUPT] to explicitly interrupt the active turn and start a new one.
     * - When [turnId] is provided while steering, it is validated verbatim against the current
     *   active turn and throws [StaleTurnException] on mismatch.
     */
    suspend fun submit(
        input: String,
        mode: TurnControlMode = DEFAULT_ACTIVE_TURN_MODE,
        metadata: Map<String, String> = emptyMap(),
        turnId: String? = null,
    ): TurnControlResult = mutex.withLock {
        require(input.isNotBlank()) { "Turn input must not be empty or blank" }
        turnId?.let { ensureIdNotNormalized(it) }

        val active = currentTurn
        if (active == null) {
            val started = startNewTurnLocked(
                input = input,
                metadata = metadata,
                explicitTurnId = turnId,
            )
            return@withLock TurnControlResult.Started(turn = started)
        }

        when (mode) {
            TurnControlMode.STEER -> {
                if (turnId != null && active.turnId != turnId) {
                    throw StaleTurnException(requestedTurnId = turnId, activeTurnId = active.turnId)
                }
                steerActiveTurnLocked(active = active, input = input, metadata = metadata)
            }
            TurnControlMode.QUEUE -> enqueueLocked(
                active = active,
                input = input,
                metadata = metadata,
                requestedTurnId = turnId,
            )
            TurnControlMode.INTERRUPT -> {
                dispatcher.onInterruptTurn(active.turnId, null)
                lastFinishedTurn = active.copy(state = TurnLifecycleState.INTERRUPTED)
                currentTurn = null
                publishStateLocked()

                val started = startNewTurnLocked(
                    input = input,
                    metadata = metadata,
                    explicitTurnId = turnId,
                )
                TurnControlResult.Started(turn = started, interruptedTurnId = active.turnId)
            }
        }
    }

    /**
     * Explicitly steers the currently active turn without ever starting a new turn.
     *
     * Targets the current active turn when [targetTurnId] is omitted, or verifies an exact
     * verbatim match against [ActiveTurn.turnId] when [targetTurnId] is supplied.
     *
     * @throws NoActiveTurnException if no turn is active and [targetTurnId] is `null`.
     * @throws StaleTurnException if [targetTurnId] does not match the currently active turn.
     */
    suspend fun steer(
        input: String,
        targetTurnId: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): TurnControlResult.Steered = mutex.withLock {
        require(input.isNotBlank()) { "Steering input must not be empty or blank" }
        val active = requireMatchingActiveTurn(targetTurnId)
        steerActiveTurnLocked(active = active, input = input, metadata = metadata)
    }

    /**
     * Registers an externally started or resumed active turn (without invoking
     * [TurnControlDispatcher.onStartTurn]) so subsequent default [submit] calls steer it directly.
     *
     * Re-attaching the exact currently active [turnId] is an idempotent no-op.
     *
     * @throws IllegalStateException if a different turn is already active.
     */
    suspend fun attachActiveTurn(
        turnId: String,
        initialInput: String,
        metadata: Map<String, String> = emptyMap(),
    ): ActiveTurn = mutex.withLock {
        ensureIdNotNormalized(turnId)
        require(initialInput.isNotBlank()) { "Turn initialInput must not be empty or blank" }

        val existing = currentTurn
        if (existing != null) {
            if (existing.turnId == turnId) {
                return@withLock existing
            }
            error("Cannot attach turn '$turnId': turn '${existing.turnId}' is already active")
        }

        val nextSeq = turnSequence + 1L
        val attached = ActiveTurn(
            turnId = turnId,
            initialInput = initialInput,
            sequence = nextSeq,
            durableSessionId = durableSessionId,
            runtimeSessionId = runtimeSessionId,
            state = TurnLifecycleState.ACTIVE,
            metadata = metadata.toMap(),
        )
        turnSequence = nextSeq
        currentTurn = attached
        publishStateLocked()
        attached
    }

    /**
     * Explicitly interrupts the currently active turn without starting a replacement turn.
     *
     * Returns the interrupted [ActiveTurn], or `null` if idle and [expectedTurnId] is `null`.
     *
     * @throws StaleTurnException if [expectedTurnId] is supplied and does not match the active turn.
     */
    suspend fun interruptActiveTurn(
        expectedTurnId: String? = null,
        reason: String? = null,
        clearQueue: Boolean = false,
    ): ActiveTurn? = mutex.withLock {
        expectedTurnId?.let { ensureIdNotNormalized(it) }
        val active = currentTurn
        if (active == null) {
            if (expectedTurnId != null) {
                throw StaleTurnException(requestedTurnId = expectedTurnId, activeTurnId = null)
            }
            if (clearQueue && queuedInputsList.isNotEmpty()) {
                queuedInputsList.clear()
                publishStateLocked()
            }
            return@withLock null
        }

        if (expectedTurnId != null && active.turnId != expectedTurnId) {
            throw StaleTurnException(requestedTurnId = expectedTurnId, activeTurnId = active.turnId)
        }

        dispatcher.onInterruptTurn(active.turnId, reason)
        val interrupted = active.copy(state = TurnLifecycleState.INTERRUPTED)
        currentTurn = null
        lastFinishedTurn = interrupted
        if (clearQueue) {
            queuedInputsList.clear()
        }
        publishStateLocked()
        interrupted
    }

    /**
     * Marks the active turn as finished ([TurnLifecycleState.COMPLETED], or
     * [TurnLifecycleState.FAILED] when [failed] is `true`).
     *
     * If inputs were explicitly queued via [TurnControlMode.QUEUE], promotes the next queued
     * input (FIFO) to a new active turn so subsequent default [submit] calls steer that new turn.
     *
     * @throws NoActiveTurnException if no turn is active and [turnId] is `null`.
     * @throws StaleTurnException if [turnId] is supplied and does not match the active turn.
     */
    suspend fun completeTurn(
        turnId: String? = null,
        failed: Boolean = false,
    ): TurnCompletionResult = mutex.withLock {
        val active = requireMatchingActiveTurn(turnId)
        val terminalState = if (failed) TurnLifecycleState.FAILED else TurnLifecycleState.COMPLETED
        val finished = active.copy(state = terminalState)

        currentTurn = null
        lastFinishedTurn = finished
        publishStateLocked()

        val nextQueued = queuedInputsList.firstOrNull()
        val startedNext = if (nextQueued != null) {
            val started = startNewTurnLocked(
                input = nextQueued.input,
                metadata = nextQueued.metadata,
                explicitTurnId = nextQueued.requestedTurnId,
            )
            queuedInputsList.removeAt(0)
            for (index in queuedInputsList.indices) {
                queuedInputsList[index] = queuedInputsList[index].copy(position = index + 1)
            }
            publishStateLocked()
            started
        } else {
            null
        }

        TurnCompletionResult(finishedTurn = finished, startedNextTurn = startedNext)
    }

    /**
     * Clears and returns any explicitly queued follow-up inputs without affecting the active turn.
     */
    suspend fun clearQueue(): List<QueuedTurnInput> = mutex.withLock {
        if (queuedInputsList.isEmpty()) {
            return@withLock emptyList()
        }
        val removed = queuedInputsList.toList()
        queuedInputsList.clear()
        publishStateLocked()
        removed
    }

    private suspend fun startNewTurnLocked(
        input: String,
        metadata: Map<String, String>,
        explicitTurnId: String?,
    ): ActiveTurn {
        val resolvedTurnId = explicitTurnId?.let { ensureIdNotNormalized(it) }
            ?: ensureIdNotNormalized(idGenerator())
        val nextSeq = turnSequence + 1L
        val turn = ActiveTurn(
            turnId = resolvedTurnId,
            initialInput = input,
            sequence = nextSeq,
            durableSessionId = durableSessionId,
            runtimeSessionId = runtimeSessionId,
            state = TurnLifecycleState.ACTIVE,
            metadata = metadata.toMap(),
        )
        dispatcher.onStartTurn(turn)
        turnSequence = nextSeq
        currentTurn = turn
        publishStateLocked()
        return turn
    }

    private suspend fun steerActiveTurnLocked(
        active: ActiveTurn,
        input: String,
        metadata: Map<String, String>,
    ): TurnControlResult.Steered {
        val directive = SteeringDirective(
            steeringId = ensureIdNotNormalized(idGenerator()),
            targetTurnId = active.turnId,
            input = input,
            sequence = active.steeringDirectives.size.toLong() + 1L,
            metadata = metadata.toMap(),
        )
        dispatcher.onSteerTurn(active.turnId, directive)
        val updatedTurn = active.copy(
            steeringDirectives = active.steeringDirectives + directive,
        )
        currentTurn = updatedTurn
        publishStateLocked()
        return TurnControlResult.Steered(
            targetTurnId = active.turnId,
            directive = directive,
            activeTurn = updatedTurn,
        )
    }

    private fun enqueueLocked(
        active: ActiveTurn,
        input: String,
        metadata: Map<String, String>,
        requestedTurnId: String?,
    ): TurnControlResult.Queued {
        val queued = QueuedTurnInput(
            queueId = ensureIdNotNormalized(idGenerator()),
            input = input,
            position = queuedInputsList.size + 1,
            requestedTurnId = requestedTurnId,
            metadata = metadata.toMap(),
        )
        queuedInputsList.add(queued)
        publishStateLocked()
        return TurnControlResult.Queued(
            activeTurnId = active.turnId,
            queuedInput = queued,
            queueDepth = queuedInputsList.size,
        )
    }

    private fun requireMatchingActiveTurn(expectedTurnId: String?): ActiveTurn {
        expectedTurnId?.let { ensureIdNotNormalized(it) }
        val active = currentTurn
        if (expectedTurnId != null && active?.turnId != expectedTurnId) {
            throw StaleTurnException(requestedTurnId = expectedTurnId, activeTurnId = active?.turnId)
        }
        return active ?: throw NoActiveTurnException()
    }

    private fun publishStateLocked() {
        _state.value = TurnControlState(
            activeTurn = currentTurn,
            queuedInputs = queuedInputsList.toList(),
            lastFinishedTurn = lastFinishedTurn,
        )
    }

    companion object {
        /** Default behavior when [submit] is invoked while a turn is active. */
        val DEFAULT_ACTIVE_TURN_MODE: TurnControlMode = TurnControlMode.STEER
    }
}
