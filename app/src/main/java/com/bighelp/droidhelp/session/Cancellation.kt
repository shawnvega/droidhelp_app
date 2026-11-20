package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.transport.Endpoint
import com.bighelp.droidhelp.transport.Owners
import com.bighelp.droidhelp.transport.ReconnectPolicy
import com.bighelp.droidhelp.transport.SocketOwner
import com.bighelp.droidhelp.transport.WebSocketTransport
import com.bighelp.droidhelp.transport.enforceTls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_REQUEST_ID_UTF8_BYTES = 1_024
private const val MAX_METHOD_UTF8_BYTES = 256
private const val MAX_REASON_UTF8_BYTES = 4_096
private const val MAX_WS_CLOSE_REASON_UTF8_BYTES = 123
private const val MAX_OPEN_REQUESTS = 64
private const val ASCII_PRINTABLE_MIN = 33
private const val ASCII_PRINTABLE_MAX = 126
private const val ASCII_HEADER_MIN = 32
private const val ASCII_HEADER_MAX = 126
private const val HEX_MASK = 0xFF
private const val HEX_RADIX = 16
private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

/**
 * Supported HTTP methods for session cancellation requests.
 *
 * Defaults to [DELETE] (`DELETE /api/sessions/{sessionId}`) per the Hermes REST contract, while
 * permitting [POST] for deployments that expose a POST cancellation endpoint.
 */
enum class CancellationHttpMethod(val verb: String) {
    DELETE("DELETE"),
    POST("POST"),
}

/**
 * Outcome of registering an open server request for exact cancellation tracking.
 */
enum class RequestRegistrationResult {
    ACCEPTED,
    DUPLICATE,
    AT_CAPACITY,
}

/**
 * Composite identity fencing an open request to its exact socket generation, opaque wire request
 * ID, and method name without Unicode normalization or case folding.
 */
data class PendingRequestKey(
    val socketGeneration: Long,
    val requestId: String,
    val method: String,
)

/**
 * Exact server-request cancellation payload (`request.cancel`), matching bighelp's wire contract.
 *
 * Wire IDs are preserved verbatim up to 1,024 UTF-8 bytes; methods must be non-empty printable
 * ASCII up to 256 bytes; reasons are bounded to 4,096 UTF-8 bytes.
 */
@Serializable
data class ServerRequestCancellation(
    val id: String,
    val method: String,
    val reason: String,
) {
    init {
        checkUtf8ByteLimit(id, MAX_REQUEST_ID_UTF8_BYTES)
        require(method.isNotEmpty()) { "Cancellation method must not be empty" }
        checkUtf8ByteLimit(method, MAX_METHOD_UTF8_BYTES)
        require(method.toByteArray(Charsets.UTF_8).all { it.toInt() in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX }) {
            "Cancellation method must be printable ASCII without whitespace: '$method'"
        }
        checkUtf8ByteLimit(reason, MAX_REASON_UTF8_BYTES)
    }
}

/**
 * JSON wire body sent alongside HTTP session cancellation requests.
 */
@Serializable
data class CancellationPayload(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("socket_handle")
    val socketHandle: String,
    @SerialName("socket_generation")
    val socketGeneration: Long,
    @SerialName("request_id")
    val requestId: String,
    val reason: String? = null,
)

/**
 * Outcome returned after cancelling a session and closing its socket handle gracefully.
 */
data class CancellationResult(
    val sessionId: String,
    val socketHandleId: String,
    val socketGeneration: Long,
    val requestId: String,
    val httpMethod: CancellationHttpMethod,
    val httpStatusCode: Int,
    val sessionClosed: Boolean,
    val retiredRequestCount: Int,
)

/**
 * Typed failure thrown when an HTTP session cancellation request does not succeed.
 *
 * [outcomeUnknown] follows [ReconnectPolicy.isOutcomeUnknownForHttpMutation]: definitive 4xx
 * rejections prove non-execution (`false`), while network failures or 5xx responses leave server
 * state uncertain (`true`).
 */
class SessionCancellationException(
    message: String,
    val statusCode: Int? = null,
    val outcomeUnknown: Boolean = true,
    cause: Throwable? = null,
) : SessionException(message) {
    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}

