package com.bighelp.droidhelp.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Interface for persisting and retrieving sessions.
 *
 * Kept deliberately small (suspend [save] / [findById]) so the coordinator can be unit-tested
 * with in-memory fakes. [findById] performs an exact-key lookup: it must return the record stored
 * under the given ID string, or `null` when no such record exists.
 */
interface SessionRepository {
    suspend fun save(session: Session)
    suspend fun findById(id: String): Session?
}

/**
 * Base type for all session-related failures.
 */
open class SessionException(message: String) : Exception(message)

/**
 * Thrown when no stored session exists for the requested ID.
 *
 * This is a *stored* concern (the record is simply absent). It is a distinct, typed error and
 * never triggers silent session creation.
 */
class SessionNotFoundException(val id: String) : SessionException("Session not found: $id")

/**
 * Thrown when the in-memory (runtime) session object fails validation.
 *
 * Runtime validation covers the live object: required fields present, profile non-blank, and
 * source == "bighelp". It is checked independently of the stored record.
 */
class SessionRuntimeException(val errors: List<String>) :
    SessionException("Runtime validation failed: ${errors.joinToString("; ")}")

/**
 * Thrown when the persisted (stored) record fails validation.
 *
 * Stored validation covers the record: schema/fields match and the stored ID equals the
 * requested ID exactly. It is checked independently of the live object.
 */
class SessionStoredException(val errors: List<String>) :
    SessionException("Stored validation failed: ${errors.joinToString("; ")}")

/**
 * Thrown when BOTH runtime and stored validation fail.
 *
 * Both failure sets are surfaced independently so neither check masks the other.
 */
class SessionValidationException(
    val runtimeErrors: List<String>,
    val storedErrors: List<String>
) : SessionException(
    "Session validation failed — runtime: [${runtimeErrors.joinToString("; ")}]; " +
        "stored: [${storedErrors.joinToString("; ")}]"
)

/**
 * Coordinates session creation and resumption.
 *
 * Thread-safe: every operation is serialized through a [Mutex], and the coordinator holds no
 * mutable state beyond the injected [repository].
 */
class SessionCoordinator(private val repository: SessionRepository) {

    private val mutex = Mutex()

    /**
     * Creates a new session.
     *
     * The session's [Session.source] is always forced to [SOURCE] ("bighelp") regardless of the
     * [source] argument, and the supplied [profile] is stored verbatim. The session is persisted
     * via the repository and returned with its generated ID.
     */
    suspend fun create(source: String, profile: String): Session = mutex.withLock {
        SecureInput.bind(profile)
        val session = Session(
            id = UUID.randomUUID().toString(),
            source = SOURCE,
            profile = profile
        )
        repository.save(session)
        session
    }

    /**
     * Resumes an existing session by its EXACT stored ID.
     *
     * The ID is used verbatim — no trimming, case-folding, or re-derivation. Any ID previously
     * returned by [create] resolves here. An unknown ID throws [SessionNotFoundException]; a new
     * session is never silently created.
     *
     * Runtime and stored validation run independently. If only one fails, its specific exception
     * is thrown; if both fail, [SessionValidationException] carries both error sets.
     */
    suspend fun resume(id: String): Session = mutex.withLock {
        val session = repository.findById(id)
            ?: throw SessionNotFoundException(id)
        
        SecureInput.bind(session.profile)

        val runtimeErrors = validateRuntime(session)
        val storedErrors = validateStored(session, requestedId = id)

        when {
            runtimeErrors.isNotEmpty() && storedErrors.isNotEmpty() ->
                throw SessionValidationException(runtimeErrors, storedErrors)
            runtimeErrors.isNotEmpty() ->
                throw SessionRuntimeException(runtimeErrors)
            storedErrors.isNotEmpty() ->
                throw SessionStoredException(storedErrors)
            else -> session
        }
    }

    /**
     * Runtime validation: checks the in-memory session object itself.
     */
    private fun validateRuntime(session: Session): List<String> {
        val errors = mutableListOf<String>()
        if (session.id.isBlank()) errors.add("Runtime validation: id must not be blank")
        if (session.runtimeSessionId != null && session.runtimeSessionId.isBlank()) {
            errors.add("Runtime validation: runtimeSessionId must not be blank")
        }
        if (session.source != SOURCE) errors.add("Runtime validation: source must be '$SOURCE'")
        if (session.profile.isBlank()) errors.add("Runtime validation: profile must not be blank")
        return errors
    }

    /**
     * Stored validation: checks the persisted record against the requested ID.
     */
    private fun validateStored(session: Session, requestedId: String): List<String> {
        val errors = mutableListOf<String>()
        if (session.durableSessionId != requestedId) {
            errors.add(
                "Stored validation: ID mismatch (expected '$requestedId', got '${session.durableSessionId}')"
            )
        }
        if (session.source.isBlank()) errors.add("Stored validation: source field missing")
        if (session.profile.isBlank()) errors.add("Stored validation: profile field missing")
        return errors
    }

    companion object {
        /** The only source a session created through this coordinator may carry. */
        const val SOURCE = "bighelp"
    }
}