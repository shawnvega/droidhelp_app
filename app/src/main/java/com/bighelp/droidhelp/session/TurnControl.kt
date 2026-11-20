package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Capabilities
import com.bighelp.droidhelp.transport.CapabilitiesRegistry
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
 * - [QUEUE]: Explicitly queues the input as a follow-up turn to start after the active turn ends
 *   (requires [TurnControlCapabilities.supportsQueue]).
 * - [INTERRUPT]: Explicitly interrupts the currently active turn and starts a fresh turn
 *   (requires [TurnControlCapabilities.supportsInterrupt]).
 */
@Serializable
enum class TurnControlMode {
    STEER,
    QUEUE,
    INTERRUPT,
}

/**
 * Capability flags gating optional turn-control operations beyond baseline start/steer.
 *
 * Fail-closed by default: both [supportsQueue] and [supportsInterrupt] default to `false` until
 * explicitly enabled by gateway [Capabilities] or a [CapabilitiesRegistry].
 */
@Serializable
data class TurnControlCapabilities(
    val supportsQueue: Boolean = false,
    val supportsInterrupt: Boolean = false,
) {
    /**
     * Returns `true` when [mode] is supported under these capabilities.
     *
     * [TurnControlMode.STEER] is always supported as the baseline active-turn control mode.
     */
    fun supports(mode: TurnControlMode): Boolean = when (mode) {
        TurnControlMode.STEER -> true
        TurnControlMode.QUEUE -> supportsQueue
        TurnControlMode.INTERRUPT -> supportsInterrupt
    }

    companion object {
        /** Canonical capability key for turn queueing in [CapabilitiesRegistry]. */
        const val KEY_TURN_QUEUE: String = "turn_queue"

        /** Canonical capability key for turn interruption in [CapabilitiesRegistry]. */
        const val KEY_TURN_INTERRUPT: String = "turn_interrupt"

        /** Alternate dot-delimited capability key accepted for turn queueing. */
        const val ALT_KEY_TURN_QUEUE: String = "turn.queue"

        /** Alternate dot-delimited capability key accepted for turn interruption. */
        const val ALT_KEY_TURN_INTERRUPT: String = "turn.interrupt"

        /** Fail-closed default: neither queue nor interrupt is supported. */
        val NONE: TurnControlCapabilities = TurnControlCapabilities(
            supportsQueue = false,
            supportsInterrupt = false,
        )

        /** Both queue and interrupt are supported. */
        val FULL: TurnControlCapabilities = TurnControlCapabilities(
            supportsQueue = true,
            supportsInterrupt = true,
        )

        /**
         * Resolves turn-control support from gateway [Capabilities].
         *
         * Fail-closed: `null` or `false` values evaluate to unsupported (`false`).
         */
        fun from(capabilities: Capabilities?): TurnControlCapabilities = TurnControlCapabilities(
            supportsQueue = capabilities?.turnQueue == true,
            supportsInterrupt = capabilities?.turnInterrupt == true,
        )

        /**
         * Resolves turn-control support from a live [CapabilitiesRegistry].
         *
         * Checks the canonical snake_case key first (`turn_queue` / `turn_interrupt`), then the
         * dot-delimited alias (`turn.queue` / `turn.interrupt`) if the canonical key is absent.
         *
         * Fail-closed:
         * - Unknown, unregistered, or owner-retired keys evaluate to `false`.
         * - Explicit `"true"` (case-insensitive) or `"1"` evaluate to `true`.
         * - A presence-only entry (`value == null`) evaluates to `true`.
         * - Any other value (`"false"`, `"0"`, blank, etc.) evaluates to `false`.
         */
        fun from(registry: CapabilitiesRegistry?): TurnControlCapabilities {
            if (registry == null) return NONE
            return TurnControlCapabilities(
                supportsQueue = isRegistryCapabilitySupported(
                    registry = registry,
                    primaryKey = KEY_TURN_QUEUE,
                    fallbackKey = ALT_KEY_TURN_QUEUE,
                ),
                supportsInterrupt = isRegistryCapabilitySupported(
                    registry = registry,
                    primaryKey = KEY_TURN_INTERRUPT,
                    fallbackKey = ALT_KEY_TURN_INTERRUPT,
                ),
            )
        }

        /**
         * Returns the canonical capability key required for [mode], or `null` for baseline modes.
         */
        fun requiredCapabilityKey(mode: TurnControlMode): String? = when (mode) {
            TurnControlMode.STEER -> null
            TurnControlMode.QUEUE -> KEY_TURN_QUEUE
            TurnControlMode.INTERRUPT -> KEY_TURN_INTERRUPT
        }

        private fun isRegistryCapabilitySupported(
            registry: CapabilitiesRegistry,
            primaryKey: String,
            fallbackKey: String,
        ): Boolean {
            val entry = registry.lookupEntry(primaryKey) ?: registry.lookupEntry(fallbackKey)
            return when {
                entry == null -> false
                entry.value == null -> true
                else -> entry.value.equals("true", ignoreCase = true) || entry.value == "1"
            }
        }
    }
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
    val capabilities: TurnControlCapabilities = TurnControlCapabilities.NONE,
) {
    /** `true` when a turn is currently active. */
    val hasActiveTurn: Boolean
        get() = activeTurn != null

    /** The identifier of the currently active turn, or `null` when idle. */
    val activeTurnId: String?
        get() = activeTurn?.turnId

    /** `true` when queueing is supported in this state snapshot. */
    val supportsQueue: Boolean
        get() = capabilities.supportsQueue

    /** `true` when interruption is supported in this state snapshot. */
    val supportsInterrupt: Boolean
        get() = capabilities.supportsInterrupt
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
     * was specified and queueing is supported.
     */
    data class Queued(
        val activeTurnId: String,
        val queuedInput: QueuedTurnInput,
        val queueDepth: Int,
    ) : TurnControlResult()

    /**
     * The requested [requestedMode] is not supported by the current [TurnControlCapabilities].
     *
     * Rejected safely with no state mutations and no [TurnControlDispatcher] side effects.
     */
    data class Rejected(
        val requestedMode: TurnControlMode,
        val requiredCapability: String,
        val reason: String,
        val activeTurnId: String? = null,
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
 * Thrown when a capability-gated turn-control operation (such as [TurnControl.interruptActiveTurn])
 * is invoked while the required capability is not supported.
 */
class UnsupportedTurnOperationException(
    val requestedMode: TurnControlMode,
    val requiredCapability: String,
    message: String = "Turn control mode $requestedMode is not supported " +
        "(missing capability '$requiredCapability')",
) : SessionException(message)

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
 * [ActiveTurn.turnId].
 *
 * **Support-gated queue and interrupt**: Explicit queueing ([TurnControlMode.QUEUE]) and explicit
 * interruption ([TurnControlMode.INTERRUPT] or [interruptActiveTurn]) are gated behind
 * [TurnControlCapabilities] resolved from gateway [Capabilities], a live [CapabilitiesRegistry],
 * or a custom provider. Fail-closed by default: unsupported queue or interrupt attempts are
 * rejected safely without state mutations or [TurnControlDispatcher] side effects.
 *
 * Thread-safe: all state transitions and dispatcher invocations are serialized through a [Mutex].
 * State mutations are committed only after the corresponding dispatcher callback succeeds.
 */
class TurnControl(
    private val dispatcher: TurnControlDispatcher = NoOpTurnControlDispatcher,
    private val durableSessionId: String? = null,
    private val runtimeSessionId: String? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    capabilities: TurnControlCapabilities = TurnControlCapabilities.NONE,
    capabilitiesProvider: (() -> TurnControlCapabilities)? = null,
) {
    /**
     * Creates a [TurnControl] gated by gateway [Capabilities].
     */
    constructor(
        capabilities: Capabilities,
        dispatcher: TurnControlDispatcher = NoOpTurnControlDispatcher,
        durableSessionId: String? = null,
        runtimeSessionId: String? = null,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ) : this(
        dispatcher = dispatcher,
        durableSessionId = durableSessionId,
        runtimeSessionId = runtimeSessionId,
        idGenerator = idGenerator,
        capabilities = TurnControlCapabilities.from(capabilities),
    )

    /**
     * Creates a [TurnControl] dynamically gated by a live [CapabilitiesRegistry].
     *
     * Capability registrations, updates, removals, and owner retirements take effect immediately.
     */
    constructor(
        capabilitiesRegistry: CapabilitiesRegistry,
        dispatcher: TurnControlDispatcher = NoOpTurnControlDispatcher,
        durableSessionId: String? = null,
        runtimeSessionId: String? = null,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ) : this(
        dispatcher = dispatcher,
        durableSessionId = durableSessionId,
        runtimeSessionId = runtimeSessionId,
        idGenerator = idGenerator,
        capabilitiesProvider = { TurnControlCapabilities.from(capabilitiesRegistry) },
    )

    init {
        durableSessionId?.let { ensureIdNotNormalized(it) }
        runtimeSessionId?.let { ensureIdNotNormalized(it) }
    }

    @Volatile
    private var currentCapabilitiesProvider: () -> TurnControlCapabilities =
        capabilitiesProvider ?: { capabilities }

    private val mutex = Mutex()
    private var currentTurn: ActiveTurn? = null
    private var lastFinishedTurn: ActiveTurn? = null
    private val queuedInputsList = mutableListOf<QueuedTurnInput>()
    private var turnSequence: Long = 0L

    private val _state = MutableStateFlow(
        TurnControlState(capabilities = currentCapabilitiesProvider()),
    )

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

    /** Current resolved turn-control capabilities. */
    val capabilities: TurnControlCapabilities
        get() = currentCapabilitiesProvider()

    /** `true` when [TurnControlMode.QUEUE] is currently supported. */
    val supportsQueue: Boolean
        get() = capabilities.supportsQueue

    /** `true` when [TurnControlMode.INTERRUPT] and [interruptActiveTurn] are currently supported. */
    val supportsInterrupt: Boolean
        get() = capabilities.supportsInterrupt

    /** Returns `true` when [mode] is currently supported. */
    fun isModeSupported(mode: TurnControlMode): Boolean = capabilities.supports(mode)

    /**
     * Replaces the active turn-control capabilities snapshot and publishes the updated state.
     */
    suspend fun updateCapabilities(capabilities: TurnControlCapabilities) = mutex.withLock {
        currentCapabilitiesProvider = { capabilities }
        publishStateLocked(capabilities)
    }

    /**
     * Replaces the active turn-control capabilities from gateway [Capabilities].
     */
    suspend fun updateCapabilities(capabilities: Capabilities) {
        updateCapabilities(TurnControlCapabilities.from(capabilities))
    }

    /**
     * Submits user [input] to the session.
     *
     * - When [mode] is not supported by the current [TurnControlCapabilities], returns
     *   [TurnControlResult.Rejected] immediately without state mutations or dispatcher side effects.
     * - When no turn is active (and [mode] is supported), starts a new active turn and returns
     *   [TurnControlResult.Started].
     * - When a turn is active, [mode] defaults to [TurnControlMode.STEER], steering the current
     *   active turn in place targeting its [ActiveTurn.turnId] without requiring explicit queueing
     *   or interrupt flags.
     * - Pass [TurnControlMode.QUEUE] (when supported) to explicitly enqueue behind the active turn,
     *   or [TurnControlMode.INTERRUPT] (when supported) to explicitly interrupt the active turn and
     *   start a new one.
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

        val resolvedCapabilities = currentCapabilitiesProvider()
        if (!resolvedCapabilities.supports(mode)) {
            val capabilityKey = TurnControlCapabilities.requiredCapabilityKey(mode)
                ?: mode.name.lowercase()
            return@withLock TurnControlResult.Rejected(
                requestedMode = mode,
                requiredCapability = capabilityKey,
                reason = "Turn control mode $mode is not supported (missing capability '$capabilityKey')",
                activeTurnId = currentTurn?.turnId,
            )
        }

        val active = currentTurn
        if (active == null) {
            val started = startNewTurnLocked(input, metadata, turnId, resolvedCapabilities)
            return@withLock TurnControlResult.Started(turn = started)
        }

        when (mode) {
            TurnControlMode.STEER -> {
                if (turnId != null && active.turnId != turnId) {
                    throw StaleTurnException(requestedTurnId = turnId, activeTurnId = active.turnId)
                }
                steerActiveTurnLocked(active, input, metadata, resolvedCapabilities)
            }
            TurnControlMode.QUEUE -> enqueueLocked(active, input, metadata, turnId, resolvedCapabilities)
            TurnControlMode.INTERRUPT -> {
                dispatcher.onInterruptTurn(active.turnId, null)
                lastFinishedTurn = active.copy(state = TurnLifecycleState.INTERRUPTED)
                currentTurn = null
                publishStateLocked(resolvedCapabilities)

                val started = startNewTurnLocked(input, metadata, turnId, resolvedCapabilities)
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
        steerActiveTurnLocked(active, input, metadata, currentCapabilitiesProvider())
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
     * Requires [TurnControlCapabilities.supportsInterrupt]. When unsupported, throws
     * [UnsupportedTurnOperationException] without modifying active turn state, clearing the queue,
     * or invoking [TurnControlDispatcher.onInterruptTurn].
     *
     * Returns the interrupted [ActiveTurn], or `null` if idle and [expectedTurnId] is `null`.
     *
     * @throws UnsupportedTurnOperationException if interrupt capability is not supported.
     * @throws StaleTurnException if [expectedTurnId] is supplied and does not match the active turn.
     */
    suspend fun interruptActiveTurn(
        expectedTurnId: String? = null,
        reason: String? = null,
        clearQueue: Boolean = false,
    ): ActiveTurn? = mutex.withLock {
        expectedTurnId?.let { ensureIdNotNormalized(it) }
        val resolvedCapabilities = currentCapabilitiesProvider()
        if (!resolvedCapabilities.supportsInterrupt) {
            throw UnsupportedTurnOperationException(
                requestedMode = TurnControlMode.INTERRUPT,
                requiredCapability = TurnControlCapabilities.KEY_TURN_INTERRUPT,
            )
        }

        val active = currentTurn
        if (active == null) {
            if (expectedTurnId != null) {
                throw StaleTurnException(requestedTurnId = expectedTurnId, activeTurnId = null)
            }
            if (clearQueue && queuedInputsList.isNotEmpty()) {
                queuedInputsList.clear()
                publishStateLocked(resolvedCapabilities)
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
        publishStateLocked(resolvedCapabilities)
        interrupted
    }

    /**
     * Marks the active turn as finished ([TurnLifecycleState.COMPLETED], or
     * [TurnLifecycleState.FAILED] when [failed] is `true`).
     *
     * If inputs were explicitly queued via [TurnControlMode.QUEUE] and queueing remains supported,
     * promotes the next queued input (FIFO) to a new active turn so subsequent default [submit]
     * calls steer that new turn. If queue support was revoked before completion, queued inputs are
     * preserved without auto-promotion.
     *
     * @throws NoActiveTurnException if no turn is active and [turnId] is `null`.
     * @throws StaleTurnException if [turnId] is supplied and does not match the active turn.
     */
    suspend fun completeTurn(
        turnId: String? = null,
        failed: Boolean = false,
    ): TurnCompletionResult = mutex.withLock {
        val active = requireMatchingActiveTurn(turnId)
        val resolvedCapabilities = currentCapabilitiesProvider()
        val terminalState = if (failed) TurnLifecycleState.FAILED else TurnLifecycleState.COMPLETED
        val finished = active.copy(state = terminalState)

        currentTurn = null
        lastFinishedTurn = finished
        publishStateLocked(resolvedCapabilities)

        val nextQueued = if (resolvedCapabilities.supportsQueue) queuedInputsList.firstOrNull() else null
        val startedNext = if (nextQueued != null) {
            val started = startNewTurnLocked(
                input = nextQueued.input,
                metadata = nextQueued.metadata,
                explicitTurnId = nextQueued.requestedTurnId,
                resolvedCapabilities = resolvedCapabilities,
            )
            queuedInputsList.removeAt(0)
            for (index in queuedInputsList.indices) {
                queuedInputsList[index] = queuedInputsList[index].copy(position = index + 1)
            }
            publishStateLocked(resolvedCapabilities)
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
        resolvedCapabilities: TurnControlCapabilities,
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
        publishStateLocked(resolvedCapabilities)
        return turn
    }

    private suspend fun steerActiveTurnLocked(
        active: ActiveTurn,
        input: String,
        metadata: Map<String, String>,
        resolvedCapabilities: TurnControlCapabilities,
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
        publishStateLocked(resolvedCapabilities)
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
        resolvedCapabilities: TurnControlCapabilities,
    ): TurnControlResult.Queued {
        val queued = QueuedTurnInput(
            queueId = ensureIdNotNormalized(idGenerator()),
            input = input,
            position = queuedInputsList.size + 1,
            requestedTurnId = requestedTurnId,
            metadata = metadata.toMap(),
        )
        queuedInputsList.add(queued)
        publishStateLocked(resolvedCapabilities)
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

    private fun publishStateLocked(
        resolvedCapabilities: TurnControlCapabilities = currentCapabilitiesProvider(),
    ) {
        _state.value = TurnControlState(
            activeTurn = currentTurn,
            queuedInputs = queuedInputsList.toList(),
            lastFinishedTurn = lastFinishedTurn,
            capabilities = resolvedCapabilities,
        )
    }

    companion object {
        /** Default behavior when [submit] is invoked while a turn is active. */
        val DEFAULT_ACTIVE_TURN_MODE: TurnControlMode = TurnControlMode.STEER
    }
}
