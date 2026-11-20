package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class SignIn(
    val canonicalEndpoint: String,
    val verifiedPrincipal: String,
    val authenticationGeneration: Long
) {
    init {
        require(canonicalEndpoint.isNotEmpty()) { "canonicalEndpoint must not be empty" }
        require(verifiedPrincipal.isNotEmpty()) { "verifiedPrincipal must not be empty" }
        require(authenticationGeneration >= 0L) {
            "authenticationGeneration must be non-negative: $authenticationGeneration"
        }
    }
}

@Serializable
data class Owner(
    val signIn: SignIn,
    val workspaceConnectionGeneration: Long
) {
    init {
        require(workspaceConnectionGeneration >= 0L) {
            "workspaceConnectionGeneration must be non-negative: $workspaceConnectionGeneration"
        }
    }
}

@Serializable
data class SocketOwner(
    val owner: Owner,
    val socketEpoch: Long
) {
    init {
        require(socketEpoch >= 0L) {
            "socketEpoch must be non-negative: $socketEpoch"
        }
    }
}

class StaleOwnerException(message: String) : Exception(message)

/**
 * Thread-safe generation/epoch fence enforcing the `SignIn` -> `Owner` -> `SocketOwner`
 * ownership hierarchy.
 *
 * Invariants enforced under a private monitor lock:
 * - Transitions at any level invalidate lower ownership levels (`SignIn` clears `Owner` and
 *   `SocketOwner`; `Owner` clears `SocketOwner`).
 * - Transitions require matching parent ownership and strictly advancing generation/epoch values
 *   within a scope so stale async callbacks cannot resurrect or clobber newer owners.
 * - Retiring a `SocketOwner` or `SignIn` preserves high-water marks so retired generations/epochs
 *   cannot be reused (preventing ABA races).
 * - Validation always rejects `null` captures and absent active owners with [StaleOwnerException].
 */
class Owners {
    private val lock = Any()
    private var _currentSignIn: SignIn? = null
    private var _currentOwner: Owner? = null
    private var _currentSocketOwner: SocketOwner? = null
    private val highestAuthGenerationByScope = mutableMapOf<Pair<String, String>, Long>()
    private var lastWorkspaceConnectionGeneration: Long = NO_GENERATION
    private var lastSocketEpoch: Long = NO_GENERATION

    fun getSignIn(): SignIn? = synchronized(lock) { _currentSignIn }

    fun getOwner(): Owner? = synchronized(lock) { _currentOwner }

    fun getSocketOwner(): SocketOwner? = synchronized(lock) { _currentSocketOwner }

    /**
     * Transitions the active [SignIn].
     *
     * Re-asserting the exact active [SignIn] is an idempotent no-op. Transitioning to a new
     * authentication generation or a different `(canonicalEndpoint, verifiedPrincipal)` scope
     * invalidates any active [Owner] and [SocketOwner]. Regressive or retired authentication
     * generations for a scope are rejected with [StaleOwnerException].
     */
    fun updateSignIn(signIn: SignIn) {
        synchronized(lock) {
            val current = _currentSignIn
            if (current == signIn) {
                return
            }
            val scope = signIn.canonicalEndpoint to signIn.verifiedPrincipal
            val previousHighest = highestAuthGenerationByScope[scope]
            if (previousHighest != null && signIn.authenticationGeneration <= previousHighest) {
                throw StaleOwnerException(
                    "Stale SignIn generation for scope $scope: " +
                        "requested ${signIn.authenticationGeneration}, highest $previousHighest"
                )
            }
            _currentSignIn = signIn
            highestAuthGenerationByScope[scope] = signIn.authenticationGeneration
            _currentOwner = null
            _currentSocketOwner = null
            lastWorkspaceConnectionGeneration = NO_GENERATION
            lastSocketEpoch = NO_GENERATION
        }
    }

    /**
     * Transitions the active workspace [Owner] under the current [SignIn].
     *
     * Rejects updates whose parent [SignIn] does not match the active [SignIn] or whose
     * [Owner.workspaceConnectionGeneration] does not strictly advance past the previous
     * generation for this [SignIn]. Advancing to a new [Owner] invalidates any active
     * [SocketOwner].
     *
     * @throws StaleOwnerException if the parent [SignIn] mismatches or generation is stale.
     */
    fun updateOwner(owner: Owner) {
        synchronized(lock) {
            validateSignIn(owner.signIn)
            if (owner.workspaceConnectionGeneration <= lastWorkspaceConnectionGeneration) {
                throw StaleOwnerException(
                    "Owner generation must advance: requested ${owner.workspaceConnectionGeneration}, " +
                        "last $lastWorkspaceConnectionGeneration"
                )
            }
            _currentOwner = owner
            lastWorkspaceConnectionGeneration = owner.workspaceConnectionGeneration
            _currentSocketOwner = null
            lastSocketEpoch = NO_GENERATION
        }
    }

    /**
     * Transitions the active [SocketOwner] under the current [Owner].
     *
     * Rejects updates whose parent [Owner] (or [SignIn]) does not match the active state, or whose
     * [SocketOwner.socketEpoch] does not strictly advance past the highest epoch observed for the
     * active [Owner] (including retired epochs).
     *
     * @throws StaleOwnerException if the parent [Owner] mismatches or [SocketOwner.socketEpoch] is stale.
     */
    fun updateSocketOwner(socketOwner: SocketOwner) {
        synchronized(lock) {
            validateOwner(socketOwner.owner)
            if (socketOwner.socketEpoch <= lastSocketEpoch) {
                throw StaleOwnerException(
                    "SocketOwner epoch must advance: requested ${socketOwner.socketEpoch}, last $lastSocketEpoch"
                )
            }
            _currentSocketOwner = socketOwner
            lastSocketEpoch = socketOwner.socketEpoch
        }
    }

