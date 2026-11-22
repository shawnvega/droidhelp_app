package com.bighelp.droidhelp.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NewlineJsonFrameDecoderTest {

    @Test
    fun testRejectsNonPositiveMaxBufferSize() {
        assertThrows(IllegalArgumentException::class.java) {
            NewlineJsonFrameDecoder(Json, 0)
        }
    }

    @Test
    fun testDecodesCoalescedNewlineAndCrLfPayloads() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val pingJson = Json.encodeToString(Message.serializer(), Message.Ping)
        val pongJson = Json.encodeToString(Message.serializer(), Message.Pong)
        val receipt = Message.Receipt(entryId = "entry-42")
        val receiptJson = Json.encodeToString(Message.serializer(), receipt)

        val result = decoder.decode("$pingJson\n\n$pongJson\r\n$receiptJson\n")

        assertEquals(listOf(Message.Ping, Message.Pong, receipt), result.messages)
        assertEquals(EXPECTED_THREE_FRAMES, result.frames.size)
        assertEquals(0, result.oversizedDrops)
        assertEquals(0, result.malformedDrops)
        assertEquals(0, decoder.bufferedByteCount())
    }

    @Test
    fun testExtractsHeadersAndPayloadFromNewlineJsonFrames() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val frame1 = """{"headers":{"x-req":"req-1","seq":7},"payload":{"text":"hello","ok":true}}"""
        val frame2 = """{"headers":{"x-type":"text"},"payload":"raw-value"}"""
        val frame3 = """{"headers":null,"payload":null}"""
        val frame4 = """{"headers":{"x-only":"yes"}}"""

        val result = decoder.decode("$frame1\n$frame2\r\n$frame3\n$frame4\n")

        assertEquals(EXPECTED_FOUR_FRAMES, result.frames.size)
        assertEquals(0, result.malformedDrops)
        assertEquals(0, result.oversizedDrops)

        assertEquals(mapOf("x-req" to "req-1", "seq" to "7"), result.frames[0].headers)
        assertEquals("hello", result.frames[0].payloadObject?.get("text")?.jsonPrimitive?.content)
        assertEquals(frame1.toByteArray(Charsets.UTF_8).size, result.frames[0].byteSize)

        assertEquals(mapOf("x-type" to "text"), result.frames[1].headers)
        assertEquals(JsonPrimitive("raw-value"), result.frames[1].payload)

        assertEquals(emptyMap<String, String>(), result.frames[2].headers)
        assertEquals(JsonNull, result.frames[2].payload)

        assertEquals(mapOf("x-only" to "yes"), result.frames[3].headers)
        assertNull(result.frames[3].payload)
    }

    @Test
    fun testExtractsHermesJsonRpcEnvelopePayloads() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val eventFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}"""
        val resultFrame = """{"jsonrpc":"2.0","id":"rpc-1","result":{"ok":true}}"""
        val errorFrame = """{"jsonrpc":"2.0","id":"rpc-2","error":{"code":4090,"message":"limit"}}"""
        val requestFrame = """{"jsonrpc":"2.0","id":"srv-1","method":"approval","params":{"session_id":"s-1"}}"""

        val result = decoder.decode("$eventFrame\n$resultFrame\n$errorFrame\n$requestFrame\n")

        assertEquals(EXPECTED_FOUR_FRAMES, result.frames.size)
        assertEquals(0, result.malformedDrops)
        assertEquals("true", result.frames[0].payloadObject?.get("heartbeat")?.jsonPrimitive?.content)
        assertEquals("true", result.frames[1].payloadObject?.get("ok")?.jsonPrimitive?.content)
        assertEquals(RPC_ERROR_CODE, result.frames[2].payloadObject?.get("code")?.jsonPrimitive?.int)
        assertEquals("s-1", result.frames[3].payloadObject?.get("session_id")?.jsonPrimitive?.content)
    }

    @Test
    fun testReassemblesSplitPayloadAcrossChunks() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val prompt = Message.Prompt(text = "split across chunks", entryId = "split-1")
        val encoded = Json.encodeToString(Message.serializer(), prompt)
        val splitIndex = encoded.length / 2

        val first = decoder.decode(encoded.substring(0, splitIndex))
        assertEquals(emptyList<Message>(), first.messages)
        assertEquals(splitIndex, decoder.bufferedByteCount())

        val second = decoder.decode(encoded.substring(splitIndex) + "\n")
        assertEquals(listOf(prompt), second.messages)
        assertEquals(1, second.frames.size)
        assertEquals(0, second.oversizedDrops)
        assertEquals(0, second.malformedDrops)
        assertEquals(0, decoder.bufferedByteCount())
    }

    @Test
    fun testEnforcesPerFrameByteLimitWhileAllowingMultiFrameChunks() {
        val decoder = NewlineJsonFrameDecoder(Json, PER_FRAME_LIMIT)
        val smallA = """{"headers":{"k":"a"},"payload":{"n":1}}"""
        val smallB = """{"headers":{"k":"b"},"payload":{"n":2}}"""
        val oversized = """{"headers":{"k":"big"},"payload":{"data":"${"x".repeat(OVERFLOW_SEGMENT_LENGTH)}"}}"""

        val result = decoder.decode("$smallA\n$oversized\n$smallB\n")

        assertEquals(2, result.frames.size)
        assertEquals(1, result.oversizedDrops)
        assertEquals(0, result.malformedDrops)
        assertEquals("a", result.frames[0].headers["k"])
        assertEquals("b", result.frames[1].headers["k"])
    }

    @Test
    fun testEnforcesUtf8ByteCountForMultiByteCharacters() {
        val decoder = NewlineJsonFrameDecoder(Json, MULTIBYTE_LIMIT)
        // Each emoji is 4 UTF-8 bytes (2 UTF-16 chars): 10 emojis = 40 UTF-8 bytes + JSON wrapper > 48 bytes
        val multibyteFrame = """{"payload":"${"🚀".repeat(EMOJI_REPEAT_COUNT)}"}"""
        val result = decoder.decode("$multibyteFrame\n")

        assertEquals(emptyList<ParsedJsonFrame>(), result.frames)
        assertEquals(1, result.oversizedDrops)
        assertEquals(0, result.malformedDrops)
    }

    @Test
    fun testDropsOversizedAccumulatedBufferAndRecovers() {
        val decoder = NewlineJsonFrameDecoder(Json, SMALL_BUFFER_SIZE)
        val firstChunk = "{\"type\":\"com.bighelp.droidhelp.transport.Message.Prompt\",\"text\":\""
        val secondChunk = "x".repeat(OVERFLOW_SEGMENT_LENGTH) + "\"}\n"

        val first = decoder.decode(firstChunk)
        assertEquals(emptyList<Message>(), first.messages)
        assertEquals(0, first.oversizedDrops)

        val overflow = decoder.decode(secondChunk)
        assertEquals(emptyList<Message>(), overflow.messages)
        assertEquals(1, overflow.oversizedDrops)
        assertEquals(0, decoder.bufferedByteCount())

        val valid = decoder.decode(Json.encodeToString(Message.serializer(), Message.Ping) + "\n")
        assertEquals(listOf(Message.Ping), valid.messages)
        assertEquals(0, valid.oversizedDrops)
    }

    @Test
    fun testHandlesMalformedNonObjectAndInvalidHeadersGracefully() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val valid = """{"headers":{"x-ok":"1"},"payload":{"status":"good"}}"""
        val chunk = listOf(
            "{broken json",
            "[1, 2, 3]",
            "\"plain-string\"",
            "42",
            """{"headers":"not-an-object","payload":{}}""",
            """{"headers":{"nested":{"a":1}},"payload":{}}""",
            """{"headers":{"nullVal":null},"payload":{}}""",
            valid
        ).joinToString("\n") + "\n"

        val result = decoder.decode(chunk)

        assertEquals(1, result.frames.size)
        assertEquals(EXPECTED_SEVEN_MALFORMED, result.malformedDrops)
        assertEquals(0, result.oversizedDrops)
        assertEquals("1", result.frames[0].headers["x-ok"])
        assertEquals("good", result.frames[0].payload?.jsonObject?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun testRejectsExcessiveNestingDepthWhileAllowingEscapedBracesInStrings() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val tooDeep = "{\"a\":".repeat(EXCESSIVE_DEPTH) + "1" + "}".repeat(EXCESSIVE_DEPTH)
        val escapedStringFrame =
            """{"headers":{"note":"{ [ \" } ] \\\" safe"},"payload":{"text":"ok"}}"""

        val result = decoder.decode("$tooDeep\n$escapedStringFrame\n")

        assertEquals(1, result.frames.size)
        assertEquals(1, result.malformedDrops)
        assertEquals("ok", result.frames[0].payloadObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun testEnforcesMaxBatchFramesLimit() {
        val decoder = NewlineJsonFrameDecoder(
            json = Json,
            maxBufferSize = DEFAULT_TEST_BUFFER_SIZE,
            maxBatchFrames = 2
        )
        val frame = """{"headers":{"k":"v"},"payload":1}"""
        val result = decoder.decode("$frame\n$frame\n$frame\n")

        assertEquals(2, result.frames.size)
        assertEquals(1, result.oversizedDrops)
        assertEquals(0, result.malformedDrops)
    }

    @Test
    fun testRecoversFromMalformedStandaloneChunkBeforeValidMessage() {
        val decoder = NewlineJsonFrameDecoder(Json, DEFAULT_TEST_BUFFER_SIZE)
        val malformed = decoder.decode("{not valid json")
        assertEquals(emptyList<Message>(), malformed.messages)

        val valid = decoder.decode(Json.encodeToString(Message.serializer(), Message.Pong))
        assertEquals(listOf(Message.Pong), valid.messages)
        assertEquals(1, valid.malformedDrops)
        assertEquals(0, decoder.bufferedByteCount())
    }

    companion object {
        private const val DEFAULT_TEST_BUFFER_SIZE = 1024
        private const val SMALL_BUFFER_SIZE = 80
        private const val PER_FRAME_LIMIT = 64
        private const val MULTIBYTE_LIMIT = 48
        private const val EMOJI_REPEAT_COUNT = 10
        private const val OVERFLOW_SEGMENT_LENGTH = 32
        private const val EXPECTED_THREE_FRAMES = 3
        private const val EXPECTED_FOUR_FRAMES = 4
        private const val EXPECTED_SEVEN_MALFORMED = 7
        private const val EXCESSIVE_DEPTH = 65
        private const val RPC_ERROR_CODE = 4090
    }
}
