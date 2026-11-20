package com.bighelp.droidhelp.transport

import kotlinx.serialization.json.Json

/**
 * Result of decoding an inbound text chunk through [NewlineJsonFrameDecoder].
 */
data class FrameDecodeResult(
    val frames: List<ParsedJsonFrame> = emptyList(),
    val messages: List<Message> = frames.mapNotNull { it.message },
    val oversizedDrops: Int = 0,
    val malformedDrops: Int = 0
)

/**
 * Bounded decoder for newline-framed JSON object payloads.
 *
 * Splits incoming chunks on `\n`, parses each non-blank line as a JSON object via
 * [JsonFrameParser] to extract `headers`, `payload`, and optional typed [Message] fields,
 * enforces [maxBufferSize] (in UTF-8 bytes per frame), [maxMessageBytes] per chunk,
 * [maxBatchFrames] per chunk, and [maxNestingDepth] per JSON object, and recovers
 * gracefully from malformed or oversized frames.
 */
class NewlineJsonFrameDecoder(
    private val json: Json = Json,
    private val maxBufferSize: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxMessageBytes: Int = maxOf(maxBufferSize, DEFAULT_MAX_MESSAGE_BYTES),
    private val maxBatchFrames: Int = DEFAULT_MAX_BATCH_FRAMES,
    private val maxNestingDepth: Int = DEFAULT_MAX_NESTING_DEPTH
) {
    init {
        require(maxBufferSize > 0) { "maxBufferSize must be positive" }
        require(maxMessageBytes >= maxBufferSize) { "maxMessageBytes must be >= maxBufferSize" }
        require(maxBatchFrames > 0) { "maxBatchFrames must be positive" }
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
    }

    private class BatchAccumulator {
        val frames = mutableListOf<ParsedJsonFrame>()
        var oversizedDrops = 0
        var malformedDrops = 0
    }

    private val buffer = StringBuilder()
    private var bufferBytes = 0

    fun decode(chunk: String): FrameDecodeResult {
        val chunkBytes = utf8Size(chunk)
        return when {
            chunkBytes > maxMessageBytes -> {
                reset()
                FrameDecodeResult(oversizedDrops = 1)
            }
            '\n' !in chunk -> decodeWithoutNewline(chunk, chunkBytes)
            else -> decodeWithNewlines(chunk)
        }
    }

    fun reset() {
        buffer.clear()
        bufferBytes = 0
    }

    fun bufferedByteCount(): Int = bufferBytes

    private fun decodeWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        return when {
            chunkBytes > maxBufferSize -> {
                reset()
                FrameDecodeResult(oversizedDrops = 1)
            }
            buffer.isEmpty() -> decodeFreshWithoutNewline(chunk, chunkBytes)
            else -> decodeBufferedWithoutNewline(chunk, chunkBytes)
        }
    }

    private fun decodeFreshWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        val trimmed = chunk.trim()
        return if (trimmed.isEmpty()) {
            FrameDecodeResult()
        } else {
            when (val outcome = JsonFrameParser.parse(trimmed, json, maxNestingDepth)) {
                is FrameParseOutcome.Valid -> FrameDecodeResult(frames = listOf(outcome.frame))
                FrameParseOutcome.DefinitivelyMalformed -> FrameDecodeResult(malformedDrops = 1)
                FrameParseOutcome.IncompleteOrInvalid -> {
                    buffer.append(chunk)
                    bufferBytes = chunkBytes
                    FrameDecodeResult()
                }
            }
        }
    }

    private fun decodeBufferedWithoutNewline(chunk: String, chunkBytes: Int): FrameDecodeResult {
        if (bufferBytes + chunkBytes > maxBufferSize) {
            reset()
            return FrameDecodeResult(oversizedDrops = 1)
        }

        val combinedText = (buffer.toString() + chunk).trim()
        return when (val combined = JsonFrameParser.parse(combinedText, json, maxNestingDepth)) {
            is FrameParseOutcome.Valid -> {
                reset()
                FrameDecodeResult(frames = listOf(combined.frame))
            }
            FrameParseOutcome.DefinitivelyMalformed -> {
                reset()
                FrameDecodeResult(malformedDrops = 1)
            }
            FrameParseOutcome.IncompleteOrInvalid -> decodeStandaloneFallback(chunk, chunkBytes)
        }
    }

    private fun decodeStandaloneFallback(chunk: String, chunkBytes: Int): FrameDecodeResult {
        return when (val standalone = JsonFrameParser.parse(chunk.trim(), json, maxNestingDepth)) {
            is FrameParseOutcome.Valid -> {
                reset()
                FrameDecodeResult(frames = listOf(standalone.frame), malformedDrops = 1)
            }
            FrameParseOutcome.DefinitivelyMalformed -> {
                reset()
                FrameDecodeResult(malformedDrops = 2)
            }
            FrameParseOutcome.IncompleteOrInvalid -> {
                buffer.append(chunk)
                bufferBytes += chunkBytes
                FrameDecodeResult()
            }
        }
    }

    private fun decodeWithNewlines(chunk: String): FrameDecodeResult {
        val acc = BatchAccumulator()
        val lines = chunk.split('\n')

        decodeFirstLine(lines[0], acc)
        for (index in 1 until lines.lastIndex) {
            decodeCompleteLine(lines[index], acc)
        }
        handleTrailingSegment(lines.last(), acc)

        return FrameDecodeResult(
            frames = acc.frames,
            oversizedDrops = acc.oversizedDrops,
            malformedDrops = acc.malformedDrops
        )
    }

    private fun decodeFirstLine(firstPart: String, acc: BatchAccumulator) {
        if (buffer.isEmpty()) {
            decodeCompleteLine(firstPart, acc)
        } else if (bufferBytes + utf8Size(firstPart) > maxBufferSize) {
            reset()
            acc.oversizedDrops++
        } else {
            val combined = buffer.append(firstPart).toString().trim()
            reset()
            if (combined.isNotEmpty()) {
                when (val combinedOutcome = JsonFrameParser.parse(combined, json, maxNestingDepth)) {
                    is FrameParseOutcome.Valid -> recordValidFrame(combinedOutcome.frame, acc)
                    else -> decodeFallbackLine(firstPart.trim(), acc)
                }
            }
        }
    }

    private fun decodeFallbackLine(fallback: String, acc: BatchAccumulator) {
        when (val fallbackOutcome = JsonFrameParser.parse(fallback, json, maxNestingDepth)) {
            is FrameParseOutcome.Valid -> {
                acc.malformedDrops++
                recordValidFrame(fallbackOutcome.frame, acc)
            }
            else -> acc.malformedDrops++
        }
    }

    private fun decodeCompleteLine(line: String, acc: BatchAccumulator) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        if (utf8Size(line) > maxBufferSize) {
            acc.oversizedDrops++
            return
        }

        when (val outcome = JsonFrameParser.parse(trimmed, json, maxNestingDepth)) {
            is FrameParseOutcome.Valid -> recordValidFrame(outcome.frame, acc)
            else -> acc.malformedDrops++
        }
    }

    private fun handleTrailingSegment(trailing: String, acc: BatchAccumulator) {
        val trimmedTrailing = trailing.trim()
        if (trimmedTrailing.isEmpty()) return
        val trailingBytes = utf8Size(trailing)
        if (trailingBytes > maxBufferSize) {
            acc.oversizedDrops++
            return
        }

        when (val outcome = JsonFrameParser.parse(trimmedTrailing, json, maxNestingDepth)) {
            is FrameParseOutcome.Valid -> recordValidFrame(outcome.frame, acc)
            FrameParseOutcome.DefinitivelyMalformed -> acc.malformedDrops++
            FrameParseOutcome.IncompleteOrInvalid -> {
                buffer.append(trailing)
                bufferBytes = trailingBytes
            }
        }
    }

    private fun recordValidFrame(frame: ParsedJsonFrame, acc: BatchAccumulator) {
        if (acc.frames.size >= maxBatchFrames) {
            acc.oversizedDrops++
        } else {
            acc.frames.add(frame)
        }
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 1024 * 1024 // 1 MiB
        const val DEFAULT_MAX_MESSAGE_BYTES = 4 * 1024 * 1024 // 4 MiB (matches iOS DirectHermesWire)
        const val DEFAULT_MAX_BATCH_FRAMES = 4_096 // matches iOS DirectHermesWire.maximumBatchCount
        const val DEFAULT_MAX_NESTING_DEPTH = 64 // matches iOS DirectHermesWire.validateNesting
    }
}
