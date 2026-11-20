package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import com.bighelp.droidhelp.session.checkUtf8ByteLimit
import com.bighelp.droidhelp.session.ensureIdNotNormalized

/**
 * Thread-safe per-connection state machine for socket generation retirement, uncertain mutation
 * tracking, sign-in boundary enforcement, and durable session resumption.
 *
 * Mirrors the socket-generation and recovery invariants of `DirectHermesClient`,
 * `WorkspaceReconnect`, and `NativeWorkspaceSessionBridge` in bighelp.
 */
class ReconnectState {
    private val lock = Any()
    private var socketGeneration: Long = 0L
    private var _boundSignIn: SignIn? = null
    private var _session: Session? = null
    private var _runtimeSessionId: String? = null
    private var _requiresResume: Boolean = false
    private val pendingOperations = LinkedHashMap<String, PendingOperation>()
    private val _uncertainMutations = LinkedHashMap<String, SettledOperation>()

    /** Current monotonic socket generation. Incremented atomically on every socket retirement. */
    val currentGeneration: Long
        get() = synchronized(lock) { socketGeneration }

    /** Currently bound [SignIn] for boundary classification, if any. */
    val boundSignIn: SignIn?
        get() = synchronized(lock) { _boundSignIn }

    /** Currently retained [Session] to resume on reconnect, if any. */
    val session: Session?
        get() = synchronized(lock) { _session }

    /** Active server runtime session ID bound to the current socket, or `null` when detached. */
    val runtimeSessionId: String?
        get() = synchronized(lock) { _runtimeSessionId }

    /** `true` when the retained session must complete durable `session.resume` before admission. */
    val requiresResume: Boolean
        get() = synchronized(lock) { _requiresResume }

    /** Number of currently tracked in-flight operations on the active socket generation. */
    val pendingOperationCount: Int
        get() = synchronized(lock) { pendingOperations.size }

    /** `true` when one or more mutations have unresolved unknown server outcomes. */
    val hasUncertainMutations: Boolean
        get() = synchronized(lock) { _uncertainMutations.isNotEmpty() }

    /** Immutable copy of unresolved uncertain mutations keyed by operation ID. */
    val uncertainMutations: Map<String, SettledOperation>
        get() = synchronized(lock) { _uncertainMutations.toMap() }

    /** `true` only when a session is retained, bound to a live runtime, and not awaiting resume. */
    val isReadyForSubmission: Boolean
        get() = synchronized(lock) { _session != null && !_requiresResume && _runtimeSessionId != null }

    /**
     * Validates that [capturedGeneration] matches [currentGeneration].
     *
     * @throws StaleOwnerException if the socket generation has been retired.
     */
    fun validateGeneration(capturedGeneration: Long) {
        synchronized(lock) {
            if (capturedGeneration != socketGeneration) {
                throw StaleOwnerException(
                    "Socket generation retired: captured $capturedGeneration, current $socketGeneration"
                )
            }
        }
    }

    /**
     * Registers an outgoing operation against the active [currentGeneration] with
     * `sendStarted = false`.
     */
    fun registerOperation(
        id: String,
        method: String,
        readOnly: Boolean = ReconnectPolicy.isReadOnlyMethod(method),
    ): PendingOperation {
        ensureIdNotNormalized(id)
        ensureIdNotNormalized(method)
        return synchronized(lock) {
            require(id !in pendingOperations) { "Operation '$id' is already pending" }
            val operation = PendingOperation(
                id = id,
                method = method,
                readOnly = readOnly,
                sendStarted = false,
                socketGeneration = socketGeneration,
            )
            pendingOperations[id] = operation
            operation
        }
    }

    /**
     * Marks [id] as having started transmission before the first socket write suspension.
     *
     * Returns `false` if [expectedGeneration] has already been retired or [id] is not pending.
     */
    fun markSendStarted(id: String, expectedGeneration: Long): Boolean {
        return synchronized(lock) {
            if (expectedGeneration != socketGeneration) {
                return@synchronized false
            }
            val existing = pendingOperations[id] ?: return@synchronized false
            if (existing.socketGeneration != expectedGeneration) {
                return@synchronized false
            }
            pendingOperations[id] = existing.copy(sendStarted = true)
            true
        }
    }

