package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import com.bighelp.droidhelp.session.SessionCoordinator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Classification of a reconnect transition relative to the prior authenticated [SignIn].
 *
 * Matches `WorkspaceReconnect` in bighelp: reconnecting to the same host, principal, and
 * authentication generation keeps open screens and retained sessions ([SAME_SIGN_IN]); switching
 * hosts, principals, or authentication generations is a hard [BOUNDARY] that retires prior state.
 */
enum class ReconnectClassification {
    SAME_SIGN_IN,
    BOUNDARY,
}

/**
 * High-level host connection state surfaced while reconnecting.
 *
 * Mirrors `WorkspaceConnectionState` in bighelp.
 */
enum class ReconnectConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
}

/**
 * Tracks an in-flight RPC or state-mutating request bound to a specific socket generation.
 *
 * Mirrors `DirectHermesClient.Pending` in bighelp: [sendStarted] is marked before the first
 * socket send suspension so any subsequent transport failure, timeout, cancellation, or socket
 * retirement treats a non-read-only operation as an uncertain mutation ([isUncertainMutation]).
 */
data class PendingOperation(
    val id: String,
    val method: String,
    val readOnly: Boolean = ReconnectPolicy.isReadOnlyMethod(method),
    val sendStarted: Boolean = false,
    val socketGeneration: Long = 0L,
) {
    /**
     * `true` when the operation may have reached the host and is not guaranteed read-only.
     * Uncertain mutations must never be silently retried or automatically replayed.
     */
    val isUncertainMutation: Boolean
        get() = sendStarted && !readOnly
}

/**
 * Settlement outcome for a pending operation interrupted by disconnect, timeout, or cancellation.
 *
 * When [outcomeUnknown] is `true`, the client cannot prove the host rejected the mutation and
 * must reconcile authoritative state before taking further action.
 */
data class SettledOperation(
    val id: String,
    val method: String,
    val outcomeUnknown: Boolean,
)

/**
 * Result of retiring a socket generation on disconnect, failure, or lifecycle transition.
 *
 * Mirrors `DirectHermesClient.closeConnection` in bighelp:
 * - Always advances the socket generation (and [Owners.socketEpoch] when supplied) immediately.
 * - Suppresses automatic recovery ([shouldAutomaticallyRecover] = `false`) whenever any in-flight
 *   operation had `sendStarted && !readOnly` ([hasUncertainMutation] = `true`).
 * - Preserves server-request handlers only across same-owner automatic recovery.
 * - Flags any retained session as requiring durable resumption ([requiresSessionResume]).
 */
data class SocketRetirement(
    val previousGeneration: Long,
    val nextGeneration: Long,
    val hasUncertainMutation: Boolean,
    val shouldAutomaticallyRecover: Boolean,
    val preservesHandlers: Boolean,
    val settledOperations: List<SettledOperation>,
    val previousSocketOwner: SocketOwner? = null,
    val nextSocketOwner: SocketOwner? = null,
    val requiresSessionResume: Boolean = false,
)

/**
 * Validated parameters for a durable `session.resume` RPC request.
 *
 * Mirrors `DirectHermesReleaseContract.resumeParameters` in bighelp: uses the durable stored
 * session ID and profile with `source = "bighelp"`, `close_on_disconnect = false`,
 * `defer_history = true`, `omit_messages = true`, and never includes prompt `"text"`.
 */
data class ResumeParameters(
    val sessionId: String,
    val profile: String,
    val source: String = SessionCoordinator.SOURCE,
    val closeOnDisconnect: Boolean = false,
    val deferHistory: Boolean = true,
    val omitMessages: Boolean = true,
) {
    init {
        validateReconnectCoordinate(sessionId, "sessionId", ReconnectPolicy.MAX_SESSION_ID_UTF8_BYTES)
        validateReconnectCoordinate(profile, "profile", ReconnectPolicy.MAX_PROFILE_UTF8_BYTES)
        require(source == SessionCoordinator.SOURCE) {
            "Resume source must be '${SessionCoordinator.SOURCE}', got '$source'"
        }
    }

    /** Wire JSON-RPC parameter map for `session.resume` (never carries prompt text). */
    val jsonParams: Map<String, JsonElement>
        get() = mapOf(
            "session_id" to JsonPrimitive(sessionId),
            "profile" to JsonPrimitive(profile),
            "source" to JsonPrimitive(source),
            "close_on_disconnect" to JsonPrimitive(closeOnDisconnect),
            "defer_history" to JsonPrimitive(deferHistory),
            "omit_messages" to JsonPrimitive(omitMessages),
        )
}