/**
 * Generated, generation-fenced handle representing an active session socket.
 *
 * Encapsulates a unique [handleId], the exact [sessionId], the monotonic [socketGeneration] (and
 * optional [socketOwner]), and an idempotent graceful closure hook using RFC 6455 normal closure
 * (`1000`).
 */
class SocketHandle private constructor(
    val handleId: String,
    val sessionId: String,
    val socketGeneration: Long,
    val socketOwner: SocketOwner?,
    private val closer: ((code: Int, reason: String) -> Unit)?,
) {
    private val closedFlag = AtomicBoolean(false)

    /** `true` once [closeGracefully] has been invoked on this handle. */
    val isClosed: Boolean
        get() = closedFlag.get()

    /**
     * Gracefully closes the underlying socket handle exactly once using [code] (defaults to
     * [WebSocketTransport.NORMAL_CLOSURE_CODE], `1000`) and [reason].
     *
     * Truncates [reason] safely on a UTF-8 character boundary to the RFC 6455 123-byte close-frame
     * limit before invoking the underlying socket closer.
     *
     * @return `true` if this invocation performed the close, or `false` if already closed.
     */
    fun closeGracefully(
        code: Int = WebSocketTransport.NORMAL_CLOSURE_CODE,
        reason: String = SessionCancellation.DEFAULT_CLOSE_REASON,
    ): Boolean {
        checkUtf8ByteLimit(reason, MAX_REASON_UTF8_BYTES)
        if (!closedFlag.compareAndSet(false, true)) {
            return false
        }
        val frameReason = truncateUtf8(reason, MAX_WS_CLOSE_REASON_UTF8_BYTES)
        closer?.invoke(code, frameReason)
        return true
    }

    companion object {
        /**
         * Generates a new [SocketHandle] bound to [sessionId] and [socketGeneration].
         */
        fun generate(
            sessionId: String,
            socketGeneration: Long = 0L,
            socketOwner: SocketOwner? = null,
            idGenerator: () -> String = { UUID.randomUUID().toString() },
            closer: ((code: Int, reason: String) -> Unit)? = null,
        ): SocketHandle {
            ensureIdNotNormalized(sessionId)
            checkUtf8ByteLimit(sessionId, ReconnectPolicy.MAX_SESSION_ID_UTF8_BYTES)
            require(sessionId != "." && sessionId != "..") {
                "Session ID must not be a path traversal segment"
            }
            require(sessionId.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }) {
                "Session ID must not contain control characters"
            }
            require(socketGeneration >= 0L) {
                "socketGeneration must be non-negative: $socketGeneration"
            }
            if (socketOwner != null) {
                require(socketOwner.socketEpoch == socketGeneration) {
                    "socketGeneration ($socketGeneration) must match socketOwner.socketEpoch " +
                        "(${socketOwner.socketEpoch})"
                }
            }
            val generatedId = ensureIdNotNormalized(idGenerator())
            checkUtf8ByteLimit(generatedId, MAX_REQUEST_ID_UTF8_BYTES)
            return SocketHandle(
                handleId = generatedId,
                sessionId = sessionId,
                socketGeneration = socketGeneration,
                socketOwner = socketOwner,
                closer = closer,
            )
        }

        /**
         * Generates a [SocketHandle] wrapping an active OkHttp [WebSocket], closing it gracefully
         * via [WebSocket.close] rather than abrupt cancellation.
         */
        fun forWebSocket(
            sessionId: String,
            webSocket: WebSocket,
            socketGeneration: Long = 0L,
            socketOwner: SocketOwner? = null,
            idGenerator: () -> String = { UUID.randomUUID().toString() },
        ): SocketHandle = generate(
            sessionId = sessionId,
            socketGeneration = socketGeneration,
            socketOwner = socketOwner,
            idGenerator = idGenerator,
            closer = { code, reason -> webSocket.close(code, reason) },
        )

        /**
         * Generates a [SocketHandle] bound to a [WebSocketTransport] and its active [SocketOwner].
         *
         * Closing the handle only disconnects [transport] when its active [SocketOwner] still
         * matches the owner captured when this handle was generated, preventing stale handles from
         * disconnecting a newer socket generation.
         */
        fun forTransport(
            sessionId: String,
            transport: WebSocketTransport,
            idGenerator: () -> String = { UUID.randomUUID().toString() },
        ): SocketHandle {
            val capturedOwner = transport.getOwners().getSocketOwner()
            val generation = capturedOwner?.socketEpoch ?: 0L
            return generate(
                sessionId = sessionId,
                socketGeneration = generation,
                socketOwner = capturedOwner,
                idGenerator = idGenerator,
                closer = { _, _ ->
                    if (!transport.isClosed()) {
                        val ownerMatches = capturedOwner == null ||
                            transport.getOwners().getSocketOwner() == capturedOwner
                        if (ownerMatches) {
                            transport.disconnect()
                        }
                    }
                },
            )
        }
    }
}