    /**
     * Completes and removes a definitively answered pending operation.
     */
    fun completeOperation(id: String): PendingOperation? {
        return synchronized(lock) {
            pendingOperations.remove(id)
        }
    }

    /**
     * Settles a locally cancelled or timed-out pending operation on [expectedGeneration].
     *
     * Mirrors `DirectHermesClient.expire` and `cancelRequest`: if `sendStarted && !readOnly`,
     * the operation settles with `outcomeUnknown = true` and is recorded in [uncertainMutations]
     * without ever being automatically replayed.
     */
    fun settleInterruptedOperation(id: String, expectedGeneration: Long): SettledOperation? {
        return synchronized(lock) {
            if (expectedGeneration != socketGeneration) {
                return@synchronized null
            }
            val operation = pendingOperations.remove(id) ?: return@synchronized null
            val settled = SettledOperation(
                id = operation.id,
                method = operation.method,
                outcomeUnknown = operation.isUncertainMutation,
            )
            if (settled.outcomeUnknown) {
                _uncertainMutations[settled.id] = settled
            }
            settled
        }
    }

    /**
     * Atomically retires the current socket generation, settles all pending operations, flags any
     * retained session for durable resumption, and decides whether automatic recovery may proceed.
     *
     * Mirrors `DirectHermesClient.closeConnection` in bighelp:
     * - Any pending operation with `sendStarted && !readOnly` marks `hasUncertainMutation = true`,
     *   settles with `outcomeUnknown = true`, is retained in [uncertainMutations], and suppresses
     *   automatic recovery (`shouldAutomaticallyRecover = false`).
     * - Advances [currentGeneration] and calls [Owners.incrementSocketEpoch] when [owners] is given.
     * - Clears [runtimeSessionId] and sets [requiresResume] when a session is retained.
     */
    fun retireSocketGeneration(
        owners: Owners? = null,
        notify: Boolean = true,
        hadConnection: Boolean = true,
        terminallyClosed: Boolean = false,
        automaticRecoveryActive: Boolean = false,
    ): SocketRetirement {
        return synchronized(lock) {
            val previousGeneration = socketGeneration
            val previousSocketOwner = owners?.getSocketOwner()

            val operations = pendingOperations.values.toList()
            pendingOperations.clear()

            val settled = operations.map { op ->
                val result = SettledOperation(
                    id = op.id,
                    method = op.method,
                    outcomeUnknown = op.isUncertainMutation,
                )
                if (result.outcomeUnknown) {
                    _uncertainMutations[result.id] = result
                }
                result
            }
            val hasUncertain = settled.any { it.outcomeUnknown }
            val shouldRecover = notify && hadConnection && !terminallyClosed && !hasUncertain
            val preservesHandlers = shouldRecover || automaticRecoveryActive

            socketGeneration += 1L
            val nextSocketOwner = owners?.incrementSocketEpoch()

            val needsResume = _session != null
            if (needsResume) {
                _requiresResume = true
                _runtimeSessionId = null
            }

            SocketRetirement(
                previousGeneration = previousGeneration,
                nextGeneration = socketGeneration,
                hasUncertainMutation = hasUncertain,
                shouldAutomaticallyRecover = shouldRecover,
                preservesHandlers = preservesHandlers,
                settledOperations = settled,
                previousSocketOwner = previousSocketOwner,
                nextSocketOwner = nextSocketOwner,
                requiresSessionResume = needsResume,
            )
        }
    }

    /**
     * Retains or clears the active session for resumption.
     *
     * When [runtimeId] is provided alongside a non-null [session], marks the session bound to that
     * runtime (`requiresResume = false`); when [session] is `null`, clears all session binding state.
     */
    fun setSession(session: Session?, runtimeId: String? = null) {
        synchronized(lock) {
            _session = session
            if (session == null) {
                _runtimeSessionId = null
                _requiresResume = false
            } else {
                _runtimeSessionId = runtimeId
                _requiresResume = runtimeId == null
            }
        }
    }

