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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSING,
        CLOSED
    }

    data class WebSocketConfig(
        val headers: Map<String, String> = emptyMap(),
        val json: Json = Json,
        val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
        val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
        val client: OkHttpClient? = null,
        val outboundOverflowPolicy: OutboundMessageQueue.OverflowPolicy =
            OutboundMessageQueue.OverflowPolicy.SUSPEND,
        val maxBufferedBytes: Long = DEFAULT_MAX_BUFFERED_BYTES,
        val maxMessageBytes: Int = maxOf(maxFrameSize, NewlineJsonFrameDecoder.DEFAULT_MAX_MESSAGE_BYTES),
        val maxNestingDepth: Int = NewlineJsonFrameDecoder.DEFAULT_MAX_NESTING_DEPTH
    ) {
        init {
            require(maxFrameSize > 0) { "maxFrameSize must be positive" }
            require(maxQueueSize > 0) { "maxQueueSize must be positive" }
            require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
            require(maxMessageBytes >= maxFrameSize) { "maxMessageBytes must be >= maxFrameSize" }
            require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        }
    }

    private val requestHeaders: Map<String, String> = config.headers.toMap()
    private val ownsHttpClient = config.client == null
    private val httpClient: OkHttpClient = config.client ?: OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val owners = Owners()
    private val connectionLock = Any()
    private val connectionSequence = AtomicLong(0L)
    private val manuallyDisconnected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

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
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    @Volatile
    private var connectionError: Exception? = null
    private var connectionAttempts = 0

    // Message and parsed-frame handling
    private val messageListeners = CopyOnWriteArrayList<(Message) -> Unit>()
    private val parsedFrameListeners = CopyOnWriteArrayList<(ParsedJsonFrame) -> Unit>()

    // Bounded inbound parsed-frame queue
    private val parsedFrameQueue = ParsedFrameQueue(config.maxQueueSize)

    // Correlator for matching incoming JSON frames and responses to outgoing request IDs
    private val correlator = RpcCorrelator()

    /** Hot stream of [CorrelationEvent]s emitted when an incoming JSON frame matches a request ID. */
    val correlationEvents: SharedFlow<CorrelationEvent>
        get() = correlator.correlationEvents

    // Statistics
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val parsedFramesReceived = AtomicInteger(0)
    private val framesDecoded = AtomicInteger(0)
    private val staleMessagesDropped = AtomicInteger(0)
    private val oversizedFramesDropped = AtomicInteger(0)
    private val malformedFramesDropped = AtomicInteger(0)

    // Bounded incoming frame intake & decoder
    private val incomingIntake = WebSocketIncomingIntake(
        config = config,
        callbacks = object : WebSocketIncomingIntake.Callbacks {
            override fun onDecodeResult(result: FrameDecodeResult) = applyDecodeResult(result)
            override fun onBinaryFrameDecoded() {
                framesDecoded.incrementAndGet()
            }
            override fun onOversizedDropped() {
                oversizedFramesDropped.incrementAndGet()
            }
            override fun onMalformedDropped(count: Int) {
                malformedFramesDropped.addAndGet(count)
            }
        }
    )

    // Bounded FIFO outbound queue + fencing + socket backpressure pipeline
    private val outboundPipeline = WebSocketOutboundPipeline(
        scope = scope,
        config = config,
        owners = owners,
        hooks = object : WebSocketOutboundPipeline.Hooks {
            override fun isClosed(): Boolean = closed.get()
            override fun isConnected(): Boolean = isTransportConnected()
            override fun currentSocket(): WebSocket? = webSocket
            override fun onMessageSent() {
                messagesSent.incrementAndGet()
            }
            override fun onStaleDropped() {
                staleMessagesDropped.incrementAndGet()
            }
        }
    )

    /**
     * Connects to the WebSocket endpoint (`/api/ws`).
     * Suspends until the handshake succeeds (`true`) or fails/is aborted (`false`).
     * @return true if connection succeeded, false otherwise
     */
    override suspend fun connect(): Boolean {
        val (deferred, attemptId) = synchronized(connectionLock) {
            when {
                closed.get() -> null to CLOSED_TRANSPORT
                _connectionState.value == ConnectionState.CONNECTED -> null to ALREADY_CONNECTED
                isConnecting -> pendingConnection to JOIN_IN_FLIGHT
                else -> {
                    manuallyDisconnected.set(false)
                    isConnecting = true
                    connectionError = null
                    _connectionState.value = ConnectionState.CONNECTING
                    val newDeferred = CompletableDeferred<Boolean>()
                    pendingConnection = newDeferred
                    newDeferred to connectionSequence.incrementAndGet()
                }
            }
        }

        return when {
            attemptId == CLOSED_TRANSPORT -> false
            attemptId == ALREADY_CONNECTED -> true
            attemptId == JOIN_IN_FLIGHT -> deferred?.await() ?: false
            deferred == null -> false
            else -> {
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
            val createdSocket = httpClient.newWebSocket(request, listener)
            synchronized(connectionLock) {
                if (connectionSequence.get() == attemptId && !closed.get()) {
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
                    connectionSequence.incrementAndGet()
                    isConnecting = false
                    pendingConnection = null
                    if (activeIncomingChannel === channel) {
                        activeIncomingChannel = null
                    }
                    if (!closed.get()) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                    wsToCancel = webSocket
                    webSocket = null
                } else {
                    wsToCancel = null
                }
            }
            channel.close()
            wsToCancel?.cancel()
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
                connectionSequence.incrementAndGet()
                connectionError = error
                isConnecting = false
                if (activeIncomingChannel === channel) {
                    activeIncomingChannel = null
                }
                if (!closed.get()) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                pendingConnection = null
            }
        }
        channel.close()
        deferred.complete(false)
    }

    internal fun buildWebSocketUrl(): String = WebSocketRequestBuilder.buildUrl(endpoint, WS_PATH)

    internal fun buildWebSocketRequest(): Request =
        WebSocketRequestBuilder.buildRequest(endpoint, requestHeaders)

    private fun createWebSocketListener(
        attemptId: Long,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handleSocketOpen(attemptId, webSocket, channel, deferred)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            incomingIntake.enqueueText(channel, text) {
                connectionSequence.get() == attemptId && !closed.get()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incomingIntake.enqueueBytes(channel, bytes) {
                connectionSequence.get() == attemptId && !closed.get()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val isCurrent = synchronized(connectionLock) {
                val matches = connectionSequence.get() == attemptId
                if (matches && !closed.get()) {
                    _connectionState.value = ConnectionState.CLOSING
                }
                matches
            }
            if (isCurrent) {
                outboundPipeline.cancelProcessor()
                webSocket.close(NORMAL_CLOSURE_CODE, reason)
                channel.close()
                deferred.complete(false)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketTermination(attemptId, webSocket, channel, deferred, error = null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = t as? Exception ?: Exception(t)
            handleSocketTermination(attemptId, webSocket, channel, deferred, error = error)
        }
    }

    private fun handleSocketOpen(
        attemptId: Long,
        socket: WebSocket,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>
    ) {
        val previousReceiveJob: Job?
        val accepted = synchronized(connectionLock) {
            val valid = connectionSequence.get() == attemptId &&
                !manuallyDisconnected.get() &&
                !closed.get()
            if (valid) {
                this.webSocket = socket
                // Increment socket epoch BEFORE marking connected so queued stale messages are fenced
                owners.incrementSocketEpoch()
                isConnecting = false
                pendingConnection = null
                connectionAttempts = 0
                previousReceiveJob = receiveJob
                _connectionState.value = ConnectionState.CONNECTED
                correlator.start()
                receiveJob = scope.launch {
                    incomingIntake.consumeLoop(socket.receiveChannel(channel))
                }
                true
            } else {
                previousReceiveJob = null
                false
            }
        }
        if (!accepted) {
            socket.close(NORMAL_CLOSURE_CODE, "Stale connection")
            channel.close()
            deferred.complete(false)
        } else {
            previousReceiveJob?.cancel()
            outboundPipeline.ensureProcessorRunning()
            if (connectionSequence.get() == attemptId && !closed.get()) {
                dispatchIncomingMessage(Message.Ready)
            }
            deferred.complete(true)
        }
    }

    private fun handleSocketTermination(
        attemptId: Long,
        socket: WebSocket,
        channel: Channel<Any>,
        deferred: CompletableDeferred<Boolean>,
        error: Exception?
    ) {
        val (isCurrent, shouldReconnect) = synchronized(connectionLock) {
            if (connectionSequence.get() != attemptId) {
                false to false
            } else {
                if (this.webSocket === socket) {
                    this.webSocket = null
                }
                if (activeIncomingChannel === channel) {
                    activeIncomingChannel = null
                }
                _connectionState.value = if (closed.get()) {
                    ConnectionState.CLOSED
                } else {
                    ConnectionState.DISCONNECTED
                }
                isConnecting = false
                if (pendingConnection === deferred) {
                    pendingConnection = null
                }
                if (error != null) {
                    connectionError = error
                }
                true to (!manuallyDisconnected.get() && !closed.get())
            }
        }
        if (isCurrent) {
            outboundPipeline.cancelProcessor()
            channel.close()
            deferred.complete(false)
            if (shouldReconnect) {
                scheduleReconnect()
            }
        }
    }

    /**
     * Disconnects from the WebSocket while keeping the transport reusable for future [connect] calls.
     */
    fun disconnect() {
        val wasConnected: Boolean
        val wsToClose: WebSocket?
        val channelToClose: Channel<Any>?
        val deferredToFail: CompletableDeferred<Boolean>?
        val connJobToCancel: Job?
        val recvJobToCancel: Job?
        val reconnJobToCancel: Job?
        synchronized(connectionLock) {
            manuallyDisconnected.set(true)
            connectionSequence.incrementAndGet()
            wasConnected = _connectionState.value == ConnectionState.CONNECTED
            _connectionState.value = if (closed.get()) {
                ConnectionState.CLOSED
            } else {
                ConnectionState.DISCONNECTED
            }
            isConnecting = false
            wsToClose = webSocket
            webSocket = null
            channelToClose = activeIncomingChannel
            activeIncomingChannel = null
            deferredToFail = pendingConnection
            pendingConnection = null
            connJobToCancel = connectionJob
            connectionJob = null
            recvJobToCancel = receiveJob
            receiveJob = null
            reconnJobToCancel = reconnectJob
            reconnectJob = null
        }
        channelToClose?.close()
        deferredToFail?.complete(false)
        connJobToCancel?.cancel()
        recvJobToCancel?.cancel()
        reconnJobToCancel?.cancel()
        outboundPipeline.cancelProcessor()
        if (wasConnected) {
            wsToClose?.close(NORMAL_CLOSURE_CODE, "Normal closure")
        } else {
            wsToClose?.cancel()
        }
        correlator.stop()
    }

    /**
     * Permanently closes this transport and releases all underlying resources
     * (active socket, coroutines, outbound queue, correlator executor, and owned OkHttpClient).
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnect()
        synchronized(connectionLock) {
            _connectionState.value = ConnectionState.CLOSED
        }
        outboundPipeline.close()
        parsedFrameQueue.close()
        messageListeners.clear()
        parsedFrameListeners.clear()
        correlator.shutdown()
        if (ownsHttpClient) {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    /**
     * Enqueues a message onto the bounded outbound queue paired with the current [SocketOwner],
     * respecting [WebSocketConfig.outboundOverflowPolicy] backpressure / drop settings while
     * preserving FIFO ordering across backpressured sends.
     * @param message The message to send
     */
    override fun send(message: Message) {
        outboundPipeline.send(message)
    }

    /**
     * Non-blocking enqueue of an outgoing [message] paired with the current [SocketOwner]
     * onto the bounded outbound queue, applying [policy] when full.
     *
     * @return `true` if the message was enqueued, or `false` if rejected/dropped or closed.
     */
    fun enqueueOutgoing(
        message: Message,
        policy: OutboundMessageQueue.OverflowPolicy = config.outboundOverflowPolicy
    ): Boolean = outboundPipeline.enqueue(message, policy)

    /**
     * Suspends until [message] paired with the current [SocketOwner] can be enqueued onto the
     * bounded outbound queue (respecting [WebSocketConfig.outboundOverflowPolicy]).
     */
    suspend fun enqueueOutgoingSuspending(message: Message): Boolean =
        outboundPipeline.enqueueSuspending(message)

    /**
     * Registers [requestId] with [RpcCorrelator] before enqueuing [message] onto the bounded
     * outbound queue, preventing races where a fast reply arrives before registration completes.
     */
    fun sendWithCorrelation(
        requestId: String,
        message: Message,
        timeoutNanos: Long = FrameCorrelator.DEFAULT_TIMEOUT_NANOS,
        onMatch: ((CorrelationEvent) -> Unit)? = null
    ) {
        check(!closed.get()) { "WebSocketTransport is closed." }
        checkNotNull(owners.getSocketOwner()) {
            "No SocketOwner available. Call connect() or resumeSession() first."
        }
        correlator.frameCorrelator.register(requestId, timeoutNanos, onMatch)
        try {
            send(message)
        } catch (e: IllegalStateException) {
            correlator.cancel(requestId)
            throw e
        }
    }

    /**
     * Non-blocking FIFO dequeue of the next pending outgoing [Message] from the outbound queue,
     * relieving backpressure for waiting producers, or `null` if the queue is empty.
     */
    fun dequeueOutgoing(): Message? = outboundPipeline.dequeue()

    /** Returns the number of outgoing messages currently waiting in the outbound queue. */
    fun getOutboundQueueSize(): Int = outboundPipeline.size()

    /** Returns the number of outgoing messages dropped due to outbound queue overflow policies. */
    fun getDroppedOutboundMessages(): Int = outboundPipeline.droppedCount()

    /**
     * Registers a callback for incoming messages.
     * @param callback The callback to invoke for each message
     */
    override fun onMessage(callback: (Message) -> Unit) {
        if (!closed.get()) {
            messageListeners.add(callback)
        }
    }

    /**
     * Registers a callback for each parsed incoming newline-delimited JSON frame.
     * @param callback The callback to invoke for each parsed frame
     */
    fun onParsedFrame(callback: (ParsedJsonFrame) -> Unit) {
        if (!closed.get()) {
            parsedFrameListeners.add(callback)
        }
    }

    /** Non-blocking FIFO dequeue of the next parsed incoming JSON frame, or null if empty. */
    fun pollParsedFrame(): ParsedJsonFrame? = parsedFrameQueue.poll()

    /**
     * Suspends until the next parsed incoming JSON frame is available in FIFO order,
     * or returns null once the transport is closed and the queue is drained.
     */
    suspend fun receiveParsedFrame(): ParsedJsonFrame? = parsedFrameQueue.receive()

    /** Drains all currently queued parsed incoming JSON frames in FIFO order. */
    fun drainParsedFrames(): List<ParsedJsonFrame> = parsedFrameQueue.drain()

    /** Returns the number of parsed incoming JSON frames currently waiting in the queue. */
    fun getParsedFrameQueueSize(): Int = parsedFrameQueue.size()

    private fun dispatchIncomingMessage(message: Message) {
        messageListeners.forEach { listener ->
            listener(message)
        }
    }

    private fun dispatchParsedFrame(frame: ParsedJsonFrame) {
        parsedFrameListeners.forEach { listener ->
            listener(frame)
        }
    }

    private fun applyDecodeResult(result: FrameDecodeResult) {
        if (result.oversizedDrops > 0) {
            oversizedFramesDropped.addAndGet(result.oversizedDrops)
        }
        if (result.malformedDrops > 0) {
            malformedFramesDropped.addAndGet(result.malformedDrops)
        }
        result.frames.forEach { frame ->
            parsedFramesReceived.incrementAndGet()
            parsedFrameQueue.enqueue(frame)
            dispatchParsedFrame(frame)
            frame.message?.let { message ->
                messagesReceived.incrementAndGet()
                dispatchIncomingMessage(message)
            }
            // Submit decoded JSON frame to correlator's ordered reader/writer loop
            correlator.submit(frame)
        }
    }

    /**
     * Schedules a reconnection attempt.
     */
    private fun scheduleReconnect() {
        synchronized(connectionLock) {
            if (manuallyDisconnected.get() || closed.get() || reconnectJob?.isActive == true) {
                return
            }
            val currentAttempt = connectionAttempts
            reconnectJob = scope.launch {
                val delayDuration = ReconnectPolicy.delay(currentAttempt)
                delay(delayDuration.inWholeMilliseconds)
                val shouldAttempt = synchronized(connectionLock) {
                    connectionAttempts++
                    !manuallyDisconnected.get() &&
                        !closed.get() &&
                        ReconnectPolicy.isQuiet(connectionAttempts)
                }
                if (shouldAttempt) {
                    val session = ReconnectPolicy.getSession()
                    if (session != null) {
                        resumeSession(session)
                    } else {
                        connect()
                    }
                }
            }
        }
    }

    /**
     * Resumes a session by re-establishing the profile and connection.
     * @param session The session to resume.
     */
    override fun resumeSession(session: Session) {
        synchronized(connectionLock) {
            if (closed.get()) return
            ReconnectPolicy.setSession(session)
            SecureInput.bind(session.profile)
            connectionJob = scope.launch { connect() }
        }
    }

    /**
     * Registers a callback for a specific request ID.
     * @param requestId The request ID
     * @param callback The callback to invoke when a response is received
     */
    fun register(requestId: String, callback: (Message) -> Unit) {
        check(!closed.get()) { "WebSocketTransport is closed." }
        correlator.register(requestId, callback)
    }

    /**
     * Registers an outgoing request ID for incoming JSON frame correlation.
     * When a matching frame arrives, a [CorrelationEvent] is emitted to [correlationEvents],
     * any [onCorrelationEvent] listeners, and [onMatch] if provided.
     */
    fun registerRequest(
        requestId: String,
        timeoutNanos: Long = FrameCorrelator.DEFAULT_TIMEOUT_NANOS,
        onMatch: ((CorrelationEvent) -> Unit)? = null
    ) {
        check(!closed.get()) { "WebSocketTransport is closed." }
        correlator.frameCorrelator.register(requestId, timeoutNanos, onMatch)
    }

    /**
     * Registers a listener invoked whenever an incoming JSON frame matches a pending request ID.
     */
    fun onCorrelationEvent(listener: (CorrelationEvent) -> Unit) {
        if (!closed.get()) {
            correlator.frameCorrelator.addListener(listener)
        }
    }

    /**
     * Unregisters a previously registered correlation event listener.
     */
    fun removeCorrelationEventListener(listener: (CorrelationEvent) -> Unit) {
        correlator.frameCorrelator.removeListener(listener)
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
    fun pendingCount(): Int = correlator.pendingCount()

    /**
     * Returns the total number of incoming frames/responses correlated to outgoing requests.
     */
    fun getCorrelatedFramesCount(): Int = correlator.frameCorrelator.correlatedCount

    /** Returns the number of messages sent. */
    fun getMessagesSent(): Int = messagesSent.get()

    /** Returns the number of messages received. */
    fun getMessagesReceived(): Int = messagesReceived.get()

    /** Returns the number of binary frames decoded. */
    fun getFramesDecoded(): Int = framesDecoded.get()

    /** Returns the number of outbound messages dropped due to stale socket ownership. */
    fun getStaleMessagesDropped(): Int = staleMessagesDropped.get()

    /** Returns the number of inbound frames dropped for exceeding [WebSocketConfig.maxFrameSize]. */
    fun getOversizedFramesDropped(): Int = oversizedFramesDropped.get()

    /** Returns the total number of valid JSON frames parsed and enqueued. */
    fun getParsedFramesReceived(): Int = parsedFramesReceived.get()

    /** Returns the number of malformed inbound JSON frames dropped gracefully. */
    fun getMalformedFramesDropped(): Int = malformedFramesDropped.get()

    /** Returns the number of oldest parsed frames evicted when the inbound queue reached capacity. */
    fun getDroppedQueuedFrames(): Int = parsedFrameQueue.droppedCount()

    /** Returns whether the transport is connected. */
    fun isTransportConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /** Returns whether this transport has been permanently closed. */
    fun isClosed(): Boolean = closed.get()

    /** Returns the connection error, if any. */
    fun getConnectionError(): Exception? = connectionError

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
        private const val CLOSED_TRANSPORT = -3L
        const val WS_PATH = "/api/ws"
        const val NORMAL_CLOSURE_CODE = 1000
        const val DEFAULT_MAX_FRAME_SIZE = 1024 * 1024 // 1MB
        const val DEFAULT_MAX_QUEUE_SIZE = 1000
        const val DEFAULT_MAX_BUFFERED_BYTES = 1024L * 1024L // 1MB
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