/**
 * Coordinates graceful session cancellation, HTTP cancellation dispatch, and exact
 * `(socketGeneration, requestId, method)` pending-request retirement.
 *
 * Enforces:
 * - **TLS policy**: validates [endpoint] via [enforceTls] before dispatching HTTP requests.
 * - **Ownership fencing**: when [owners] is provided, validates [SocketHandle.socketOwner] before
 *   and after HTTP cancellation via [Owners.withSocketOwner] so stale generations cannot mutate or
 *   close a newer socket.
 * - **Exact opaque matching**: retires pending requests and secure inputs only on exact socket
 *   generation, verbatim unnormalized request ID bytes, and method name.
 * - **Graceful closure**: sends the configured HTTP cancellation request (default `DELETE`) with
 *   the generated socket handle and request ID, retires tracked requests, and closes the socket
 *   handle cleanly with normal closure code `1000`.
 */
class SessionCancellation(
    private val httpClient: OkHttpClient,
    private val endpoint: Endpoint,
    private val owners: Owners? = null,
    private val headers: Map<String, String> = emptyMap(),
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val handleIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRetireSecureInput: (String) -> Unit = { SecureInput.clear() },
) {
    private enum class PendingPhase {
        HANDLING,
        RESPONSE_QUEUED,
    }

    private data class PendingEntry(
        val handleId: String,
        val socketGeneration: Long,
        val method: String,
        val phase: PendingPhase,
        val secureInputId: String?,
    )

    private val pendingLock = Any()
    private val pendingRequests = LinkedHashMap<Pair<Long, String>, PendingEntry>()
    private val json = Json { encodeDefaults = false }
    private val jsonMediaType = JSON_MEDIA_TYPE.toMediaType()

    /** Number of currently tracked open requests across all active socket handles. */
    val pendingRequestCount: Int
        get() = synchronized(pendingLock) { pendingRequests.size }

    /** Immutable snapshot of currently active pending request keys. */
    val activePendingRequests: Set<PendingRequestKey>
        get() = synchronized(pendingLock) {
            pendingRequests.map { (key, entry) ->
                PendingRequestKey(
                    socketGeneration = key.first,
                    requestId = key.second,
                    method = entry.method,
                )
            }.toSet()
        }

    /**
     * Generates a new [SocketHandle] for [session], targeting its live [Session.runtimeSessionId]
     * when attached or falling back to [Session.durableSessionId].
     */
    fun createSocketHandle(
        session: Session,
        transport: WebSocketTransport? = null,
        closer: ((code: Int, reason: String) -> Unit)? = null,
    ): SocketHandle {
        val targetSessionId = session.runtimeSessionId ?: session.durableSessionId
        if (transport != null && closer == null) {
            return SocketHandle.forTransport(
                sessionId = targetSessionId,
                transport = transport,
                idGenerator = handleIdGenerator,
            )
        }
        val activeOwner = owners?.getSocketOwner() ?: transport?.getOwners()?.getSocketOwner()
        return SocketHandle.generate(
            sessionId = targetSessionId,
            socketGeneration = activeOwner?.socketEpoch ?: 0L,
            socketOwner = activeOwner,
            idGenerator = handleIdGenerator,
            closer = closer,
        )
    }

    /**
     * Registers an open request bound to [socketHandle], exact [requestId], and [method].
     *
     * Enforces capacity bound ([MAX_OPEN_REQUESTS] = 64) and deduplicates exact
     * `(socketGeneration, requestId)` keys without Unicode normalization.
     */
    fun registerPendingRequest(
        socketHandle: SocketHandle,
        requestId: String,
        method: String,
        secureInputId: String? = null,
    ): RequestRegistrationResult {
        checkUtf8ByteLimit(requestId, MAX_REQUEST_ID_UTF8_BYTES)
        require(method.isNotEmpty()) { "Request method must not be empty" }
        checkUtf8ByteLimit(method, MAX_METHOD_UTF8_BYTES)
        require(method.toByteArray(Charsets.UTF_8).all { it.toInt() in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX }) {
            "Request method must be printable ASCII without whitespace: '$method'"
        }
        return synchronized(pendingLock) {
            val key = socketHandle.socketGeneration to requestId
            when {
                key in pendingRequests -> RequestRegistrationResult.DUPLICATE
                pendingRequests.size >= MAX_OPEN_REQUESTS -> RequestRegistrationResult.AT_CAPACITY
                else -> {
                    pendingRequests[key] = PendingEntry(
                        handleId = socketHandle.handleId,
                        socketGeneration = socketHandle.socketGeneration,
                        method = method,
                        phase = PendingPhase.HANDLING,
                        secureInputId = secureInputId,
                    )
                    RequestRegistrationResult.ACCEPTED
                }
            }
        }
    }

    /**
     * Stages a prepared response for an open request. The staged response remains cancellable
     * until [beginSendingResponse] commits it to the wire.
     */
    fun stageResponse(
        socketHandle: SocketHandle,
        requestId: String,
        method: String,
    ): Boolean = synchronized(pendingLock) {
        val key = socketHandle.socketGeneration to requestId
        val entry = pendingRequests[key] ?: return false
        if (entry.handleId != socketHandle.handleId ||
            entry.method != method ||
            entry.phase != PendingPhase.HANDLING
        ) {
            return false
        }
        pendingRequests[key] = entry.copy(phase = PendingPhase.RESPONSE_QUEUED)
        true
    }

    /**
     * Atomically transitions a staged response into sending state and removes it from tracking.
     *
     * Returns `false` if the request was cancelled, retired, or does not match the exact
     * `(socketHandle, requestId, method)` tuple, preventing late or stale writes.
     */
    fun beginSendingResponse(
        socketHandle: SocketHandle,
        requestId: String,
        method: String,
    ): Boolean = synchronized(pendingLock) {
        val key = socketHandle.socketGeneration to requestId
        val entry = pendingRequests[key] ?: return false
        if (entry.handleId != socketHandle.handleId ||
            entry.method != method ||
            entry.phase != PendingPhase.RESPONSE_QUEUED
        ) {
            return false
        }
        pendingRequests.remove(key)
        true
    }

    /**
     * Retires a single pending request matching [socketHandle]'s exact socket generation,
     * [ServerRequestCancellation.id], and [ServerRequestCancellation.method].
     *
     * Also retires any secure input bound to that request. A mismatched generation, method, or
     * opaque request ID leaves existing entries intact and returns `false`.
     */
    fun cancelPendingRequest(
        socketHandle: SocketHandle,
        cancellation: ServerRequestCancellation,
    ): Boolean {
        val retiredSecureId: String?
        val removed = synchronized(pendingLock) {
            val key = socketHandle.socketGeneration to cancellation.id
            val entry = pendingRequests[key]
            if (entry == null ||
                entry.handleId != socketHandle.handleId ||
                entry.method != cancellation.method
            ) {
                retiredSecureId = null
                false
            } else {
                pendingRequests.remove(key)
                retiredSecureId = entry.secureInputId
                true
            }
        }
        if (retiredSecureId != null) {
            onRetireSecureInput(retiredSecureId)
        }
        return removed
    }

    /**
     * Cancels [sessionId] over HTTP using [httpMethod] (defaults to [CancellationHttpMethod.DELETE]),
     * carrying the generated [socketHandle] and [requestId], and closes the session gracefully upon
     * confirmation.
     *
     * @throws IllegalArgumentException if [sessionId], [requestId], [reason], [socketHandle], or
     *   TLS policy validation fails.
     * @throws com.bighelp.droidhelp.transport.StaleOwnerException if [owners] is configured and
     *   ownership changes before or during cancellation.
     * @throws SessionCancellationException if the HTTP cancellation request fails or returns a
     *   non-2xx status code.
     */
    suspend fun cancelSession(
        sessionId: String,
        socketHandle: SocketHandle,
        requestId: String = requestIdGenerator(),
        httpMethod: CancellationHttpMethod = CancellationHttpMethod.DELETE,
        profile: String? = null,
        reason: String? = null,
        transport: WebSocketTransport? = null,
    ): CancellationResult {
        ensureIdNotNormalized(sessionId)
        checkUtf8ByteLimit(sessionId, ReconnectPolicy.MAX_SESSION_ID_UTF8_BYTES)
        require(socketHandle.sessionId == sessionId) {
            "Socket handle sessionId '${socketHandle.sessionId}' does not match requested '$sessionId'"
        }
        ensureIdNotNormalized(requestId)
        checkUtf8ByteLimit(requestId, MAX_REQUEST_ID_UTF8_BYTES)
        reason?.let { checkUtf8ByteLimit(it, MAX_REASON_UTF8_BYTES) }
        profile?.let {
            ensureIdNotNormalized(it)
            checkUtf8ByteLimit(it, ReconnectPolicy.MAX_PROFILE_UTF8_BYTES)
        }
        enforceTls(endpoint)

        val payload = CancellationPayload(
            sessionId = sessionId,
            socketHandle = socketHandle.handleId,
            socketGeneration = socketHandle.socketGeneration,
            requestId = requestId,
            reason = reason,
        )
        val httpRequest = buildHttpRequest(
            payload = payload,
            httpMethod = httpMethod,
            profile = profile,
        )

        transport?.cancel(requestId)

        val statusCode = if (owners != null) {
            owners.withSocketOwner(socketHandle.socketOwner) {
                executeHttpCall(httpRequest)
            }
        } else {
            executeHttpCall(httpRequest)
        }

        val closeReason = reason ?: DEFAULT_CLOSE_REASON
        val retiredCount = closeSessionGracefully(
            socketHandle = socketHandle,
            reason = closeReason,
            transport = transport,
        )

        return CancellationResult(
            sessionId = sessionId,
            socketHandleId = socketHandle.handleId,
            socketGeneration = socketHandle.socketGeneration,
            requestId = requestId,
            httpMethod = httpMethod,
            httpStatusCode = statusCode,
            sessionClosed = socketHandle.isClosed,
            retiredRequestCount = retiredCount,
        )
    }

    /**
     * Convenience overload cancelling a [Session] using its live [Session.runtimeSessionId] (or
     * [Session.durableSessionId] when detached) and [Session.profile].
     */
    suspend fun cancelSession(
        session: Session,
        socketHandle: SocketHandle,
        requestId: String = requestIdGenerator(),
        httpMethod: CancellationHttpMethod = CancellationHttpMethod.DELETE,
        reason: String? = null,
        transport: WebSocketTransport? = null,
    ): CancellationResult = cancelSession(
        sessionId = session.runtimeSessionId ?: session.durableSessionId,
        socketHandle = socketHandle,
        requestId = requestId,
        httpMethod = httpMethod,
        profile = session.profile,
        reason = reason,
        transport = transport,
    )

    /**
     * Gracefully closes [socketHandle], retires all pending requests bound to that handle, clears
     * any associated secure inputs, and disconnects [transport] if still owned by [socketHandle].
     *
     * @return The number of pending requests retired for [socketHandle].
     */
    fun closeSessionGracefully(
        socketHandle: SocketHandle,
        reason: String = DEFAULT_CLOSE_REASON,
        transport: WebSocketTransport? = null,
    ): Int {
        val retiredSecureIds = mutableListOf<String>()
        val retiredCount = synchronized(pendingLock) {
            val keysToRemove = pendingRequests.entries
                .filter { (_, entry) ->
                    entry.handleId == socketHandle.handleId &&
                        entry.socketGeneration == socketHandle.socketGeneration
                }
                .map { it.key }
            keysToRemove.forEach { key ->
                pendingRequests.remove(key)?.secureInputId?.let { retiredSecureIds.add(it) }
            }
            keysToRemove.size
        }
        retiredSecureIds.forEach { onRetireSecureInput(it) }

        socketHandle.closeGracefully(
            code = WebSocketTransport.NORMAL_CLOSURE_CODE,
            reason = reason,
        )

        if (transport != null && !transport.isClosed()) {
            val activeOwner = owners?.getSocketOwner() ?: transport.getOwners().getSocketOwner()
            val ownerMatches = socketHandle.socketOwner == null ||
                activeOwner == socketHandle.socketOwner
            if (ownerMatches) {
                transport.disconnect()
            }
        }
        return retiredCount
    }

    private fun buildHttpRequest(
        payload: CancellationPayload,
        httpMethod: CancellationHttpMethod,
        profile: String?,
    ): Request {
        val httpScheme = if (endpoint.isSecure) "https" else "http"
        val urlBuilder = HttpUrl.Builder()
            .scheme(httpScheme)
            .host(endpoint.host)
            .port(endpoint.port)

        endpoint.deploymentPrefix
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { urlBuilder.addPathSegment(it) }

        endpoint.path
            .trim('/')
            .takeIf { it.isNotEmpty() }
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?.forEach { urlBuilder.addPathSegment(it) }

        urlBuilder
            .addPathSegment("api")
            .addPathSegment("sessions")
            .addPathSegment(payload.sessionId)

        if (profile != null) {
            urlBuilder.addQueryParameter("profile", profile)
        }

        val requestBody = json.encodeToString(payload).toRequestBody(jsonMediaType)

        val builder = Request.Builder().url(urlBuilder.build())
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        builder
            .header(HEADER_REQUEST_ID, encodeHeaderValue(payload.requestId))
            .header(HEADER_SOCKET_HANDLE, encodeHeaderValue(payload.socketHandle))
            .header(HEADER_SOCKET_GENERATION, payload.socketGeneration.toString())

        return when (httpMethod) {
            CancellationHttpMethod.DELETE -> builder.delete(requestBody).build()
            CancellationHttpMethod.POST -> builder.post(requestBody).build()
        }
    }

    private suspend fun executeHttpCall(request: Request): Int = withContext(ioDispatcher) {
        val call = httpClient.newCall(request)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(
                            SessionCancellationException(
                                message = "Session cancellation request failed: ${e.message}",
                                statusCode = null,
                                outcomeUnknown = ReconnectPolicy.isOutcomeUnknownForHttpMutation(
                                    bytesMayHaveLeft = true,
                                    receivedStatusCode = null,
                                ),
                                cause = e,
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (continuation.isCancelled) return
                            val code = res.code
                            if (res.isSuccessful) {
                                continuation.resume(code)
                            } else {
                                continuation.resumeWithException(
                                    SessionCancellationException(
                                        message = "Session cancellation failed with HTTP $code: ${res.message}",
                                        statusCode = code,
                                        outcomeUnknown = ReconnectPolicy.isOutcomeUnknownForHttpMutation(
                                            bytesMayHaveLeft = true,
                                            receivedStatusCode = code,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    companion object {
        const val HEADER_REQUEST_ID = "X-Request-Id"
        const val HEADER_SOCKET_HANDLE = "X-Socket-Handle"
        const val HEADER_SOCKET_GENERATION = "X-Socket-Generation"
        const val DEFAULT_CLOSE_REASON = "Session cancelled"
        const val MAX_OPEN_REQUEST_COUNT = MAX_OPEN_REQUESTS
    }
}

private fun encodeHeaderValue(value: String): String {
    if (value.all { it.code in ASCII_HEADER_MIN..ASCII_HEADER_MAX }) {
        return value
    }
    val bytes = value.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val unsigned = byte.toInt() and HEX_MASK
        if (unsigned in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX && unsigned != '%'.code) {
            sb.append(unsigned.toChar())
        } else {
            sb.append('%')
            sb.append(unsigned.toString(HEX_RADIX).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

private fun truncateUtf8(input: String, maxBytes: Int): String {
    if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) {
        return input
    }
    val sb = StringBuilder()
    var usedBytes = 0
    var index = 0
    while (index < input.length) {
        val codePoint = input.codePointAt(index)
        val charCount = Character.charCount(codePoint)
        val chunk = input.substring(index, index + charCount)
        val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
        if (usedBytes + chunkBytes > maxBytes) {
            break
        }
        sb.append(chunk)
        usedBytes += chunkBytes
        index += charCount
    }
    return sb.toString()
}
