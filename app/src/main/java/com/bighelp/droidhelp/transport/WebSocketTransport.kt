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
        val maxBufferedBytes: Long = DEFAULT_MAX_BUFFERED_BYTES
    ) {
        init {
            require(maxFrameSize > 0) { "maxFrameSize must be positive" }
            require(maxQueueSize > 0) { "maxQueueSize must be positive" }
            require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
        }
    }

    /** Represents a message paired with its captured socket owner for fencing. */
    private data class FencedMessage(val message: Message, val capturedSocketOwner: SocketOwner)

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

    // Bounded outbound message queue
    private val outboundQueue = OutboundMessageQueue<FencedMessage>(
        maxSize = config.maxQueueSize,
        overflowPolicy = config.outboundOverflowPolicy
    )
    private var queueJob: Job? = null

    // Correlator stub for request/response correlation
    private val correlator = RpcCorrelator()

    // Statistics
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val parsedFramesReceived = AtomicInteger(0)
    private val framesDecoded = AtomicInteger(0)
    private val staleMessagesDropped = AtomicInteger(0)
    private val oversizedFramesDropped = AtomicInteger(0)
    private val malformedFramesDropped = AtomicInteger(0)

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
                    isConnecting = false
                    pendingConnection = null
                    if (!closed.get()) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
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
                if (!closed.get()) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                pendingConnection = null
            }
        }
        channel.close()
        deferred.complete(false)
    }

    internal fun buildWebSocketUrl(): String {
        val wsScheme = if (endpoint.scheme == "https") "wss" else "ws"
        val wsCanonical = endpoint.canonical.replaceFirst("${endpoint.scheme}://", "$wsScheme://")
        val base = wsCanonical.trimEnd('/')
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
            if (connectionSequence.get() != attemptId || manuallyDisconnected.get() || closed.get()) {
                webSocket.close(NORMAL_CLOSURE_CODE, "Stale connection")
                channel.close()
                deferred.complete(false)
                return
            }
            val previousReceiveJob: Job?
            synchronized(connectionLock) {
                this@WebSocketTransport.webSocket = webSocket
                // Increment socket epoch BEFORE marking connected so queued stale messages are fenced
                owners.incrementSocketEpoch()
                isConnecting = false
                pendingConnection = null
                connectionAttempts = 0
                previousReceiveJob = receiveJob
                _connectionState.value = ConnectionState.CONNECTED
            }
            previousReceiveJob?.cancel()
            correlator.start()
            receiveJob = scope.launch {
                receiveMessages(webSocket.receiveChannel(channel))
            }
            ensureQueueProcessorRunning()
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
                if (!closed.get()) {
                    _connectionState.value = ConnectionState.CLOSING
                }
            }
            cancelQueueProcessor()
            webSocket.close(NORMAL_CLOSURE_CODE, reason)
            channel.close()
            deferred.complete(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionSequence.get() != attemptId) return
            synchronized(connectionLock) {
                if (this@WebSocketTransport.webSocket === webSocket) {
                    this@WebSocketTransport.webSocket = null
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
            }
            cancelQueueProcessor()
            channel.close()
            deferred.complete(false)
            if (!manuallyDisconnected.get() && !closed.get()) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (connectionSequence.get() != attemptId) return
            synchronized(connectionLock) {
                if (this@WebSocketTransport.webSocket === webSocket) {
                    this@WebSocketTransport.webSocket = null
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
                connectionError = t as? Exception ?: Exception(t)
            }
            cancelQueueProcessor()
            channel.close()
            deferred.complete(false)
            if (!manuallyDisconnected.get() && !closed.get()) {
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
        }
        deferredToFail?.complete(false)
        connectionJob?.cancel()
        receiveJob?.cancel()
        reconnectJob?.cancel()
        cancelQueueProcessor()
        connectionJob = null
        receiveJob = null
        reconnectJob = null
        channelToClose?.close()
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
        outboundQueue.close()
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
     * respecting [WebSocketConfig.outboundOverflowPolicy] backpressure / drop settings.
     * @param message The message to send
     */
    override fun send(message: Message) {
        check(!closed.get()) { "WebSocketTransport is closed." }
        val socketOwner = checkNotNull(owners.getSocketOwner()) {
            "No SocketOwner available. Call connect() or resumeSession() first."
        }
        val fenced = FencedMessage(message, socketOwner)
        val enqueued = outboundQueue.enqueueOutgoing(fenced)
        if (!enqueued &&
            !closed.get() &&
            config.outboundOverflowPolicy == OutboundMessageQueue.OverflowPolicy.SUSPEND
        ) {
            scope.launch {
                outboundQueue.enqueueOutgoingSuspending(fenced)
            }
        }
        if (_connectionState.value == ConnectionState.CONNECTED) {
            ensureQueueProcessorRunning()
        }
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
    ): Boolean {
        check(!closed.get()) { "WebSocketTransport is closed." }
        val socketOwner = checkNotNull(owners.getSocketOwner()) {
            "No SocketOwner available. Call connect() or resumeSession() first."
        }
        val enqueued = outboundQueue.enqueueOutgoing(FencedMessage(message, socketOwner), policy)
        if (enqueued && _connectionState.value == ConnectionState.CONNECTED) {
            ensureQueueProcessorRunning()
        }
        return enqueued
    }

    /**
     * Non-blocking FIFO dequeue of the next pending outgoing [Message] from the outbound queue,
     * relieving backpressure for waiting producers, or `null` if the queue is empty.
     */
    fun dequeueOutgoing(): Message? = outboundQueue.dequeueOutgoing()?.message

    /** Returns the number of outgoing messages currently waiting in the outbound queue. */
    fun getOutboundQueueSize(): Int = outboundQueue.size()

    /** Returns the number of outgoing messages dropped due to outbound queue overflow policies. */
    fun getDroppedOutboundMessages(): Int = outboundQueue.droppedCount()

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

    private fun ensureQueueProcessorRunning() {
        synchronized(connectionLock) {
            if (!closed.get() &&
                _connectionState.value == ConnectionState.CONNECTED &&
                queueJob?.isActive != true
            ) {
                queueJob = scope.launch {
                    processOutboundQueue()
                }
            }
        }
    }

    private fun cancelQueueProcessor() {
        val jobToCancel = synchronized(connectionLock) {
            queueJob.also { queueJob = null }
        }
        jobToCancel?.cancel()
    }

    private fun isSocketBackpressured(): Boolean {
        val ws = webSocket ?: return false
        return !closed.get() && isTransportConnected() && ws.queueSize() >= config.maxBufferedBytes
    }

    private suspend fun awaitSocketBackpressureRelief() {
        while (isSocketBackpressured()) {
            delay(BACKPRESSURE_POLL_MS)
        }
    }

    /**
     * Processes the bounded outbound message queue while enforcing [SocketOwner] fencing
     * and socket write-buffer backpressure.
     */
    private suspend fun processOutboundQueue() {
        var keepRunning = true
        while (keepRunning && !closed.get() && isTransportConnected()) {
            awaitSocketBackpressureRelief()
            val fenced = if (!closed.get() && isTransportConnected()) {
                outboundQueue.dequeueOutgoingSuspending()
            } else {
                null
            }
            if (fenced == null) {
                keepRunning = false
            } else {
                sendFencedMessage(fenced)
            }
        }
    }

    private fun sendFencedMessage(fenced: FencedMessage) {
        try {
            owners.validateSocketOwner(fenced.capturedSocketOwner)
            if (isTransportConnected()) {
                actualSend(fenced.message)
            }
        } catch (_: StaleOwnerException) {
            staleMessagesDropped.incrementAndGet()
        }
    }

    /**
     * Performs the actual network send using newline-framed JSON encoding.
     */
    private fun actualSend(message: Message) {
        val ws = webSocket ?: return
        val jsonString = config.json.encodeToString(Message.serializer(), message)
        if (ws.send("$jsonString\n")) {
            messagesSent.incrementAndGet()
        }
    }

    /**
     * Handles incoming text messages (newline-framed JSON payloads) with bounded decode,
     * storing parsed frames in the bounded inbound queue and dispatching listeners.
     * @param text The incoming text payload
     * @param frameDecoder The per-connection bounded newline JSON decoder
     */
    private fun handleIncomingText(text: String, frameDecoder: NewlineJsonFrameDecoder) {
        applyDecodeResult(frameDecoder.decode(text))
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
                // Submit message to correlator's ordered reader/writer loop
                correlator.submit(message)
            }
        }
    }

    /**
     * Handles incoming binary messages with bounded frame decode.
     * Binary messages carrying UTF-8 newline-delimited JSON objects are also decoded
     * into the parsed frame queue without perturbing the text stream's partial buffer.
     * @param bytes The binary data
     */
    private fun handleIncomingBytes(bytes: ByteString) {
        val maxMessageBytes = maxOf(config.maxFrameSize, NewlineJsonFrameDecoder.DEFAULT_MAX_MESSAGE_BYTES)
        val hasNewline = bytes.indexOf(NEWLINE_BYTES) != -1
        when {
            bytes.size > maxMessageBytes -> oversizedFramesDropped.incrementAndGet()
            bytes.size > config.maxFrameSize && !hasNewline -> oversizedFramesDropped.incrementAndGet()
            else -> decodeIncomingBytes(bytes)
        }
    }

    private fun decodeIncomingBytes(bytes: ByteString) {
        val text = bytes.utf8()
        if (!looksLikeJsonFrame(text)) {
            if (bytes.size > config.maxFrameSize) {
                oversizedFramesDropped.incrementAndGet()
            } else {
                framesDecoded.incrementAndGet()
            }
        } else {
            framesDecoded.incrementAndGet()
            val binaryDecoder = NewlineJsonFrameDecoder(config.json, config.maxFrameSize)
            applyDecodeResult(binaryDecoder.decode(text))
        }
    }

    private fun looksLikeJsonFrame(text: String): Boolean {
        val firstChar = text.firstOrNull { !it.isWhitespace() }
        return firstChar == '{' || firstChar == '['
    }

    /**
     * Schedules a reconnection attempt.
     */
    private fun scheduleReconnect() {
        if (manuallyDisconnected.get() || closed.get() || reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            val delayDuration = ReconnectPolicy.delay(connectionAttempts)
            delay(delayDuration.inWholeMilliseconds)
            connectionAttempts++

            if (manuallyDisconnected.get() || closed.get() || !ReconnectPolicy.isQuiet(connectionAttempts)) {
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
     * Receives messages from the WebSocket using a fresh bounded newline JSON decoder per connection.
     */
    private suspend fun receiveMessages(channel: ReceiveChannel<Any>) {
        val frameDecoder = NewlineJsonFrameDecoder(config.json, config.maxFrameSize)
        try {
            for (message in channel) {
                when (message) {
                    is String -> handleIncomingText(message, frameDecoder)
                    is ByteString -> handleIncomingBytes(message)
                }
            }
        } finally {
            frameDecoder.reset()
        }
    }

    /**
     * Resumes a session by re-establishing the profile and connection.
     * @param session The session to resume.
     */
    override fun resumeSession(session: Session) {
        if (closed.get()) return
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
     * Returns the total number of valid JSON frames parsed and enqueued.
     */
    fun getParsedFramesReceived(): Int {
        return parsedFramesReceived.get()
    }

    /**
     * Returns the number of malformed inbound JSON frames dropped gracefully.
     */
    fun getMalformedFramesDropped(): Int {
        return malformedFramesDropped.get()
    }

    /**
     * Returns the number of oldest parsed frames evicted when the inbound queue reached capacity.
     */
    fun getDroppedQueuedFrames(): Int {
        return parsedFrameQueue.droppedCount()
    }

    /**
     * Returns whether the transport is connected.
     * @return true if connected, false otherwise
     */
    fun isTransportConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }

    /**
     * Returns whether this transport has been permanently closed.
     */
    fun isClosed(): Boolean {
        return closed.get()
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
        private const val CLOSED_TRANSPORT = -3L
        private const val BACKPRESSURE_POLL_MS = 10L
        private val NEWLINE_BYTES = ByteString.of('\n'.code.toByte())
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
