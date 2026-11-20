package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.SecureInput
import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An extension to convert WebSocket messages into a ReceiveChannel.
 */
fun WebSocket.receiveChannel(channel: Channel<Any>): ReceiveChannel<Any> = channel

class WebSocketTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint,
    private val config: WebSocketConfig = WebSocketConfig()
) : Transport {

    data class WebSocketConfig(
        val headers: Map<String, String> = emptyMap(),
        val json: Json = Json,
        val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
        val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    ) {
        init {
            require(maxFrameSize > 0) { "maxFrameSize must be positive" }
            require(maxQueueSize > 0) { "maxQueueSize must be positive" }
        }
    }

    /** Represents a message paired with its captured socket owner for fencing. */
    private data class FencedMessage(val message: Message, val capturedSocketOwner: SocketOwner)

    private val owners = Owners()
    private val connectionLock = Any()
    private val connectionSequence = AtomicLong(0L)
    private val manuallyDisconnected = AtomicBoolean(false)

    // WebSocket connection state
    @Volatile
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeIncomingChannel: Channel<Any>? = null
    private var pendingConnection: CompletableDeferred<Boolean>? = null
    @Volatile
    private var isConnecting = false
    private val connectedState = MutableStateFlow(false)
    @Volatile
    private var connectionError: Exception? = null
    private var connectionAttempts = 0

    // Message handling
    private val messageListeners = CopyOnWriteArrayList<(Message) -> Unit>()

    // Bounded outbound message queue
    private val outboundQueue: Channel<FencedMessage> = Channel(config.maxQueueSize)
    private var queueJob: Job? = null

    // Correlator stub for request/response correlation
    private val correlator = RpcCorrelator()

    // Statistics
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val framesDecoded = AtomicInteger(0)
    private val staleMessagesDropped = AtomicInteger(0)
    private val oversizedFramesDropped = AtomicInteger(0)

    init {
        ensureQueueProcessorRunning()
    }

    /**
     * Connects to the WebSocket endpoint (`/api/ws`).
     * Suspends until the handshake succeeds (`true`) or fails/is aborted (`false`).
     * @return true if connection succeeded, false otherwise
     */
    override suspend fun connect(): Boolean {
        val (deferred, attemptId) = synchronized(connectionLock) {
            when {
                connectedState.value -> null to ALREADY_CONNECTED
                isConnecting -> pendingConnection to JOIN_IN_FLIGHT
                else -> {
                    manuallyDisconnected.set(false)
                    isConnecting = true
                    connectionError = null
                    val newDeferred = CompletableDeferred<Boolean>()
                    pendingConnection = newDeferred
                    newDeferred to connectionSequence.incrementAndGet()
                }
            }
        }

        return when {
            attemptId == ALREADY_CONNECTED -> true
            attemptId == JOIN_IN_FLIGHT -> deferred?.await() ?: false
            deferred == null -> false
            else -> {
                ensureQueueProcessorRunning()
                val channel = Channel<Any>(config.maxQueueSize)
                synchronized(connectionLock) {
                    activeIncomingChannel = channel
                }
                executeConnectionAttempt(attemptId, channel, deferred)
            }
        }
    }

    private suspend fun executeConnectionAttempt(
        attemptId: Long,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ): Boolean {
        val initiated = try {
            val request = buildWebSocketRequest()
            val listener = createWebSocketListener(attemptId, channel, deferred)
            val createdSocket = config.client.newWebSocket(request, listener)
            synchronized(connectionLock) {
                if (connectionSequence.get() == attemptId) {
                    webSocket = createdSocket
                } else {
                    createdSocket.cancel()
                }
            }
            true
        } catch (e: IllegalArgumentException) {
            failConnectionAttempt(attemptId, e, channel, deferred)
            false
        } catch (e: SecurityException) {
            failConnectionAttempt(attemptId, e, channel, deferred)
            false
        } catch (e: IllegalStateException) {
            failConnectionAttempt(attemptId, e, channel, deferred)
            false
        }

        return if (initiated) {
            awaitConnectionResult(attemptId, channel, deferred)
        } else {
            false
        }
    }

    private suspend fun awaitConnectionResult(
        attemptId: Long,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ): Boolean {
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            val wsToCancel: WebSocket?
            synchronized(connectionLock) {
                if (connectionSequence.get() == attemptId) {
                    isConnecting = false
                    pendingConnection = null
                    wsToCancel = webSocket
                    webSocket = null
                } else {
                    wsToCancel = null
                }
            }
            wsToCancel?.cancel()
            channel.close()
            deferred.complete(false)
            throw e
        }
    }

    private fun failConnectionAttempt(
        attemptId: Long,
        error: Exception,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ) {
        synchronized(connectionLock) {
            if (connectionSequence.get() == attemptId) {
                connectionError = error
                isConnecting = false
                connectedState.value = false
                pendingConnection = null
            }
        }
        channel.close()
        deferred.complete(false)
    }

    internal fun buildWebSocketUrl(): String {
        val base = endpoint.canonical.trimEnd('/')
        return if (base.endsWith(WS_PATH)) base else "$base$WS_PATH"
    }

    private fun buildWebSocketRequest(): Request {
        val requestBuilder = Request.Builder()
            .url(buildWebSocketUrl())

        config.headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        return requestBuilder.build()
    }

    private fun createWebSocketListener(
        attemptId: Long,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (connectionSequence.get() != attemptId || manuallyDisconnected.get()) {
                webSocket.close(NORMAL_CLOSURE_CODE, "Stale connection")
                channel.close()
                deferred.complete(false)
                return
            }
            synchronized(connectionLock) {
                this@WebSocketTransport.webSocket = webSocket
                // Increment socket epoch BEFORE marking connected so queued stale messages are fenced
                owners.incrementSocketEpoch()
                isConnecting = false
                pendingConnection = null
                connectionAttempts = 0
                connectedState.value = true
            }
            correlator.start()
            receiveJob = scope.launch {
                receiveMessages(webSocket.receiveChannel(channel))
            }
            dispatchIncomingMessage(Message.Ready)
            deferred.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connectionSequence.get() == attemptId) {
                channel.trySend(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (connectionSequence.get() == attemptId) {
                channel.trySend(bytes)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionSequence.get() != attemptId) return
            synchronized(connectionLock) {
                connectedState.value = false
            }
            channel.close()
            deferred.complete(false)
            if (!manuallyDisconnected.get()) {
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionSequence.get() != attemptId) return
            synchronized(connectionLock) {
                connectedState.value = false
                isConnecting = false
                if (pendingConnection === deferred) {
                    pendingConnection = null
                }
            }
            channel.close()
            deferred.complete(false)
            if (!manuallyDisconnected.get()) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (connectionSequence.get() != attemptId) return
            synchronized(connectionLock) {
                connectedState.value = false
                isConnecting = false
                if (pendingConnection === deferred) {
                    pendingConnection = null
                }
                connectionError = t as? Exception ?: Exception(t)
            }
            channel.close()
            deferred.complete(false)
            if (!manuallyDisconnected.get()) {
                scheduleReconnect()
            }
        }
    }

    /**
     * Disconnects from the WebSocket.
     */
    fun disconnect() {
        val wasConnected: Boolean
        val wsToClose: WebSocket?
        val channelToClose: Channel<Any>?
        val deferredToFail: CompletableDeferred<Boolean>?
        synchronized(connectionLock) {
            manuallyDisconnected.set(true)
            connectionSequence.incrementAndGet()
            wasConnected = connectedState.value
            connectedState.value = false
            isConnecting = false
            wsToClose = webSocket
            webSocket = null
            channelToClose = activeIncomingChannel
            activeIncomingChannel = null
            deferredToFail = pendingConnection
            pendingConnection = null
        }
        deferredToFail?.complete(false)
        connectionJob?.cancel()
        receiveJob?.cancel()
        reconnectJob?.cancel()
        queueJob?.cancel()
        connectionJob = null
        receiveJob = null
        reconnectJob = null
        queueJob = null
        channelToClose?.close()
        if (wasConnected) {
            wsToClose?.close(NORMAL_CLOSURE_CODE, "Normal closure")
        } else {
            wsToClose?.cancel()
        }
        correlator.stop()
    }

    /**
     * Enqueues a message onto the bounded outbound queue paired with the current [SocketOwner].
     * @param message The message to send
     */
    override fun send(message: Message) {
        val socketOwner = checkNotNull(owners.getSocketOwner()) {
            "No SocketOwner available. Call connect() or resumeSession() first."
        }
        ensureQueueProcessorRunning()
        val fenced = FencedMessage(message, socketOwner)
        val enqueueResult = outboundQueue.trySend(fenced)
        if (enqueueResult.isFailure && !enqueueResult.isClosed) {
            scope.launch {
                outboundQueue.send(fenced)
            }
        }
    }

    /**
     * Registers a callback for incoming messages.
     * @param callback The callback to invoke for each message
     */
    override fun onMessage(callback: (Message) -> Unit) {
        messageListeners.add(callback)
    }

    private fun dispatchIncomingMessage(message: Message) {
        messageListeners.forEach { listener ->
            listener(message)
        }
    }

    private fun ensureQueueProcessorRunning() {
        synchronized(connectionLock) {
            if (queueJob?.isActive != true) {
                queueJob = scope.launch {
                    processOutboundQueue()
                }
            }
        }
    }

    /**
     * Processes the bounded outbound message queue while enforcing [SocketOwner] fencing.
     */
    private suspend fun processOutboundQueue() {
        for (fenced in outboundQueue) {
            try {
                owners.validateSocketOwner(fenced.capturedSocketOwner)
                if (!connectedState.value) {
                    connectedState.first { it }
                }
                owners.validateSocketOwner(fenced.capturedSocketOwner)
                actualSend(fenced.message)
            } catch (_: StaleOwnerException) {
                staleMessagesDropped.incrementAndGet()
            }
        }
    }

    /**
     * Performs the actual network send.
     */
    private fun actualSend(message: Message) {
        val ws = webSocket ?: return
        val jsonString = config.json.encodeToString(Message.serializer(), message)
        if (ws.send(jsonString)) {
            messagesSent.incrementAndGet()
        }
    }

    /**
     * Handles incoming text messages (JSON frames) with bounded frame decode.
     * @param text The JSON text
     */
    private fun handleIncomingText(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > config.maxFrameSize) {
            oversizedFramesDropped.incrementAndGet()
            return
        }
        try {
            val message = config.json.decodeFromString(Message.serializer(), text)
            messagesReceived.incrementAndGet()
            dispatchIncomingMessage(message)
            // Submit message to correlator's ordered reader/writer loop
            correlator.submit(message)
        } catch (_: SerializationException) {
            // Drop malformed JSON frames without terminating the receive loop
        } catch (_: IllegalArgumentException) {
            // Drop invalid payloads without terminating the receive loop
        }
    }

    /**
     * Handles incoming binary messages (chunked frames) with bounded frame decode.
     * @param bytes The binary data
     */
    private fun handleIncomingBytes(bytes: ByteString) {
        if (bytes.size > config.maxFrameSize) {
            oversizedFramesDropped.incrementAndGet()
            return
        }
        framesDecoded.incrementAndGet()
    }

    /**
     * Schedules a reconnection attempt.
     */
    private fun scheduleReconnect() {
        if (manuallyDisconnected.get() || reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            val delayDuration = ReconnectPolicy.delay(connectionAttempts)
            delay(delayDuration.inWholeMilliseconds)
            connectionAttempts++

            if (manuallyDisconnected.get() || !ReconnectPolicy.isQuiet(connectionAttempts)) {
                return@launch
            }

            val session = ReconnectPolicy.getSession()
            if (session != null) {
                resumeSession(session)
            } else {
                connect()
            }
        }
    }

    /**
     * Receives messages from the WebSocket.
     */
    private suspend fun receiveMessages(channel: ReceiveChannel<Any>) {
        for (message in channel) {
            when (message) {
                is String -> handleIncomingText(message)
                is ByteString -> handleIncomingBytes(message)
            }
        }
    }

    /**
     * Resumes a session by re-establishing the profile and connection.
     * @param session The session to resume.
     */
    override fun resumeSession(session: Session) {
        ReconnectPolicy.setSession(session)
        SecureInput.bind(session.profile)
        connectionJob = scope.launch { connect() }
    }

    /**
     * Registers a callback for a specific request ID.
     * @param requestId The request ID
     * @param callback The callback to invoke when a response is received
     */
    fun register(requestId: String, callback: (Message) -> Unit) {
        correlator.register(requestId, callback)
    }

    /**
     * Cancels a pending request.
     * @param requestId The request ID to cancel
     */
    fun cancel(requestId: String) {
        correlator.cancel(requestId)
    }

    /**
     * Returns the number of pending requests.
     * @return The number of pending requests
     */
    fun pendingCount(): Int {
        return correlator.pendingCount()
    }

    /**
     * Returns the number of messages sent.
     * @return The number of messages sent
     */
    fun getMessagesSent(): Int {
        return messagesSent.get()
    }

    /**
     * Returns the number of messages received.
     * @return The number of messages received
     */
    fun getMessagesReceived(): Int {
        return messagesReceived.get()
    }

    /**
     * Returns the number of binary frames decoded.
     * @return The number of frames decoded
     */
    fun getFramesDecoded(): Int {
        return framesDecoded.get()
    }

    /**
     * Returns the number of outbound messages dropped due to stale socket ownership.
     */
    fun getStaleMessagesDropped(): Int {
        return staleMessagesDropped.get()
    }

    /**
     * Returns the number of inbound frames dropped for exceeding [WebSocketConfig.maxFrameSize].
     */
    fun getOversizedFramesDropped(): Int {
        return oversizedFramesDropped.get()
    }

    /**
     * Returns whether the transport is connected.
     * @return true if connected, false otherwise
     */
    fun isTransportConnected(): Boolean {
        return connectedState.value
    }

    /**
     * Returns the connection error, if any.
     * @return The connection error, or null if no error
     */
    fun getConnectionError(): Exception? {
        return connectionError
    }

    /** Returns the owners instance. */
    fun getOwners(): Owners = owners

    /** Updates the current sign-in state. */
    fun setSignIn(signIn: SignIn) {
        owners.updateSignIn(signIn)
    }

    /** Updates the current owner state. */
    fun setOwner(owner: Owner) {
        owners.updateOwner(owner)
    }

    companion object {
        private const val ALREADY_CONNECTED = -1L
        private const val JOIN_IN_FLIGHT = -2L
        const val WS_PATH = "/api/ws"
        const val NORMAL_CLOSURE_CODE = 1000
        const val DEFAULT_MAX_FRAME_SIZE = 1024 * 1024 // 1MB
        const val DEFAULT_MAX_QUEUE_SIZE = 1000
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 30L
        const val DEFAULT_READ_TIMEOUT_SECONDS = 0L
        const val DEFAULT_WRITE_TIMEOUT_SECONDS = 30L

        /**
         * Creates a WebSocketTransport instance.
         * @param endpoint The WebSocket endpoint
         * @param headers Additional HTTP headers
         * @param scope Coroutine scope
         * @return A WebSocketTransport instance
         */
        fun create(
            endpoint: Endpoint,
            headers: Map<String, String> = emptyMap(),
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
        ): WebSocketTransport {
            return WebSocketTransport(scope, endpoint, WebSocketConfig(headers = headers))
        }
    }
}
