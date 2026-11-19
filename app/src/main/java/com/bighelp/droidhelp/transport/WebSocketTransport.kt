package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.SecureInput
import com.bighelp.droidhelp.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.lang.Exception
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebSocketTransport(
    private val scope: CoroutineScope,
    private val endpoint: Endpoint,
    private val headers: Map<String, String> = emptyMap(),
    private val json: Json = Json,
    private val maxFrameSize: Int = 1024 * 1024, // 1MB
    private val maxQueueSize: Int = 1000,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) : Transport {

    // WebSocket connection state
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var isConnecting = false
    private var isConnected = false
    private var connectionError: Exception? = null
    private var connectionAttempts = 0

    // Message handling
    private val _incomingMessages = MutableStateFlow<Message?>(null)
    private val incomingChannel = Channel<Any>(Channel.BUFFERED)

    // Outbound message queue
    private val outboundQueue: Channel<Message> = Channel(maxQueueSize)
    private val queueJob: Job

    // Correlator stub for request/response correlation
    private val correlator = RpcCorrelator()

    // Frame processing
    private val pendingFrames = ConcurrentHashMap<String, MutableList<ByteString>>() // frameId -> chunks

    // Statistics
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val framesDecoded = AtomicInteger(0)

    init {
        queueJob = scope.launch {
            processOutboundQueue()
        }
    }

    /** 
     * Connects to the WebSocket endpoint.
     * @return true if connection successful, false otherwise
     */
    fun connect(): Boolean {
        if (isConnecting || isConnected) {
            return false
        }

        isConnecting = true
        connectionError = null

        connectionJob = scope.launch {
            try {
                val requestBuilder = Request.Builder()
                    .url(endpoint.canonical)

                // Add custom headers
                headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                val request = requestBuilder.build()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        this@WebSocketTransport.webSocket = webSocket
                        isConnected = true
                        isConnecting = false
                        connectionAttempts = 0
                        _incomingMessages.value = Message.Ready
                        correlator.start()
                        scope.launch {
                            receiveMessages()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch {
                            incomingChannel.trySend(text)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        scope.launch {
                            incomingChannel.trySend(bytes)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        isConnected = false
                        scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        isConnected = false
                        isConnecting = false
                        scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        isConnected = false
                        isConnecting = false
                        connectionError = t as? Exception ?: Exception(t)
                        scheduleReconnect()
                    }
                }

                val response = client.newWebSocket(request, listener)
                this@WebSocketTransport.webSocket = response
            } catch (e: Exception) {
                connectionError = e
                isConnecting = false
                scheduleReconnect()
            }
        }

        return true
    }

    /** 
     * Disconnects from the WebSocket.
     */
    fun disconnect() {
        isConnected = false
        isConnecting = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        queueJob.cancel()
        connectionJob = null
        reconnectJob = null
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        correlator.stop()
    }

    /** 
     * Sends a message through the WebSocket.
     * @param message The message to send
     */
    override fun send(message: Message) {
        scope.launch {
            outboundQueue.send(message)
        }
    }

    /** 
     * Registers a callback for incoming messages.
     * @param callback The callback to invoke for each message
     */
    override fun onMessage(callback: (Message) -> Unit) {
        scope.launch {
            while (true) {
                val message = _incomingMessages.value
                if (message != null) {
                    callback(message)
                    _incomingMessages.value = null
                }
                kotlinx.coroutines.delay(10)
            }
        }
    }

    /** 
     * Processes the outbound message queue.
     */
    private suspend fun processOutboundQueue() {
        for (message in outboundQueue) {
            while (!isConnected) {
                kotlinx.coroutines.delay(100)
            }
            actualSend(message)
        }
    }

    /**
     * Performs the actual network send.
     */
    private fun actualSend(message: Message) {
        val jsonString = json.encodeToString(Message.serializer(), message)
        webSocket?.send(jsonString)
        messagesSent.incrementAndGet()
    }

    /** 
     * Handles incoming text messages (JSON frames).
     * @param text The JSON text
     */
    private suspend fun handleIncomingText(text: String) {
        try {
            val message = json.decodeFromString(Message.serializer(), text)
            _incomingMessages.value = message
            messagesReceived.incrementAndGet()
            // Submit message to correlator's ordered reader/writer loop
            correlator.submit(message)
        } catch (e: Exception) {
            // Handle malformed JSON
        }
    }

    /** 
     * Handles incoming binary messages (chunked frames).
     * @param bytes The binary data
     */
    private suspend fun handleIncomingBytes(bytes: ByteString) {
        try {
            // For now, just log the received bytes
            // In a real implementation, this would decode chunked frames
            framesDecoded.incrementAndGet()
        } catch (e: Exception) {
            // Handle decode errors
        }
    }

    /** 
     * Schedules a reconnection attempt.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            val delay = ReconnectPolicy.delay(connectionAttempts)
            kotlinx.coroutines.delay(delay.inWholeMilliseconds)
            connectionAttempts++

            if (!ReconnectPolicy.isQuiet(connectionAttempts)) {
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
    private suspend fun receiveMessages() {
        webSocket?.let { ws ->
            for (message in incomingChannel) {
                when (message) {
                    is String -> handleIncomingText(message)
                    is ByteString -> handleIncomingBytes(message)
                }
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
        connect()
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
     * Returns the number of frames decoded.
     * @return The number of frames decoded
     */
    fun getFramesDecoded(): Int {
        return framesDecoded.get()
    }

    /** 
     * Returns whether the transport is connected.
     * @return true if connected, false otherwise
     */
    fun isTransportConnected(): Boolean {
        return isConnected
    }

    /** 
     * Returns the connection error, if any.
     * @return The connection error, or null if no error
     */
    fun getConnectionError(): Exception? {
        return connectionError
    }

    companion object {
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
            return WebSocketTransport(scope, endpoint, headers)
        }
    }
}