    /**
     * Handles a [SignIn] lifecycle transition.
     *
     * On [ReconnectClassification.BOUNDARY], retires the active socket generation and clears any
     * retained session and uncertain mutations so state cannot cross account/host boundaries.
     * On [ReconnectClassification.SAME_SIGN_IN], keeps the retained session and flags it for resume.
     */
    fun onSignInTransition(newSignIn: SignIn, owners: Owners? = null): ReconnectClassification {
        return synchronized(lock) {
            val classification = ReconnectPolicy.classifyReconnect(newSignIn, _boundSignIn)
            _boundSignIn = newSignIn
            when (classification) {
                ReconnectClassification.BOUNDARY -> {
                    retireSocketGeneration(
                        owners = owners,
                        notify = false,
                        hadConnection = true,
                        terminallyClosed = true,
                        automaticRecoveryActive = false,
                    )
                    _session = null
                    _runtimeSessionId = null
                    _requiresResume = false
                    _uncertainMutations.clear()
                }
                ReconnectClassification.SAME_SIGN_IN -> {
                    if (_session != null) {
                        _requiresResume = true
                        _runtimeSessionId = null
                    }
                }
            }
            classification
        }
    }

    /**
     * Adopts a validated [ResumedSession] returned by `session.resume` on [expectedGeneration].
     *
     * Supports compaction-successor stored IDs while rejecting stale socket generations via
     * [StaleOwnerException].
     */
    fun adoptResumedSession(resumed: ResumedSession, expectedGeneration: Long): Session {
        return synchronized(lock) {
            if (expectedGeneration != socketGeneration) {
                throw StaleOwnerException(
                    "Cannot adopt resumed session for retired socket generation " +
                        "$expectedGeneration (current $socketGeneration)"
                )
            }
            val current = checkNotNull(_session) {
                "No retained session available to adopt resumed coordinates"
            }
            val updated = current.copy(id = resumed.storedId)
            _session = updated
            _runtimeSessionId = resumed.runtimeId
            _requiresResume = false
            updated
        }
    }

    /**
     * Handles a server `session.reclaimed` event.
     *
     * Matches `DirectHermesReconnectParityTests`: revokes only when [runtimeId] matches the
     * currently bound runtime (or, when no runtime is bound yet, when [storedId] matches the
     * retained session ID). Foreign runtime reclaims are ignored.
     */
    fun reclaimSession(runtimeId: String, storedId: String? = null): Boolean {
        return synchronized(lock) {
            val current = _session ?: return@synchronized false
            val matches = if (_runtimeSessionId != null) {
                _runtimeSessionId == runtimeId
            } else {
                storedId != null && current.id == storedId
            }
            if (matches) {
                _runtimeSessionId = null
                _requiresResume = true
            }
            matches
        }
    }

    /**
     * Marks a previously recorded uncertain mutation as resolved after authoritative reconciliation.
     *
     * @return `true` if an uncertain mutation with [id] was present and removed.
     */
    fun resolveUncertainMutation(id: String): Boolean {
        return synchronized(lock) {
            _uncertainMutations.remove(id) != null
        }
    }

    /** Returns an atomic point-in-time [ReconnectSnapshot] of this state. */
    fun snapshot(): ReconnectSnapshot {
        return synchronized(lock) {
            ReconnectSnapshot(
                socketGeneration = socketGeneration,
                boundSignIn = _boundSignIn,
                session = _session,
                runtimeSessionId = _runtimeSessionId,
                requiresResume = _requiresResume,
                pendingOperationCount = pendingOperations.size,
                uncertainMutations = _uncertainMutations.toMap(),
            )
        }
    }

    /** Resets all state back to its initial values. */
    fun reset() {
        synchronized(lock) {
            socketGeneration = 0L
            _boundSignIn = null
            _session = null
            _runtimeSessionId = null
            _requiresResume = false
            pendingOperations.clear()
            _uncertainMutations.clear()
        }
    }
}

/**
 * Validates a session or profile coordinate verbatim without normalizing whitespace or casing.
 *
 * Mirrors `WorkspaceAuthority.validateIdentifier` in bighelp: non-empty, bounded UTF-8 byte
 * length, no leading/trailing whitespace, and no Unicode control characters.
 */
internal fun validateReconnectCoordinate(
    value: String,
    label: String,
    maxUtf8Bytes: Int,
): String {
    ensureIdNotNormalized(value)
    checkUtf8ByteLimit(value, maxUtf8Bytes)
    require(value == value.trim()) {
        "$label must not have leading or trailing whitespace"
    }
    require(value.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }) {
        "$label must not contain control characters"
    }
    return value
}
