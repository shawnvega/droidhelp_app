package com.bighelp.droidhelp.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Separated session identifiers distinguishing the persisted [durableSessionId] from the
 * volatile, runtime-only [runtimeSessionId].
 *
 * Both identifiers are validated and preserved verbatim via [ensureIdNotNormalized] without
 * trimming, case-folding, or Unicode normalization. [runtimeSessionId] is marked [Transient] so
 * serialization persists only [durableSessionId] and always decodes [runtimeSessionId] as `null`.
 */
@Serializable
data class SessionIds(
    val durableSessionId: String,
    @Transient
    val runtimeSessionId: String? = null,
) {
    init {
        ensureIdNotNormalized(durableSessionId)
        runtimeSessionId?.let { ensureIdNotNormalized(it) }
    }

    /** `true` when a volatile [runtimeSessionId] is currently attached. */
    val hasRuntimeSessionId: Boolean
        get() = runtimeSessionId != null

    /**
     * Returns a copy bound to [runtimeSessionId] without altering [durableSessionId].
     */
    fun attachRuntime(runtimeSessionId: String): SessionIds =
        copy(runtimeSessionId = ensureIdNotNormalized(runtimeSessionId))

    /**
     * Returns a copy with [runtimeSessionId] cleared (`null`) while preserving [durableSessionId].
     */
    fun detachRuntime(): SessionIds = copy(runtimeSessionId = null)
}

/**
 * Persisted/stored representation of a session.
 *
 * Deliberately carries only [durableSessionId] and excludes any volatile runtime session ID so
 * runtime-only identifiers cannot leak into durable storage.
 */
@Serializable
data class StoredSession(
    val durableSessionId: String,
    val source: String,
    val profile: String,
) {
    init {
        ensureIdNotNormalized(durableSessionId)
    }

    /**
     * Maps this stored record into a [Session].
     *
     * [Session.durableSessionId] (and [Session.id]) is populated verbatim from [durableSessionId].
     * [Session.runtimeSessionId] defaults to `null` (detached) and is never derived from
     * [durableSessionId].
     */
    fun toSession(runtimeSessionId: String? = null): Session = Session(
        id = durableSessionId,
        source = source,
        profile = profile,
        runtimeSessionId = runtimeSessionId?.let { ensureIdNotNormalized(it) },
    )

    /**
     * Attaches an active volatile [runtimeSessionId] to produce a [RuntimeSession] while
     * preserving [durableSessionId] verbatim.
     */
    fun toRuntimeSession(runtimeSessionId: String): RuntimeSession = RuntimeSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        source = source,
        profile = profile,
    )
}

/**
 * Volatile, runtime-only representation of an actively bound session.
 *
 * Keeps the live [runtimeSessionId] strictly separate from the persisted [durableSessionId].
 * Intentionally non-serializable so volatile runtime state cannot be persisted directly; map via
 * [toStoredSession] before writing to durable storage.
 */
data class RuntimeSession(
    val runtimeSessionId: String,
    val durableSessionId: String,
    val source: String,
    val profile: String,
) {
    init {
        ensureIdNotNormalized(runtimeSessionId)
        ensureIdNotNormalized(durableSessionId)
    }

    /**
     * Maps this live runtime session into a [Session] carrying both [durableSessionId] (as
     * [Session.id]) and the volatile [runtimeSessionId] without conflating them.
     */
    fun toSession(): Session = Session(
        id = durableSessionId,
        source = source,
        profile = profile,
        runtimeSessionId = runtimeSessionId,
    )

    /**
     * Strips the volatile [runtimeSessionId] and maps to a persistable [StoredSession].
     */
    fun toStoredSession(): StoredSession = StoredSession(
        durableSessionId = durableSessionId,
        source = source,
        profile = profile,
    )

    /**
     * Extracts the separated [SessionIds] pair.
     */
    fun toSessionIds(): SessionIds = SessionIds(
        durableSessionId = durableSessionId,
        runtimeSessionId = runtimeSessionId,
    )
}

/**
 * Represents a user session with explicit separation between its durable (persisted/stored)
 * identifier and its volatile, runtime-only identifier.
 *
 * - [durableSessionId] (backed by [id]) is the persisted/stored session identifier used for
 *   repository lookup and durable resumption across disconnects or runtime reclaims.
 * - [runtimeSessionId] is the volatile, runtime-only identifier bound to a live server runtime.
 *   It is marked [Transient] so serialization never persists it, and defaults to `null` when
 *   detached or loaded from storage.
 */
@Serializable
data class Session(
    val id: String,
    val source: String,
    val profile: String,
    @Transient
    val runtimeSessionId: String? = null,
) {
    /**
     * Durable (persisted/stored) session identifier.
     *
     * Mirrors [id] so existing callers reading or updating [id] (including via [copy]) stay
     * consistent with [durableSessionId] without ever conflating it with [runtimeSessionId].
     */
    val durableSessionId: String
        get() = id

    /** `true` when this session is currently bound to a volatile [runtimeSessionId]. */
    val hasRuntimeSessionId: Boolean
        get() = runtimeSessionId != null

    /**
     * Returns a copy bound to [runtimeSessionId] (or detached when `null`) without altering
     * [durableSessionId].
     */
    fun withRuntimeSessionId(runtimeSessionId: String?): Session =
        copy(runtimeSessionId = runtimeSessionId?.let { ensureIdNotNormalized(it) })

    /**
     * Returns a copy with [runtimeSessionId] cleared (`null`) while preserving
     * [durableSessionId], [source], and [profile].
     */
    fun withoutRuntimeSessionId(): Session = copy(runtimeSessionId = null)

    /**
     * Returns a copy with an updated [durableSessionId] (for example, a compaction successor)
     * while keeping [runtimeSessionId] separate and unchanged.
     */
    fun withDurableSessionId(durableSessionId: String): Session =
        copy(id = ensureIdNotNormalized(durableSessionId))

    /**
     * Maps this session into a persistable [StoredSession], preserving [durableSessionId] verbatim
     * and stripping the volatile [runtimeSessionId].
     */
    fun toStoredSession(): StoredSession = StoredSession(
        durableSessionId = durableSessionId,
        source = source,
        profile = profile,
    )

    /**
     * Maps this session into a live [RuntimeSession] when [runtimeSessionId] is attached, or
     * returns `null` when detached.
     */
    fun toRuntimeSessionOrNull(): RuntimeSession? {
        val activeRuntimeId = runtimeSessionId ?: return null
        return RuntimeSession(
            runtimeSessionId = activeRuntimeId,
            durableSessionId = durableSessionId,
            source = source,
            profile = profile,
        )
    }

    /**
     * Extracts the separated [SessionIds] pair from this session.
     */
    fun toSessionIds(): SessionIds = SessionIds(
        durableSessionId = durableSessionId,
        runtimeSessionId = runtimeSessionId,
    )

    companion object {
        /**
         * Constructs a [Session] with explicit [durableSessionId] and optional volatile
         * [runtimeSessionId] fields, validating both verbatim without normalization or conflation.
         */
        fun create(
            durableSessionId: String,
            source: String,
            profile: String,
            runtimeSessionId: String? = null,
        ): Session = Session(
            id = ensureIdNotNormalized(durableSessionId),
            source = source,
            profile = profile,
            runtimeSessionId = runtimeSessionId?.let { ensureIdNotNormalized(it) },
        )
    }
}