/**
 * Validated runtime and durable stored identifiers decoded from a `session.resume` response.
 *
 * Mirrors `DirectHermesReleaseContract.ResumedSession` in bighelp.
 */
data class ResumedSession(
    val runtimeId: String,
    val storedId: String,
)

/**
 * Immutable point-in-time snapshot of a [ReconnectState] instance.
 */
data class ReconnectSnapshot(
    val socketGeneration: Long,
    val boundSignIn: SignIn?,
    val session: Session?,
    val runtimeSessionId: String?,
    val requiresResume: Boolean,
    val pendingOperationCount: Int,
    val uncertainMutations: Map<String, SettledOperation> = emptyMap(),
) {
    /**
     * `true` only when a session is retained, bound to an active runtime, and does not require
     * durable resumption after a socket retirement or reclaim.
     */
    val isReadyForSubmission: Boolean
        get() = session != null && !requiresResume && runtimeSessionId != null
}

/**
 * Policy for handling reconnection backoff, socket generation retirement, uncertain state
 * mutations, and durable session resumption with logic parity to bighelp.
 */
object ReconnectPolicy {
    private const val DELAY_STEP_1_SECONDS = 1
    private const val DELAY_STEP_2_SECONDS = 2
    private const val DELAY_STEP_3_SECONDS = 4
    private const val DELAY_STEP_4_SECONDS = 8
    private const val DELAY_STEP_5_SECONDS = 15
    private const val DELAY_STEP_6_SECONDS = 30
    private const val AUTO_RECOVERY_STEP_1_SECONDS = 0
    private const val AUTO_RECOVERY_STEP_2_SECONDS = 1
    private const val AUTO_RECOVERY_STEP_3_SECONDS = 3

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_METHOD_NOT_ALLOWED = 405
    private const val HTTP_CONFLICT = 409
    private const val HTTP_PAYLOAD_TOO_LARGE = 413
    private const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415
    private const val HTTP_UNPROCESSABLE_ENTITY = 422
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /** Maximum UTF-8 byte length for runtime and stored session identifiers. */
    const val MAX_SESSION_ID_UTF8_BYTES: Int = 512

    /** Maximum UTF-8 byte length for profile identifiers. */
    const val MAX_PROFILE_UTF8_BYTES: Int = 128

    /** Number of initial reconnect attempts kept in the quiet window before offering manual retry. */
    const val QUIET_ATTEMPTS: Int = 3

    /**
     * Workspace exponential backoff schedule matching `WorkspaceReconnectPolicy.delays` in bighelp:
     * `[1s, 2s, 4s, 8s, 15s, 30s]` (holding at `30s` thereafter).
     */
    val DELAYS: List<Duration> = listOf(
        DELAY_STEP_1_SECONDS.seconds,
        DELAY_STEP_2_SECONDS.seconds,
        DELAY_STEP_3_SECONDS.seconds,
        DELAY_STEP_4_SECONDS.seconds,
        DELAY_STEP_5_SECONDS.seconds,
        DELAY_STEP_6_SECONDS.seconds,
    )

    /**
     * Bounded transport-level automatic recovery schedule matching
     * `DirectHermesClient.startAutomaticRecovery` in bighelp: `[0s, 1s, 3s]`.
     */
    val AUTOMATIC_RECOVERY_DELAYS: List<Duration> = listOf(
        AUTO_RECOVERY_STEP_1_SECONDS.seconds,
        AUTO_RECOVERY_STEP_2_SECONDS.seconds,
        AUTO_RECOVERY_STEP_3_SECONDS.seconds,
    )

