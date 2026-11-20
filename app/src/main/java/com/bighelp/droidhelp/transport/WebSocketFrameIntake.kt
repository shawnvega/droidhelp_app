package com.bighelp.droidhelp.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.ByteString
import okio.utf8Size

/**
 * Builds `/api/ws` WebSocket URLs and OkHttp [Request] instances from an [Endpoint]
 * and snapshotted custom headers.
 */
internal object WebSocketRequestBuilder {

    fun buildUrl(endpoint: Endpoint, wsPath: String = WebSocketTransport.WS_PATH): String {
        val wsScheme = if (endpoint.isSecure) "wss" else "ws"
        val formattedHost = if (endpoint.host.contains(':') && !endpoint.host.startsWith("[")) {
            "[${endpoint.host}]"
        } else {
            endpoint.host
        }
        val prefixSegment = endpoint.deploymentPrefix
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "/$it" } ?: ""
        val pathSegment = endpoint.path.trimEnd('/').let { if (it == "/") "" else it }
        val combinedPath = "$prefixSegment$pathSegment"
        val finalPath = if (combinedPath == wsPath || combinedPath.endsWith(wsPath)) {
            combinedPath
        } else {
            "$combinedPath$wsPath"
        }
        return "$wsScheme://$formattedHost:${endpoint.port}$finalPath"
    }

    fun buildRequest(endpoint: Endpoint, headers: Map<String, String>): Request {
        val requestBuilder = Request.Builder().url(buildUrl(endpoint))
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        return requestBuilder.build()
    }
}

/**
 * Handles bounded incoming text/binary WebSocket frame intake and decoding, preventing
 * memory exhaustion by rejecting oversized frames before channel buffering and applying
 * backpressure instead of silently dropping bursty messages.
 */
internal class WebSocketIncomingIntake(
    private val config: WebSocketTransport.WebSocketConfig,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onDecodeResult(result: FrameDecodeResult)
        fun onBinaryFrameDecoded()
        fun onOversizedDropped()
        fun onMalformedDropped(count: Int)
    }

    private sealed interface OversizedMarker {
        data object Text : OversizedMarker
        data object Binary : OversizedMarker
    }

    fun enqueueText(channel: Channel<Any>, text: String, isStillActive: () -> Boolean) {
        val isOversized = text.length > config.maxMessageBytes ||
            text.utf8Size() > config.maxMessageBytes.toLong()
        val item: Any = if (isOversized) OversizedMarker.Text else text
        enqueueItem(channel, item, isStillActive)
    }

    fun enqueueBytes(channel: Channel<Any>, bytes: ByteString, isStillActive: () -> Boolean) {
        val isOversized = bytes.size > config.maxMessageBytes ||
            (bytes.size > config.maxFrameSize && bytes.indexOf(NEWLINE_BYTES) == -1)
        val item: Any = if (isOversized) OversizedMarker.Binary else bytes
        enqueueItem(channel, item, isStillActive)
    }

    suspend fun consumeLoop(channel: ReceiveChannel<Any>) {
        val frameDecoder = NewlineJsonFrameDecoder(
            json = config.json,
            maxBufferSize = config.maxFrameSize,
            maxMessageBytes = config.maxMessageBytes,
            maxNestingDepth = config.maxNestingDepth
        )
        try {
            for (message in channel) {
                when (message) {
                    is String -> callbacks.onDecodeResult(frameDecoder.decode(message))
                    is ByteString -> handleIncomingBytes(message)
                    OversizedMarker.Text -> {
                        frameDecoder.reset()
                        callbacks.onOversizedDropped()
                    }
                    OversizedMarker.Binary -> callbacks.onOversizedDropped()
                }
            }
        } finally {
            frameDecoder.reset()
            channel.cancel()
        }
    }

    private fun enqueueItem(channel: Channel<Any>, item: Any, isStillActive: () -> Boolean) {
        if (isStillActive() && !channel.trySend(item).isSuccess) {
            sendBlocking(channel, item, isStillActive)
        }
    }

    private fun sendBlocking(channel: Channel<Any>, item: Any, isStillActive: () -> Boolean) {
        if (!isStillActive()) return
        try {
            runBlocking {
                channel.send(item)
            }
        } catch (_: ClosedSendChannelException) {
            // Channel closed by disconnect/close/onClosing; ignore remaining frames.
        } catch (_: CancellationException) {
            // Transport scope or channel cancelled; stop delivering frames.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleIncomingBytes(bytes: ByteString) {
        val hasNewline = bytes.indexOf(NEWLINE_BYTES) != -1
        when {
            bytes.size > config.maxMessageBytes -> callbacks.onOversizedDropped()
            bytes.size > config.maxFrameSize && !hasNewline -> callbacks.onOversizedDropped()
            else -> decodeIncomingBytes(bytes)
        }
    }

    private fun decodeIncomingBytes(bytes: ByteString) {
        val text = bytes.utf8()
        if (!looksLikeJsonFrame(text)) {
            if (bytes.size > config.maxFrameSize) {
                callbacks.onOversizedDropped()
            } else {
                callbacks.onBinaryFrameDecoded()
            }
        } else {
            val binaryDecoder = NewlineJsonFrameDecoder(
                json = config.json,
                maxBufferSize = config.maxFrameSize,
                maxMessageBytes = config.maxMessageBytes,
                maxNestingDepth = config.maxNestingDepth
            )
            val result = binaryDecoder.decode(text)
            val trailingMalformed = if (binaryDecoder.bufferedByteCount() > 0) 1 else 0
            binaryDecoder.reset()
            if (result.frames.isNotEmpty()) {
                callbacks.onBinaryFrameDecoded()
            }
            callbacks.onDecodeResult(result)
            if (trailingMalformed > 0) {
                callbacks.onMalformedDropped(trailingMalformed)
            }
        }
    }

    private fun looksLikeJsonFrame(text: String): Boolean {
        val firstChar = text.firstOrNull { !it.isWhitespace() }
        return firstChar == '{' || firstChar == '['
    }

    companion object {
        private val NEWLINE_BYTES = ByteString.of('\n'.code.toByte())
    }
}