    /**
     * Atomically increments the socket epoch for the current [Owner], returning the new
     * [SocketOwner] or `null` if no [Owner] is currently active.
     *
     * Always advances past the highest epoch previously issued for this [Owner] (even if the
     * prior socket owner was retired), preventing epoch reuse.
     */
    fun incrementSocketEpoch(): SocketOwner? = synchronized(lock) {
        val owner = _currentOwner ?: return null
        val baseEpoch = if (lastSocketEpoch == NO_GENERATION) 0L else lastSocketEpoch
        check(baseEpoch < Long.MAX_VALUE) { "Socket epoch overflow for owner $owner" }
        val newEpoch = baseEpoch + 1L
        val next = SocketOwner(owner, newEpoch)
        _currentSocketOwner = next
        lastSocketEpoch = newEpoch
        next
    }

    /**
     * Atomically verifies that [expectedOwner] is still the active [Owner] and increments its
     * socket epoch, returning the newly issued [SocketOwner].
     *
     * @throws StaleOwnerException if [expectedOwner] no longer matches the active [Owner].
     */
    fun incrementSocketEpoch(expectedOwner: Owner): SocketOwner = synchronized(lock) {
        validateOwner(expectedOwner)
        checkNotNull(incrementSocketEpoch()) {
            "Expected active Owner after validation"
        }
    }

    /**
     * Retires the active [SocketOwner], clearing it while retaining the high-water epoch so
     * retired epochs cannot be resurrected or reused under the same [Owner].
     *
     * When [expectedSocketOwner] is non-null, retirement occurs only if it matches the currently
     * active [SocketOwner] (preventing a late callback from an older socket from retiring a newer
     * socket).
     *
     * @return `true` if an active [SocketOwner] was retired, `false` otherwise.
     */
    fun retireSocketOwner(expectedSocketOwner: SocketOwner? = null): Boolean = synchronized(lock) {
        val current = _currentSocketOwner ?: return false
        if (expectedSocketOwner != null && current != expectedSocketOwner) {
            return false
        }
        _currentSocketOwner = null
        true
    }

    /**
     * Retires the active [SignIn] along with any dependent [Owner] and [SocketOwner].
     *
     * Preserves recorded authentication-generation high-water marks so retired sign-ins cannot be
     * resurrected by stale callbacks. When [expectedSignIn] is non-null, retirement occurs only if
     * it matches the currently active [SignIn].
     *
     * @return `true` if an active [SignIn] was retired, `false` otherwise.
     */
    fun retireSignIn(expectedSignIn: SignIn? = null): Boolean = synchronized(lock) {
        val current = _currentSignIn ?: return false
        if (expectedSignIn != null && current != expectedSignIn) {
            return false
        }
        _currentSignIn = null
        _currentOwner = null
        _currentSocketOwner = null
        lastWorkspaceConnectionGeneration = NO_GENERATION
        lastSocketEpoch = NO_GENERATION
        true
    }

    /**
     * Validates that the captured [SignIn] is non-null and matches the active [SignIn].
     *
     * @throws StaleOwnerException if [captured] is null, no [SignIn] is active, or they differ.
     */
    fun validateSignIn(captured: SignIn?) {
        synchronized(lock) {
            val current = _currentSignIn
            if (captured == null || current == null || captured != current) {
                throw StaleOwnerException("SignIn mismatch: captured $captured, current $current")
            }
        }
    }

    /**
     * Validates that the captured [Owner] is non-null, matches the active [Owner], and belongs to
     * the active [SignIn].
     *
     * @param captured The owner captured before an async operation.
     * @throws StaleOwnerException if [captured] is null, no [Owner] is active, or there is a mismatch.
     */
    fun validateOwner(captured: Owner?) {
        synchronized(lock) {
            val current = _currentOwner
            val valid = captured != null && current == captured && current.signIn == _currentSignIn
            if (!valid) {
                throw StaleOwnerException("Owner mismatch: captured $captured, current $current")
            }
        }
    }

    /**
     * Validates that the captured [SocketOwner] is non-null, matches the active [SocketOwner], and
     * belongs to the active [Owner] and [SignIn].
     *
     * @param captured The socket owner captured before an async operation.
     * @throws StaleOwnerException if [captured] is null, no [SocketOwner] is active, or there is a mismatch.
     */
    fun validateSocketOwner(captured: SocketOwner?) {
        synchronized(lock) {
            val current = _currentSocketOwner
            val hierarchyValid = current != null &&
                current.owner == _currentOwner &&
                current.owner.signIn == _currentSignIn
            if (captured == null || !hierarchyValid || captured != current) {
                throw StaleOwnerException("SocketOwner mismatch: captured $captured, current $current")
            }
        }
    }

    /**
     * Executes [block] fenced by [captured] [SocketOwner], validating before execution and again
     * after both normal completion and non-cancellation failures.
     *
     * If ownership changes while [block] is running, any returned result or non-cancellation error
     * is superseded by [StaleOwnerException]. [CancellationException] always propagates unchanged.
     *
     * @throws StaleOwnerException if [captured] is null or stale before or after [block].
     */
    inline fun <T> withSocketOwner(captured: SocketOwner?, block: (SocketOwner) -> T): T {
        validateSocketOwner(captured)
        val active = requireNotNull(captured)
        var cancelled = false
        try {
            return block(active)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            if (!cancelled) {
                validateSocketOwner(active)
            }
        }
    }

    private companion object {
        private const val NO_GENERATION = -1L
    }
}