    /**
     * Conservative read-only RPC allowlist matching `DirectHermesClient.readOnlyMethods` in bighelp.
     * Any method absent from this set is conservatively assumed to mutate server state.
     */
    val READ_ONLY_METHODS: Set<String> = setOf(
        "gateway.ping",
        "session.list",
        "session.active_list",
        "session.history",
        "session.status",
        "session.usage",
        "session.events.since",
        "commands.catalog",
        "config.get",
        "delegation.status",
        "spawn_tree.list",
        "profiles.list",
        "profiles.describe",
        "profiles.get_asset",
        "model.options",
        "projects.list",
        "projects.get",
        "subagent.list",
        "subagent.tail",
        "process.list",
        "client.capabilities",
        "groups.capabilities",
        "groups.list",
        "groups.state",
        "groups.log",
        "pet.gallery",
        "pet.thumb",
    )

    /**
     * HTTP status codes that definitively prove a non-GET mutation was rejected by the host.
     * Matches `DirectHermesClient` HTTP and multipart mutation boundaries in bighelp.
     */
    val DEFINITIVE_HTTP_REJECTION_CODES: Set<Int> = setOf(
        HTTP_BAD_REQUEST,
        HTTP_UNAUTHORIZED,
        HTTP_FORBIDDEN,
        HTTP_NOT_FOUND,
        HTTP_METHOD_NOT_ALLOWED,
        HTTP_CONFLICT,
        HTTP_PAYLOAD_TOO_LARGE,
        HTTP_UNSUPPORTED_MEDIA_TYPE,
        HTTP_UNPROCESSABLE_ENTITY,
        HTTP_TOO_MANY_REQUESTS,
    )

    private val STORED_SESSION_ALIAS_KEYS: List<String> = listOf(
        "session_key",
        "stored_session_id",
        "resumed",
    )

    /** Total duration covered by the [QUIET_ATTEMPTS] quiet window (`1s + 2s + 4s = 7s`). */
    val quietWindowDuration: Duration
        get() = (0 until QUIET_ATTEMPTS).fold(Duration.ZERO) { acc, attempt -> acc + delay(attempt) }

    /** Shared default state backing top-level session/retirement helpers. */
    val defaultState: ReconnectState = ReconnectState()

    /** Creates an isolated, thread-safe [ReconnectState] for a single connection or transport. */
    fun createState(): ReconnectState = ReconnectState()

    /** Sets the current session to be resumed upon reconnection in [defaultState]. */
    fun setSession(session: Session?) {
        defaultState.setSession(session)
    }

    /** Gets the current session retained in [defaultState]. */
    fun getSession(): Session? = defaultState.session

    /**
     * Returns the workspace exponential backoff delay for [afterAttempt], clamping negative
     * values to `1s` and capping attempts beyond the schedule at `30s`.
     */
    fun delay(afterAttempt: Int): Duration {
        val index = afterAttempt.coerceIn(0, DELAYS.lastIndex)
        return DELAYS[index]
    }

    /**
     * Returns `true` if [attempt] is within the quiet reconnection window (`attempt < 3`).
     *
     * Note: Leaving the quiet window transitions UI presentation from reconnecting to
     * disconnected (offering manual retry), while workspace backoff continues retrying.
     */
    fun isQuiet(attempt: Int): Boolean = attempt < QUIET_ATTEMPTS

    /**
     * Computes the connection state for the given reconnect [attempt], matching
     * `WorkspaceConnectionKeeper.evaluate` in bighelp.
     */
    fun connectionStateForAttempt(
        attempt: Int,
        isConnecting: Boolean = false,
    ): ReconnectConnectionState {
        return if (isConnecting || isQuiet(attempt)) {
            ReconnectConnectionState.RECONNECTING
        } else {
            ReconnectConnectionState.DISCONNECTED
        }
    }

    /**
     * Returns the transport-level bounded automatic recovery delay for [attempt] (`0s`, `1s`, `3s`),
     * or `null` once the bounded attempts are exhausted (or for negative attempts).
     */
    fun automaticRecoveryDelay(attempt: Int): Duration? = AUTOMATIC_RECOVERY_DELAYS.getOrNull(attempt)

    /**
     * Classifies whether reconnecting under [current] belongs to the same sign-in as [previous]
     * or crosses an account/host boundary.
     */
    fun classifyReconnect(current: SignIn, previous: SignIn?): ReconnectClassification {
        return if (previous != null && current == previous) {
            ReconnectClassification.SAME_SIGN_IN
        } else {
            ReconnectClassification.BOUNDARY
        }
    }

