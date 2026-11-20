package com.bighelp.droidhelp.transport

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Result of decoding an inbound text chunk through [NewlineJsonFrameDecoder].
 */
internal data class FrameDecodeResult(
    val messages: List<Message>,
    val oversizedDrops: Int = 0,
    val malformedDrops: Int = 0
)

/**
 * Bounded decoder for newline-framed JSON [Message] payloads.
 *
 * Enforces [maxBufferSize] (in UTF-8 bytes) for both individual inbound chunks and
 * buffered partial frames split across multiple WebSocket text frames.
 */
internal class NewlineJsonFrameDecoder(
    private val json: Json,
    private val maxBufferSize: Int
) {
    init {
        require(maxBufferSize > 0) { "maxBufferSize must be positive" }
    }

    private val buffer = StringBuilder()
    private var bufferBytes = 0

    fun decode(chunk: String): FrameDecodeResult {
        val chunkBytes = utf8Size(chunk)
        if (chunkBytes > maxBufferSize) {
            reset()
            return FrameDecodeResult(messages = emptyList(), oversizedDrops = 1)
        }

        return if ('\n' !in chunk) {
            decodeWithoutNewline(chunk, chunkBytes)
        } else {
            decodeWithNewlines(chunk)
        }
    }

    fun reset() {
        buffer.clear()
        bufferBytes = 0
    }

    fun bufferedByteCount(): Int = bufferBytes

    private fun decodeWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        return if (buffer.isEmpty()) {
            decodeFreshWithoutNewline(chunk, chunkBytes)
        } else {
            decodeBufferedWithoutNewline(chunk, chunkBytes)
        }
    }

    private fun decodeFreshWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        val trimmed = chunk.trim()
        val direct = tryDecode(trimmed)
        return when {
            trimmed.isEmpty() -> FrameDecodeResult(emptyList())
            direct != null -> FrameDecodeResult(listOf(direct))
            else -> {
                buffer.append(chunk)
                bufferBytes = chunkBytes
                FrameDecodeResult(emptyList())
            }
        }
    }

    private fun decodeBufferedWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        if (bufferBytes + chunkBytes > maxBufferSize) {
            reset()
            return FrameDecodeResult(messages = emptyList(), oversizedDrops = 1)
        }

        val combinedMessage = tryDecode((buffer.toString() + chunk).trim())
        val standaloneMessage = if (combinedMessage == null) tryDecode(chunk.trim()) else null
        return when {
            combinedMessage != null -> {
                reset()
                FrameDecodeResult(listOf(combinedMessage))
            }
            standaloneMessage != null -> {
                reset()
                FrameDecodeResult(messages = listOf(standaloneMessage), malformedDrops = 1)
            }
            else -> {
                buffer.append(chunk)
                bufferBytes += chunkBytes
                FrameDecodeResult(emptyList())
            }
        }
    }

    private fun decodeWithNewlines(chunk: String): FrameDecodeResult {
        val messages = mutableListOf<Message>()
        var oversizedDrops = 0
        var malformedDrops = 0

        val lines = chunk.split('\n')
        val (firstOversized, firstMalformed) = decodeFirstLine(lines[0], messages)
        oversizedDrops += firstOversized
        malformedDrops += firstMalformed

        for (index in 1 until lines.lastIndex) {
            malformedDrops += decodeCompleteLine(lines[index], messages)
        }

        handleTrailingSegment(lines.last(), messages)

        return FrameDecodeResult(
            messages = messages,
            oversizedDrops = oversizedDrops,
            malformedDrops = malformedDrops
        )
    }

    private fun handleTrailingSegment(trailing: String, messages: MutableList<Message>) {
        val trimmedTrailing = trailing.trim()
        if (trimmedTrailing.isEmpty()) return
        val standalone = tryDecode(trimmedTrailing)
        if (standalone != null) {
            messages.add(standalone)
        } else {
            buffer.append(trailing)
            bufferBytes = utf8Size(trailing)
        }
    }

    private fun decodeFirstLine(firstPart: String, messages: MutableList<Message>): Pair<Int, Int> {
        if (buffer.isEmpty()) {
            return 0 to decodeCompleteLine(firstPart, messages)
        }

        val firstBytes = utf8Size(firstPart)
        return if (bufferBytes + firstBytes > maxBufferSize) {
            reset()
            1 to 0
        } else {
            val combined = buffer.append(firstPart).toString().trim()
            reset()
            decodeCombinedOrFallback(combined, firstPart.trim(), messages)
        }
    }

    private fun decodeCombinedOrFallback(
        combined: String,
        fallback: String,
        messages: MutableList<Message>
    ): Pair<Int, Int> {
        if (combined.isEmpty()) return 0 to 0
        val combinedMsg = tryDecode(combined)
        val fallbackMsg = if (combinedMsg == null) tryDecode(fallback) else null
        return when {
            combinedMsg != null -> {
                messages.add(combinedMsg)
                0 to 0
            }
            fallbackMsg != null -> {
                messages.add(fallbackMsg)
                0 to 1
            }
            else -> 0 to 1
        }
    }

    private fun decodeCompleteLine(line: String, messages: MutableList<Message>): Int {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return 0
        val decoded = tryDecode(trimmed)
        return if (decoded != null) {
            messages.add(decoded)
            0
        } else {
            1
        }
    }

    private fun tryDecode(payload: String): Message? {
        if (payload.isEmpty()) return null
        return try {
            json.decodeFromString(Message.serializer(), payload)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
