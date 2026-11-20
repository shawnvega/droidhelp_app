package com.bighelp.droidhelp.transport

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

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
        assertEquals(0, result.oversizedDrops)
        assertEquals(0, result.malformedDrops)
        assertEquals(0, decoder.bufferedByteCount())
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
        assertEquals(0, second.oversizedDrops)
        assertEquals(0, second.malformedDrops)
        assertEquals(0, decoder.bufferedByteCount())
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
        private const val OVERFLOW_SEGMENT_LENGTH = 32
    }
}