    /**
     * Returns `true` only when [method] is in the conservative [READ_ONLY_METHODS] allowlist.
     * Unknown methods return `false` and are treated as state-mutating.
     */
    fun isReadOnlyMethod(method: String): Boolean = method in READ_ONLY_METHODS

    /**
     * Determines whether an interrupted non-GET HTTP mutation has an unknown server outcome.
     *
     * Once bytes may have left the client, only a status code in [DEFINITIVE_HTTP_REJECTION_CODES]
     * proves the host rejected the mutation.
     */
    fun isOutcomeUnknownForHttpMutation(
        bytesMayHaveLeft: Boolean,
        receivedStatusCode: Int?,
    ): Boolean {
        return bytesMayHaveLeft &&
            (receivedStatusCode == null || receivedStatusCode !in DEFINITIVE_HTTP_REJECTION_CODES)
    }

    /**
     * Builds validated `session.resume` parameters for [session] without submitting any prompt text.
     */
    fun buildResumeParameters(session: Session): ResumeParameters {
        return ResumeParameters(
            sessionId = session.id,
            profile = session.profile,
            source = session.source,
        )
    }

    /**
     * Validates and decodes a `session.resume` response payload, matching
     * `DirectHermesReleaseContract.decodeResumedSession` in bighelp.
     *
     * Rejects missing/malformed runtime or stored IDs, foreign `info.profile_name`, conflicting
     * stored-session aliases (`session_key`, `stored_session_id`, `resumed`), and non-boolean
     * `running` values.
     */
    fun decodeResumedSession(response: JsonObject, expectedProfile: String): ResumedSession {
        validateReconnectCoordinate(expectedProfile, "expectedProfile", MAX_PROFILE_UTF8_BYTES)
        val runtimePrimitive = response["session_id"] as? JsonPrimitive
        require(runtimePrimitive != null && runtimePrimitive.isString) {
            "Missing or non-string session_id in resume response"
        }
        val runtimeId = validateReconnectCoordinate(
            runtimePrimitive.content,
            "session_id",
            MAX_SESSION_ID_UTF8_BYTES,
        )

        val info = response["info"] as? JsonObject
        require(info != null) { "Missing or non-object info in resume response" }
        val returnedProfileElement = info["profile_name"]
        if (returnedProfileElement != null) {
            val returnedProfile = (returnedProfileElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(returnedProfile != null && returnedProfile == expectedProfile) {
                "Foreign or invalid profile_name in resume response"
            }
        }

        val storedValues = STORED_SESSION_ALIAS_KEYS.mapNotNull { key ->
            val element = response[key] ?: return@mapNotNull null
            val primitive = element as? JsonPrimitive
            require(primitive != null && primitive.isString) {
                "Non-string stored session alias '$key' in resume response"
            }
            validateReconnectCoordinate(primitive.content, key, MAX_SESSION_ID_UTF8_BYTES)
        }
        val storedId = storedValues.firstOrNull()
        require(storedId != null && storedValues.all { it == storedId }) {
            "Missing or conflicting stored session aliases in resume response"
        }

        val runningElement = response["running"]
        if (runningElement != null) {
            val runningPrimitive = runningElement as? JsonPrimitive
            require(
                runningPrimitive != null &&
                    !runningPrimitive.isString &&
                    runningPrimitive.booleanOrNull != null
            ) {
                "Invalid non-boolean running flag in resume response"
            }
        }

        return ResumedSession(runtimeId = runtimeId, storedId = storedId)
    }

    /**
     * Retires the active socket generation in [defaultState] (and optionally bumps [owners]),
     * settling pending operations and gating automatic recovery on uncertain mutations.
     */
    fun retireSocketGeneration(
        owners: Owners? = null,
        notify: Boolean = true,
        hadConnection: Boolean = true,
        terminallyClosed: Boolean = false,
        automaticRecoveryActive: Boolean = false,
    ): SocketRetirement {
        return defaultState.retireSocketGeneration(
            owners = owners,
            notify = notify,
            hadConnection = hadConnection,
            terminallyClosed = terminallyClosed,
            automaticRecoveryActive = automaticRecoveryActive,
        )
    }

    /** Resets [defaultState] back to its clean initial state. */
    fun reset() {
        defaultState.reset()
    }
}
